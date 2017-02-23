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
package io.fabric8.maven.generator.api;

import java.util.List;
import java.util.Objects;

import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.service.GeneratorService;
import io.fabric8.maven.core.util.ClassUtil;
import io.fabric8.maven.core.util.PluginServiceFactory;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.util.Logger;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Service responsible for finding and calling generators
 *
 * @since 22/02/2017
 */
public class DefaultGeneratorService implements GeneratorService {

    private GeneratorContext context;

    public DefaultGeneratorService(GeneratorContext context) {
        Objects.requireNonNull(context, "you must provide a context");
        this.context = context;
    }

    @Override
    public List<ImageConfiguration> generate(List<ImageConfiguration> imageConfigs, boolean prePackagePhase) throws MojoExecutionException {
        List<ImageConfiguration> ret = imageConfigs;

        PluginServiceFactory<GeneratorContext> pluginFactory =
                context.isUseProjectClasspath() ?
                        new PluginServiceFactory<>(context, ClassUtil.createProjectClassLoader(context.getProject(), context.getLogger())) :
                        new PluginServiceFactory<>(context);

        List<Generator> generators =
                pluginFactory.createServiceObjects("META-INF/fabric8/generator-default",
                        "META-INF/fabric8/fabric8-generator-default",
                        "META-INF/fabric8/generator",
                        "META-INF/fabric8-generator");
        ProcessorConfig config = context.getConfig();
        Logger log = context.getLogger();
        List<Generator> usableGenerators = config.prepareProcessors(generators, "generator");
        log.verbose("Generators:");
        for (Generator generator : usableGenerators) {
            log.verbose(" - %s",generator.getName());
            if (generator.isApplicable(ret)) {
                log.info("Running generator %s", generator.getName());
                ret = generator.customize(ret, prePackagePhase);
            }
        }
        return ret;
    }

}
