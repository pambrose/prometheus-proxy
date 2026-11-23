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

import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * The embedded agent, started and stopped with the app. It starts in the last phase, once the app is up, so it stops
 * first, before a graceful shutdown stops the web server. It starts only with a {@code prometheus-proxy.agent.proxy}:
 * without one it logs a line and stays off. An agent that can't start is logged and stays off too, and the app runs
 * without it, unless {@code prometheus-proxy.agent.required} is set.
 *
 * <p>The agent's settings are the {@code prometheus-proxy.agent.*} properties, then each
 * {@link PrometheusProxyAgentCustomizer} in order. It serves the app's {@code PrometheusRegistry} bean in-process, at
 * the proxy's {@code prometheus-proxy.agent.path}, so the app needs no {@code /actuator/prometheus} endpoint, and no
 * web server, for the agent to read its metrics.
 */
public class PrometheusProxyAgent implements SmartLifecycle {
  private static final Logger logger = LoggerFactory.getLogger(PrometheusProxyAgent.class);
  private static final String PROXY_UNSET = "prometheus-proxy.agent.proxy isn't set";

  private final PrometheusProxyAgentProperties properties;
  // Read as the agent starts, once every bean is: the app's name, its build info, its registry, and the customizers
  private final ApplicationContext context;
  private volatile EmbeddedAgentInfo agent;

  /**
   * An agent with {@code properties}, which reads the rest of its settings from {@code context} when it starts.
   *
   * @param properties the {@code prometheus-proxy.agent.*} properties
   * @param context the app's context
   */
  public PrometheusProxyAgent(PrometheusProxyAgentProperties properties, ApplicationContext context) {
    this.properties = properties;
    this.context = context;
  }

  /** Starts the agent, unless it's running already, which Spring checks too. */
  @Override
  public void start() {
    if (agent != null) {
      return;
    }
    if (isBlank(properties.getProxy())) {
      withoutAProxy();
      return;
    }
    try {
      launch();
    } catch (RuntimeException e) {
      // A key the agent's config can't hold, or a customizer's own failure, or, for a required agent, a setting the
      // agent rejects (ConfigLoadException) or its failure to start. Any other agent's start logs its own failure.
      failed(e, e);
    } catch (Exception e) {
      // A checked exception, which Kotlin throws undeclared, such as a required agent's start timing out
      failed(e, new IllegalStateException("The Prometheus agent could not start", e));
    }
  }

  // No proxy: the agent stays off, as on a developer's machine, unless it's required
  private void withoutAProxy() {
    if (properties.isRequired()) {
      throw new IllegalStateException(PROXY_UNSET + ", and prometheus-proxy.agent.required is true");
    }
    logger.info("{}, so the Prometheus agent stays off", PROXY_UNSET);
  }

  // A required agent starts with start(), whose failure fails the app, and any other with startIfConfigured(), which
  // logs a failure, with why, and leaves the agent off
  private void launch() {
    EmbeddedAgentConfig config = config();
    logger.info("Starting the Prometheus agent with {}", config);
    EmbeddedAgentInfo started = properties.isRequired() ? config.start(false) : config.startIfConfigured(false);
    if (started == null) {
      return;
    }
    agent = started;
    // No proxy named: a customizer, or the agent's own PROXY_HOSTNAME, can override the property's. The line above
    // gives the settings, and the agent logs the proxy it connects to.
    logger.info("Prometheus agent {} started", started.getAgentName());
  }

  // A start that threw e: a required agent fails the app with thrown, and any other stays off, logging why
  private void failed(Exception e, RuntimeException thrown) {
    if (properties.isRequired()) {
      throw thrown;
    }
    String reason = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
    logger.error("The Prometheus agent could not start, so it stays off: {}", reason);
  }

  @Override
  public void stop() {
    EmbeddedAgentInfo started = agent;
    if (started != null) {
      logger.info("Stopping the Prometheus agent {}", started.getAgentName());
      agent = null;
      started.shutdown();
    }
  }

