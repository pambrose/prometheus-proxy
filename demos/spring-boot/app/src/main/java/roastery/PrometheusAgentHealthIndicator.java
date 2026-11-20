package roastery;

import io.prometheus.agent.EmbeddedAgentInfo;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The embedded agent in Actuator's {@code /actuator/health}, as the {@code prometheusAgent} component: UP while it's
 * connected to a proxy that can scrape through it, with the proxy's address, and UNKNOWN when it's off or reconnecting.
 * Not DOWN: the roastery keeps serving without its agent, so a lost proxy leaves the app's own health UP.
 */
@Component
public class PrometheusAgentHealthIndicator implements HealthIndicator {
  private final PrometheusAgentLifecycle lifecycle;

  public PrometheusAgentHealthIndicator(PrometheusAgentLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public Health health() {
    EmbeddedAgentInfo agent = lifecycle.agent();
    if (agent == null) {
      return Health.unknown().withDetail("agent", "off").build();
    }
    if (agent.isConnected()) {
      return Health.up().withDetail("agent", agent.getAgentName()).withDetail("proxy", agent.getProxyEndpoint()).build();
    }
    return Health.unknown().withDetail("agent", agent.getAgentName()).withDetail("proxy", "reconnecting").build();
  }
}
