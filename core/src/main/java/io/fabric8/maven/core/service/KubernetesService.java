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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Scaleable;
import io.fabric8.maven.docker.util.ImageName;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.client.OpenShiftClient;

import static io.fabric8.kubernetes.api.KubernetesHelper.getKind;
import static io.fabric8.kubernetes.api.KubernetesHelper.getName;

/**
 * @author nicola
 * @since 24/02/2017
 */
public class KubernetesService {

    private Logger log;

    private KubernetesClient kubernetes;

    private String s2iBuildNameSuffix;

    public KubernetesService(Logger log, KubernetesClient kubernetes, String s2iBuildNameSuffix) {
        this.log = log;
        this.kubernetes = kubernetes;
        this.s2iBuildNameSuffix = s2iBuildNameSuffix;
    }

    public KubernetesClient getKubernetesClient() {
        return kubernetes;
    }

    public void resizeApp(String namespace, Set<HasMetadata> entities, int replicas) {
        for (HasMetadata entity : entities) {
            String name = getName(entity);
            Scaleable<?> scalable = null;
            if (entity instanceof Deployment) {
                scalable = kubernetes.extensions().deployments().inNamespace(namespace).withName(name);
            } else if (entity instanceof ReplicaSet) {
                scalable = kubernetes.extensions().replicaSets().inNamespace(namespace).withName(name);
            } else if (entity instanceof ReplicationController) {
                scalable = kubernetes.replicationControllers().inNamespace(namespace).withName(name);
            } else if (entity instanceof DeploymentConfig) {
                OpenShiftClient openshiftClient = new Controller(kubernetes).getOpenShiftClientOrNull();
                if (openshiftClient == null) {
                    log.warn("Ignoring DeploymentConfig %s as not connected to an OpenShift cluster", name);
                    continue;
                }
                scalable = openshiftClient.deploymentConfigs().inNamespace(namespace).withName(name);
            }
            if (scalable != null) {
                log.info("Scaling " + getKind(entity) + " " + namespace + "/" + name + " to replicas: " + replicas);
                scalable.scale(replicas, true);
            }
        }
    }

    public void deleteEntities(String namespace, Set<HasMetadata> entities) {
        List<HasMetadata> list = new ArrayList<>(entities);

        // For OpenShift cluster, also delete s2i buildconfig
        OpenShiftClient openshiftClient = new Controller(kubernetes).getOpenShiftClientOrNull();
        if (openshiftClient != null) {
            for (HasMetadata entity : list) {
                if ("ImageStream".equals(getKind(entity))) {
                    ImageName imageName = new ImageName(entity.getMetadata().getName());
                    String buildName = getS2IBuildName(imageName, s2iBuildNameSuffix);
                    log.info("Deleting resource BuildConfig " + namespace + "/" + buildName);
                    openshiftClient.buildConfigs().inNamespace(namespace).withName(buildName).delete();
                }
            }
        }

        // lets delete in reverse order
        Collections.reverse(list);

        for (HasMetadata entity : list) {
            log.info("Deleting resource " + getKind(entity) + " " + namespace + "/" + getName(entity));
            kubernetes.resource(entity).inNamespace(namespace).cascading(true).delete();
        }
    }

    private static String getS2IBuildName(ImageName imageName, String s2iBuildNameSuffix) {
        return imageName.getSimpleName() + s2iBuildNameSuffix;
    }



}