  /** Whether the agent is running: one that's off, or failed to start, has nothing to stop. */
  @Override
  public boolean isRunning() {
    return agent != null;
  }

  /**
   * The running agent's handle, with its connection to the proxy, or empty when it's off.
   *
   * @return the agent, while it runs
   */
  public Optional<EmbeddedAgentInfo> agent() {
    return Optional.ofNullable(agent);
  }

  // The settings the agent starts with: the properties, then the customizers
  EmbeddedAgentConfig config() {
    EmbeddedAgentConfig.Builder builder = EmbeddedAgentConfig.builder();
    connection(builder);
    features(builder);
    paths(builder);
    properties.getSettings().forEach(builder::setting);
    context.getBeanProvider(PrometheusProxyAgentCustomizer.class).orderedStream().forEach(c -> c.customize(builder));
    return builder.build();
  }

  // Who the agent is, and how it reaches the proxy
  private void connection(EmbeddedAgentConfig.Builder builder) {
    String name = agentName();
    if (name != null) {
      builder.name(name);
    }
    if (!isBlank(properties.getProxy())) {
      builder.proxy(properties.getProxy());
    }
    if (!isBlank(properties.getToken())) {
      builder.token(properties.getToken());
    }
    if (!isBlank(properties.getTrustCertificate())) {
      builder.trustCertificatePem(properties.getTrustCertificate(), blankToNull(properties.getAuthority()));
    }
    if (!isBlank(properties.getClientCertificate()) || !isBlank(properties.getClientKey())) {
      builder.clientCertificatePem(orEmpty(properties.getClientCertificate()), orEmpty(properties.getClientKey()));
    }
  }

  // What the agent serves of its own: consolidated mode, its metrics, and its dashboard
  private void features(EmbeddedAgentConfig.Builder builder) {
    if (properties.isConsolidated()) {
      builder.consolidated(true);
    }
    if (properties.getMetricsPort() != null) {
      builder.agentMetrics(properties.getMetricsPort());
    }
    PrometheusProxyAgentProperties.Dashboard dashboard = properties.getDashboard();
    if (dashboard.getPort() != null) {
      builder.dashboard(dashboard.getPort(), blankToNull(dashboard.getHost()));
    }
    if (!isBlank(dashboard.getPassword())) {
      builder.dashboardLogin(dashboard.getPassword(), orEmpty(dashboard.getUsername()));
    }
  }

  // The targets it scrapes, then the app's registry
  private void paths(EmbeddedAgentConfig.Builder builder) {
    for (PrometheusProxyAgentProperties.Target target : properties.getPaths()) {
      builder.path(
          orEmpty(target.getPath()), orEmpty(target.getUrl()), target.getUsername(), target.getPassword(),
          target.getLabels());
    }
    if (properties.getSource() != PrometheusProxyAgentProperties.Source.REGISTRY) {
      return;
    }
    PrometheusRegistry registry = context.getBeanProvider(PrometheusRegistry.class).getIfUnique();
    if (registry == null) {
      logger.warn(
          "The app has no single PrometheusRegistry bean, so the agent doesn't serve its metrics; Spring Boot makes one "
              + "with micrometer-registry-prometheus and spring-boot-starter-actuator or "
              + "spring-boot-starter-micrometer-metrics");
      return;
    }
    builder.source(metricsPath(), properties.getLabels(), registry);
  }

  // The name given, or the app's, with its build's version when it has build info, or none, for the agent's default
  private String agentName() {
    if (!isBlank(properties.getName())) {
      return properties.getName();
    }
    String app = appName();
    if (app == null) {
      return null;
    }
    BuildProperties build = context.getBeanProvider(BuildProperties.class).getIfAvailable();
    return build == null || isBlank(build.getVersion()) ? app : app + "-" + build.getVersion();
  }

  private String metricsPath() {
    if (!isBlank(properties.getPath())) {
      return properties.getPath();
    }
    String app = appName();
    return (app == null ? "application" : app) + "_metrics";
  }

  private String appName() {
    return blankToNull(context.getEnvironment().getProperty("spring.application.name"));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
