/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.hyracks.bootstrap;

import java.io.PrintWriter;
import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.uci.ics.asterix.api.common.APIFramework.DisplayFormat;
import edu.uci.ics.asterix.api.common.SessionConfig;
import edu.uci.ics.asterix.aql.base.Statement;
import edu.uci.ics.asterix.aql.expression.BeginFeedStatement;
import edu.uci.ics.asterix.aql.expression.DataverseDecl;
import edu.uci.ics.asterix.aql.expression.Identifier;
import edu.uci.ics.asterix.aql.translator.AqlTranslator;
import edu.uci.ics.asterix.common.exceptions.ACIDException;
import edu.uci.ics.asterix.hyracks.bootstrap.FeedLifecycleListener.FeedFailure.FailureType;
import edu.uci.ics.asterix.metadata.MetadataException;
import edu.uci.ics.asterix.metadata.MetadataManager;
import edu.uci.ics.asterix.metadata.MetadataTransactionContext;
import edu.uci.ics.asterix.metadata.api.IClusterEventsSubscriber;
import edu.uci.ics.asterix.metadata.api.IClusterManagementWork;
import edu.uci.ics.asterix.metadata.bootstrap.MetadataConstants;
import edu.uci.ics.asterix.metadata.cluster.AddNodeWork;
import edu.uci.ics.asterix.metadata.cluster.ClusterManager;
import edu.uci.ics.asterix.metadata.cluster.IClusterManagementWorkResponse;
import edu.uci.ics.asterix.metadata.entities.FeedActivity;
import edu.uci.ics.asterix.metadata.entities.FeedActivity.FeedActivityDetails;
import edu.uci.ics.asterix.metadata.entities.FeedActivity.FeedActivityType;
import edu.uci.ics.asterix.metadata.entities.FeedPolicy;
import edu.uci.ics.asterix.metadata.feeds.AdapterRuntimeManager;
import edu.uci.ics.asterix.metadata.feeds.BuiltinFeedPolicies;
import edu.uci.ics.asterix.metadata.feeds.FeedId;
import edu.uci.ics.asterix.metadata.feeds.FeedIntakeOperatorDescriptor;
import edu.uci.ics.asterix.om.util.AsterixAppContextInfo;
import edu.uci.ics.asterix.om.util.AsterixClusterProperties;
import edu.uci.ics.asterix.om.util.AsterixClusterProperties.State;
import edu.uci.ics.hyracks.algebricks.runtime.base.IPushRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.meta.AlgebricksMetaOperatorDescriptor;
import edu.uci.ics.hyracks.algebricks.runtime.operators.std.AssignRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.std.EmptyTupleSourceRuntimeFactory;
import edu.uci.ics.hyracks.api.client.IHyracksClientConnection;
import edu.uci.ics.hyracks.api.dataflow.IOperatorDescriptor;
import edu.uci.ics.hyracks.api.dataflow.OperatorDescriptorId;
import edu.uci.ics.hyracks.api.exceptions.HyracksException;
import edu.uci.ics.hyracks.api.job.IActivityClusterGraphGenerator;
import edu.uci.ics.hyracks.api.job.IActivityClusterGraphGeneratorFactory;
import edu.uci.ics.hyracks.api.job.IJobLifecycleListener;
import edu.uci.ics.hyracks.api.job.JobFlag;
import edu.uci.ics.hyracks.api.job.JobId;
import edu.uci.ics.hyracks.api.job.JobInfo;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.api.job.JobStatus;
import edu.uci.ics.hyracks.storage.am.lsm.common.dataflow.LSMTreeIndexInsertUpdateDeleteOperatorDescriptor;

