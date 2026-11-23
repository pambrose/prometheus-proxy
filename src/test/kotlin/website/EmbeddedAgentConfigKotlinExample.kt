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
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.agent.embeddedAgent

// Starts the agent from the app's own settings, in memory, so no config file holds the token or the password. Returns
// null when the agent is off, without PROMETHEUS_PROXY, or can't start, which it logs: the app's metrics aren't worth
// an outage.
fun startPrometheusAgent(
  appVersion: String,
  appPort: Int,
): EmbeddedAgentInfo? =
  embeddedAgent {
    // The version tells the old and new revisions apart during a deploy
    name = "my-app-$appVersion"
    // The proxy, its token, and its certificate: PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, and PROMETHEUS_PROXY_CERT
    fromEnvironment()
    // The app's own /metrics, on 127.0.0.1; the agent sends the credentials as Basic Auth
    scrapeSelf(
      path = "my_app_metrics",
      port = appPort,
      username = env("METRICS_USER"),
      password = env("METRICS_PASSWORD"),
    )
  }.startIfConfigured(logBanner = false)

private fun env(name: String) = System.getenv(name).orEmpty().trim()
// --8<-- [end:embedded-kotlin-config]
