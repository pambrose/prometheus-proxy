package roastery;

import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;
import io.prometheus.common.ConfigLoadException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// The website's Spring Boot Demo page shows this class, with its Javadoc, from here to its end marker.
// --8<-- [start:agent-lifecycle]

/**
 * Runs the prometheus-proxy agent embedded in the roastery, from its {@link AgentSettings}, in memory: no config file
 * holds the token, the proxy's certificate, or the password for {@code /actuator/prometheus}. The agent connects to
 * the proxy, so the app opens no port for Prometheus, and answers each scrape by reading Actuator's
 * {@code /actuator/prometheus} on 127.0.0.1. It's named after the app's version, so the proxy's dashboard and logs tell
 * the old and new versions apart during a deploy.
 *
 * <p>As a {@link SmartLifecycle} in the last phase, it starts once the web server is serving, so the agent's first
 * scrape finds Actuator, and stops first when the app shuts down, before the web server drains: the agent disconnects
 * from the proxy, which drops the roastery's path, and no scrape reaches a server that's going away.
 *
 * <p>The agent stays off without a proxy, or when it can't start: the roastery's metrics aren't worth an outage.
 */
@Component
public class PrometheusAgentLifecycle implements SmartLifecycle {
  private static final Logger logger = LoggerFactory.getLogger(PrometheusAgentLifecycle.class);

  private final AgentSettings settings;
  private final BuildProperties build;
  private final Environment environment;
  private volatile EmbeddedAgentInfo agent;
  private volatile boolean running;

  public PrometheusAgentLifecycle(AgentSettings settings, BuildProperties build, Environment environment) {
    this.settings = settings;
    this.build = build;
    this.environment = environment;
  }

  @Override
  public void start() {
    running = true;
    if (!settings.enabled()) {
      logger.info("roastery.monitoring.proxy-endpoint isn't set, so the Prometheus agent stays off");
      return;
    }
    // The port the web server took, which Spring Boot sets once it's serving
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    logger.info("Starting the Prometheus agent with {}", settings);
    try {
      agent =
          EmbeddedAgentConfig.builder()
              // The version tells the old and new deployments apart on the proxy's dashboard and in its logs
              .name("roastery-" + build.getVersion())
              .proxy(settings.proxyEndpoint())
              .token(settings.token())
              // The certificate the proxy's is signed with, as PEM or its base64, and the name it was issued to
              .trustCertificatePem(settings.certificate(), settings.authority())
              // Actuator's metrics; the agent sends the credentials as Basic Auth
              .scrapeSelf(
                  "roastery_metrics",
                  port,
                  settings.metricsUser(),
                  settings.metricsPassword(),
                  "actuator/prometheus",
                  Map.of())
              .build()
              .start(false);
      logger.info(
          "Prometheus agent {} started; it connects to the proxy at {}",
          agent.getAgentName(),
          settings.proxyEndpoint());
    } catch (ConfigLoadException e) {
      logger.error("The Prometheus agent's settings are invalid, so it stays off: {}", e.getMessage());
    } catch (IllegalStateException e) {
      logger.error("The Prometheus agent could not start, so it stays off: {}", e.getMessage());
    }
  }

  @Override
  public void stop() {
    EmbeddedAgentInfo started = agent;
    if (started != null) {
      logger.info("Stopping the Prometheus agent {}", started.getAgentName());
      started.shutdown();
      agent = null;
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
// --8<-- [end:agent-lifecycle]
