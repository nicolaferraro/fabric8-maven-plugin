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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.fabric8.kubernetes.api.Annotations;
import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.DoneableService;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressRuleValue;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressBackend;
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder;
import io.fabric8.kubernetes.api.model.extensions.IngressList;
import io.fabric8.kubernetes.api.model.extensions.IngressRule;
import io.fabric8.kubernetes.api.model.extensions.IngressSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ClientResource;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.util.ProcessUtil;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.api.model.RouteTargetReference;
import io.fabric8.openshift.api.model.RouteTargetReferenceBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.utils.Strings;

import com.fasterxml.jackson.core.JsonProcessingException;

import static io.fabric8.kubernetes.api.KubernetesHelper.createIntOrString;
import static io.fabric8.kubernetes.api.KubernetesHelper.getLabels;
import static io.fabric8.kubernetes.api.KubernetesHelper.getName;
import static io.fabric8.kubernetes.api.KubernetesHelper.getOrCreateAnnotations;

/**
 * @author nicola
 * @since 23/02/2017
 */
public class ApplyService {

    private Logger log;

    private ClusterAccess clusterAccess;

    private KubernetesClient kubernetes;

    private ApplyServiceConfig config;

    private Controller controller;

    public ApplyService(ApplyServiceConfig config, Controller controller, ClusterAccess clusterAccess, KubernetesClient kubernetes, Logger log) {
        this.config = config;
        this.log = log;
        this.clusterAccess = clusterAccess;
        this.kubernetes = kubernetes;
        this.controller = controller;
    }


    public File findKubeCtlExecutable() throws Exception {
        OpenShiftClient openShiftClient = controller.getOpenShiftClientOrNull();
        String command = openShiftClient != null ? "oc" : "kubectl";

        String missingCommandMessage;
        File file = ProcessUtil.findExecutable(log, command);
        if (file == null && command.equals("oc")) {
            file = ProcessUtil.findExecutable(log, command);
            missingCommandMessage = "commands oc or kubectl";
        } else {
            missingCommandMessage = "command " + command;
        }
        if (file == null) {
            throw new IllegalStateException("Could not find " + missingCommandMessage +
                    ". Please try running `mvn fabric8:install` to install the necessary binaries and ensure they get added to your $PATH");
        }
        return file;
    }

    public void applyEntity(String fileName, HasMetadata entity) throws Exception {
        applyEntities(fileName, Collections.singleton(entity));
    }

    public void applyEntities(String fileName, Set<HasMetadata> entities) throws Exception {
        // lets check we have created the namespace
        String namespace = clusterAccess.getNamespace();
        controller.applyNamespace(namespace);
        controller.setNamespace(namespace);
        if (config.isCreateExternalUrls()) {
            if (controller.getOpenShiftClientOrNull() != null) {
                createRoutes(controller, entities);
            } else {
                createIngress(controller, kubernetes, entities);
            }
        }

        // Apply all items
        for (HasMetadata entity : entities) {
            if (entity instanceof Pod) {
                Pod pod = (Pod) entity;
                controller.applyPod(pod, fileName);
            } else if (entity instanceof Service) {
                Service service = (Service) entity;
                controller.applyService(service, fileName);
            } else if (entity instanceof ReplicationController) {
                ReplicationController replicationController = (ReplicationController) entity;
                controller.applyReplicationController(replicationController, fileName);
            } else if (entity != null) {
                controller.apply(entity, fileName);
            }
        }

        File file = null;
        try {
            file = findKubeCtlExecutable();
        } catch (Exception e) {
            log.warn("%s", e.getMessage());
        }
        if (file != null) {
            log.info("[[B]]HINT:[[B]] Use the command `%s get pods -w` to watch your pods start up",file.getName());
        }

        Logger serviceLogger = config.getExternalProcessLogger();
        long serviceUrlWaitTimeSeconds = config.getServiceUrlWaitTimeSeconds();
        for (HasMetadata entity : entities) {
            if (entity instanceof Service) {
                Service service = (Service) entity;
                String name = getName(service);
                ClientResource<Service, DoneableService> serviceResource = kubernetes.services().inNamespace(namespace).withName(name);
                String url = null;
                // lets wait a little while until there is a service URL in case the exposecontroller is running slow
                for (int i = 0; i < serviceUrlWaitTimeSeconds; i++) {
                    if (i > 0) {
                        Thread.sleep(1000);
                    }
                    Service s = serviceResource.get();
                    if (s != null) {
                        url = getExternalServiceURL(s);
                        if (Strings.isNotBlank(url)) {
                            break;
                        }
                    }
                    if (!isExposeService(service)) {
                        break;
                    }
                }

                // lets not wait for other services
                serviceUrlWaitTimeSeconds = 1;
                if (Strings.isNotBlank(url) && url.startsWith("http")) {
                    serviceLogger.info("" + name + ": " + url);
                }
            }
        }
    }