public class FeedLifecycleListener implements IJobLifecycleListener, IClusterEventsSubscriber, Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(FeedLifecycleListener.class.getName());

    public static FeedLifecycleListener INSTANCE = new FeedLifecycleListener();

    private LinkedBlockingQueue<Message> jobEventInbox;
    private LinkedBlockingQueue<IClusterManagementWorkResponse> responseInbox;

    private State state;

    private FeedLifecycleListener() {
        jobEventInbox = new LinkedBlockingQueue<Message>();
        feedJobNotificationHandler = new FeedJobNotificationHandler(jobEventInbox);
        responseInbox = new LinkedBlockingQueue<IClusterManagementWorkResponse>();
        feedWorkRequestResponseHandler = new FeedWorkRequestResponseHandler(responseInbox);

        new Thread(feedJobNotificationHandler).start();
        new Thread(feedWorkRequestResponseHandler).start();
        ClusterManager.INSTANCE.registerSubscriber(this);
        state = AsterixClusterProperties.INSTANCE.getState();
    }

    private final FeedJobNotificationHandler feedJobNotificationHandler;
    private final FeedWorkRequestResponseHandler feedWorkRequestResponseHandler;

    @Override
    public void notifyJobStart(JobId jobId) throws HyracksException {
        if (feedJobNotificationHandler.isRegisteredFeed(jobId)) {
            jobEventInbox.add(new Message(jobId, Message.MessageKind.JOB_START));
        }
    }

    @Override
    public void notifyJobFinish(JobId jobId) throws HyracksException {
        if (feedJobNotificationHandler.isRegisteredFeed(jobId)) {
            jobEventInbox.add(new Message(jobId, Message.MessageKind.JOB_FINISH));
        }
    }

    @Override
    public void notifyJobCreation(JobId jobId, IActivityClusterGraphGeneratorFactory acggf) throws HyracksException {

        IActivityClusterGraphGenerator acgg = acggf.createActivityClusterGraphGenerator(jobId, AsterixAppContextInfo
                .getInstance().getCCApplicationContext(), EnumSet.noneOf(JobFlag.class));
        JobSpecification spec = acggf.getJobSpecification();
        boolean feedIngestionJob = false;
        FeedId feedId = null;
        String feedPolicy = null;
        for (IOperatorDescriptor opDesc : spec.getOperatorMap().values()) {
            if (!(opDesc instanceof FeedIntakeOperatorDescriptor)) {
                continue;
            }
            feedId = ((FeedIntakeOperatorDescriptor) opDesc).getFeedId();
            feedPolicy = ((FeedIntakeOperatorDescriptor) opDesc).getFeedPolicy().get(
                    BuiltinFeedPolicies.CONFIG_FEED_POLICY_KEY);
            feedIngestionJob = true;
            break;
        }
        if (feedIngestionJob) {
            feedJobNotificationHandler.registerFeed(feedId, jobId, spec, feedPolicy);
        }

    }

    private static class Message {
        public JobId jobId;

        public enum MessageKind {
            JOB_START,
            JOB_FINISH
        }

        public MessageKind messageKind;

        public Message(JobId jobId, MessageKind msgKind) {
            this.jobId = jobId;
            this.messageKind = msgKind;
        }
    }

    public static class FeedFailureReport {
        public Map<FeedInfo, List<FeedFailure>> failures = new HashMap<FeedInfo, List<FeedFailure>>();

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<FeedLifecycleListener.FeedInfo, List<FeedLifecycleListener.FeedFailure>> entry : failures
                    .entrySet()) {
                builder.append(entry.getKey() + " -> failures");
                for (FeedFailure failure : entry.getValue()) {
                    builder.append("failure -> " + failure);
                }
            }
            return builder.toString();
        }
    }

    private static class FeedJobNotificationHandler implements Runnable, Serializable {

        private static final long serialVersionUID = 1L;
        private LinkedBlockingQueue<Message> inbox;
        private Map<JobId, FeedInfo> registeredFeeds = new HashMap<JobId, FeedInfo>();

        public FeedJobNotificationHandler(LinkedBlockingQueue<Message> inbox) {
            this.inbox = inbox;
        }

        public boolean isRegisteredFeed(JobId jobId) {
            return registeredFeeds.containsKey(jobId);
        }

        public void registerFeed(FeedId feedId, JobId jobId, JobSpecification jobSpec, String feedPolicy) {
            if (registeredFeeds.containsKey(jobId)) {
                throw new IllegalStateException(" Feed already registered ");
            }
            registeredFeeds.put(jobId, new FeedInfo(feedId, jobSpec, feedPolicy));
        }

        @Override
        public void run() {
            Message mesg;
            while (true) {
                try {
                    mesg = inbox.take();
                    FeedInfo feedInfo = registeredFeeds.get(mesg.jobId);
                    switch (mesg.messageKind) {
                        case JOB_START:
                            handleJobStartMessage(feedInfo, mesg);
                            break;
                        case JOB_FINISH:
                            handleJobFinishMessage(feedInfo, mesg);
                            break;
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }

        private void handleJobStartMessage(FeedInfo feedInfo, Message message) {

            JobSpecification jobSpec = feedInfo.jobSpec;

            List<OperatorDescriptorId> ingestOperatorIds = new ArrayList<OperatorDescriptorId>();
            List<OperatorDescriptorId> computeOperatorIds = new ArrayList<OperatorDescriptorId>();
            List<OperatorDescriptorId> storageOperatorIds = new ArrayList<OperatorDescriptorId>();

            Map<OperatorDescriptorId, IOperatorDescriptor> operators = jobSpec.getOperatorMap();
            for (Entry<OperatorDescriptorId, IOperatorDescriptor> entry : operators.entrySet()) {
                if (entry.getValue() instanceof AlgebricksMetaOperatorDescriptor) {
                    AlgebricksMetaOperatorDescriptor op = ((AlgebricksMetaOperatorDescriptor) entry.getValue());
                    IPushRuntimeFactory[] runtimeFactories = op.getPipeline().getRuntimeFactories();
                    for (IPushRuntimeFactory rf : runtimeFactories) {
                        if (rf instanceof EmptyTupleSourceRuntimeFactory) {
                            ingestOperatorIds.add(entry.getKey());
                        } else if (rf instanceof AssignRuntimeFactory) {
                            computeOperatorIds.add(entry.getKey());
                        }
                    }
                } else if (entry.getValue() instanceof LSMTreeIndexInsertUpdateDeleteOperatorDescriptor) {
                    storageOperatorIds.add(entry.getKey());
                }
            }

            try {
                IHyracksClientConnection hcc = AsterixAppContextInfo.getInstance().getHcc();
                JobInfo info = hcc.getJobInfo(message.jobId);
                feedInfo.jobInfo = info;
                Map<String, String> feedActivityDetails = new HashMap<String, String>();
                StringBuilder ingestLocs = new StringBuilder();
                for (OperatorDescriptorId ingestOpId : ingestOperatorIds) {
                    feedInfo.ingestLocations.addAll(info.getOperatorLocations().get(ingestOpId));
                }
                StringBuilder computeLocs = new StringBuilder();
                for (OperatorDescriptorId computeOpId : computeOperatorIds) {
                    List<String> locations = info.getOperatorLocations().get(computeOpId);
                    if (locations != null) {
                        feedInfo.computeLocations.addAll(locations);
                    } else {
                        feedInfo.computeLocations.addAll(feedInfo.ingestLocations);
                    }
                }
                StringBuilder storageLocs = new StringBuilder();
                for (OperatorDescriptorId storageOpId : storageOperatorIds) {
                    feedInfo.storageLocations.addAll(info.getOperatorLocations().get(storageOpId));
                }

                for (String ingestLoc : feedInfo.ingestLocations) {
                    ingestLocs.append(ingestLoc);
                    ingestLocs.append(",");
                }
                for (String computeLoc : feedInfo.computeLocations) {
                    computeLocs.append(computeLoc);
                    computeLocs.append(",");
                }
                for (String storageLoc : feedInfo.storageLocations) {
                    storageLocs.append(storageLoc);
                    storageLocs.append(",");
                }

                feedActivityDetails.put(FeedActivity.FeedActivityDetails.INGEST_LOCATIONS, ingestLocs.toString());
                feedActivityDetails.put(FeedActivity.FeedActivityDetails.COMPUTE_LOCATIONS, computeLocs.toString());
                feedActivityDetails.put(FeedActivity.FeedActivityDetails.STORAGE_LOCATIONS, storageLocs.toString());
                feedActivityDetails.put(FeedActivity.FeedActivityDetails.FEED_POLICY_NAME, feedInfo.feedPolicy);

                FeedActivity feedActivity = new FeedActivity(feedInfo.feedId.getDataverse(),
                        feedInfo.feedId.getDataset(), FeedActivityType.FEED_BEGIN, feedActivityDetails);

                MetadataManager.INSTANCE.acquireWriteLatch();
                MetadataTransactionContext mdTxnCtx = null;
                try {
                    mdTxnCtx = MetadataManager.INSTANCE.beginTransaction();
                    MetadataManager.INSTANCE.registerFeedActivity(mdTxnCtx, new FeedId(feedInfo.feedId.getDataverse(),
                            feedInfo.feedId.getDataset()), feedActivity);
                    MetadataManager.INSTANCE.commitTransaction(mdTxnCtx);
                } catch (Exception e) {
                    MetadataManager.INSTANCE.abortTransaction(mdTxnCtx);
                } finally {
                    MetadataManager.INSTANCE.releaseWriteLatch();
                }
            } catch (Exception e) {
                // TODO Add Exception handling here
            }

        }

        private void handleJobFinishMessage(FeedInfo feedInfo, Message message) {

            MetadataManager.INSTANCE.acquireWriteLatch();
            MetadataTransactionContext mdTxnCtx = null;

            try {
                IHyracksClientConnection hcc = AsterixAppContextInfo.getInstance().getHcc();
                JobInfo info = hcc.getJobInfo(message.jobId);
                JobStatus status = info.getPendingStatus();
                List<Exception> exceptions;
                boolean failure = status != null && status.equals(JobStatus.FAILURE);
                FeedActivityType activityType = FeedActivityType.FEED_END;
                Map<String, String> details = new HashMap<String, String>();
                if (failure) {
                    exceptions = info.getPendingExceptions();
                    activityType = FeedActivityType.FEED_FAILURE;
                    details.put(FeedActivity.FeedActivityDetails.EXCEPTION_MESSAGE, exceptions.get(0).getMessage());
                }
                mdTxnCtx = MetadataManager.INSTANCE.beginTransaction();
                FeedActivity feedActivity = new FeedActivity(feedInfo.feedId.getDataverse(),
                        feedInfo.feedId.getDataset(), activityType, details);
                MetadataManager.INSTANCE.registerFeedActivity(mdTxnCtx, new FeedId(feedInfo.feedId.getDataverse(),
                        feedInfo.feedId.getDataset()), feedActivity);
                MetadataManager.INSTANCE.commitTransaction(mdTxnCtx);
            } catch (RemoteException | ACIDException | MetadataException e) {
                try {
                    MetadataManager.INSTANCE.abortTransaction(mdTxnCtx);
                } catch (RemoteException | ACIDException ae) {
                    throw new IllegalStateException(" Unable to abort ");
                }
            } catch (Exception e) {
                // add exception handling here
            } finally {
                MetadataManager.INSTANCE.releaseWriteLatch();
            }

        }
    }

    public static class FeedInfo {
        public FeedId feedId;
        public JobSpecification jobSpec;
        public List<String> ingestLocations = new ArrayList<String>();
        public List<String> computeLocations = new ArrayList<String>();
        public List<String> storageLocations = new ArrayList<String>();
        public JobInfo jobInfo;
        public String feedPolicy;

        public FeedInfo(FeedId feedId, JobSpecification jobSpec, String feedPolicy) {
            this.feedId = feedId;
            this.jobSpec = jobSpec;
            this.feedPolicy = feedPolicy;
        }

    }

    @Override
    public Set<IClusterManagementWork> notifyNodeFailure(Set<String> deadNodeIds) {
        Collection<FeedInfo> feedInfos = feedJobNotificationHandler.registeredFeeds.values();
        FeedFailureReport failureReport = new FeedFailureReport();
        for (FeedInfo feedInfo : feedInfos) {
            for (String deadNodeId : deadNodeIds) {
                if (feedInfo.ingestLocations.contains(deadNodeId)) {
                    List<FeedFailure> failures = failureReport.failures.get(feedInfo);
                    if (failures == null) {
                        failures = new ArrayList<FeedFailure>();
                        failureReport.failures.put(feedInfo, failures);
                    }
                    failures.add(new FeedFailure(FeedFailure.FailureType.INGESTION_NODE, deadNodeId));
                }
                if (feedInfo.computeLocations.contains(deadNodeId)) {
                    List<FeedFailure> failures = failureReport.failures.get(feedInfo);
                    if (failures == null) {
                        failures = new ArrayList<FeedFailure>();
                        failureReport.failures.put(feedInfo, failures);
                    }
                    failures.add(new FeedFailure(FeedFailure.FailureType.COMPUTE_NODE, deadNodeId));
                }
            }
        }

        return handleFailure(failureReport);
    }

    private Set<IClusterManagementWork> handleFailure(FeedFailureReport failureReport) {
        reportFeedFailure(failureReport);
        Set<IClusterManagementWork> work = new HashSet<IClusterManagementWork>();
        Map<String, Map<FeedInfo, List<FailureType>>> failureMap = new HashMap<String, Map<FeedInfo, List<FailureType>>>();
        for (Map.Entry<FeedInfo, List<FeedFailure>> entry : failureReport.failures.entrySet()) {
            FeedInfo feedInfo = entry.getKey();
            List<FeedFailure> feedFailures = entry.getValue();
            for (FeedFailure feedFailure : feedFailures) {
                switch (feedFailure.failureType) {
                    case COMPUTE_NODE:
                    case INGESTION_NODE:
                        Map<FeedInfo, List<FailureType>> failuresBecauseOfThisNode = failureMap.get(feedFailure.nodeId);
                        if (failuresBecauseOfThisNode == null) {
                            failuresBecauseOfThisNode = new HashMap<FeedInfo, List<FailureType>>();
                            failuresBecauseOfThisNode.put(feedInfo, new ArrayList<FailureType>());
                            failureMap.put(feedFailure.nodeId, failuresBecauseOfThisNode);
                        }
                        List<FailureType> feedF = failuresBecauseOfThisNode.get(feedInfo);
                        if (feedF == null) {
                            feedF = new ArrayList<FailureType>();
                            failuresBecauseOfThisNode.put(feedInfo, feedF);
                        }
                        feedF.add(feedFailure.failureType);

                        break;
                    case STORAGE_NODE:
                        if (LOGGER.isLoggable(Level.SEVERE)) {
                            LOGGER.severe("Unrecoverable situation! lost storage node for the feed " + feedInfo.feedId);
                        }
                        break;
                }
            }
        }

        AddNodeWork addNodesWork = new AddNodeWork(failureMap.keySet().size(), this);
        work.add(addNodesWork);
        feedWorkRequestResponseHandler.registerFeedWork(addNodesWork.getWorkId(), failureReport);
        return work;
    }

    private void reportFeedFailure(FeedFailureReport failureReport) {
        MetadataTransactionContext ctx = null;
        FeedActivity fa = null;
        Map<String, String> feedActivityDetails = new HashMap<String, String>();
        StringBuilder builder = new StringBuilder();
        try {
            ctx = MetadataManager.INSTANCE.beginTransaction();
            for (Entry<FeedInfo, List<FeedFailure>> entry : failureReport.failures.entrySet()) {
                FeedInfo feedInfo = entry.getKey();
                List<FeedFailure> feedFailures = entry.getValue();
                for (FeedFailure failure : feedFailures) {
                    builder.append(failure + ",");
                }
                builder.deleteCharAt(builder.length() - 1);
                feedActivityDetails.put(FeedActivityDetails.FEED_NODE_FAILURE, builder.toString());
                fa = new FeedActivity(feedInfo.feedId.getDataverse(), feedInfo.feedId.getDataset(),
                        FeedActivityType.FEED_FAILURE, feedActivityDetails);
                MetadataManager.INSTANCE.registerFeedActivity(ctx, feedInfo.feedId, fa);
            }
            MetadataManager.INSTANCE.commitTransaction(ctx);
        } catch (Exception e) {
            if (ctx != null) {
                try {
                    MetadataManager.INSTANCE.abortTransaction(ctx);
                } catch (Exception e2) {
                    e2.addSuppressed(e);
                    throw new IllegalStateException("Unable to abort transaction " + e2);
                }
            }
        }
    }

    public static class FeedFailure {

        public enum FailureType {
            INGESTION_NODE,
            COMPUTE_NODE,
            STORAGE_NODE
        }

        public FailureType failureType;
        public String nodeId;

        public FeedFailure(FailureType failureType, String nodeId) {
            this.failureType = failureType;
            this.nodeId = nodeId;
        }

        @Override
        public String toString() {
            return failureType + " (" + nodeId + ") ";
        }
    }

    @Override
    public Set<IClusterManagementWork> notifyNodeJoin(String joinedNodeId) {
        State newState = AsterixClusterProperties.INSTANCE.getState();
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(joinedNodeId + " joined the cluster. " + "Asterix state: " + newState);
        }
        if (!newState.equals(state)) {
            if (newState == State.ACTIVE) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info(joinedNodeId + " Resuming loser feeds (if any)");
                }
                try {
                    FeedsActivator activator = new FeedsActivator();
                    (new Thread(activator)).start();
                } catch (Exception e) {
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("Exception in resuming feeds" + e.getMessage());
                    }
                }
            }
            state = newState;
        }
        return null;
    }

    @Override
    public void notifyRequestCompletion(IClusterManagementWorkResponse response) {
        try {
            responseInbox.put(response);
        } catch (InterruptedException e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("Interrupted exception");
            }
        }
    }

    @Override
    public void notifyStateChange(State previousState, State newState) {
        switch (newState) {
            case ACTIVE:
                if (previousState.equals(State.UNUSABLE)) {
                    try {
                        FeedsActivator activator = new FeedsActivator();
                        (new Thread(activator)).start();
                    } catch (Exception e) {
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("Exception in resuming feeds" + e.getMessage());
                        }
                    }
                }
                break;
        }

    }

    private static class FeedsActivator implements Runnable {

        @Override
        public void run() {
            MetadataTransactionContext ctx = null;

            SessionConfig pc = new SessionConfig(true, false, false, false, false, false, true, false);
            PrintWriter writer = new PrintWriter(System.out, true);
            try {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Attempting to Resume feeds!");
                }
                Thread.sleep(2000);
                MetadataManager.INSTANCE.init();
                ctx = MetadataManager.INSTANCE.beginTransaction();
                List<FeedActivity> activeFeeds = MetadataManager.INSTANCE.getActiveFeeds(ctx);
                MetadataManager.INSTANCE.commitTransaction(ctx);
                for (FeedActivity fa : activeFeeds) {

                    String feedPolicy = fa.getFeedActivityDetails().get(FeedActivityDetails.FEED_POLICY_NAME);

                    FeedPolicy policy = MetadataManager.INSTANCE.getFeedPolicy(ctx, fa.getDataverseName(), feedPolicy);
                    if (policy == null) {
                        policy = MetadataManager.INSTANCE.getFeedPolicy(ctx, MetadataConstants.METADATA_DATAVERSE_NAME,
                                feedPolicy);
                        if (policy == null) {
                            if (LOGGER.isLoggable(Level.SEVERE)) {
                                LOGGER.severe("Unable to resume feed: " + fa.getDataverseName() + ":"
                                        + fa.getDatasetName() + "." + " Unknown policy :" + feedPolicy);
                            }
                        }
                    }

                    String dataverse = fa.getDataverseName();
                    String datasetName = fa.getDatasetName();
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("Resuming loser feed: " + dataverse + ":" + datasetName + " using policy "
                                + feedPolicy);
                    }
                    try {
                        DataverseDecl dataverseDecl = new DataverseDecl(new Identifier(dataverse));
                        BeginFeedStatement stmt = new BeginFeedStatement(new Identifier(dataverse), new Identifier(
                                datasetName), feedPolicy, 0);
                        stmt.setForceBegin(true);
                        List<Statement> statements = new ArrayList<Statement>();
                        statements.add(dataverseDecl);
                        statements.add(stmt);
                        AqlTranslator translator = new AqlTranslator(statements, writer, pc, DisplayFormat.TEXT);
                        translator.compileAndExecute(AsterixAppContextInfo.getInstance().getHcc(), null, false);
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("Resumed feed: " + dataverse + ":" + datasetName + " using policy "
                                    + feedPolicy);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("Exception in resuming loser feed: " + dataverse + ":" + datasetName
                                    + " using policy " + feedPolicy + " Exception " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    MetadataManager.INSTANCE.abortTransaction(ctx);
                } catch (Exception e1) {
                    if (LOGGER.isLoggable(Level.SEVERE)) {
                        LOGGER.severe("Exception in aborting" + e.getMessage());
                    }
                    throw new IllegalStateException(e1);
                }
            }

        }

    }

}