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
package io.fabric8.maven.plugin.mojo.build;


import java.io.File;
import java.net.URL;
import java.util.Set;

import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.service.ApplyService;
import io.fabric8.maven.core.service.KubernetesService;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.plugin.mojo.AbstractFabric8Mojo;
import io.fabric8.utils.Files;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Base class for goals which deploy the generated artifacts into the Kubernetes cluster
 */
@Mojo(name = "apply", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.INSTALL)
public class ApplyMojo extends AbstractFabric8Mojo {

    public static final String DEFAULT_KUBERNETES_MANIFEST = "${basedir}/target/classes/META-INF/fabric8/kubernetes.yml";
    public static final String DEFAULT_OPENSHIFT_MANIFEST = "${basedir}/target/classes/META-INF/fabric8/openshift.yml";

    /**
     * The domain added to the service ID when creating OpenShift routes
     */
    @Parameter(property = "fabric8.domain")
    protected String routeDomain;

    /**
     * Should we fail the build if an apply fails?
     */
    @Parameter(property = "fabric8.deploy.failOnError", defaultValue = "true")
    protected boolean failOnError;

    /**
     * Should we update resources by deleting them first and then creating them again?
     */
    @Parameter(property = "fabric8.recreate", defaultValue = "false")
    protected boolean recreate;

    /**
     * The generated kubernetes YAML file
     */
    @Parameter(property = "fabric8.kubernetesManifest", defaultValue = DEFAULT_KUBERNETES_MANIFEST)
    private File kubernetesManifest;

    /**
     * The generated openshift YAML file
     */
    @Parameter(property = "fabric8.openshiftManifest", defaultValue = DEFAULT_OPENSHIFT_MANIFEST)
    private File openshiftManifest;

    /**
     * Should we create new kubernetes resources?
     */
    @Parameter(property = "fabric8.deploy.create", defaultValue = "true")
    private boolean createNewResources;

    /**
     * Should we use rolling upgrades to apply changes?
     */
    @Parameter(property = "fabric8.rolling", defaultValue = "false")
    private boolean rollingUpgrades;

    /**
     * Should we fail if there is no kubernetes json
     */
    @Parameter(property = "fabric8.deploy.failOnNoKubernetesJson", defaultValue = "false")
    private boolean failOnNoKubernetesJson;

    /**
     * In services only mode we only process services so that those can be recursively created/updated first
     * before creating/updating any pods and replication controllers
     */
    @Parameter(property = "fabric8.deploy.servicesOnly", defaultValue = "false")
    private boolean servicesOnly;

    /**
     * Do we want to ignore services. This is particularly useful when in recreate mode
     * to let you easily recreate all the ReplicationControllers and Pods but leave any service
     * definitions alone to avoid changing the portalIP addresses and breaking existing pods using
     * the service.
     */
    @Parameter(property = "fabric8.deploy.ignoreServices", defaultValue = "false")
    private boolean ignoreServices;

    /**
     * Process templates locally in Java so that we can apply OpenShift templates on any Kubernetes environment
     */
    @Parameter(property = "fabric8.deploy.processTemplatesLocally", defaultValue = "false")
    private boolean processTemplatesLocally;

    /**
     * Should we delete all the pods if we update a Replication Controller
     */
    @Parameter(property = "fabric8.deploy.deletePods", defaultValue = "true")
    private boolean deletePodsOnReplicationControllerUpdate;

    /**
     * Do we want to ignore OAuthClients which are already running?. OAuthClients are shared across namespaces
     * so we should not try to update or create/delete global oauth clients
     */
    @Parameter(property = "fabric8.deploy.ignoreRunningOAuthClients", defaultValue = "true")
    private boolean ignoreRunningOAuthClients;

    /**
     * Should we create external Ingress/Routes for any LoadBalancer Services which don't already have them.
     * <p>
     * We now do not do this by default and defer this to the
     * <a href="https://github.com/fabric8io/exposecontroller/">exposecontroller</a> to decide
     * if Ingress or Router is being used or whether we should use LoadBalancer or NodePorts for single node clusters
     */
    @Parameter(property = "fabric8.deploy.createExternalUrls", defaultValue = "false")
    private boolean createExternalUrls;

    /**
     * The folder we should store any temporary json files or results
     */
    @Parameter(property = "fabric8.deploy.jsonLogDir", defaultValue = "${basedir}/target/fabric8/applyJson")
    private File jsonLogDir;

    /**
     * Namespace on which to operate
     */
    @Parameter(property = "fabric8.namespace")
    private String namespace;

    /**
     * How many seconds to wait for a URL to be generated for a service
     */
    @Parameter(property = "fabric8.serviceUrl.waitSeconds", defaultValue = "5")
    protected long serviceUrlWaitTimeSeconds;