    private Route createRouteForService(String routeDomainPostfix, String namespace, Service service) {
        Route route = null;
        String id = KubernetesHelper.getName(service);
        if (Strings.isNotBlank(id) && hasExactlyOneService(service, id)) {
            route = new Route();
            String routeId = id;
            KubernetesHelper.setName(route, namespace, routeId);
            RouteSpec routeSpec = new RouteSpec();
            RouteTargetReference objectRef = new RouteTargetReferenceBuilder().withName(id).build();
            //objectRef.setNamespace(namespace);
            routeSpec.setTo(objectRef);
            if (!Strings.isNullOrBlank(routeDomainPostfix)) {
                String host = Strings.stripSuffix(Strings.stripSuffix(id, "-service"), ".");
                routeSpec.setHost(host + "." + Strings.stripPrefix(routeDomainPostfix, "."));
            } else {
                routeSpec.setHost("");
            }
            route.setSpec(routeSpec);
            String json;
            try {
                json = KubernetesHelper.toJson(route);
            } catch (JsonProcessingException e) {
                json = e.getMessage() + ". object: " + route;
            }
            log.debug("Created route: " + json);
        }
        return route;
    }

    private Ingress createIngressForService(String routeDomainPostfix, String namespace, Service service) {
        Ingress ingress = null;
        String serviceName = KubernetesHelper.getName(service);
        ServiceSpec serviceSpec = service.getSpec();
        if (serviceSpec != null && Strings.isNotBlank(serviceName) && shouldCreateExternalURLForService(service, serviceName)) {
            String ingressId = serviceName;
            String host = "";
            if (Strings.isNotBlank(routeDomainPostfix)) {
                host = serviceName + "." + namespace + "." + Strings.stripPrefix(routeDomainPostfix, ".");
            }
            List<HTTPIngressPath> paths = new ArrayList<>();
            List<ServicePort> ports = serviceSpec.getPorts();
            if (ports != null) {
                for (ServicePort port : ports) {
                    Integer portNumber = port.getPort();
                    if (portNumber != null) {
                        HTTPIngressPath path = new HTTPIngressPathBuilder().withNewBackend().
                                withServiceName(serviceName).withServicePort(createIntOrString(portNumber.intValue())).
                                endBackend().build();
                        paths.add(path);
                    }
                }
            }
            if (paths.isEmpty()) {
                return ingress;
            }
            ingress = new IngressBuilder().
                    withNewMetadata().withName(ingressId).withNamespace(namespace).endMetadata().
                    withNewSpec().
                    addNewRule().
                    withHost(host).
                    withNewHttp().
                    withPaths(paths).
                    endHttp().
                    endRule().
                    endSpec().build();

            String json;
            try {
                json = KubernetesHelper.toJson(ingress);
            } catch (JsonProcessingException e) {
                json = e.getMessage() + ". object: " + ingress;
            }
            log.debug("Created ingress: " + json);
        }
        return ingress;
    }

