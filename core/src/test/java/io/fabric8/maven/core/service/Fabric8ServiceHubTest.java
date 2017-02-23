/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.core.service;

import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.config.PlatformMode;
import io.fabric8.maven.core.service.kubernetes.DockerBuildService;
import io.fabric8.maven.core.service.openshift.OpenshiftBuildService;
import io.fabric8.maven.docker.service.ServiceHub;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.client.OpenShiftClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import mockit.Expectations;
import mockit.Mocked;
import mockit.integration.junit4.JMockit;

import static org.junit.Assert.assertTrue;

@RunWith(JMockit.class)
public class Fabric8ServiceHubTest {

    private ClusterAccess clusterAccess = new ClusterAccess("test");

    @Mocked
    private ClusterAccess mockClusterAccess;

    @Mocked
    private OpenShiftClient openShiftClient;

    @Mocked
    private Logger log;

    @Mocked
    private ServiceHub dockerServiceHub;

    @Mocked
    private GeneratorService generatorService;

    @Mocked
    private EnricherService enricherService;

    @Before
    public void init() {
        new Expectations() {{
            // OpenshiftClient inherits from KubernetesClient
            mockClusterAccess.createDefaultClient(log); result = openShiftClient;
        }};
    }

    @Test
    public void testDetectedKubernetesBuilderReturned() {
        new Expectations() {{
           mockClusterAccess.resolvePlatformMode(withAny(PlatformMode.class.cast(null)), log); result = PlatformMode.kubernetes;
        }};
        Fabric8ServiceHub hub = createServiceHub(PlatformMode.auto);
        assertTrue(hub.getBuildService() instanceof DockerBuildService);

        Fabric8ServiceHub hub2 = createServiceHub(PlatformMode.kubernetes);
        assertTrue(hub2.getBuildService() instanceof DockerBuildService);
    }

    @Test
    public void testDetectedOpenshiftBuilderReturned() {
        new Expectations() {{
            mockClusterAccess.resolvePlatformMode(withAny(PlatformMode.class.cast(null)), log); result = PlatformMode.openshift;
        }};
        Fabric8ServiceHub hub = createServiceHub(PlatformMode.auto);
        assertTrue(hub.getBuildService() instanceof OpenshiftBuildService);

        Fabric8ServiceHub hub2 = createServiceHub(PlatformMode.openshift);
        assertTrue(hub2.getBuildService() instanceof OpenshiftBuildService);
    }

    private Fabric8ServiceHub createServiceHub(PlatformMode mode) {
        return new Fabric8ServiceHub(mockClusterAccess, mode, log, dockerServiceHub, generatorService, enricherService);
    }

}
