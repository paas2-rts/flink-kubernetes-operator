/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.utils;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.configuration.KubernetesDeploymentTarget;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.crd.spec.Resource;
import org.apache.flink.kubernetes.operator.crd.spec.UpgradeMode;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.util.StringUtils;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.internal.SerializationUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.flink.configuration.DeploymentOptionsInternal.CONF_DIR;
import static org.apache.flink.configuration.WebOptions.CANCEL_ENABLE;
import static org.apache.flink.kubernetes.configuration.KubernetesConfigOptions.REST_SERVICE_EXPOSED_TYPE;
import static org.apache.flink.kubernetes.operator.utils.FlinkUtils.mergePodTemplates;
import static org.apache.flink.kubernetes.utils.Constants.CONFIG_FILE_LOG4J_NAME;
import static org.apache.flink.kubernetes.utils.Constants.CONFIG_FILE_LOGBACK_NAME;

/** Builder to get effective flink config from {@link FlinkDeployment}. */
public class FlinkConfigBuilder {
    private final FlinkDeployment deploy;
    private final ObjectMeta meta;
    private final FlinkDeploymentSpec spec;
    private final Configuration effectiveConfig;

    public static final Duration DEFAULT_CHECKPOINTING_INTERVAL = Duration.ofMinutes(5);

    public FlinkConfigBuilder(FlinkDeployment deploy, Configuration flinkConfig) {
        this(deploy.getMetadata(), deploy.getSpec(), flinkConfig, deploy);
    }

    public FlinkConfigBuilder(
            ObjectMeta metadata,
            FlinkDeploymentSpec spec,
            Configuration flinkConfig,
            FlinkDeployment deploy) {
        this.meta = metadata;
        this.spec = spec;
        this.effectiveConfig = new Configuration(flinkConfig);
        this.deploy = deploy;
    }

    public FlinkConfigBuilder applyImage() {
        if (!StringUtils.isNullOrWhitespaceOnly(spec.getImage())) {
            effectiveConfig.set(KubernetesConfigOptions.CONTAINER_IMAGE, spec.getImage());
        }
        return this;
    }

    public FlinkConfigBuilder applyImagePullPolicy() {
        if (!StringUtils.isNullOrWhitespaceOnly(spec.getImagePullPolicy())) {
            effectiveConfig.set(
                    KubernetesConfigOptions.CONTAINER_IMAGE_PULL_POLICY,
                    KubernetesConfigOptions.ImagePullPolicy.valueOf(spec.getImagePullPolicy()));
        }
        return this;
    }

    public FlinkConfigBuilder applyFlinkConfiguration() {
        // Parse config from spec's flinkConfiguration
        if (spec.getFlinkConfiguration() != null && !spec.getFlinkConfiguration().isEmpty()) {
            spec.getFlinkConfiguration().forEach(effectiveConfig::setString);
        }

        // Adapt default rest service type from 1.15+
        if (!effectiveConfig.contains(REST_SERVICE_EXPOSED_TYPE)) {
            effectiveConfig.set(
                    REST_SERVICE_EXPOSED_TYPE,
                    KubernetesConfigOptions.ServiceExposedType.ClusterIP);
        }

        if (spec.getJob() != null) {
            if (!effectiveConfig.contains(CANCEL_ENABLE)) {
                // Set 'web.cancel.enable' to false for application deployments to avoid users
                // accidentally cancelling jobs.
                effectiveConfig.set(CANCEL_ENABLE, false);
            }
            // With last-state upgrade mode, set the default value of
            // 'execution.checkpointing.interval'
            // to 5 minutes when HA is enabled.
            if (spec.getJob().getUpgradeMode() == UpgradeMode.LAST_STATE
                    && !effectiveConfig.contains(
                            ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL)) {
                effectiveConfig.set(
                        ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL,
                        DEFAULT_CHECKPOINTING_INTERVAL);
            }
        }

        return this;
    }

    public FlinkConfigBuilder applyLogConfiguration() throws IOException {
        if (spec.getLogConfiguration() != null) {
            String confDir =
                    createLogConfigFiles(
                            spec.getLogConfiguration().get(CONFIG_FILE_LOG4J_NAME),
                            spec.getLogConfiguration().get(CONFIG_FILE_LOGBACK_NAME));
            effectiveConfig.setString(CONF_DIR, confDir);
        }
        return this;
    }

    public FlinkConfigBuilder applyCommonPodTemplate() throws IOException {
        if (spec.getPodTemplate() != null) {
            effectiveConfig.set(
                    KubernetesConfigOptions.KUBERNETES_POD_TEMPLATE,
                    createTempFile(spec.getPodTemplate()));
        }
        return this;
    }

    public FlinkConfigBuilder applyIngressDomain() {
        // Web UI
        if (spec.getIngress() != null) {
            effectiveConfig.set(
                    REST_SERVICE_EXPOSED_TYPE,
                    KubernetesConfigOptions.ServiceExposedType.ClusterIP);
        }
        return this;
    }

    public FlinkConfigBuilder applyServiceAccount() {
        if (spec.getServiceAccount() != null) {
            effectiveConfig.set(
                    KubernetesConfigOptions.KUBERNETES_SERVICE_ACCOUNT, spec.getServiceAccount());
        }
        return this;
    }

    public FlinkConfigBuilder applyJobManagerSpec() throws IOException {
        if (spec.getJobManager() != null) {
            setResource(spec.getJobManager().getResource(), effectiveConfig, true);
            setPodTemplate(
                    spec.getPodTemplate(),
                    spec.getJobManager().getPodTemplate(),
                    effectiveConfig,
                    true);
            if (spec.getJobManager().getReplicas() > 0) {
                effectiveConfig.set(
                        KubernetesConfigOptions.KUBERNETES_JOBMANAGER_REPLICAS,
                        spec.getJobManager().getReplicas());
            }
        }
        return this;
    }