    /**
     * Should we try to create an external URL for the given service?
     * <p/>
     * By default lets ignore the kubernetes services and any service which does not expose ports 80 and 443
     *
     * @return true if we should create an OpenShift Route for this service.
     */
    private boolean shouldCreateExternalURLForService(Service service, String id) {
        if ("kubernetes".equals(id) || "kubernetes-ro".equals(id)) {
            return false;
        }
        Set<Integer> ports = KubernetesHelper.getPorts(service);
        log.debug("Service " + id + " has ports: " + ports);
        if (ports.size() == 1) {
            String type = null;
            ServiceSpec spec = service.getSpec();
            if (spec != null) {
                type = spec.getType();
                if (Objects.equals(type, "LoadBalancer")) {
                    return true;
                }
            }
            log.info("Not generating route for service " + id + " type is not LoadBalancer: " + type);
            return false;
        } else {
            log.info("Not generating route for service " + id + " as only single port services are supported. Has ports: " + ports);
            return false;
        }
    }

    private boolean hasExactlyOneService(Service service, String id) {
        Set<Integer> ports = KubernetesHelper.getPorts(service);
        if (ports.size() != 1) {
            log.info("Not generating route for service " + id + " as only single port services are supported. Has ports: " +
                    ports);
            return false;
        } else {
            return true;
        }
    }

    private String getExternalServiceURL(Service service) {
        return getOrCreateAnnotations(service).get(Annotations.Service.EXPOSE_URL);
    }

    private boolean isExposeService(Service service) {
        String expose = getLabels(service).get("expose");
        return expose != null && expose.toLowerCase().equals("true");
    }


// CHECK: code removed because it was never used

//    protected static Object applyTemplates(Template template, KubernetesClient kubernetes, Controller controller, String namespace, String fileName, MavenProject project, Logger log) throws Exception {
//        KubernetesHelper.setNamespace(template, namespace);
//        overrideTemplateParameters(template, project, log);
//        return controller.applyTemplate(template, fileName);
//    }
//
//    /**
//     * Before applying the given template lets allow template parameters to be overridden via the maven
//     * properties - or optionally - via the command line if in interactive mode.
//     */
//    protected static void overrideTemplateParameters(Template template, MavenProject project, Logger log) {
//        List<io.fabric8.openshift.api.model.Parameter> parameters = template.getParameters();
//        if (parameters != null && project != null) {
//            Properties properties = getProjectAndFabric8Properties(project);
//            boolean missingProperty = false;
//            for (io.fabric8.openshift.api.model.Parameter parameter : parameters) {
//                String parameterName = parameter.getName();
//                String name = "fabric8.apply." + parameterName;
//                String propertyValue = properties.getProperty(name);
//                if (propertyValue != null) {
//                    log.info("Overriding template parameter " + name + " with value: " + propertyValue);
//                    parameter.setValue(propertyValue);
//                } else {
//                    missingProperty = true;
//                    log.info("No property defined for template parameter: " + name);
//                }
//            }
//            if (missingProperty) {
//                log.debug("Current properties " + new TreeSet<>(properties.keySet()));
//            }
//        }
//    }
//
//    protected static Properties getProjectAndFabric8Properties(MavenProject project) {
//        Properties properties = project.getProperties();
//        properties.putAll(project.getProperties());
//        // let system properties override so we can read from the command line
//        properties.putAll(System.getProperties());
//        return properties;
//    }

    private void createRoutes(Controller controller, Collection<HasMetadata> collection) {
        String routeDomainPostfix = config.getRouteDomain();
        String namespace = clusterAccess.getNamespace();
        // lets get the routes first to see if we should bother
        try {
            OpenShiftClient openshiftClient = controller.getOpenShiftClientOrNull();
            if (openshiftClient == null) {
                return;
            }
            RouteList routes = openshiftClient.routes().inNamespace(namespace).list();
            if (routes != null) {
                routes.getItems();
            }
        } catch (Exception e) {
            log.warn("Cannot load OpenShift Routes; maybe not connected to an OpenShift platform? " + e, e);
            return;
        }
        List<Route> routes = new ArrayList<>();
        for (Object object : collection) {
            if (object instanceof Service) {
                Service service = (Service) object;
                Route route = createRouteForService(routeDomainPostfix, namespace, service);
                if (route != null) {
                    routes.add(route);
                }
            }
        }
        collection.addAll(routes);
    }

