package coffeeshop

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.agent.ktor.PrometheusProxyAgent

// The website's Embedded Demo page shows this function, with its KDoc, from here to its end marker.
// --8<-- [start:agent-config]

/**
 * Runs the prometheus-proxy agent embedded in the shop, with the Ktor plugin, from the app's own settings, in memory: no
 * config file holds the token or the proxy's certificate. It connects to the proxy, so the app opens no port for
 * Prometheus, and reads [registry] in-process for each scrape, so the app has no /metrics either. It's named after
 * [appVersion], so the proxy's dashboard and logs tell the old and new versions apart during a deploy.
 *
 * The plugin starts the agent once the app has loaded, and stops it as the server stops. Without PROMETHEUS_PROXY, or
 * with a setting it can't use, the agent logs why and stays off: the shop's metrics aren't worth an outage, so it keeps
 * serving either way.
 */
fun Application.installPrometheusAgent(
  registry: PrometheusMeterRegistry,
  appVersion: String,
) {
  install(PrometheusProxyAgent) {
    // The shop's metrics, Ktor's and the JVM's among them, at the proxy's /coffee_shop_metrics
    this.registry = registry.prometheusRegistry
    path = "coffee_shop_metrics"
    agent {
      // The version tells the old and new deployments apart on the proxy's dashboard and in its logs
      name = "coffee-shop-$appVersion"
      // host:port, the proxy's agent port
      proxy = env("PROMETHEUS_PROXY")
      token = env("PROMETHEUS_AGENT_TOKEN")
      // The certificate the proxy's is signed with, as PEM or its base64, and the name it was issued to
      trustCertificatePem(
        pem = env("PROMETHEUS_PROXY_CERT"),
        authority = env("PROMETHEUS_PROXY_AUTHORITY").ifEmpty { "prometheus-proxy" },
      )
      // The agent's own metrics, served to the proxy too, when METRICS_ENABLED in the app's environment turns them on,
      // on their default port, 8083, inside the container only; a METRICS_PORT would have to change this too. Without
      // them, the path would only fail. Its admin endpoints and /debug come from the environment as well, with nothing
      // here: ADMIN_ENABLED and DEBUG_ENABLED.
      if (env("METRICS_ENABLED").equals("true", ignoreCase = true))
        scrapeSelf(path = "coffee_shop_agent_metrics", port = 8083)
    }
  }
}
// --8<-- [end:agent-config]
