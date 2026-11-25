/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.agent.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import website.AgentCustomizations;
import website.BatchJobs;
import website.SecretStore;

// The docs site's Spring Boot Starter page shows website/application.yml and website.AgentCustomizations: they do
// what the page says.
// Each test asserts inside the context runner's lambda, the Spring Boot idiom, where PMD's rule doesn't look
@SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
class StarterExamplesTest {
  private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(PrometheusProxyAgentAutoConfigurationTest.STARTER)
          .withBean(PrometheusRegistry.class, PrometheusRegistry::new);

  @Test
  void thePagesYamlReadsTheAgentsSettingsFromTheEnvironment() throws IOException {
    PropertySource<?> yaml =
        new YamlPropertySourceLoader().load("page", new ClassPathResource("website/application.yml")).get(0);
    int closedPort = freePort();
    runner
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(yaml))
        // As the environment gives them; the placeholders read system properties too
        .withSystemProperties("PROMETHEUS_PROXY=localhost:" + closedPort, "PROMETHEUS_AGENT_TOKEN=s3cret")
        .run(context -> {
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isTrue();
          assertThat(context.getBean(PrometheusProxyAgent.class).config().toString())
              .contains(
                  "proxy={endpoints=[localhost:" + closedPort + "]}",
                  "agentToken=(set)",
                  "name=roastery",
                  "sources={roastery_metrics=in-process}")
              // No PROMETHEUS_PROXY_CERT, so no TLS
              .doesNotContain("tls=");
        });
  }

  // With PROMETHEUS_PROXY_CERT, the page's YAML checks the proxy's certificate against the name fromEnvironment()
  // does: PROMETHEUS_PROXY_AUTHORITY, or without it prometheus-proxy, the name Cloud Platforms issues it to, rather than
  // the host the agent dials
  @Test
  void thePagesYamlChecksTheCertificateAgainstTheNameFromEnvironmentDoes() throws IOException {
    PropertySource<?> yaml =
        new YamlPropertySourceLoader().load("page", new ClassPathResource("website/application.yml")).get(0);
    ApplicationContextRunner withYaml =
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(yaml));
    String proxy = "PROMETHEUS_PROXY=localhost:" + freePort();
    String cert = "PROMETHEUS_PROXY_CERT=" + PEM;

    withYaml
        .withSystemProperties(proxy, cert)
        .run(context -> assertThat(context.getBean(PrometheusProxyAgent.class).config().toString())
            .contains("tls={overrideAuthority=prometheus-proxy, trustCertCollectionPem=(set)}"));
    withYaml
        .withSystemProperties(proxy, cert, "PROMETHEUS_PROXY_AUTHORITY=proxy.example.com")
        .run(context -> assertThat(context.getBean(PrometheusProxyAgent.class).config().toString())
            .contains("tls={overrideAuthority=proxy.example.com, trustCertCollectionPem=(set)}"));
  }

  @Test
  void thePagesCustomizerSetsTheCertificateAndServesASecondRegistry() {
    runner
        .withUserConfiguration(AgentCustomizations.class)
        .withBean(SecretStore.class, () -> name -> PEM)
        .withBean(BatchJobs.class, () -> PrometheusRegistry::new)
        .run(context ->
            assertThat(context.getBean(PrometheusProxyAgent.class).config().toString())
                .contains(
                    "tls={overrideAuthority=prometheus-proxy, trustCertCollectionPem=(set)}",
                    "batch_jobs_metrics=in-process",
                    "application_metrics=in-process"));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
