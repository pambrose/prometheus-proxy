package bakery

import io.prometheus.agent.embeddedAgent
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.net.InetAddress

// The demo's oven: a worker with no HTTP server, which bakes the orders it takes from a Redis queue (Oven.kt), with
// the prometheus-proxy agent embedded in it. See demos/worker/README.md in the repository.

// The website's Worker Demo page and the demo's README show main, from here to the end marker.
// --8<-- [start:main]
fun main() {
  // The host name, the container's ID in Docker, so each oven's paths are its own
  val replica = replicaName()
  val oven = Oven(queue = OrderQueue(url = env("REDIS_URL")), registry = PrometheusRegistry.defaultRegistry)

  // On SIGTERM, the JVM runs its shutdown hooks, then exits without returning from main. This one has the oven finish
  // the order it's baking and take no more, then waits for main to return, by which time use { } has stopped the agent.
  val mainThread = Thread.currentThread()
  Runtime.getRuntime().addShutdownHook(
    Thread {
      oven.stop()
      mainThread.join()
    },
  )

  embeddedAgent {
    name = "oven-$replica"
    fromEnvironment()
    source(path = "oven_${replica}_metrics", registry = PrometheusRegistry.defaultRegistry)
    agentMetricsAt(path = "oven_${replica}_agent_metrics")
  }.startIfConfigured(logBanner = false).use {
    oven.runUntilStopped()
  }
}
// --8<-- [end:main]

// The oven's name among the others: HOSTNAME, which Docker sets to the container's ID, or else this machine's host
// name, as on a developer's machine, whose shell doesn't usually export HOSTNAME. Any character but a letter, a digit,
// '.', '_', or '-' becomes '-', so it can name a proxy path, which can't hold whitespace, '?', or '#'.
internal fun replicaName(): String =
  env("HOSTNAME")
    .ifEmpty { runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("local") }
    .replace(NOT_IN_PATH, "-")

private val NOT_IN_PATH = Regex("[^A-Za-z0-9._-]")

// An environment variable's value, trimmed, or empty when it isn't set.
internal fun env(name: String) = System.getenv(name).orEmpty().trim()
