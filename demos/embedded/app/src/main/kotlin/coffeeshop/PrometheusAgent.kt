package coffeeshop

import com.typesafe.config.ConfigValueFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.common.ConfigLoadException
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}

// The agent's own metrics endpoint, inside the container only: the agent serves the proxy its scrapes of it.
private const val AGENT_METRICS_PORT = 8083

// The website's Embedded Demo page shows this function, with its KDoc, from here to its end marker.
// --8<-- [start:agent-config]

/**
 * Starts the prometheus-proxy agent embedded in the shop, from the app's own settings, in memory: no config file holds
 * the token, the proxy's certificate, or the password for /metrics. It connects to the proxy, so the app opens no port
 * for Prometheus, and answers each scrape by reading the app's /metrics on 127.0.0.1.
 *
 * Returns null when the agent is off, without PROMETHEUS_PROXY, or can't start: the shop's metrics aren't worth an
 * outage, so it keeps serving either way.
 */
fun startPrometheusAgent(appPort: Int): EmbeddedAgentInfo? {
  // host:port, the proxy's agent port
  val proxy = env("PROMETHEUS_PROXY").ifEmpty {
    logger.info { "PROMETHEUS_PROXY isn't set, so the Prometheus agent stays off" }
    return null
  }
  val config =
    ConfigValueFactory
      .fromMap(
        mapOf(
          "agent" to
            mapOf(
              // The version tells the old and new deployments apart on the proxy's dashboard and in its logs
              "name" to "coffee-shop-${env("APP_VERSION").ifEmpty { "dev" }}",
              "proxy" to mapOf("hostname" to proxy),
              "agentToken" to env("PROMETHEUS_AGENT_TOKEN"),
              "tls" to
                mapOf(
                  // The certificate the proxy's is signed with, as PEM or its base64, and the name it was issued to
                  "trustCertCollectionPem" to env("PROMETHEUS_PROXY_CERT"),
                  "overrideAuthority" to env("PROMETHEUS_PROXY_AUTHORITY").ifEmpty { "prometheus-proxy" },
                ),
              "admin" to mapOf("enabled" to false),
              "metrics" to mapOf("enabled" to true, "port" to AGENT_METRICS_PORT),
              "pathConfigs" to
                listOf(
                  mapOf(
                    "name" to "Coffee shop",
                    "path" to "coffee_shop_metrics",
                    // The app's own /metrics; the agent sends the credentials as Basic Auth
                    "url" to "http://${userInfo("METRICS_USER")}:${userInfo("METRICS_PASSWORD")}" +
                      "@127.0.0.1:$appPort/metrics",
                  ),
                  mapOf(
                    "name" to "Coffee shop's agent",
                    "path" to "coffee_shop_agent_metrics",
                    "url" to "http://127.0.0.1:$AGENT_METRICS_PORT/metrics",
                  ),
                ),
            ),
        ),
      ).toConfig()

  return try {
    Agent.startAsyncAgent(config, logBanner = false).also { agent ->
      logger.info { "Prometheus agent ${agent.agentName} started; it connects to the proxy at $proxy" }
    }
  } catch (e: ConfigLoadException) {
    logger.error { "The Prometheus agent's settings are invalid, so it stays off: ${e.message}" }
    null
  } catch (e: IllegalStateException) {
    logger.error { "The Prometheus agent could not start, so it stays off: ${e.message}" }
    null
  }
}
// --8<-- [end:agent-config]

private fun env(name: String) = System.getenv(name).orEmpty().trim()

// A credential for a URL's user info, percent-encoded. URLEncoder writes a space as '+', which user info keeps as a
// plus, so it becomes %20.
private fun userInfo(name: String) = URLEncoder.encode(env(name), Charsets.UTF_8).replace("+", "%20")
