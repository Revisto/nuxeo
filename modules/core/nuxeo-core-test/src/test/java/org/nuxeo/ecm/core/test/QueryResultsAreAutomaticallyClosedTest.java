/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Stephane Lacoin
 */
package org.nuxeo.ecm.core.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.inject.Inject;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;
import org.nuxeo.runtime.test.runner.LogCaptureFeature.NoLogCaptureFilterException;
import org.nuxeo.runtime.transaction.TransactionHelper;

@RunWith(FeaturesRunner.class)
@Features({ CoreFeature.class, LogCaptureFeature.class })
@RepositoryConfig(init = DefaultRepositoryInit.class)
@LogCaptureFeature.FilterWith(QueryResultsAreAutomaticallyClosedTest.LogFilter.class)
public class QueryResultsAreAutomaticallyClosedTest {

    private static final String VCS_CLOSING_WARN = "Closing a query results for you, check stack trace for allocating point";

    public static class LogFilter implements LogCaptureFeature.Filter {
        @Override
        public boolean accept(LogEvent event) {
            return Level.WARN.equals(event.getLevel());
        }
    }

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected LogCaptureFeature.Result logCaptureResults;

    protected void assertWarnInLogs() throws NoLogCaptureFilterException {
        if (coreFeature.getStorageConfiguration().isVCS()) {
            logCaptureResults.assertHasEvent();
            LogEvent event = logCaptureResults.getCaughtEvents().get(0);
            assertEquals(Level.WARN, event.getLevel());
            assertEquals(VCS_CLOSING_WARN, event.getMessage().getFormattedMessage());
        }
    }

    // needs a JCA connection for this to work
    @Test
    public void testTransactional() throws Exception {
        CoreSession session = coreFeature.getCoreSessionSystem();
        try (IterableQueryResult results = session.queryAndFetch("SELECT * from Document", "NXQL")) {
            TransactionHelper.commitOrRollbackTransaction();
            TransactionHelper.startTransaction();
            assertFalse(results.mustBeClosed());
            assertWarnInLogs();
        }
    }

    protected static class NestedQueryRunner extends UnrestrictedSessionRunner {

        public NestedQueryRunner(String reponame) {
            super(reponame);
        }

        protected IterableQueryResult result;

        @Override
        public void run() {
            result = session.queryAndFetch("SELECT * from Document", "NXQL");
        }

    }

    @SuppressWarnings("resource") // we specifically test indirect close
    @Test
    public void testNested() throws Exception {
        IterableQueryResult mainResults;
        CoreSession main = coreFeature.getCoreSessionSystem();
        NestedQueryRunner runner = new NestedQueryRunner(main.getRepositoryName());
        mainResults = main.queryAndFetch("SELECT * from Document", "NXQL");
        runner.runUnrestricted();
        if (coreFeature.getStorageConfiguration().isVCS()) {
            // autoclose done at commit time, not CoreSession close time
            assertTrue(runner.result.mustBeClosed());
            assertTrue(mainResults.mustBeClosed());
        }
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        assertFalse(mainResults.mustBeClosed());
        assertWarnInLogs();
    }
}
