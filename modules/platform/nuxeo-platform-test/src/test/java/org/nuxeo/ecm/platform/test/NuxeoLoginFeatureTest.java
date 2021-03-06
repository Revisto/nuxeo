/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Contributors:
 *      Kevin Leturc <kleturc@nuxeo.com>
 */

package org.nuxeo.ecm.platform.test;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.platform.ui.web.auth.service.LoginAsImpl;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.api.login.LoginAs;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * @since 11.1
 */
@RunWith(FeaturesRunner.class)
@Features(NuxeoLoginFeature.class)
public class NuxeoLoginFeatureTest {

    @Test
    public void testLoginAsIsDeployed() {
        LoginAs loginAs = Framework.getService(LoginAs.class);
        assertTrue(loginAs instanceof LoginAsImpl);
    }

}
