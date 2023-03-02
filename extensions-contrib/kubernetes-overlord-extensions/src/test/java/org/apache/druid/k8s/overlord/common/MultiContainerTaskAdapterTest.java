/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.k8s.overlord.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.druid.guice.FirehoseModule;
import org.apache.druid.indexing.common.TestUtils;
import org.apache.druid.indexing.common.task.IndexTask;
import org.apache.druid.indexing.common.task.NoopTask;
import org.apache.druid.indexing.common.task.batch.parallel.ParallelIndexTuningConfig;
import org.apache.druid.k8s.overlord.KubernetesTaskRunnerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

@EnableKubernetesMockClient(crud = true)
class MultiContainerTaskAdapterTest
{

  KubernetesClient client;

  private ObjectMapper jsonMapper;

  public MultiContainerTaskAdapterTest()
  {
    TestUtils utils = new TestUtils();
    jsonMapper = utils.getTestObjectMapper();
    for (Module jacksonModule : new FirehoseModule().getJacksonModules()) {
      jsonMapper.registerModule(jacksonModule);
    }
    jsonMapper.registerSubtypes(
        new NamedType(ParallelIndexTuningConfig.class, "index_parallel"),
        new NamedType(IndexTask.IndexTuningConfig.class, "index")
    );
  }

  @Test
  public void testMultiContainerSupport() throws IOException
  {
    TestKubernetesClient testClient = new TestKubernetesClient(client);
    Pod pod = client.pods().load(this.getClass().getClassLoader().getResourceAsStream("multiContainerPodSpec.yaml")).get();
    KubernetesTaskRunnerConfig config = new KubernetesTaskRunnerConfig();
    config.namespace = "test";
    MultiContainerTaskAdapter adapter = new MultiContainerTaskAdapter(testClient, config, jsonMapper);
    NoopTask task = NoopTask.create("id", 1);
    Job actual = adapter.createJobFromPodSpec(
        pod.getSpec(),
        task,
        new PeonCommandContext(Collections.singletonList("/peon.sh /druid/data/baseTaskDir/noop_2022-09-26T22:08:00.582Z_352988d2-5ff7-4b70-977c-3de96f9bfca6 1"),
                               new ArrayList<>(),
                               new File("/tmp")
        )
    );
    Job expected = client.batch()
                     .v1()
                     .jobs()
                     .load(this.getClass().getClassLoader().getResourceAsStream("expectedMultiContainerOutput.yaml"))
                     .get();

    // something is up with jdk 17, where if you compress with jdk < 17 and try and decompress you get different results,
    // this would never happen in real life, but for the jdk 17 tests this is a problem
    // could be related to: https://bugs.openjdk.org/browse/JDK-8081450
    actual.getSpec()
          .getTemplate()
          .getSpec()
          .getContainers()
          .get(0)
          .getEnv()
          .removeIf(x -> x.getName().equals("TASK_JSON"));
    expected.getSpec()
            .getTemplate()
            .getSpec()
            .getContainers()
            .get(0)
            .getEnv()
            .removeIf(x -> x.getName().equals("TASK_JSON"));
    Assertions.assertEquals(expected, actual);
  }

  @Test
  public void testMultiContainerOverride() throws IOException
  {
    TestKubernetesClient testClient = new TestKubernetesClient(client);
    Pod pod = client.pods().load(this.getClass().getClassLoader().getResourceAsStream("envOverridePodSpec.yaml")).get();
    KubernetesTaskRunnerConfig config = new KubernetesTaskRunnerConfig();
    config.namespace = "test";
    config.peonOverrides.put("druid_monitoring_monitors", "'[\"org.apache.druid.java.util.metrics.JvmMonitor\"]'");
    ObjectMapper jsonMapper = new ObjectMapper();
    String jsonString = jsonMapper.writeValueAsString(config);
    System.out.println("jsonString = " + jsonString);

    MultiContainerTaskAdapter adapter = new MultiContainerTaskAdapter(testClient, config, this.jsonMapper);
    NoopTask task = NoopTask.create("id", 1);
    PodSpec spec = pod.getSpec();
    Job actual = adapter.createJobFromPodSpec(
        spec,
        task,
        new PeonCommandContext(Collections.singletonList("/peon.sh /druid/data/baseTaskDir/noop_2022-09-26T22:08:00.582Z_352988d2-5ff7-4b70-977c-3de96f9bfca6 1"),
                               new ArrayList<>(),
                               new File("/tmp")
        )
    );
    Job expected = client.batch()
                         .v1()
                         .jobs()
                         .load(this.getClass().getClassLoader().getResourceAsStream("expectedEnvOverridePodSpec.yaml"))
                         .get();

    // something is up with jdk 17, where if you compress with jdk < 17 and try and decompress you get different results,
    // this would never happen in real life, but for the jdk 17 tests this is a problem
    // could be related to: https://bugs.openjdk.org/browse/JDK-8081450
    actual.getSpec()
          .getTemplate()
          .getSpec()
          .getContainers()
          .get(0)
          .getEnv()
          .removeIf(x -> x.getName().equals("TASK_JSON"));
    expected.getSpec()
            .getTemplate()
            .getSpec()
            .getContainers()
            .get(0)
            .getEnv()
            .removeIf(x -> x.getName().equals("TASK_JSON"));
    Assertions.assertEquals(expected, actual);
  }

