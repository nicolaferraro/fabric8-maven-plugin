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

import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.docker.util.Logger;

import org.junit.Test;
import org.junit.runner.RunWith;

import mockit.Mocked;
import mockit.integration.junit4.JMockit;

@RunWith(JMockit.class)
public class ApplyServiceTest {

    @Mocked
    private Controller controller;

    @Mocked
    private ClusterAccess clusterAccess;

    @Mocked
    private KubernetesClient kubernetes;

    @Mocked
    private Logger logger;

    @Test
    public void testEmptyBuild() throws Exception {
        ApplyService.ApplyServiceConfig config = new ApplyService.ApplyServiceConfig.Builder()
                .serviceUrlWaitTimeSeconds(1000L)
                .externalProcessLogger(logger)
                .createExternalUrls(false)
                .build();

        ApplyService service = new ApplyService(config, controller, clusterAccess, kubernetes, logger);
        service.applyEntities("", new HashSet<HasMetadata>());
    }

}
