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

// --8<-- [start:embedded-java-config]
import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;
import java.time.Duration;

public class EmbeddedAgentConfigJavaExample {
  // Starts the agent from the app's own settings, in memory, so no config file holds the token or the password.
  // Returns null when the agent is off, without PROMETHEUS_PROXY, or can't start, which it logs: the app's metrics
  // aren't worth an outage.
  public static EmbeddedAgentInfo startPrometheusAgent(String appVersion, int appPort) {
    return EmbeddedAgentConfig.builder()
      // The version tells the old and new revisions apart during a deploy
      .name("my-app-" + appVersion)
      // The proxy, its token, and its certificate: PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, and PROMETHEUS_PROXY_CERT
      .fromEnvironment()
      // The app's own /metrics, on 127.0.0.1; the agent sends the credentials as Basic Auth
      .scrapeSelf("my_app_metrics", appPort, env("METRICS_USER"), env("METRICS_PASSWORD"))
      // How long the agent's stop may hold up the app's shutdown: inside the platform's window after SIGTERM, such as
      // Cloud Run's 10 seconds
      .stopTimeout(Duration.ofSeconds(8))
      .build()
      .startIfConfigured(false);
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.trim();
  }
}
// --8<-- [end:embedded-java-config]
