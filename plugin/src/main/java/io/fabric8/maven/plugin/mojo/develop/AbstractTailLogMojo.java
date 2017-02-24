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
package io.fabric8.maven.plugin.mojo.develop;

import io.fabric8.maven.core.service.PodLogService;
import io.fabric8.maven.plugin.mojo.build.ApplyMojo;

import org.apache.maven.plugins.annotations.Parameter;

/**
 */
public class AbstractTailLogMojo extends ApplyMojo {

    @Parameter(property = "fabric8.log.container")
    private String logContainerName;

    @Parameter(property = "fabric8.log.pod")
    private String podName;

    protected PodLogService getLogService() {
        return new PodLogService(getLogServiceContext(), getKubernetesService());
    }

    protected PodLogService.PodLogServiceContext getLogServiceContext() {
        return new PodLogService.PodLogServiceContext.Builder()
                .log(log)
                .logContainerName(logContainerName)
                .podName(podName)
                .newPodLog(createLogger("[[C]][NEW][[C]] "))
                .oldPodLog(createLogger("[[R]][OLD][[R]] "))
                .build();
    }


}
