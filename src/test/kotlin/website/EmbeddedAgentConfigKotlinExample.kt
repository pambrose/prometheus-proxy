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

package website

// --8<-- [start:embedded-kotlin-config]
import com.typesafe.config.ConfigValueFactory
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.common.ConfigLoadException
import java.net.URLEncoder

// Starts the agent from the app's own settings, in memory, so no config file holds the token or the password. Returns
// null when the agent is off, or can't start: the app's metrics aren't worth an outage.
fun startPrometheusAgent(
  appVersion: String,
  appPort: Int,
): EmbeddedAgentInfo? {
  // host:port, the proxy's agent port; the agent stays off without it
  val proxy = env("PROMETHEUS_PROXY").ifEmpty { return null }
  val config =
    ConfigValueFactory
      .fromMap(
        mapOf(
          "agent" to
            mapOf(
              // The version tells the old and new revisions apart during a deploy
              "name" to "my-app-$appVersion",
              "proxy" to mapOf("hostname" to proxy),
              "agentToken" to env("PROMETHEUS_AGENT_TOKEN"),
              "tls" to
                mapOf(
                  // The proxy's certificate, as PEM or its base64, and the name it was issued to
                  "trustCertCollectionPem" to env("PROMETHEUS_PROXY_CERT"),
                  "overrideAuthority" to "prometheus-proxy",
                ),
              // The agent opens no port of its own
              "admin" to mapOf("enabled" to false),
              "metrics" to mapOf("enabled" to false),
              "pathConfigs" to
                listOf(
                  mapOf(
                    "name" to "My App",
                    "path" to "my_app_metrics",
                    // The app's own /metrics, on this machine; the agent sends the credentials as Basic Auth
                    "url" to "http://${userInfo("METRICS_USER")}:${userInfo("METRICS_PASSWORD")}" +
                      "@127.0.0.1:$appPort/metrics",
                  ),
                ),
            ),
        ),
      ).toConfig()

  return try {
    Agent.startAsyncAgent(config, logBanner = false)
  } catch (e: ConfigLoadException) {
    System.err.println("The Prometheus agent's settings are invalid: ${e.message}")
    null
  } catch (e: IllegalStateException) {
    System.err.println("The Prometheus agent could not start: ${e.message}")
    null
  }
}

private fun env(name: String) = System.getenv(name).orEmpty().trim()

// A credential for a URL's user info, percent-encoded. URLEncoder writes a space as '+', which user info keeps as a
// plus, so it becomes %20.
private fun userInfo(name: String) = URLEncoder.encode(env(name), Charsets.UTF_8).replace("+", "%20")
// --8<-- [end:embedded-kotlin-config]
