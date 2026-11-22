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

import io.prometheus.agent.EmbeddedAgentInfo;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The agent's connection, as Actuator's {@code prometheusAgent} health component: UP, with the agent's name and the
 * proxy it reached, while it's connected, and UNKNOWN while it's off or reconnecting, never DOWN, so a proxy that's
 * away doesn't take the app out of a load balancer or have it restarted.
 */
public class PrometheusProxyAgentHealthIndicator implements HealthIndicator {
  private final PrometheusProxyAgent agent;

  /**
   * The health of {@code agent}'s connection.
   *
   * @param agent the agent
   */
  public PrometheusProxyAgentHealthIndicator(PrometheusProxyAgent agent) {
    this.agent = agent;
  }

  @Override
  public Health health() {
    return agent.agent()
        .map(PrometheusProxyAgentHealthIndicator::health)
        .orElseGet(() -> Health.unknown().withDetail("agent", "off").build());
  }

  private static Health health(EmbeddedAgentInfo info) {
    if (info.isConnected()) {
      return Health.up()
          .withDetail("agent", info.getAgentName())
          // Read apart from isConnected, so a connection that dropped in between has no endpoint
          .withDetail("proxy", Objects.requireNonNullElse(info.getProxyEndpoint(), "reconnecting"))
          .build();
    }
    return Health.unknown().withDetail("agent", info.getAgentName()).withDetail("proxy", "reconnecting").build();
  }
}
