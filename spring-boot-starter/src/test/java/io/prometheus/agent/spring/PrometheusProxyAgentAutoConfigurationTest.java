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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.prometheus.agent.EmbeddedAgentInfo;
import io.prometheus.common.ConfigLoadException;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.SystemEnvironmentPropertySource;

// Each test asserts inside the context runner's lambda, the Spring Boot idiom, where PMD's rule doesn't look
@SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
class PrometheusProxyAgentAutoConfigurationTest {
  private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";

  // The starter's auto-configurations, as its AutoConfiguration.imports lists them
  static final AutoConfigurations STARTER =
      AutoConfigurations.of(PrometheusProxyAgentAutoConfiguration.class, PrometheusProxyAgentHealthAutoConfiguration.class);

  // The app's registry, as Spring Boot's Prometheus export makes it a bean
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(STARTER)
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
            "prometheus-proxy.agent.metrics-proxy-path=/orders_agent_metrics",
            "prometheus-proxy.agent.metrics-proxy-path-labels.team=coffee",
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
                  "metrics={enabled=true, port=9101, proxyPath=orders_agent_metrics, "
                      + "proxyPathLabels={\"team\":\"coffee\"}}",
                  "dashboard={enabled=true, host=127.0.0.1, password=(set), port=9102, username=ops}",
                  "labels={\"team\":\"coffee\"}, name=grinder_metrics, path=grinder_metrics, url=http://",
                  "grinder:9100/metrics",
                  "sources={orders_metrics=in-process}")
              .doesNotContain("s3cret", "hunter2", "p@ss", "MIIB");
        });
  }

  // The properties' toString, there so they can be logged, masks a target URL as the agent's logs do: its user info and
  // each query value, keeping the keys
  @Test
  void theLoggedPropertiesMaskATargetUrlsCredentialsAndQueryValues() {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.paths[0].path=grinder_metrics",
            "prometheus-proxy.agent.paths[0].url=https://u:pw@grinder:9100/metrics?api_key=k3y&x=1#top")
        .run(context -> assertThat(context.getBean(PrometheusProxyAgentProperties.class).toString())
            .contains("url=https://***@grinder:9100/metrics?api_key=***&x=***#top")
            .doesNotContain("pw", "k3y", "x=1"));

    assertThat(PrometheusProxyAgentProperties.shownUrl("http://grinder:9100/metrics")).isEqualTo(
        "http://grinder:9100/metrics");
    assertThat(PrometheusProxyAgentProperties.shownUrl("http://a@b@grinder/m?flag&k=v&")).isEqualTo(
        "http://***@b@grinder/m?flag&k=***&");
    assertThat(PrometheusProxyAgentProperties.shownUrl(null)).isNull();
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
        .withConfiguration(STARTER)
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
        .withConfiguration(STARTER)
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

  // As the customizer's Javadoc and the page say: a builder method doesn't replace a settings.<key> property, which is
  // a setting(), but the customizer's own setting() does
  @Test
  void aCustomizerChangesASettingsPropertyWithSettingNotABuilderMethod() {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.settings.name=from-settings",
            "prometheus-proxy.agent.settings.scrapeTimeoutSecs=7")
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder ->
            builder.name("from-the-customizer").setting("scrapeTimeoutSecs", 9))
        .run(context ->
            assertThat(config(context))
                .contains("name=from-settings", "scrapeTimeoutSecs=9")
                .doesNotContain("from-the-customizer", "scrapeTimeoutSecs=7"));
  }

  @Test
  void aRequiredAgentWithoutAProxyFailsTheApp() {
    runner
        .withPropertyValues("prometheus-proxy.agent.required=true")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessage("No proxy is set (prometheus-proxy.agent.proxy or PROXY_HOSTNAME), and "
                    + "prometheus-proxy.agent.required is true"));
  }

  // The agent's own rule, EmbeddedAgentConfig.namesProxy(), read once the customizers have run: the starter read only
  // its proxy property, before them, so a customizer's proxy, a settings key's, or the agent's PROXY_HOSTNAME left the
  // agent off, and failed a required one's app (November 23 review, #11)
  @Test
  void aCustomizersProxyStartsTheAgent() {
    runner
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> builder.proxy("localhost:" + closedPort()))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isTrue();
        });
  }

  @Test
  void aRequiredAgentWhoseCustomizerSetsTheProxyStarts() {
    runner
        .withPropertyValues("prometheus-proxy.agent.required=true")
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> builder.proxy("localhost:" + closedPort()))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isTrue();
        });
  }

  @Test
  void aProxySettingStartsTheAgent() throws IOException {
    runner
        .withPropertyValues("prometheus-proxy.agent.settings.proxy.hostname=localhost:" + freePort())
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isTrue();
        });
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

  // The builder's stopTimeout, which caps how long the app's shutdown waits for the agent's stop: Spring's own phase
  // timeout doesn't, as stop() waits for it
  @Test
  void theStopTimeoutIsThirtySecondsUnlessThePropertySetsAnother() {
    runner.run(context -> assertThat(config(context)).contains("stopTimeout=30s"));
    runner
        .withPropertyValues("prometheus-proxy.agent.stop-timeout=5s")
        .run(context -> assertThat(config(context)).contains("stopTimeout=5s"));
  }

  // The builder's onFailure, which startIfConfigured tells: a customizer adds one, as it makes any other setting
  @Test
  void aCustomizersOnFailureIsToldWhyTheAgentCouldNotStart() throws IOException {
    List<String> reasons = new CopyOnWriteArrayList<>();
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.settings.scrapeTimeoutSecs=soon")
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder ->
            builder.onFailure((reason, e) -> reasons.add(reason)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(reasons).singleElement().asString().contains("scrapeTimeoutSecs");
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

  // A checked exception, which Kotlin code can throw undeclared
  @Test
  void aRequiredAgentThatFailsWithACheckedExceptionFailsTheAppWithIt() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.required=true", "prometheus-proxy.agent.proxy=localhost:" + freePort())
        .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> sneakyThrow(new IOException("unreachable")))
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IOException.class)
                .hasMessage("unreachable"));
  }

  // A setting the builder takes but the agent rejects as it starts, as the Ktor plugin's test of its counterpart has:
  // only the agent's start() reaches it, which the starter calls for a required agent, where startIfConfigured() would
  // log it and leave the app running without the agent. The other required tests fail before the starter picks either
  // (November 23 review, #47).
  @Test
  void aRequiredAgentTheAgentRejectsFailsTheApp() throws IOException {
    runner
        .withPropertyValues(
            "prometheus-proxy.agent.required=true",
            "prometheus-proxy.agent.proxy=localhost:" + freePort(),
            "prometheus-proxy.agent.trust-certificate=not a certificate")
        .run(context ->
            assertThat(causes(context.getStartupFailure())).hasAtLeastOneElementOfType(ConfigLoadException.class));
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

  // Lifecycle's contract: start() on a running component does nothing, rather than starting a second agent and losing
  // the first one's handle, so nothing could stop it
  @Test
  void startingARunningAgentAgainKeepsIt() throws IOException {
    runner
        .withPropertyValues("prometheus-proxy.agent.proxy=localhost:" + freePort())
        .run(context -> {
          PrometheusProxyAgent agent = context.getBean(PrometheusProxyAgent.class);
          EmbeddedAgentInfo first = agent.agent().orElseThrow();
          agent.start();
          assertThat(agent.agent()).containsSame(first);
        });
  }

  // Labels with no path would do nothing, so the starter says so rather than drop them unseen
  @Test
  void metricsProxyPathLabelsWithoutAPathAreWarnedOf() {
    List<String> lines = agentLog(() ->
        runner
            .withPropertyValues("prometheus-proxy.agent.metrics-proxy-path-labels.team=coffee")
            .run(context -> assertThat(config(context)).doesNotContain("proxyPath")));

    assertThat(lines)
        .contains(
            "prometheus-proxy.agent.metrics-proxy-path-labels is set without metrics-proxy-path, so it does nothing");
  }

  // A customizer can point the agent elsewhere, so the line that says it started names no proxy: the line before it
  // gives the settings it starts with, and the agent logs the proxy it connects to
  @Test
  void theStartedLineNamesNoProxyTheAgentMightNotUse() throws IOException {
    String fromTheProperty = "localhost:" + freePort();
    List<String> lines = agentLog(() ->
        runner
            .withPropertyValues("prometheus-proxy.agent.proxy=" + fromTheProperty)
            .withBean(PrometheusProxyAgentCustomizer.class, () -> builder -> builder.proxy("localhost:" + closedPort()))
            .run(context -> assertThat(context.getBean(PrometheusProxyAgent.class).isRunning()).isTrue()));

    assertThat(lines).anyMatch(line -> line.startsWith("Prometheus agent orders started"));
    assertThat(lines).noneMatch(line -> line.startsWith("Prometheus agent") && line.contains(fromTheProperty));
  }

  // An app's own agents, more than one, which a health indicator can't choose between: the app starts without one
  @Test
  void twoAgentsOfTheAppsOwnGetNoHealthIndicatorAndTheAppRuns() {
    runner
        .withUserConfiguration(TwoAgents.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(PrometheusProxyAgent.class)).hasSize(2);
          assertThat(context).doesNotHaveBean("prometheusAgentHealthIndicator");
        });
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

  // A throwable and its causes, outermost first; none for null
  private static List<Throwable> causes(Throwable failure) {
    List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure; cause != null && !causes.contains(cause); cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
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

  // freePort() for a lambda, which can't throw IOException
  private static int closedPort() {
    try {
      return freePort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The lines PrometheusProxyAgent logs while run runs, at INFO whatever level another test left its logger at
  private static List<String> agentLog(Runnable run) {
    Logger logger = (Logger) LoggerFactory.getLogger(PrometheusProxyAgent.class);
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    Level level = logger.getLevel();
    logger.setLevel(Level.INFO);
    logger.addAppender(events);
    try {
      run.run();
    } finally {
      logger.detachAppender(events);
      logger.setLevel(level);
    }
    return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoAgents {
    @Bean
    PrometheusProxyAgent east(ApplicationContext context) {
      return new PrometheusProxyAgent(new PrometheusProxyAgentProperties(), context);
    }

    @Bean
    PrometheusProxyAgent west(ApplicationContext context) {
      return new PrometheusProxyAgent(new PrometheusProxyAgentProperties(), context);
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
