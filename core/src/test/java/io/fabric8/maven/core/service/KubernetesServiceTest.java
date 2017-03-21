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

import java.util.HashSet;
import java.util.Set;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSet;
import io.fabric8.maven.core.test.support.KubernetesTestUtils;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.server.mock.OpenShiftMockServer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import mockit.Mocked;
import mockit.integration.junit4.JMockit;

import static org.junit.Assert.assertEquals;

@RunWith(JMockit.class)
public class KubernetesServiceTest {

    @Mocked
    private Logger logger;

    private KubernetesService.KubernetesServiceConfig config;

    @Before
    public void init() {
        config = new KubernetesService.KubernetesServiceConfig.Builder()
                .s2iBuildNameSuffix("-s2i")
                .build();
    }

    @Test
    public void testResize() throws Exception {

        Set<HasMetadata> resources = KubernetesTestUtils.createAllResourceTypes("name", 1, 0L);

        OpenShiftMockServer mockServer = createMockServer(resources, "name", 2);

        KubernetesService service = new KubernetesService(config, logger, mockServer.createOpenShiftClient());

        service.resizeApp("ns", resources, 2);

        assertEquals(5 * resources.size(), mockServer.getRequestCount());
    }



    protected OpenShiftMockServer createMockServer(Set<HasMetadata> resources, String name, int targetReplicas) {
        OpenShiftMockServer mockServer = new OpenShiftMockServer(false);

        mockResponse(mockServer, "/oapi/v1/namespaces/ns/deploymentconfigs/" + name, filter(resources, DeploymentConfig.class), KubernetesTestUtils.createResource(KubernetesTestUtils.ScalableResurce.DEPLOYMENT_CONFIG, name, targetReplicas, 1L));
        mockResponse(mockServer, "/apis/extensions/v1beta1/namespaces/ns/deployments/" + name, filter(resources, Deployment.class), KubernetesTestUtils.createResource(KubernetesTestUtils.ScalableResurce.DEPLOYMENT, name, targetReplicas, 1L));
        mockResponse(mockServer, "/api/v1/namespaces/ns/replicationcontrollers/" + name, filter(resources, ReplicationController.class), KubernetesTestUtils.createResource(KubernetesTestUtils.ScalableResurce.REPLICATION_CONTROLLER, name, targetReplicas, 1L));
        mockResponse(mockServer, "/apis/extensions/v1beta1/namespaces/ns/replicasets/" + name, filter(resources, ReplicaSet.class), KubernetesTestUtils.createResource(KubernetesTestUtils.ScalableResurce.REPLICA_SET, name, targetReplicas, 1L));

        return mockServer;
    }

    protected void mockResponse(OpenShiftMockServer mockServer, String path, HasMetadata resource, HasMetadata scaledResource) {
        mockServer.expect().get().withPath(path).andReturn(200, resource).times(3);
        mockServer.expect().get().withPath(path).andReturn(200, scaledResource).once();
        mockServer.expect().patch().withPath(path).andReturn(200, resource).once();
    }

    protected HasMetadata filter(Set<HasMetadata> resources, Class<? extends HasMetadata> type) {
        for(HasMetadata hm : resources) {
            if(type.isInstance(hm)) {
                return type.cast(hm);
            }
        }
        throw new IllegalArgumentException("Type not found: " + type);
    }


}