    public FlinkConfigBuilder applyTaskManagerSpec() throws IOException {
        if (spec.getTaskManager() != null) {
            setResource(spec.getTaskManager().getResource(), effectiveConfig, false);
            setPodTemplate(
                    spec.getPodTemplate(),
                    spec.getTaskManager().getPodTemplate(),
                    effectiveConfig,
                    false);
        }
        return this;
    }

    public FlinkConfigBuilder applyJobOrSessionSpec() throws URISyntaxException {
        if (spec.getJob() != null) {
            effectiveConfig.set(
                    DeploymentOptions.TARGET, KubernetesDeploymentTarget.APPLICATION.getName());
            final URI uri = new URI(spec.getJob().getJarURI());
            effectiveConfig.set(PipelineOptions.JARS, Collections.singletonList(uri.toString()));

            if (spec.getJob().getParallelism() > 0) {
                effectiveConfig.set(
                        CoreOptions.DEFAULT_PARALLELISM, spec.getJob().getParallelism());
            }
        } else {
            effectiveConfig.set(
                    DeploymentOptions.TARGET, KubernetesDeploymentTarget.SESSION.getName());
        }
        return this;
    }

    public FlinkConfigBuilder applyOwnerReference() {
        Map<String, String> ownerReference =
                Map.of(
                        "apiVersion", deploy.getApiVersion(),
                        "kind", deploy.getKind(),
                        "name", deploy.getMetadata().getName(),
                        "uid", deploy.getMetadata().getUid(),
                        "blockOwnerDeletion", "false",
                        "controller", "false");
        effectiveConfig.set(
                KubernetesConfigOptions.JOB_MANAGER_OWNER_REFERENCE, List.of(ownerReference));
        return this;
    }

    public Configuration build() {

        // Set cluster config
        final String namespace = meta.getNamespace();
        final String clusterId = meta.getName();
        effectiveConfig.setString(KubernetesConfigOptions.NAMESPACE, namespace);
        effectiveConfig.setString(KubernetesConfigOptions.CLUSTER_ID, clusterId);
        return effectiveConfig;
    }

    public static Configuration buildFrom(FlinkDeployment dep, Configuration flinkConfig)
            throws IOException, URISyntaxException {
        return buildFrom(dep, dep.getSpec(), flinkConfig);
    }

    public static Configuration buildFrom(
            FlinkDeployment dep, FlinkDeploymentSpec spec, Configuration flinkConfig)
            throws IOException, URISyntaxException {
        return new FlinkConfigBuilder(dep.getMetadata(), spec, flinkConfig, dep)
                .applyFlinkConfiguration()
                .applyLogConfiguration()
                .applyImage()
                .applyImagePullPolicy()
                .applyServiceAccount()
                .applyCommonPodTemplate()
                .applyIngressDomain()
                .applyJobManagerSpec()
                .applyOwnerReference()
                .applyTaskManagerSpec()
                .applyJobOrSessionSpec()
                .build();
    }

    private static void setResource(
            Resource resource, Configuration effectiveConfig, boolean isJM) {
        if (resource != null) {
            final ConfigOption<MemorySize> memoryConfigOption =
                    isJM
                            ? JobManagerOptions.TOTAL_PROCESS_MEMORY
                            : TaskManagerOptions.TOTAL_PROCESS_MEMORY;
            final ConfigOption<Double> cpuConfigOption =
                    isJM
                            ? KubernetesConfigOptions.JOB_MANAGER_CPU
                            : KubernetesConfigOptions.TASK_MANAGER_CPU;
            effectiveConfig.setString(memoryConfigOption.key(), resource.getMemory());
            effectiveConfig.setDouble(cpuConfigOption.key(), resource.getCpu());
        }
    }

    private static void setPodTemplate(
            Pod basicPod, Pod appendPod, Configuration effectiveConfig, boolean isJM)
            throws IOException {

        if (basicPod == null && appendPod == null) {
            return;
        }

        final ConfigOption<String> podConfigOption =
                isJM
                        ? KubernetesConfigOptions.JOB_MANAGER_POD_TEMPLATE
                        : KubernetesConfigOptions.TASK_MANAGER_POD_TEMPLATE;
        effectiveConfig.setString(
                podConfigOption, createTempFile(mergePodTemplates(basicPod, appendPod)));
    }

    private static String createLogConfigFiles(String log4jConf, String logbackConf)
            throws IOException {
        File tmpDir = Files.createTempDirectory("conf").toFile();

        if (log4jConf != null) {
            File log4jConfFile = new File(tmpDir.getAbsolutePath(), CONFIG_FILE_LOG4J_NAME);
            Files.write(log4jConfFile.toPath(), log4jConf.getBytes());
        }

        if (logbackConf != null) {
            File logbackConfFile = new File(tmpDir.getAbsolutePath(), CONFIG_FILE_LOGBACK_NAME);
            Files.write(logbackConfFile.toPath(), logbackConf.getBytes());
        }
        tmpDir.deleteOnExit();
        return tmpDir.getAbsolutePath();
    }

    private static String createTempFile(Pod podTemplate) throws IOException {
        final File tmp = File.createTempFile("podTemplate_", ".yaml");
        Files.write(tmp.toPath(), SerializationUtils.dumpAsYaml(podTemplate).getBytes());
        tmp.deleteOnExit();
        return tmp.getAbsolutePath();
    }
}
