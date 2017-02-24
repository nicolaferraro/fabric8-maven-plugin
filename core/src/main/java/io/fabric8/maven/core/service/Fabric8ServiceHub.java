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

import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.config.PlatformMode;
import io.fabric8.maven.core.service.kubernetes.DockerBuildService;
import io.fabric8.maven.core.service.openshift.OpenshiftBuildService;
import io.fabric8.maven.docker.service.ServiceHub;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.client.OpenShiftClient;

/**
 * @author nicola
 * @since 17/02/2017
 */
public class Fabric8ServiceHub {

    /*
     * User parameters
     */
    private ClusterAccess clusterAccess;

    private PlatformMode platformMode;

    private Logger log;

    private ServiceHub dockerServiceHub;

    private GeneratorService generatorService;

    private EnricherService enricherService;

    private WatcherService watcherService;

    private BuildService.BuildServiceConfig buildServiceConfig;

    private ApplyService.ApplyServiceConfig applyServiceConfig;

    private KubernetesService.KubernetesServiceConfig kubernetesServiceConfig;

    private PodLogService.PodLogServiceConfig podLogServiceConfig;

    // TODO move to applyService
    private Controller controller;

    /*
     * Computed resources
     */

    private PlatformMode resolvedPlatformMode;

    private KubernetesClient kubernetesClient;

    private BuildService buildService;

    private ApplyService applyService;

    private KubernetesService kubernetesService;

    private PodLogService podLogService;

    private Fabric8ServiceHub() {
    }

    private void init() {
        ensureNotNull(clusterAccess, "cluster access");
        ensureNotNull(platformMode, "platform mode");
        ensureNotNull(log, "log");

        this.resolvedPlatformMode = clusterAccess.resolvePlatformMode(platformMode, log);
        this.kubernetesClient = clusterAccess.createDefaultClient(log);

        // Creating platform independent modules
        if (applyServiceConfig != null && controller != null) {
            applyService = new ApplyService(applyServiceConfig, controller, clusterAccess, kubernetesClient, log);
        }
        if (kubernetesServiceConfig != null) {
            kubernetesService = new KubernetesService(kubernetesServiceConfig, log, kubernetesClient);
        }
        if(podLogServiceConfig != null && kubernetesService != null) {
            podLogService = new PodLogService(podLogServiceConfig, kubernetesService, kubernetesClient);
        }

        // Creating platform-dependent services
        if (resolvedPlatformMode == PlatformMode.kubernetes) {
            // Kubernetes services
            if (buildServiceConfig != null && dockerServiceHub != null) {
                this.buildService = new DockerBuildService(buildServiceConfig, dockerServiceHub);
            }

        } else if(resolvedPlatformMode == PlatformMode.openshift) {
            // Openshift services
            if (buildServiceConfig != null && dockerServiceHub != null && enricherService != null) {
                this.buildService = new OpenshiftBuildService(buildServiceConfig, (OpenShiftClient) kubernetesClient, log, dockerServiceHub, enricherService);
            }

        } else {
            throw new IllegalArgumentException("Unknown platform mode " + platformMode + " resolved as "+ resolvedPlatformMode);
        }

    }

    public BuildService getBuildService() {
        ensureNotNull(buildService, "build service");
        return buildService;
    }

    public ApplyService getApplyService() {
        ensureNotNull(applyService, "apply service");
        return applyService;
    }

    public KubernetesService getKubernetesService() {
        ensureNotNull(kubernetesService, "kubernetes service");
        return kubernetesService;
    }

    public PodLogService getPodLogService() {
        ensureNotNull(podLogService, "pod log service");
        return podLogService;
    }

    public GeneratorService getGeneratorService() {
        ensureNotNull(generatorService, "generator service");
        return generatorService;
    }

    public EnricherService getEnricherService() {
        ensureNotNull(enricherService, "enricher service");
        return enricherService;
    }

    public WatcherService getWatcherService() {
        ensureNotNull(watcherService, "watcher service");
        return watcherService;
    }

    public boolean isOpenshift() {
        ensureNotNull(resolvedPlatformMode, "resolved platform mode");
        return resolvedPlatformMode == PlatformMode.openshift;
    }

    public KubernetesClient getKubernetesClient() {
        ensureNotNull(kubernetesClient, "kubernetes client");
        return kubernetesClient;
    }

    public ClusterAccess getClusterAccess() {
        ensureNotNull(clusterAccess, "cluster access");
        return clusterAccess;
    }

    public ServiceHub getDockerServiceHub() {
        ensureNotNull(dockerServiceHub, "docker service hub");
        return dockerServiceHub;
    }

    private void ensureNotNull(Object object, String name) {
        if (object == null) {
            throw new IllegalStateException("The " + name + " cannot be obtained: some requirements are missing in the configuration");
        }
    }

    // =================================================

    public static class Builder {

        private Fabric8ServiceHub hub;

        public Builder() {
            this.hub = new Fabric8ServiceHub();
        }

        public Builder clusterAccess(ClusterAccess clusterAccess) {
            hub.clusterAccess = clusterAccess;
            return this;
        }

        public Builder platformMode(PlatformMode platformMode) {
            hub.platformMode = platformMode;
            return this;
        }

        public Builder log(Logger log) {
            hub.log = log;
            return this;
        }

        public Builder dockerServiceHub(ServiceHub dockerServiceHub) {
            hub.dockerServiceHub = dockerServiceHub;
            return this;
        }

        public Builder generatorService(GeneratorService generatorService) {
            hub.generatorService = generatorService;
            return this;
        }

        public Builder enricherService(EnricherService enricherService) {
            hub.enricherService = enricherService;
            return this;
        }

        public Builder watcherService(WatcherService watcherService) {
            hub.watcherService = watcherService;
            return this;
        }

        public Builder buildServiceConfig(BuildService.BuildServiceConfig buildServiceConfig) {
            hub.buildServiceConfig = buildServiceConfig;
            return this;
        }

        public Builder applyServiceConfig(ApplyService.ApplyServiceConfig applyServiceConfig) {
            hub.applyServiceConfig = applyServiceConfig;
            return this;
        }

        public Builder kubernetesServiceConfig(KubernetesService.KubernetesServiceConfig kubernetesServiceConfig) {
            hub.kubernetesServiceConfig = kubernetesServiceConfig;
            return this;
        }

        public Builder podLogServiceConfig(PodLogService.PodLogServiceConfig podLogServiceConfig) {
            hub.podLogServiceConfig = podLogServiceConfig;
            return this;
        }

        public Builder controller(Controller controller) {
            hub.controller = controller;
            return this;
        }

        public Fabric8ServiceHub build() {
            hub.init();
            return hub;
        }
    }

}
