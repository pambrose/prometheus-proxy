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
import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

import io.prometheus.common.ConfigLoadException;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.SystemEnvironmentPropertySource;

// Each test asserts inside the context runner's lambda, the Spring Boot idiom, where PMD's rule doesn't look
@SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
class PrometheusProxyAgentAutoConfigurationTest {
  private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";

  // The app's registry, as Spring Boot's Prometheus export makes it a bean
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PrometheusProxyAgentAutoConfiguration.class))
          .withBean(PrometheusRegistry.class, PrometheusRegistry::new)
          .withPropertyValues("spring.application.name=orders");

  @Test
  void withoutAProxyTheAgentStaysOff() {
    runner.run(context -> {
      PrometheusProxyAgent agent = context.getBean(PrometheusProxyAgent.class);
      assertThat(agent.isRunning()).isFalse();
      assertThat(agent.agent()).isEmpty();
    });
  }

  @Test
  void turnedOffThereIsNoAgent() {
    runner
        .withPropertyValues("prometheus-proxy.agent.enabled=false", "prometheus-proxy.agent.proxy=localhost:50051")
        .run(context -> assertThat(context).doesNotHaveBean(PrometheusProxyAgent.class));
  }

  @Test
  void withAProxyTheAgentStartsAndStopsWithTheApp() throws IOException {
    int closedPort = freePort();
    PrometheusProxyAgent[] started = new PrometheusProxyAgent[1];
    runner
        .withPropertyValues("prometheus-proxy.agent.proxy=localhost:" + closedPort)
        .run(context -> {
          assertThat(context).hasNotFailed();
          started[0] = context.getBean(PrometheusProxyAgent.class);
          assertThat(started[0].isRunning()).isTrue();
          assertThat(started[0].agent()).hasValueSatisfying(info -> assertThat(info.getAgentName()).isEqualTo("orders"));
        });
    assertThat(started[0].isRunning()).isFalse();
    assertThat(started[0].agent()).isEmpty();
  }

  @Test
  void thePropertiesBecomeTheAgentsSettings() {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.name=orders-east",
            "prometheus-proxy.agent.token=s3cret",
            "prometheus-proxy.agent.trust-certificate=" + PEM,
            "prometheus-proxy.agent.authority=prometheus-proxy",
            "prometheus-proxy.agent.client-certificate=" + PEM,
            "prometheus-proxy.agent.client-key=" + PEM,
            "prometheus-proxy.agent.consolidated=true",
            "prometheus-proxy.agent.metrics-port=9101",
            "prometheus-proxy.agent.dashboard.port=9102",
            "prometheus-proxy.agent.dashboard.host=127.0.0.1",
            "prometheus-proxy.agent.dashboard.username=ops",
            "prometheus-proxy.agent.dashboard.password=hunter2",
            "prometheus-proxy.agent.paths[0].path=grinder_metrics",
            "prometheus-proxy.agent.paths[0].url=http://grinder:9100/metrics",
            "prometheus-proxy.agent.paths[0].username=scraper",
            "prometheus-proxy.agent.paths[0].password=p@ss",
            "prometheus-proxy.agent.paths[0].labels.team=coffee")
        .run(context -> {
          String settings = config(context);
          assertThat(settings)
              .contains(
                  "name=orders-east",
                  "agentToken=(set)",
                  "tls={certChainPem=(set), overrideAuthority=prometheus-proxy, privateKeyPem=(set), "
                      + "trustCertCollectionPem=(set)}",
                  "consolidated=true",
                  "metrics={enabled=true, port=9101}",
                  "dashboard={enabled=true, host=127.0.0.1, password=(set), port=9102, username=ops}",
                  "labels={\"team\":\"coffee\"}, name=grinder_metrics, path=grinder_metrics, url=http://",
                  "grinder:9100/metrics",
                  "sources={orders_metrics=in-process}")
              .doesNotContain("s3cret", "hunter2", "p@ss", "MIIB");
        });
  }

  @Test
  void theAppsRegistryIsServedAtTheGivenPath() {
    runner
        .withPropertyValues("prometheus-proxy.agent.path=/roastery_metrics")
        .run(context -> assertThat(config(context)).contains("sources={roastery_metrics=in-process}"));
  }

  @Test
  void theAgentIsNamedForTheAppAndItsBuild() {
    runner
        .withBean(BuildProperties.class, () -> new BuildProperties(version("1.2.3")))
        .run(context -> assertThat(config(context)).contains("name=orders-1.2.3"));
  }

  @Test
  void withoutAnAppNameTheAgentKeepsItsOwnNameAndThePathIsGeneric() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrometheusProxyAgentAutoConfiguration.class))
        .withBean(PrometheusRegistry.class, PrometheusRegistry::new)
        .run(context ->
            assertThat(config(context)).doesNotContain("name=").contains("sources={application_metrics=in-process}"));
  }

  @Test
  void theRegistryIsNotServedWhenTheSourceIsNone() {
    runner
        .withPropertyValues("prometheus-proxy.agent.source=none")
        .run(context -> assertThat(config(context)).doesNotContain("sources="));
  }

  @Test
  void withoutARegistryNothingIsServedInProcess() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrometheusProxyAgentAutoConfiguration.class))
        .run(context -> assertThat(config(context)).doesNotContain("sources="));
  }

  // The agent's keys are camel case, which a map's keys keep
  @Test
  void otherSettingsKeepTheirKeys() {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.settings.scrapeTimeoutSecs=7",
            "prometheus-proxy.agent.settings.http.clientTimeoutSecs=11")
        .run(context -> assertThat(config(context)).contains("scrapeTimeoutSecs=7", "http={clientTimeoutSecs=11}"));
  }

  @Test
  void customizersRunAfterThePropertiesInTheirOrder() {
    runner
        .withPropertyValues("prometheus-proxy.agent.name=from-properties")
        .withUserConfiguration(Customizers.class)
        .run(context -> assertThat(config(context)).contains("name=second", "scrapeTimeoutSecs=9"));
  }

  @Test
  void aRequiredAgentWithoutAProxyFailsTheApp() {
    runner
        .withPropertyValues("prometheus-proxy.agent.required=true")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("prometheus-proxy.agent.proxy isn't set"));
  }

  @Test
  void aRequiredAgentWithABadSettingFailsTheApp() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.required=true",
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.paths[0].path=",
            "prometheus-proxy.agent.paths[0].url=http://grinder:9100/metrics")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("A path is required"));
  }

  @Test
  void anAgentWithABadSettingStaysOffAndTheAppRuns() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.paths[0].path=",
            "prometheus-proxy.agent.paths[0].url=http://grinder:9100/metrics")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isFalse();
        });
  }

  // One the builder takes but the agent rejects as it starts
  @Test
  void anAgentTheAgentRejectsStaysOffAndTheAppRuns() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.settings.scrapeTimeoutSecs=soon")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isFalse();
        });
  }

  // A key the agent's config can't hold throws Typesafe Config's own exception as the settings are built
  @Test
  void anAgentWithASettingKeyItCantHoldStaysOffAndTheAppRuns() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.proxy=localhost:" + freePort(), "prometheus-proxy.agent.settings[foo$bar]=1")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isFalse();
        });
  }

  // As a secret store that's down would, in the page's example
  @Test
  void aCustomizerThatFailsLeavesTheAgentOffAndTheAppRunning() throws IOException {
    runner
        .withPropertyValues("prometheus-proxy.agent.proxy=localhost:" + freePort())
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> {
          throw new UnsupportedOperationException("the secret store is down");
        })
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isFalse();
        });
  }

  @Test
  void aRequiredAgentWhoseCustomizerFailsFailsTheApp() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.required=true", "prometheus-proxy.agent.proxy=localhost:" + freePort())
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> {
          throw new UnsupportedOperationException("the secret store is down");
        })
        .run(context ->
            assertThat(context).getFailure().rootCause().hasMessageContaining("the secret store is down"));
  }

  // A checked exception, which Kotlin throws undeclared, such as the agent's start timing out
  @Test
  void aRequiredAgentThatFailsWithACheckedExceptionFailsTheAppWithIt() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.required=true", "prometheus-proxy.agent.proxy=localhost:" + freePort())
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> sneakyThrow(new TimeoutException("too slow")))
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(TimeoutException.class)
                .hasMessage("too slow"));
  }

  // The docs site's Spring Boot Starter page says so: a certificate the agent can't read doesn't stop the app
  @Test
  void anAgentWithACertificateItCantReadStaysOffAndTheAppRuns() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.trust-certificate=not a certificate")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isFalse();
        });
  }

  // By Spring Boot's rules, as the page gives them: the prefix's dash goes, and the variable sets the property
  @Test
  void anEnvironmentVariableSetsAProperty() {
    runner
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(environment(
            Map.of("PROMETHEUSPROXY_AGENT_NAME", "from-the-environment"))))
        .run(context -> assertThat(config(context)).contains("name=from-the-environment"));
  }

  // Which the page warns of: a settings key from an environment variable loses its case, so it names no setting
  @Test
  void aSettingsKeyFromAnEnvironmentVariableIsLowercased() {
    runner
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(environment(
            Map.of("PROMETHEUSPROXY_AGENT_SETTINGS_SCRAPETIMEOUTSECS", "7"))))
        .run(context -> assertThat(config(context)).contains("scrapetimeoutsecs=7"));
  }

  @Test
  void anAgentThatIsOffIsUnknownToHealth() {
    runner.run(context -> {
      Health health = context.getBean("prometheusAgentHealthIndicator", HealthIndicator.class).health();
      assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
      assertThat(health.getDetails()).containsEntry("agent", "off");
    });
  }

  // Never DOWN, so a proxy that's away doesn't take the app out of a load balancer
  @Test
  void anAgentStillConnectingIsUnknownToHealth() throws IOException {
    runner
        .withPropertyValues("prometheus-proxy.agent.proxy=localhost:" + freePort())
        .run(context -> {
          Health health = context.getBean("prometheusAgentHealthIndicator", HealthIndicator.class).health();
          assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
          assertThat(health.getDetails()).containsEntry("agent", "orders").containsEntry("proxy", "reconnecting");
        });
  }

  @Test
  void theHealthIndicatorCanBeTurnedOff() {
    runner
        .withPropertyValues("management.health.prometheusagent.enabled=false")
        .run(context -> {
          assertThat(context).hasSingleBean(PrometheusProxyAgent.class);
          assertThat(context).doesNotHaveBean("prometheusAgentHealthIndicator");
        });
  }

  @Test
  void withoutSpringBootHealthThereIsNoHealthIndicator() {
    runner
        .withClassLoader(new FilteredClassLoader("org.springframework.boot.health"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(PrometheusProxyAgent.class);
          assertThat(context).doesNotHaveBean("prometheusAgentHealthIndicator");
        });
  }

  private static String config(AssertableApplicationContext context) {
    return context.getBean(PrometheusProxyAgent.class).config().toString();
  }

  // Variables as the process environment gives them, which Spring Boot maps to properties by its rules
  private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
    return new SystemEnvironmentPropertySource("test-" + SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables);
  }

  // Throws a checked exception undeclared, as Kotlin code called from Java can
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  private static Properties version(String version) {
    Properties properties = new Properties();
    properties.setProperty("version", version);
    return properties;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Customizers {
    @Bean
    @Order(2)
    PrometheusProxyAgentCustomizer second() {
      return builder -> builder.name("second");
    }

    @Bean
    @Order(1)
    PrometheusProxyAgentCustomizer first() {
      return builder -> builder.name("first").setting("scrapeTimeoutSecs", 9);
    }
  }
}
