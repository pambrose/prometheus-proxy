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

package website;

// --8<-- [start:embedded-java-source]
import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public class EmbeddedAgentSourceJavaExample {
  // A worker with no HTTP server of its own: the agent reads its metrics from the registry, in-process, for each
  // scrape, and serves its own beside them. Without PROMETHEUS_PROXY, as on a developer's machine, the worker runs
  // without it.
  public static void runWorker(String version, Runnable work) {
    EmbeddedAgentConfig config =
      EmbeddedAgentConfig.builder()
        .name("worker-" + version)
        .fromEnvironment()
        // Where the worker's metrics are: Micrometer's PrometheusMeterRegistry gives its own with getPrometheusRegistry()
        .source("worker_metrics", PrometheusRegistry.defaultRegistry)
        // The agent's own, for the Agents dashboard
        .agentMetricsAt("worker_agent_metrics")
        .build();
    // Null when the agent stays off, which try-with-resources takes: the worker runs either way
    try (EmbeddedAgentInfo agent = config.startIfConfigured(false)) {
      work.run();
    }
  }
}
// --8<-- [end:embedded-java-source]
