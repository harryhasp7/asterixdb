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
package org.apache.asterix.installer.test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.asterix.external.util.ExternalDataConstants;
import org.apache.asterix.external.util.IdentitiyResolverFactory;
import org.apache.asterix.test.aql.TestExecutor;
import org.apache.asterix.test.base.RetainLogsRule;
import org.apache.asterix.test.runtime.HDFSCluster;
import org.apache.asterix.testframework.context.TestCaseContext;
import org.apache.asterix.testframework.context.TestFileContext;
import org.apache.asterix.testframework.xml.TestCase.CompilationUnit;
import org.apache.asterix.testframework.xml.TestGroup;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.plexus.util.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Runs the runtime test cases under 'asterix-app/src/test/resources/runtimets'.
 */
@RunWith(Parameterized.class)
public abstract class AbstractExecutionIT {

    protected static final Logger LOGGER = Logger.getLogger(AbstractExecutionIT.class.getName());

    protected static final String PATH_ACTUAL = "target" + File.separator + "ittest" + File.separator;
    protected static final String PATH_BASE = StringUtils
            .join(new String[] { "..", "asterix-app", "src", "test", "resources", "runtimets" }, File.separator);

    protected static final String HDFS_BASE = "../asterix-app/";

    protected static final TestExecutor testExecutor = new TestExecutor();

    private static final String EXTERNAL_LIBRARY_TEST_GROUP = "lib";

    private static final List<String> badTestCases = new ArrayList<>();

    private static String reportPath =
            new File(StringUtils.join(new String[] { "target", "failsafe-reports" }, File.separator)).getAbsolutePath();

    @Rule
    public TestRule retainLogs = new RetainLogsRule(
            AsterixInstallerIntegrationUtil.getManagixHome(), reportPath);

    @BeforeClass
    public static void setUp() throws Exception {
        System.out.println("Starting setup");
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Starting setup");
        }
        File outdir = new File(PATH_ACTUAL);
        outdir.mkdirs();

        HDFSCluster.getInstance().setup(HDFS_BASE);

        //This is nasty but there is no very nice way to set a system property on each NC that I can figure.
        //The main issue is that we need the NC resolver to be the IdentityResolver and not the DNSResolver.
        FileUtils
                .copyFile(
                        new File(StringUtils.join(new String[] { "src", "test", "resources", "integrationts",
                                "asterix-configuration.xml" }, File.separator)),
                        new File(AsterixInstallerIntegrationUtil.getManagixHome() + "/conf/asterix-configuration.xml"));

        AsterixLifecycleIT.setUp();

        File externalTestsJar = new File(StringUtils.join(
                new String[] { "..", "asterix-external-data", "target" }, File.separator)).listFiles(
                        (dir, name) -> name.matches("asterix-external-data-.*-tests.jar"))[0];

        FileUtils.copyFile(externalTestsJar, new File(
                AsterixInstallerIntegrationUtil.getManagixHome() + "/clusters/local/working_dir/asterix/repo/",
                externalTestsJar.getName()));

        AsterixLifecycleIT.restartInstance();

        FileUtils.copyDirectoryStructure(
                new File(StringUtils.join(new String[] { "..", "asterix-app", "data" }, File.separator)),
                new File(AsterixInstallerIntegrationUtil.getManagixHome() + "/clusters/local/working_dir/data"));

        FileUtils.copyDirectoryStructure(
                new File(StringUtils.join(new String[] { "..", "asterix-app", "target", "data" }, File.separator)),
                new File(AsterixInstallerIntegrationUtil.getManagixHome() + "/clusters/local/working_dir/target/data"));

        FileUtils.copyDirectoryStructure(new File(StringUtils.join(new String[] { "target", "data" }, File.separator)),
                new File(AsterixInstallerIntegrationUtil.getManagixHome()
                        + "/clusters/local/working_dir/target/data/csv"));

        // Set the node resolver to be the identity resolver that expects node names
        // to be node controller ids; a valid assumption in test environment.
        System.setProperty(ExternalDataConstants.NODE_RESOLVER_FACTORY_PROPERTY,
                IdentitiyResolverFactory.class.getName());

        reportPath = new File(StringUtils.join(new String[] { "target", "failsafe-reports" }, File.separator))
                .getAbsolutePath();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        File outdir = new File(PATH_ACTUAL);
        File[] files = outdir.listFiles();
        if ((files == null) || (files.length == 0)) {
            outdir.delete();
        }
        AsterixLifecycleIT.tearDown();
        HDFSCluster.getInstance().cleanup();
        if (!badTestCases.isEmpty()) {
            System.out.println("The following test cases left some data");
            for (String testCase : badTestCases) {
                System.out.println(testCase);
            }
        }
    }

    @Parameters
    public static Collection<Object[]> tests() throws Exception {
        Collection<Object[]> testArgs = new ArrayList<Object[]>();
        TestCaseContext.Builder b = new TestCaseContext.Builder();
        for (TestCaseContext ctx : b.build(new File(PATH_BASE))) {
            testArgs.add(new Object[] { ctx });
        }
        return testArgs;
    }

    private TestCaseContext tcCtx;

    public AbstractExecutionIT(TestCaseContext tcCtx) {
        this.tcCtx = tcCtx;
    }

    @Test
    public void test() throws Exception {
        if (skip()) {
            return;
        }
        testExecutor.executeTest(PATH_ACTUAL, tcCtx, null, false);
        testExecutor.cleanup(tcCtx.toString(), badTestCases);
    }

    protected boolean skip() {
        // If the test case contains library commands, we skip them
        List<CompilationUnit> cUnits = tcCtx.getTestCase().getCompilationUnit();
        for (CompilationUnit cUnit : cUnits) {
            List<TestFileContext> testFileCtxs = tcCtx.getTestFiles(cUnit);
            for (TestFileContext ctx : testFileCtxs) {
                if (ctx.getType().equals(EXTERNAL_LIBRARY_TEST_GROUP)) {
                    return true;
                }
            }
        }
        // For now we skip api tests.
        for (TestGroup group : tcCtx.getTestGroups()) {
            if (group != null && "api".equals(group.getName())) {
                LOGGER.info("Skipping test: " + tcCtx.toString());
                return true;
            }
        }
        return false;
    }
}
