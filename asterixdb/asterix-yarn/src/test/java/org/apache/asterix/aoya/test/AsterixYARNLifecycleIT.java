/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.aoya.test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import org.apache.asterix.aoya.AsterixYARNClient;
import org.apache.asterix.aoya.Utils;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized.Parameters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AsterixYARNLifecycleIT {

    private static final Logger LOGGER = Logger.getLogger(AsterixYARNLifecycleIT.class.getName());
    private static final String INSTANCE_NAME = "asterix-integration-test";
    private static YarnConfiguration appConf;
    private static String configPath;
    private static String aoyaServerPath;
    private static String parameterPath;
    private static AsterixYARNInstanceUtil instance;

    @BeforeClass
    public static void setUp() throws Exception {
        AsterixYARNInstanceUtil.cleanUp();
        instance = new AsterixYARNInstanceUtil();
        appConf = instance.setUp();
        configPath = instance.configPath;
        aoyaServerPath = instance.aoyaServerPath;
        parameterPath = instance.parameterPath;
    }

    @AfterClass
    public static void tearDown() throws Exception {
        instance.tearDown();
    }

    @Parameters
    public static Collection<Object[]> tests() throws Exception {
        Collection<Object[]> testArgs = new ArrayList<Object[]>();
        return testArgs;
    }

    @Test
    public void test_1_InstallActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -c " + configPath + " -bc " + parameterPath + " -zip "
                + aoyaServerPath + " install";
        executeAoyaCommand(command);
    }

    @Test
    public void test_2_StopActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -bc " + parameterPath + " stop";
        executeAoyaCommand(command);
    }

    @Test
    public void test_3_BackupInActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -zip " + aoyaServerPath + " -f" + " backup";
        executeAoyaCommand(command);
    }

    @Test
    public void test_4_StartActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -bc " + parameterPath + " start";
        executeAoyaCommand(command);
    }

    @Test
    public void test_5_KillActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -bc " + parameterPath + " -f" + " stop";
        executeAoyaCommand(command);
    }

    @Test
    public void test_6_RestoreInActiveInstance() throws Exception {
        List<String> backupNames = Utils.getBackups(appConf, ".asterix" + File.separator, INSTANCE_NAME);
        if (backupNames.size() != 1) {
            throw new IllegalStateException();
        }
        String command = "-n " + INSTANCE_NAME + " -zip " + aoyaServerPath + " -s" + backupNames.get(0) + " -f"
                + " restore";
        executeAoyaCommand(command);
    }

    @Test
    public void test_7_StartRestoredInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -bc " + parameterPath + " start";
        executeAoyaCommand(command);
    }

    @Test
    public void test_8_DeleteActiveInstance() throws Exception {
        String command = "-n " + INSTANCE_NAME + " -zip " + aoyaServerPath + " -f" + " -bc " + parameterPath
                + " destroy";
        executeAoyaCommand(command);
    }

    static void executeAoyaCommand(String cmd) throws Exception {
        AsterixYARNClient aoyaClient = new AsterixYARNClient(appConf);
        aoyaClient.init(cmd.split(" "));
        AsterixYARNClient.execute(aoyaClient);
    }

    public static void main(String[] args) throws Exception {
        try {
            setUp();
            new AsterixYARNLifecycleIT();
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.info("TEST CASE(S) FAILED");
        } finally {
            tearDown();
        }
    }

}