    /**
     * The S2I binary builder BuildConfig name suffix appended to the image name to avoid
     * clashing with the underlying BuildConfig for the Jenkins pipeline
     */
    @Parameter(property = "fabric8.s2i.buildNameSuffix", defaultValue = "-s2i")
    protected String s2iBuildNameSuffix;

    private ClusterAccess clusterAccess;

    private KubernetesClient kubernetes;

    public void executeInternal() throws MojoExecutionException, MojoFailureException {
        clusterAccess = new ClusterAccess(namespace);

        try {
            kubernetes = clusterAccess.createDefaultClient(log);
            URL masterUrl = kubernetes.getMasterUrl();
            File manifest;
            if (KubernetesHelper.isOpenShift(kubernetes)) {
                manifest = openshiftManifest;
            } else {
                manifest = kubernetesManifest;
            }
            if (!Files.isFile(manifest)) {
                if (failOnNoKubernetesJson) {
                    throw new MojoFailureException("No such generated manifest file: " + manifest);
                } else {
                    log.warn("No such generated manifest file %s for this project so ignoring", manifest);
                    return;
                }
            }

            String clusterKind = "Kubernetes";
            if (KubernetesHelper.isOpenShift(kubernetes)) {
                clusterKind = "OpenShift";
            }
            KubernetesResourceUtil.validateKubernetesMasterUrl(masterUrl);

            String namespace = clusterAccess.getNamespace();
            log.info("Using %s at %s in namespace %s with manifest %s ", clusterKind, masterUrl, namespace, manifest);



            Set<HasMetadata> entities = KubernetesResourceUtil.loadResources(manifest);

            applyEntities(kubernetes, namespace, manifest.getName(), entities);

        } catch (KubernetesClientException e) {
            KubernetesResourceUtil.handleKubernetesClientException(e, this.log);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    protected void applyEntities(KubernetesClient kubernetes, String namespace, String fileName, Set<HasMetadata> entities) throws Exception {
        getApplyService().applyEntities(fileName, entities);
    }

    protected ApplyService getApplyService() {
        return new ApplyService(getApplyServiceConfig(), createController(), clusterAccess, kubernetes, log);
    }

    protected KubernetesService getKubernetesService() {
        return new KubernetesService(log, kubernetes, s2iBuildNameSuffix);
    }

    protected ApplyService.ApplyServiceConfig getApplyServiceConfig() {
        return new ApplyService.ApplyServiceConfig.Builder()
                .createExternalUrls(createExternalUrls)
                .externalProcessLogger(createExternalProcessLogger("[[G]][SVC][[G]] "))
                .routeDomain(routeDomain)
                .serviceUrlWaitTimeSeconds(serviceUrlWaitTimeSeconds)
                .build();
    }

    protected Controller createController() {
        Controller controller = new Controller(clusterAccess.createDefaultClient(log));
        controller.setThrowExceptionOnError(failOnError);
        controller.setRecreateMode(recreate);
        controller.setAllowCreate(createNewResources);
        controller.setServicesOnlyMode(servicesOnly);
        controller.setIgnoreServiceMode(ignoreServices);
        controller.setLogJsonDir(jsonLogDir);
        controller.setBasedir(getRootProjectFolder());
        controller.setIgnoreRunningOAuthClients(ignoreRunningOAuthClients);
        controller.setProcessTemplatesLocally(processTemplatesLocally);
        controller.setDeletePodsOnReplicationControllerUpdate(deletePodsOnReplicationControllerUpdate);
        controller.setRollingUpgrade(rollingUpgrades);
        controller.setRollingUpgradePreserveScale(isRollingUpgradePreserveScale());

        getLog().debug("Using recreate mode: " + recreate);

        boolean openShift = KubernetesHelper.isOpenShift(kubernetes);
        if (openShift) {
            getLog().info("OpenShift platform detected");
        } else {
            disableOpenShiftFeatures(controller);
        }

        return controller;
    }

    /**
     * Lets disable OpenShift-only features if we are not running on OpenShift
     */
    protected void disableOpenShiftFeatures(Controller controller) {
        // TODO we could check if the Templates service is running and if so we could still support templates?
        this.processTemplatesLocally = true;
        controller.setSupportOAuthClients(false);
        controller.setProcessTemplatesLocally(true);
    }

    public boolean isRollingUpgradePreserveScale() {
        return false;
    }

    /**
     * Returns the root project folder
     */
    protected File getRootProjectFolder() {
        File answer = null;
        while (project != null) {
            File basedir = project.getBasedir();
            if (basedir != null) {
                answer = basedir;
            }
            project = project.getParent();
        }
        return answer;
    }

}
