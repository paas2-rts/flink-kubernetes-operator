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

package org.apache.flink.kubernetes.operator.service;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.highavailability.KubernetesHaServicesFactory;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.TestingClusterClient;
import org.apache.flink.kubernetes.operator.config.FlinkOperatorConfiguration;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.spec.UpgradeMode;
import org.apache.flink.kubernetes.operator.crd.status.JobStatus;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.rest.handler.async.TriggerResponse;
import org.apache.flink.runtime.rest.messages.TriggerId;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointTriggerMessageParameters;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointTriggerRequestBody;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @link FlinkService unit tests */
@EnableKubernetesMockClient(crud = true)
public class FlinkServiceTest {
    KubernetesClient client;
    private final Configuration configuration = new Configuration();
    private static final String CLUSTER_ID = "testing-flink-cluster";
    private static final String TESTING_NAMESPACE = "test";

    @BeforeEach
    public void setup() {
        configuration.set(KubernetesConfigOptions.CLUSTER_ID, CLUSTER_ID);
        configuration.set(KubernetesConfigOptions.NAMESPACE, TESTING_NAMESPACE);
    }

    @Test
    public void testCancelJobWithStatelessUpgradeMode() throws Exception {
        final TestingClusterClient<String> testingClusterClient =
                new TestingClusterClient<>(configuration, CLUSTER_ID);
        final CompletableFuture<JobID> cancelFuture = new CompletableFuture<>();
        testingClusterClient.setCancelFunction(
                jobID -> {
                    cancelFuture.complete(jobID);
                    return CompletableFuture.completedFuture(Acknowledge.get());
                });

        final FlinkService flinkService = createFlinkService(testingClusterClient);

        final JobID jobID = JobID.generate();
        Optional<String> result =
                flinkService.cancelJob(jobID, UpgradeMode.STATELESS, configuration);
        assertTrue(cancelFuture.isDone());
        assertEquals(jobID, cancelFuture.get());
        assertFalse(result.isPresent());
    }

    @Test
    public void testCancelJobWithSavepointUpgradeMode() throws Exception {
        final TestingClusterClient<String> testingClusterClient =
                new TestingClusterClient<>(configuration, CLUSTER_ID);
        final CompletableFuture<Tuple3<JobID, Boolean, String>> stopWithSavepointFuture =
                new CompletableFuture<>();
        final String savepointPath = "file:///path/of/svp-1";
        configuration.set(CheckpointingOptions.SAVEPOINT_DIRECTORY, savepointPath);
        testingClusterClient.setStopWithSavepointFunction(
                (jobID, advanceToEndOfEventTime, savepointDir) -> {
                    stopWithSavepointFuture.complete(
                            new Tuple3<>(jobID, advanceToEndOfEventTime, savepointDir));
                    return CompletableFuture.completedFuture(savepointPath);
                });

        final FlinkService flinkService = createFlinkService(testingClusterClient);

        final JobID jobID = JobID.generate();
        Optional<String> result =
                flinkService.cancelJob(jobID, UpgradeMode.SAVEPOINT, configuration);
        assertTrue(stopWithSavepointFuture.isDone());
        assertEquals(jobID, stopWithSavepointFuture.get().f0);
        assertFalse(stopWithSavepointFuture.get().f1);
        assertEquals(savepointPath, stopWithSavepointFuture.get().f2);
        assertTrue(result.isPresent());
        assertEquals(savepointPath, result.get());
    }

    @Test
    public void testCancelJobWithLastStateUpgradeMode() throws Exception {
        configuration.set(
                HighAvailabilityOptions.HA_MODE,
                KubernetesHaServicesFactory.class.getCanonicalName());
        configuration.set(HighAvailabilityOptions.HA_STORAGE_PATH, "file:///path/of/ha");
        final TestingClusterClient<String> testingClusterClient =
                new TestingClusterClient<>(configuration, CLUSTER_ID);
        final FlinkService flinkService = createFlinkService(testingClusterClient);

        client.apps()
                .deployments()
                .inNamespace(TESTING_NAMESPACE)
                .create(createTestingDeployment());
        assertNotNull(
                client.apps()
                        .deployments()
                        .inNamespace(TESTING_NAMESPACE)
                        .withName(CLUSTER_ID)
                        .get());
        final JobID jobID = JobID.generate();
        Optional<String> result =
                flinkService.cancelJob(jobID, UpgradeMode.LAST_STATE, configuration);
        assertFalse(result.isPresent());
        assertNull(
                client.apps()
                        .deployments()
                        .inNamespace(TESTING_NAMESPACE)
                        .withName(CLUSTER_ID)
                        .get());
    }

    @Test
    public void testTriggerSavepoint() throws Exception {
        final TestingClusterClient<String> testingClusterClient =
                new TestingClusterClient<>(configuration, CLUSTER_ID);
        final CompletableFuture<Tuple3<JobID, String, Boolean>> triggerSavepointFuture =
                new CompletableFuture<>();
        final String savepointPath = "file:///path/of/svp";
        configuration.set(CheckpointingOptions.SAVEPOINT_DIRECTORY, savepointPath);
        testingClusterClient.setTriggerSavepointFunction(
                (headers, parameters, requestBody) -> {
                    triggerSavepointFuture.complete(
                            new Tuple3<>(
                                    ((SavepointTriggerMessageParameters) parameters)
                                            .jobID.getValue(),
                                    ((SavepointTriggerRequestBody) requestBody)
                                            .getTargetDirectory(),
                                    ((SavepointTriggerRequestBody) requestBody).isCancelJob()));
                    return CompletableFuture.completedFuture(new TriggerResponse(new TriggerId()));
                });

        final FlinkService flinkService = createFlinkService(testingClusterClient);

        final JobID jobID = JobID.generate();
        final FlinkDeployment flinkDeployment = TestUtils.buildApplicationCluster();
        JobStatus jobStatus = new JobStatus();
        jobStatus.setJobId(jobID.toString());
        flinkDeployment.getStatus().setJobStatus(jobStatus);
        flinkService.triggerSavepoint(
                flinkDeployment.getStatus().getJobStatus().getJobId(),
                flinkDeployment.getStatus().getJobStatus().getSavepointInfo(),
                configuration);
        assertTrue(triggerSavepointFuture.isDone());
        assertEquals(jobID, triggerSavepointFuture.get().f0);
        assertEquals(savepointPath, triggerSavepointFuture.get().f1);
        assertFalse(triggerSavepointFuture.get().f2);
    }

    private FlinkService createFlinkService(ClusterClient<String> clusterClient) {
        return new FlinkService(
                client, FlinkOperatorConfiguration.fromConfiguration(configuration)) {
            @Override
            protected ClusterClient<String> getClusterClient(Configuration config) {
                return clusterClient;
            }
        };
    }

    private Deployment createTestingDeployment() {
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(CLUSTER_ID)
                .withNamespace(TESTING_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();
    }
}
