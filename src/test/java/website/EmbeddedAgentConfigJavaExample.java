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
import io.prometheus.common.ConfigLoadException;

public class EmbeddedAgentConfigJavaExample {
  // Starts the agent from the app's own settings, in memory, so no config file holds the token or the password.
  // Returns null when the agent is off, or can't start: the app's metrics aren't worth an outage.
  public static EmbeddedAgentInfo startPrometheusAgent(String appVersion, int appPort) {
    // host:port, the proxy's agent port; the agent stays off without it
    String proxy = env("PROMETHEUS_PROXY");
    if (proxy.isEmpty()) {
      return null;
    }

    try {
      return EmbeddedAgentConfig.builder()
        // The version tells the old and new revisions apart during a deploy
        .name("my-app-" + appVersion)
        .proxy(proxy)
        .token(env("PROMETHEUS_AGENT_TOKEN"))
        // The proxy's certificate, as PEM or its base64, and the name it was issued to
        .trustCertificatePem(env("PROMETHEUS_PROXY_CERT"), "prometheus-proxy")
        // The app's own /metrics, on 127.0.0.1; the agent sends the credentials as Basic Auth
        .scrapeSelf("my_app_metrics", appPort, env("METRICS_USER"), env("METRICS_PASSWORD"))
        .build()
        .start(false);
    } catch (ConfigLoadException e) {
      System.err.println("The Prometheus agent's settings are invalid: " + e.getMessage());
      return null;
    } catch (IllegalStateException e) {
      System.err.println("The Prometheus agent could not start: " + e.getMessage());
      return null;
    }
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.trim();
  }
}
// --8<-- [end:embedded-java-config]
