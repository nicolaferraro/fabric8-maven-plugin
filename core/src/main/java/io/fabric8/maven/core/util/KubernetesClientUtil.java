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

package io.fabric8.maven.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirement;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.ClientNonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ClientPodResource;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.utils.Strings;

/**
 * Utility class for executing common tasks using the Kubernetes client
 *
 * @author nicola
 * @since 09/02/17
 */
public class KubernetesClientUtil {

    public static FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> withSelector(ClientNonNamespaceOperation<Pod, PodList, DoneablePod, ClientPodResource<Pod, DoneablePod>> pods, LabelSelector selector, Logger log) {
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> answer = pods;
        Map<String, String> matchLabels = selector.getMatchLabels();
        if (matchLabels != null && !matchLabels.isEmpty()) {
            answer = answer.withLabels(matchLabels);
        }
        List<LabelSelectorRequirement> matchExpressions = selector.getMatchExpressions();
        if (matchExpressions != null) {
            for (LabelSelectorRequirement expression : matchExpressions) {
                String key = expression.getKey();
                List<String> values = expression.getValues();
                if (Strings.isNullOrBlank(key)) {
                    log.warn("Ignoring empty key in selector expression %s", expression);
                    continue;
                }
                if (values == null || values.isEmpty()) {
                    log.warn("Ignoring empty values in selector expression %s", expression);
                    continue;
                }
                String[] valuesArray = values.toArray(new String[values.size()]);
                String operator = expression.getOperator();
                switch (operator) {
                case "In":
                    answer = answer.withLabelIn(key, valuesArray);
                    break;
                case "NotIn":
                    answer = answer.withLabelNotIn(key, valuesArray);
                    break;
                default:
                    log.warn("Ignoring unknown operator %s in selector expression %s", operator, expression);
                }
            }
        }
        return answer;
    }

    public static void printLogsAsync(LogWatch logWatcher, final String failureMessage, final CountDownLatch terminateLatch, final Logger log) {
        final InputStream in = logWatcher.getOutput();
        Thread thread = new Thread() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                    while (true) {
                        String line = reader.readLine();
                        if (line == null) {
                            return;
                        }
                        if (terminateLatch.getCount() <= 0L) {
                            return;
                        }
                        log.info("[[s]]%s", line);
                    }
                } catch (IOException e) {
                    // Check again the latch which could be already count down to zero in between
                    // so that an IO exception occurs on read
                    if (terminateLatch.getCount() > 0L) {
                        log.error("%s : %s", failureMessage, e);
                    }
                }
            }
        };
        thread.start();
    }

    public static String getPodStatusDescription(Pod pod) {
        return KubernetesHelper.getPodStatusText(pod) + " " + getPodCondition(pod);
    }

    public static String getPodStatusMessagePostfix(Watcher.Action action) {
        String message = "";
        switch (action) {
        case DELETED:
            message = ": Pod Deleted";
            break;
        case ERROR:
            message = ": Error";
            break;
        }
        return message;
    }

    protected static String getPodCondition(Pod pod) {
        PodStatus podStatus = pod.getStatus();
        if (podStatus == null) {
            return "";
        }
        List<PodCondition> conditions = podStatus.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return "";
        }


        for (PodCondition condition : conditions) {
            String type = condition.getType();
            if (Strings.isNotBlank(type)) {
                if ("ready".equalsIgnoreCase(type)) {
                    String statusText = condition.getStatus();
                    if (Strings.isNotBlank(statusText)) {
                        if (Boolean.parseBoolean(statusText)) {
                            return type;
                        }
                    }
                }
            }
        }
        return "";
    }

}