    private void createIngress(Controller controller, KubernetesClient kubernetesClient, Collection<HasMetadata> collection) {
        String routeDomainPostfix = config.getRouteDomain();
        String namespace = clusterAccess.getNamespace();
        List<Ingress> ingressList = null;
        // lets get the routes first to see if we should bother
        try {
            IngressList ingresses = kubernetesClient.extensions().ingresses().inNamespace(namespace).list();
            if (ingresses != null) {
                ingressList = ingresses.getItems();
            }
        } catch (Exception e) {
            log.warn("Cannot load Ingress instances. Must be an older version of Kubernetes? Error: " + e, e);
            return;
        }
        List<Ingress> ingresses = new ArrayList<>();
        for (Object object : collection) {
            if (object instanceof Service) {
                Service service = (Service) object;
                if (!serviceHasIngressRule(ingressList, service)) {
                    Ingress ingress = createIngressForService(routeDomainPostfix, namespace, service);
                    if (ingress != null) {
                        ingresses.add(ingress);
                        log.info("Created ingress for " + namespace + ":" + KubernetesHelper.getName(service));
                    } else {
                        log.debug("No ingress required for " + namespace + ":" + KubernetesHelper.getName(service));
                    }
                } else {
                    log.info("Already has ingress for service " + namespace + ":" + KubernetesHelper.getName(service));
                }
            }
        }
        collection.addAll(ingresses);

    }

    /**
     * Returns true if there is an existing ingress rule for the given service
     */
    private boolean serviceHasIngressRule(List<Ingress> ingresses, Service service) {
        String serviceName = KubernetesHelper.getName(service);
        for (Ingress ingress : ingresses) {
            IngressSpec spec = ingress.getSpec();
            if (spec == null) {
                break;
            }
            List<IngressRule> rules = spec.getRules();
            if (rules == null) {
                break;
            }
            for (IngressRule rule : rules) {
                HTTPIngressRuleValue http = rule.getHttp();
                if (http == null) {
                    break;
                }
                List<HTTPIngressPath> paths = http.getPaths();
                if (paths == null) {
                    break;
                }
                for (HTTPIngressPath path : paths) {
                    IngressBackend backend = path.getBackend();
                    if (backend == null) {
                        break;
                    }
                    if (Objects.equals(serviceName, backend.getServiceName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Class to hold configuration parameters for the apply service.
     */
    public static class ApplyServiceConfig {

        private boolean createExternalUrls;

        private Long serviceUrlWaitTimeSeconds;

        private Logger externalProcessLogger;

        private String routeDomain;

        public ApplyServiceConfig() {
        }

        public boolean isCreateExternalUrls() {
            return createExternalUrls;
        }

        public Long getServiceUrlWaitTimeSeconds() {
            return serviceUrlWaitTimeSeconds;
        }

        public Logger getExternalProcessLogger() {
            return externalProcessLogger;
        }

        public String getRouteDomain() {
            return routeDomain;
        }

        public static class Builder {
            private ApplyService.ApplyServiceConfig config;

            public Builder() {
                this.config = new ApplyService.ApplyServiceConfig();
            }

            public Builder(ApplyService.ApplyServiceConfig config) {
                this.config = config;
            }

            public Builder createExternalUrls(boolean createExternalUrls) {
                config.createExternalUrls = createExternalUrls;
                return this;
            }

            public Builder serviceUrlWaitTimeSeconds(Long serviceUrlWaitTimeSeconds) {
                config.serviceUrlWaitTimeSeconds = serviceUrlWaitTimeSeconds;
                return this;
            }

            public Builder externalProcessLogger(Logger externalProcessLogger) {
                config.externalProcessLogger = externalProcessLogger;
                return this;
            }

            public Builder routeDomain(String routeDomain) {
                config.routeDomain = routeDomain;
                return this;
            }

            public ApplyService.ApplyServiceConfig build() {
                return config;
            }

        }

    }

}
