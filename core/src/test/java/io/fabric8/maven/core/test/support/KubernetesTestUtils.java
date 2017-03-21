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
package io.fabric8.maven.core.test.support;

import java.util.HashSet;
import java.util.Set;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.JobBuilder;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.extensions.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.fabric8.openshift.api.model.DeploymentConfigBuilder;

/**
 * @author nicola
 * @since 24/02/2017
 */
public class KubernetesTestUtils {

    public static Set<HasMetadata> createAllResourceTypes(String name, int replicas, Long observedGeneration) {
        Set<HasMetadata> result = new HashSet<>();
        for (ScalableResurce sr : ScalableResurce.values()) {
            result.add(createResource(sr, name, replicas, observedGeneration));
        }
        return result;
    }

    public static HasMetadata createResource(ScalableResurce resourceType, String name, int replicas, Long observedGeneration) {
        switch (resourceType) {
        case DEPLOYMENT:
            return new DeploymentBuilder().withNewMetadata().withGeneration(0L).withName(name).endMetadata().withNewSpec().withReplicas(replicas).endSpec().withNewStatus().withReplicas(replicas).withObservedGeneration(observedGeneration).endStatus().build();
        case DEPLOYMENT_CONFIG:
            return new DeploymentConfigBuilder().withNewMetadata().withGeneration(0L).withName(name).endMetadata().withNewSpec().withReplicas(replicas).endSpec().withNewStatus().withReplicas(replicas).withObservedGeneration(observedGeneration).endStatus().build();
        case REPLICA_SET:
            return new ReplicaSetBuilder().withNewMetadata().withGeneration(0L).withName(name).endMetadata().withNewSpec().withReplicas(replicas).endSpec().withNewStatus().withReplicas(replicas).withObservedGeneration(observedGeneration).endStatus().build();
        case REPLICATION_CONTROLLER:
            return new ReplicationControllerBuilder().withNewMetadata().withGeneration(0L).withName(name).endMetadata().withNewSpec().withReplicas(replicas).endSpec().withNewStatus().withReplicas(replicas).withObservedGeneration(observedGeneration).endStatus().build();
        case STATEFUL_SET:
            return new StatefulSetBuilder().withNewMetadata().withGeneration(0L).withName(name).endMetadata().withNewSpec().withReplicas(replicas).endSpec().withNewStatus().withReplicas(replicas).withObservedGeneration(observedGeneration).endStatus().build();
        default:
            throw new IllegalArgumentException("Unsupported resource type: " + resourceType);
        }
    }


    public enum ScalableResurce {
        REPLICA_SET,
        REPLICATION_CONTROLLER,
        DEPLOYMENT,
        DEPLOYMENT_CONFIG,
        STATEFUL_SET
    }
}