  @Test
  public void testserde() throws JsonProcessingException
  {
    KubernetesTaskRunnerConfig config = new KubernetesTaskRunnerConfig();
    config.namespace = "test";
    config.peonOverrides.put("druid_monitoring_monitors", "[\"org.apache.druid.java.util.metrics.JvmMonitor\"]");
    ObjectMapper jsonMapper = new ObjectMapper();
    String jsonString = jsonMapper.writeValueAsString(config);
    System.out.println("jsonString = " + jsonString);

    String str = "{\n"
                 + "\t\"namespace\": \"test\",\n"
                 + "\t\"debugJobs\": false,\n"
                 + "\t\"sidecarSupport\": false,\n"
                 + "\t\"kubexitImage\": \"karlkfi/kubexit:v0.3.2\",\n"
                 + "\t\"graceTerminationPeriodSeconds\": null,\n"
                 + "\t\"disableClientProxy\": false,\n"
                 + "\t\"maxTaskDuration\": {\n"
                 + "\t\t\"hours\": 4,\n"
                 + "\t\t\"minutes\": 0,\n"
                 + "\t\t\"millis\": 0,\n"
                 + "\t\t\"months\": 0,\n"
                 + "\t\t\"weeks\": 0,\n"
                 + "\t\t\"years\": 0,\n"
                 + "\t\t\"days\": 0,\n"
                 + "\t\t\"seconds\": 0,\n"
                 + "\t\t\"periodType\": {\n"
                 + "\t\t\t\"name\": \"Standard\"\n"
                 + "\t\t},\n"
                 + "\t\t\"values\": [0, 0, 0, 0, 4, 0, 0, 0],\n"
                 + "\t\t\"fieldTypes\": [{\n"
                 + "\t\t\t\"name\": \"years\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"months\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"weeks\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"days\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"hours\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"minutes\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"seconds\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"millis\"\n"
                 + "\t\t}]\n"
                 + "\t},\n"
                 + "\t\"taskCleanupDelay\": {\n"
                 + "\t\t\"hours\": 0,\n"
                 + "\t\t\"minutes\": 0,\n"
                 + "\t\t\"millis\": 0,\n"
                 + "\t\t\"months\": 0,\n"
                 + "\t\t\"weeks\": 0,\n"
                 + "\t\t\"years\": 0,\n"
                 + "\t\t\"days\": 2,\n"
                 + "\t\t\"seconds\": 0,\n"
                 + "\t\t\"periodType\": {\n"
                 + "\t\t\t\"name\": \"Standard\"\n"
                 + "\t\t},\n"
                 + "\t\t\"values\": [0, 0, 0, 2, 0, 0, 0, 0],\n"
                 + "\t\t\"fieldTypes\": [{\n"
                 + "\t\t\t\"name\": \"years\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"months\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"weeks\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"days\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"hours\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"minutes\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"seconds\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"millis\"\n"
                 + "\t\t}]\n"
                 + "\t},\n"
                 + "\t\"taskCleanupInterval\": {\n"
                 + "\t\t\"hours\": 0,\n"
                 + "\t\t\"minutes\": 10,\n"
                 + "\t\t\"millis\": 0,\n"
                 + "\t\t\"months\": 0,\n"
                 + "\t\t\"weeks\": 0,\n"
                 + "\t\t\"years\": 0,\n"
                 + "\t\t\"days\": 0,\n"
                 + "\t\t\"seconds\": 0,\n"
                 + "\t\t\"periodType\": {\n"
                 + "\t\t\t\"name\": \"Standard\"\n"
                 + "\t\t},\n"
                 + "\t\t\"values\": [0, 0, 0, 0, 0, 10, 0, 0],\n"
                 + "\t\t\"fieldTypes\": [{\n"
                 + "\t\t\t\"name\": \"years\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"months\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"weeks\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"days\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"hours\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"minutes\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"seconds\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"millis\"\n"
                 + "\t\t}]\n"
                 + "\t},\n"
                 + "\t\"k8sjobLaunchTimeout\": {\n"
                 + "\t\t\"hours\": 1,\n"
                 + "\t\t\"minutes\": 0,\n"
                 + "\t\t\"millis\": 0,\n"
                 + "\t\t\"months\": 0,\n"
                 + "\t\t\"weeks\": 0,\n"
                 + "\t\t\"years\": 0,\n"
                 + "\t\t\"days\": 0,\n"
                 + "\t\t\"seconds\": 0,\n"
                 + "\t\t\"periodType\": {\n"
                 + "\t\t\t\"name\": \"Standard\"\n"
                 + "\t\t},\n"
                 + "\t\t\"values\": [0, 0, 0, 0, 1, 0, 0, 0],\n"
                 + "\t\t\"fieldTypes\": [{\n"
                 + "\t\t\t\"name\": \"years\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"months\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"weeks\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"days\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"hours\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"minutes\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"seconds\"\n"
                 + "\t\t}, {\n"
                 + "\t\t\t\"name\": \"millis\"\n"
                 + "\t\t}]\n"
                 + "\t},\n"
                 + "\t\"javaOptsArray\": null,\n"
                 + "\t\"classpath\": \"/Users/atulmohan/.m2/repository/org/junit/platform/junit-platform-launcher/1.8.2/junit-platform-launcher-1.8.2.jar:/Users/atulmohan/.m2/repository/org/junit/platform/junit-platform-engine/1.8.2/junit-platform-engine-1.8.2.jar:/Users/atulmohan/.m2/repository/org/junit/jupiter/junit-jupiter-engine/5.8.2/junit-jupiter-engine-5.8.2.jar:/Users/atulmohan/.m2/repository/org/junit/vintage/junit-vintage-engine/5.8.2/junit-vintage-engine-5.8.2.jar:/Applications/IntelliJ IDEA CE.app/Contents/lib/idea_rt.jar:/Applications/IntelliJ IDEA CE.app/Contents/plugins/junit/lib/junit5-rt.jar:/Applications/IntelliJ IDEA CE.app/Contents/plugins/junit/lib/junit-rt.jar:/Users/atulmohan/druidcommunity/druid/extensions-contrib/kubernetes-overlord-extensions/target/test-classes:/Users/atulmohan/druidcommunity/druid/extensions-contrib/kubernetes-overlord-extensions/target/classes:/Users/atulmohan/druidcommunity/druid/server/target/classes:/Users/atulmohan/druidcommunity/druid/cloud/aws-common/target/classes:/Users/atulmohan/.m2/repository/com/amazonaws/aws-java-sdk-ec2/1.12.317/aws-java-sdk-ec2-1.12.317.jar:/Users/atulmohan/.m2/repository/com/amazonaws/jmespath-java/1.12.317/jmespath-java-1.12.317.jar:/Users/atulmohan/.m2/repository/com/amazonaws/aws-java-sdk-s3/1.12.317/aws-java-sdk-s3-1.12.317.jar:/Users/atulmohan/.m2/repository/com/amazonaws/aws-java-sdk-kms/1.12.317/aws-java-sdk-kms-1.12.317.jar:/Users/atulmohan/.m2/repository/com/amazonaws/aws-java-sdk-core/1.12.317/aws-java-sdk-core-1.12.317.jar:/Users/atulmohan/.m2/repository/software/amazon/ion/ion-java/1.0.2/ion-java-1.0.2.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/dataformat/jackson-dataformat-cbor/2.10.5/jackson-dataformat-cbor-2.10.5.jar:/Users/atulmohan/.m2/repository/com/amazonaws/aws-java-sdk-sts/1.12.317/aws-java-sdk-sts-1.12.317.jar:/Users/atulmohan/druidcommunity/druid/cloud/gcp-common/target/classes:/Users/atulmohan/.m2/repository/com/google/api-client/google-api-client/1.26.0/google-api-client-1.26.0.jar:/Users/atulmohan/.m2/repository/com/google/oauth-client/google-oauth-client/1.26.0/google-oauth-client-1.26.0.jar:/Users/atulmohan/.m2/repository/com/google/http-client/google-http-client-jackson2/1.26.0/google-http-client-jackson2-1.26.0.jar:/Users/atulmohan/druidcommunity/druid/hll/target/classes:/Users/atulmohan/.m2/repository/jakarta/inject/jakarta.inject-api/1.0.3/jakarta.inject-api-1.0.3.jar:/Users/atulmohan/.m2/repository/org/apache/zookeeper/zookeeper/3.5.9/zookeeper-3.5.9.jar:/Users/atulmohan/.m2/repository/org/apache/yetus/audience-annotations/0.5.0/audience-annotations-0.5.0.jar:/Users/atulmohan/.m2/repository/org/apache/zookeeper/zookeeper-jute/3.5.9/zookeeper-jute-3.5.9.jar:/Users/atulmohan/.m2/repository/org/apache/curator/curator-framework/5.4.0/curator-framework-5.4.0.jar:/Users/atulmohan/.m2/repository/org/apache/curator/curator-x-discovery/5.4.0/curator-x-discovery-5.4.0.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/jaxrs/jackson-jaxrs-json-provider/2.10.5/jackson-jaxrs-json-provider-2.10.5.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/jaxrs/jackson-jaxrs-base/2.10.5/jackson-jaxrs-base-2.10.5.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/module/jackson-module-jaxb-annotations/2.10.5/jackson-module-jaxb-annotations-2.10.5.jar:/Users/atulmohan/.m2/repository/jakarta/xml/bind/jakarta.xml.bind-api/2.3.2/jakarta.xml.bind-api-2.3.2.jar:/Users/atulmohan/.m2/repository/jakarta/activation/jakarta.activation-api/1.2.1/jakarta.activation-api-1.2.1.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/jaxrs/jackson-jaxrs-smile-provider/2.10.5/jackson-jaxrs-smile-provider-2.10.5.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/dataformat/jackson-dataformat-smile/2.10.5/jackson-dataformat-smile-2.10.5.jar:/Users/atulmohan/.m2/repository/com/sun/jersey/jersey-server/1.19.4/jersey-server-1.19.4.jar:/Users/atulmohan/.m2/repository/com/sun/jersey/jersey-core/1.19.4/jersey-core-1.19.4.jar:/Users/atulmohan/.m2/repository/com/google/inject/extensions/guice-servlet/4.1.0/guice-servlet-4.1.0.jar:/Users/atulmohan/.m2/repository/com/sun/jersey/contribs/jersey-guice/1.19.4/jersey-guice-1.19.4.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-server/9.4.48.v20220622/jetty-server-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-util/9.4.48.v20220622/jetty-util-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-proxy/9.4.48.v20220622/jetty-proxy-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-rewrite/9.4.48.v20220622/jetty-rewrite-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/io/tesla/aether/tesla-aether/0.0.5/tesla-aether-0.0.5.jar:/Users/atulmohan/.m2/repository/org/eclipse/aether/aether-spi/0.9.0.M2/aether-spi-0.9.0.M2.jar:/Users/atulmohan/.m2/repository/org/eclipse/aether/aether-util/0.9.0.M2/aether-util-0.9.0.M2.jar:/Users/atulmohan/.m2/repository/org/eclipse/aether/aether-impl/0.9.0.M2/aether-impl-0.9.0.M2.jar:/Users/atulmohan/.m2/repository/org/eclipse/aether/aether-connector-file/0.9.0.M2/aether-connector-file-0.9.0.M2.jar:/Users/atulmohan/.m2/repository/io/tesla/aether/aether-connector-okhttp/0.0.9/aether-connector-okhttp-0.0.9.jar:/Users/atulmohan/.m2/repository/com/squareup/okhttp/okhttp/1.0.2/okhttp-1.0.2.jar:/Users/atulmohan/.m2/repository/org/apache/maven/wagon/wagon-provider-api/2.4/wagon-provider-api-2.4.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-aether-provider/3.1.1/maven-aether-provider-3.1.1.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-model/3.1.1/maven-model-3.1.1.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-model-builder/3.1.1/maven-model-builder-3.1.1.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-repository-metadata/3.1.1/maven-repository-metadata-3.1.1.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-settings-builder/3.1.1/maven-settings-builder-3.1.1.jar:/Users/atulmohan/.m2/repository/org/codehaus/plexus/plexus-interpolation/1.19/plexus-interpolation-1.19.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-settings/3.1.1/maven-settings-3.1.1.jar:/Users/atulmohan/.m2/repository/net/spy/spymemcached/2.12.3/spymemcached-2.12.3.jar:/Users/atulmohan/.m2/repository/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-servlet/9.4.48.v20220622/jetty-servlet-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-security/9.4.48.v20220622/jetty-security-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-util-ajax/9.4.48.v20220622/jetty-util-ajax-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-servlets/9.4.48.v20220622/jetty-servlets-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-continuation/9.4.48.v20220622/jetty-continuation-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/org/apache/derby/derby/10.14.2.0/derby-10.14.2.0.jar:/Users/atulmohan/.m2/repository/org/apache/derby/derbynet/10.14.2.0/derbynet-10.14.2.0.jar:/Users/atulmohan/.m2/repository/org/apache/derby/derbyclient/10.14.2.0/derbyclient-10.14.2.0.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar:/Users/atulmohan/.m2/repository/it/unimi/dsi/fastutil-core/8.5.4/fastutil-core-8.5.4.jar:/Users/atulmohan/.m2/repository/com/github/ben-manes/caffeine/caffeine/2.8.0/caffeine-2.8.0.jar:/Users/atulmohan/.m2/repository/org/jdbi/jdbi/2.63.1/jdbi-2.63.1.jar:/Users/atulmohan/.m2/repository/io/netty/netty/3.10.6.Final/netty-3.10.6.Final.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-dbcp2/2.0.1/commons-dbcp2-2.0.1.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-pool2/2.2/commons-pool2-2.2.jar:/Users/atulmohan/.m2/repository/commons-logging/commons-logging/1.1.1/commons-logging-1.1.1.jar:/Users/atulmohan/.m2/repository/org/apache/logging/log4j/log4j-api/2.18.0/log4j-api-2.18.0.jar:/Users/atulmohan/.m2/repository/org/apache/logging/log4j/log4j-core/2.18.0/log4j-core-2.18.0.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/datatype/jackson-datatype-joda/2.10.5/jackson-datatype-joda-2.10.5.jar:/Users/atulmohan/.m2/repository/org/asynchttpclient/async-http-client/2.5.3/async-http-client-2.5.3.jar:/Users/atulmohan/.m2/repository/org/asynchttpclient/async-http-client-netty-utils/2.5.3/async-http-client-netty-utils-2.5.3.jar:/Users/atulmohan/.m2/repository/io/netty/netty-codec-socks/4.1.86.Final/netty-codec-socks-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-handler-proxy/4.1.86.Final/netty-handler-proxy-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-transport-native-epoll/4.1.86.Final/netty-transport-native-epoll-4.1.86.Final-linux-x86_64.jar:/Users/atulmohan/.m2/repository/io/netty/netty-transport-classes-epoll/4.1.86.Final/netty-transport-classes-epoll-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-resolver-dns/4.1.86.Final/netty-resolver-dns-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-codec-dns/4.1.86.Final/netty-codec-dns-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/org/reactivestreams/reactive-streams/1.0.2/reactive-streams-1.0.2.jar:/Users/atulmohan/.m2/repository/com/typesafe/netty/netty-reactive-streams/2.0.0/netty-reactive-streams-2.0.0.jar:/Users/atulmohan/.m2/repository/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar:/Users/atulmohan/.m2/repository/org/apache/curator/curator-client/5.4.0/curator-client-5.4.0.jar:/Users/atulmohan/.m2/repository/commons-codec/commons-codec/1.13/commons-codec-1.13.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-io/9.4.48.v20220622/jetty-io-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/io/netty/netty-handler/4.1.86.Final/netty-handler-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-resolver/4.1.86.Final/netty-resolver-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-transport/4.1.86.Final/netty-transport-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-transport-native-unix-common/4.1.86.Final/netty-transport-native-unix-common-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-codec/4.1.86.Final/netty-codec-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/org/skife/config/config-magic/0.9/config-magic-0.9.jar:/Users/atulmohan/.m2/repository/org/apache/curator/curator-recipes/5.4.0/curator-recipes-5.4.0.jar:/Users/atulmohan/.m2/repository/com/sun/jersey/jersey-servlet/1.19.4/jersey-servlet-1.19.4.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-client/9.4.48.v20220622/jetty-client-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/javax/ws/rs/jsr311-api/1.1.1/jsr311-api-1.1.1.jar:/Users/atulmohan/.m2/repository/javax/servlet/javax.servlet-api/3.1.0/javax.servlet-api-3.1.0.jar:/Users/atulmohan/.m2/repository/org/eclipse/jetty/jetty-http/9.4.48.v20220622/jetty-http-9.4.48.v20220622.jar:/Users/atulmohan/.m2/repository/com/google/errorprone/error_prone_annotations/2.11.0/error_prone_annotations-2.11.0.jar:/Users/atulmohan/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:/Users/atulmohan/.m2/repository/io/github/resilience4j/resilience4j-bulkhead/1.3.1/resilience4j-bulkhead-1.3.1.jar:/Users/atulmohan/.m2/repository/io/vavr/vavr/0.10.2/vavr-0.10.2.jar:/Users/atulmohan/.m2/repository/io/vavr/vavr-match/0.10.2/vavr-match-0.10.2.jar:/Users/atulmohan/.m2/repository/io/github/resilience4j/resilience4j-core/1.3.1/resilience4j-core-1.3.1.jar:/Users/atulmohan/.m2/repository/io/timeandspace/cron-scheduler/0.1/cron-scheduler-0.1.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/module/jackson-module-guice/2.10.5/jackson-module-guice-2.10.5.jar:/Users/atulmohan/druidcommunity/druid/core/target/classes:/Users/atulmohan/.m2/repository/org/apache/datasketches/datasketches-java/3.2.0/datasketches-java-3.2.0.jar:/Users/atulmohan/.m2/repository/org/apache/datasketches/datasketches-memory/2.0.0/datasketches-memory-2.0.0.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar:/Users/atulmohan/.m2/repository/org/hibernate/hibernate-validator/5.2.5.Final/hibernate-validator-5.2.5.Final.jar:/Users/atulmohan/.m2/repository/org/jboss/logging/jboss-logging/3.2.1.Final/jboss-logging-3.2.1.Final.jar:/Users/atulmohan/.m2/repository/com/fasterxml/classmate/1.1.0/classmate-1.1.0.jar:/Users/atulmohan/.m2/repository/javax/el/javax.el-api/3.0.0/javax.el-api-3.0.0.jar:/Users/atulmohan/.m2/repository/org/glassfish/javax.el/3.0.0/javax.el-3.0.0.jar:/Users/atulmohan/.m2/repository/javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1.jar:/Users/atulmohan/.m2/repository/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/datatype/jackson-datatype-guava/2.10.5/jackson-datatype-guava-2.10.5.jar:/Users/atulmohan/.m2/repository/org/apache/logging/log4j/log4j-slf4j-impl/2.18.0/log4j-slf4j-impl-2.18.0.jar:/Users/atulmohan/.m2/repository/org/apache/logging/log4j/log4j-jul/2.18.0/log4j-jul-2.18.0.jar:/Users/atulmohan/.m2/repository/org/apache/logging/log4j/log4j-1.2-api/2.18.0/log4j-1.2-api-2.18.0.jar:/Users/atulmohan/.m2/repository/org/slf4j/jcl-over-slf4j/1.7.36/jcl-over-slf4j-1.7.36.jar:/Users/atulmohan/.m2/repository/com/github/rvesse/airline/2.8.4/airline-2.8.4.jar:/Users/atulmohan/.m2/repository/com/github/rvesse/airline-io/2.8.4/airline-io-2.8.4.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-collections4/4.2/commons-collections4-4.2.jar:/Users/atulmohan/.m2/repository/net/thisptr/jackson-jq/0.0.10/jackson-jq-0.0.10.jar:/Users/atulmohan/.m2/repository/org/jruby/joni/joni/2.1.27/joni-2.1.27.jar:/Users/atulmohan/.m2/repository/org/jruby/jcodings/jcodings/1.0.43/jcodings-1.0.43.jar:/Users/atulmohan/.m2/repository/it/unimi/dsi/fastutil/8.5.4/fastutil-8.5.4.jar:/Users/atulmohan/.m2/repository/it/unimi/dsi/fastutil-extra/8.5.4/fastutil-extra-8.5.4.jar:/Users/atulmohan/.m2/repository/io/netty/netty-buffer/4.1.86.Final/netty-buffer-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/io/netty/netty-codec-http/4.1.86.Final/netty-codec-http-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/com/opencsv/opencsv/4.6/opencsv-4.6.jar:/Users/atulmohan/.m2/repository/commons-beanutils/commons-beanutils/1.9.4/commons-beanutils-1.9.4.jar:/Users/atulmohan/.m2/repository/commons-collections/commons-collections/3.2.2/commons-collections-3.2.2.jar:/Users/atulmohan/.m2/repository/org/mozilla/rhino/1.7.11/rhino-1.7.11.jar:/Users/atulmohan/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar:/Users/atulmohan/.m2/repository/com/github/luben/zstd-jni/1.5.2-3/zstd-jni-1.5.2-3.jar:/Users/atulmohan/.m2/repository/com/jayway/jsonpath/json-path/2.3.0/json-path-2.3.0.jar:/Users/atulmohan/.m2/repository/net/minidev/json-smart/2.3/json-smart-2.3.jar:/Users/atulmohan/.m2/repository/net/minidev/accessors-smart/1.2/accessors-smart-1.2.jar:/Users/atulmohan/.m2/repository/org/antlr/antlr4-runtime/4.5.1/antlr4-runtime-4.5.1.jar:/Users/atulmohan/.m2/repository/com/lmax/disruptor/3.3.6/disruptor-3.3.6.jar:/Users/atulmohan/.m2/repository/net/java/dev/jna/jna/4.5.1/jna-4.5.1.jar:/Users/atulmohan/.m2/repository/org/hyperic/sigar/1.6.5.132/sigar-1.6.5.132.jar:/Users/atulmohan/druidcommunity/druid/processing/target/classes:/Users/atulmohan/druidcommunity/druid/extendedset/target/classes:/Users/atulmohan/.m2/repository/org/roaringbitmap/RoaringBitmap/0.9.0/RoaringBitmap-0.9.0.jar:/Users/atulmohan/.m2/repository/org/roaringbitmap/shims/0.9.0/shims-0.9.0.jar:/Users/atulmohan/.m2/repository/com/ning/compress-lzf/1.0.4/compress-lzf-1.0.4.jar:/Users/atulmohan/.m2/repository/com/github/seancfoley/ipaddress/5.3.4/ipaddress-5.3.4.jar:/Users/atulmohan/.m2/repository/com/ibm/icu/icu4j/55.1/icu4j-55.1.jar:/Users/atulmohan/.m2/repository/org/ow2/asm/asm/9.3/asm-9.3.jar:/Users/atulmohan/.m2/repository/org/ow2/asm/asm-commons/9.3/asm-commons-9.3.jar:/Users/atulmohan/.m2/repository/org/ow2/asm/asm-tree/9.3/asm-tree-9.3.jar:/Users/atulmohan/.m2/repository/org/ow2/asm/asm-analysis/9.3/asm-analysis-9.3.jar:/Users/atulmohan/.m2/repository/org/checkerframework/checker-qual/2.5.7/checker-qual-2.5.7.jar:/Users/atulmohan/.m2/repository/org/apache/maven/maven-artifact/3.6.0/maven-artifact-3.6.0.jar:/Users/atulmohan/.m2/repository/org/codehaus/plexus/plexus-utils/3.0.24/plexus-utils-3.0.24.jar:/Users/atulmohan/druidcommunity/druid/indexing-service/target/classes:/Users/atulmohan/druidcommunity/druid/indexing-hadoop/target/classes:/Users/atulmohan/.m2/repository/io/dropwizard/metrics/metrics-core/4.0.0/metrics-core-4.0.0.jar:/Users/atulmohan/.m2/repository/org/eclipse/aether/aether-api/0.9.0.M2/aether-api-0.9.0.M2.jar:/Users/atulmohan/.m2/repository/commons-lang/commons-lang/2.6/commons-lang-2.6.jar:/Users/atulmohan/.m2/repository/commons-io/commons-io/2.11.0/commons-io-2.11.0.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar:/Users/atulmohan/.m2/repository/io/netty/netty-common/4.1.86.Final/netty-common-4.1.86.Final.jar:/Users/atulmohan/.m2/repository/com/google/http-client/google-http-client/1.26.0/google-http-client-1.26.0.jar:/Users/atulmohan/.m2/repository/org/apache/httpcomponents/httpclient/4.5.13/httpclient-4.5.13.jar:/Users/atulmohan/.m2/repository/org/apache/httpcomponents/httpcore/4.4.11/httpcore-4.4.11.jar:/Users/atulmohan/.m2/repository/com/google/j2objc/j2objc-annotations/1.1/j2objc-annotations-1.1.jar:/Users/atulmohan/.m2/repository/org/apache/commons/commons-text/1.10.0/commons-text-1.10.0.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-core/5.12.2/kubernetes-model-core-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-common/5.12.2/kubernetes-model-common-5.12.2.jar:/Users/atulmohan/.m2/repository/javax/validation/validation-api/1.1.0.Final/validation-api-1.1.0.Final.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-batch/5.12.2/kubernetes-model-batch-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-client/5.12.2/kubernetes-client-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-rbac/5.12.2/kubernetes-model-rbac-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-admissionregistration/5.12.2/kubernetes-model-admissionregistration-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-apps/5.12.2/kubernetes-model-apps-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-autoscaling/5.12.2/kubernetes-model-autoscaling-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-apiextensions/5.12.2/kubernetes-model-apiextensions-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-certificates/5.12.2/kubernetes-model-certificates-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-coordination/5.12.2/kubernetes-model-coordination-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-discovery/5.12.2/kubernetes-model-discovery-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-events/5.12.2/kubernetes-model-events-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-extensions/5.12.2/kubernetes-model-extensions-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-flowcontrol/5.12.2/kubernetes-model-flowcontrol-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-networking/5.12.2/kubernetes-model-networking-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-metrics/5.12.2/kubernetes-model-metrics-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-policy/5.12.2/kubernetes-model-policy-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-scheduling/5.12.2/kubernetes-model-scheduling-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-storageclass/5.12.2/kubernetes-model-storageclass-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-model-node/5.12.2/kubernetes-model-node-5.12.2.jar:/Users/atulmohan/.m2/repository/com/squareup/okhttp3/okhttp/3.12.12/okhttp-3.12.12.jar:/Users/atulmohan/.m2/repository/com/squareup/okio/okio/1.15.0/okio-1.15.0.jar:/Users/atulmohan/.m2/repository/com/squareup/okhttp3/logging-interceptor/3.12.12/logging-interceptor-3.12.12.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.10.5/jackson-dataformat-yaml-2.10.5.jar:/Users/atulmohan/.m2/repository/org/yaml/snakeyaml/1.26/snakeyaml-1.26.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.10.5/jackson-datatype-jsr310-2.10.5.jar:/Users/atulmohan/.m2/repository/io/fabric8/zjsonpatch/0.3.0/zjsonpatch-0.3.0.jar:/Users/atulmohan/.m2/repository/com/github/mifmif/generex/1.0.2/generex-1.0.2.jar:/Users/atulmohan/.m2/repository/dk/brics/automaton/automaton/1.11-8/automaton-1.11-8.jar:/Users/atulmohan/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar:/Users/atulmohan/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar:/Users/atulmohan/.m2/repository/org/easymock/easymock/4.3/easymock-4.3.jar:/Users/atulmohan/.m2/repository/org/objenesis/objenesis/3.2/objenesis-3.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/kubernetes-server-mock/5.12.2/kubernetes-server-mock-5.12.2.jar:/Users/atulmohan/.m2/repository/io/fabric8/mockwebserver/0.2.2/mockwebserver-0.2.2.jar:/Users/atulmohan/.m2/repository/com/squareup/okhttp3/mockwebserver/3.12.12/mockwebserver-3.12.12.jar:/Users/atulmohan/.m2/repository/org/junit/jupiter/junit-jupiter-api/5.8.2/junit-jupiter-api-5.8.2.jar:/Users/atulmohan/.m2/repository/org/opentest4j/opentest4j/1.2.0/opentest4j-1.2.0.jar:/Users/atulmohan/.m2/repository/org/junit/platform/junit-platform-commons/1.8.2/junit-platform-commons-1.8.2.jar:/Users/atulmohan/.m2/repository/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar:/Users/atulmohan/.m2/repository/com/google/code/findbugs/jsr305/2.0.1/jsr305-2.0.1.jar:/Users/atulmohan/.m2/repository/com/google/inject/guice/4.1.0/guice-4.1.0.jar:/Users/atulmohan/.m2/repository/javax/inject/javax.inject/1/javax.inject-1.jar:/Users/atulmohan/.m2/repository/aopalliance/aopalliance/1.0/aopalliance-1.0.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.10.5.1/jackson-databind-2.10.5.1.jar:/Users/atulmohan/.m2/repository/com/google/inject/extensions/guice-multibindings/4.1.0/guice-multibindings-4.1.0.jar:/Users/atulmohan/.m2/repository/joda-time/joda-time/2.10.5/joda-time-2.10.5.jar:/Users/atulmohan/.m2/repository/com/google/guava/guava/16.0.1/guava-16.0.1.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.10.5/jackson-core-2.10.5.jar:/Users/atulmohan/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.10.5/jackson-annotations-2.10.5.jar:/Users/atulmohan/.m2/repository/org/mockito/mockito-all/1.9.5/mockito-all-1.9.5.jar:/Users/atulmohan/druidcommunity/druid/indexing-service/target/test-classes:/Users/atulmohan/druidcommunity/druid/server/target/test-classes:/Users/atulmohan/druidcommunity/druid/core/target/test-classes:/Users/atulmohan/druidcommunity/druid/processing/target/test-classes:/Users/atulmohan/.m2/repository/nl/jqno/equalsverifier/equalsverifier/3.10.1/equalsverifier-3.10.1.jar:/Users/atulmohan/.m2/repository/net/bytebuddy/byte-buddy/1.12.12/byte-buddy-1.12.12.jar\",\n"
                 + "\t\"labels\": {},\n"
                 + "\t\"annotations\": {},\n"
                 + "\t\"allowedPrefixes\": [\"com.metamx\", \"druid\", \"org.apache.druid\", \"user.timezone\", \"file.encoding\", \"java.io.tmpdir\", \"hadoop\"],\n"
                 + "\t\"peonOverrides\": {\n"
                 + "\t\t\"druid_monitoring_monitors\": \"[\\\"org.apache.druid.java.util.metrics.JvmMonitor\\\"]\"\n"
                 + "\t}\n"
                 + "}";

    KubernetesTaskRunnerConfig cong = jsonMapper.readValue(str, KubernetesTaskRunnerConfig.class);
    System.out.println(String.valueOf(cong.peonOverrides.get("druid_monitoring_monitors")));
    String testStr = "{\"druid_monitoring_monitors\": \"[\"org.apache.druid.java.util.metrics.JvmMonitor\"]\"}";
    System.out.println(testStr);
  }

  @Test
  public void testJsonSerde() throws JsonProcessingException
  {
    String test = "{\"druid_monitoring_monitors\": \"[\"org.apache.druid.java.util.metrics.JvmMonitor\"]\"}";
    LinkedHashMap<String,String> testMap = jsonMapper.readValue(test, new TypeReference<LinkedHashMap<String,String>>(){});
    System.out.println(testMap.get("druid_monitoring_monitors"));

  }

}
