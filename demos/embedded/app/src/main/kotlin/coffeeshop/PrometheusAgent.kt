package coffeeshop

import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.agent.embeddedAgent
import io.prometheus.common.ConfigLoadException

private val logger = KotlinLogging.logger {}

// The agent's own metrics endpoint, inside the container only: the agent serves the proxy its scrapes of it.
private const val AGENT_METRICS_PORT = 8083

// The website's Embedded Demo page shows this function, with its KDoc, from here to its end marker.
// --8<-- [start:agent-config]

/**
 * Starts the prometheus-proxy agent embedded in the shop, from the app's own settings, in memory: no config file holds
 * the token, the proxy's certificate, or the password for /metrics. It connects to the proxy, so the app opens no port
 * for Prometheus, and answers each scrape by reading the app's /metrics on 127.0.0.1. It's named after [appVersion],
 * so the proxy's dashboard and logs tell the old and new versions apart during a deploy.
 *
 * Returns null when the agent is off, without PROMETHEUS_PROXY, or can't start: the shop's metrics aren't worth an
 * outage, so it keeps serving either way.
 */
fun startPrometheusAgent(
  appPort: Int,
  appVersion: String,
): EmbeddedAgentInfo? {
  // host:port, the proxy's agent port
  val proxyEndpoint = env("PROMETHEUS_PROXY").ifEmpty {
    logger.info { "PROMETHEUS_PROXY isn't set, so the Prometheus agent stays off" }
    return null
  }
  return try {
    embeddedAgent {
      // The version tells the old and new deployments apart on the proxy's dashboard and in its logs
      name("coffee-shop-$appVersion")
      proxy(proxyEndpoint)
      token(env("PROMETHEUS_AGENT_TOKEN"))
      // The certificate the proxy's is signed with, as PEM or its base64, and the name it was issued to
      trustCertificatePem(
        env("PROMETHEUS_PROXY_CERT"),
        authority = env("PROMETHEUS_PROXY_AUTHORITY").ifEmpty { "prometheus-proxy" },
      )
      // The app's own /metrics; the agent sends the credentials as Basic Auth
      scrapeSelf(
        path = "coffee_shop_metrics",
        port = appPort,
        username = env("METRICS_USER"),
        password = env("METRICS_PASSWORD"),
      )
      // The agent's own metrics, which it serves the proxy too
      agentMetrics(AGENT_METRICS_PORT)
      scrapeSelf(path = "coffee_shop_agent_metrics", port = AGENT_METRICS_PORT)
    }.start(logBanner = false).also { agent ->
      logger.info { "Prometheus agent ${agent.agentName} started; it connects to the proxy at $proxyEndpoint" }
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
