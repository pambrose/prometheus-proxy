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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.containers

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestPorts.NGINX_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.TestPorts.PROXY_METRICS_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.logConsumer
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.ContainerTestSupport.transferable
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.time.Duration.Companion.seconds

/**
 * A single parameter-driven spec that scales the system along its real load axes and verifies every path is
 * scrapable end-to-end through the proxy. Each [ScalingScenario] stands up its own topology:
 *
 *  - **agents × endpoints/agent** — N agents, each registering M unique paths.
 *  - **payload size per endpoint** — `seriesPerEndpoint` filler series, exercising the chunking/gzip path.
 *  - **consolidated fan-out** — K agents sharing one `consolidated` path; one scrape merges across all K.
 *  - **scrape concurrency** — the per-path checks run concurrently or sequentially.
 *
 * After the per-path checks it asserts the proxy's own `proxy_agent_map_size` / `proxy_path_map_size` gauges
 * reflect the expected counts. A small default table runs in CI; setting any `SCALE_*` env var collapses the
 * run to a single tuned scenario so the inputs can be dialed up without recompiling.
 */
class ContainersScalingTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Scaling: N agents x M endpoints (set RUN_CONTAINER_TESTS=true to enable)".config(enabled = false) { }
    } else {
      withData(nameFn = { it.toString() }, scalingScenarios) { s ->
        val network = Network.newNetwork()
        val client = httpClient()
        val stubs = mutableListOf<GenericContainer<*>>()
        val agents = mutableListOf<GenericContainer<*>>()
        val agentMemMib = s.agentMemoryMib()
        val proxy =
          proxyContainer(
            network,
            env = mapOf("METRICS_ENABLED" to "true"),
            exposedPorts = listOf(PROXY_HTTP_PORT, PROXY_METRICS_PORT),
          ).withMemoryMib(PROXY_MEM_MIB)
        try {
          // --- unique-path agents (dimensions: agents × endpoints, payload size) ---
          for (a in 0 until s.agentCount) {
            val stubAlias = "scale-stub-a$a"
            val entries = (0 until s.endpointsPerAgent).map { e -> "scale_a${a}_e$e" to "ep_$e" }
            stubs +=
              nginxStub(network, stubAlias, waitPath = "/ep_${s.endpointsPerAgent - 1}") {
                entries.forEachIndexed { e, (_, file) ->
                  withCopyToContainer(
                    transferable(endpointText(a, e, s.seriesPerEndpoint)),
                    "/usr/share/nginx/html/$file",
                  )
                }
              }
            agents +=
              agentContainer(
                network,
                alias = "scale-agent-a$a",
                configText = agentConfig("scale-agent-a$a", stubAlias, entries),
                waitTimes = s.endpointsPerAgent,
              ).withMemoryMib(agentMemMib)
          }

          // --- consolidated fan-out agents (K agents share one path) ---
          for (k in 0 until s.consolidatedAgents) {
            val stubAlias = "scale-cons-stub-$k"
            stubs +=
              nginxStub(network, stubAlias, waitPath = "/c") {
                withCopyToContainer(transferable(consolidatedText(k)), "/usr/share/nginx/html/c")
              }
            agents +=
              agentContainer(
                network,
                alias = "scale-cons-agent-$k",
                configText =
                  agentConfig("scale-cons-agent-$k", stubAlias, listOf(CONSOLIDATED_PATH to "c"), consolidated = true),
                waitLogRegex = ".*Registered .* as /$CONSOLIDATED_PATH.*",
              ).withMemoryMib(agentMemMib)
          }

          stubs.forEach { it.start() }
          proxy.start()
          agents.forEach { it.start() }

          // --- per-path correctness (run concurrently or sequentially per the scenario) ---
          val checks = buildList<suspend () -> Unit> {
            for (a in 0 until s.agentCount) {
              for (e in 0 until s.endpointsPerAgent) {
                add {
                  eventually(60.seconds) {
                    client.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/scale_a${a}_e$e")
                      .shouldContain("$SCALE_SENTINEL{agent=\"$a\",endpoint=\"$e\"} ${a * 1000 + e}")
                  }
                }
              }
            }
            if (s.consolidatedAgents > 0)
              add {
                eventually(60.seconds) {
                  val body = client.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/$CONSOLIDATED_PATH")
                  for (k in 0 until s.consolidatedAgents) {
                    body shouldContain "scale_consolidated_metric{agent=\"$k\"} $k"
                  }
                }
              }
          }
          if (s.concurrentScrapes) {
            // A positive concurrencyLimit caps in-flight checks so a huge path count doesn't hold every
            // scrape body in memory at once; 0 means unbounded (launch them all).
            val gate = if (s.concurrencyLimit > 0) Semaphore(s.concurrencyLimit) else null
            coroutineScope {
              checks.forEach { check ->
                launch { if (gate != null) gate.withPermit { check() } else check() }
              }
            }
          } else {
            for (check in checks) check()
          }

          // --- observability: the proxy's own gauges must reflect the expected counts ---
          eventually(30.seconds) {
            val metrics = client.bodyOf(proxy.baseUrl(PROXY_METRICS_PORT) + "/metrics")
            metrics shouldContain "proxy_agent_map_size ${s.totalAgents}.0"
            metrics shouldContain "proxy_path_map_size ${s.totalDistinctPaths}.0"
          }
        } finally {
          client.close()
          stopQuietly(*agents.toTypedArray(), proxy, *stubs.toTypedArray())
          closeQuietly(network)
        }
      }
    }
  }
}

/** One scaling configuration; drives a single `withData` row and a single end-to-end topology. */
data class ScalingScenario(
  val agentCount: Int,
  val endpointsPerAgent: Int,
  val seriesPerEndpoint: Int,
  val consolidatedAgents: Int,
  val concurrentScrapes: Boolean,
  val concurrencyLimit: Int = 0,
) {
  val totalAgents get() = agentCount + consolidatedAgents
  val totalDistinctPaths get() = (agentCount * endpointsPerAgent) + (if (consolidatedAgents > 0) 1 else 0)

  override fun toString() =
    "$agentCount agents x $endpointsPerAgent endpoints, $seriesPerEndpoint series, " +
      "$consolidatedAgents consolidated, ${if (concurrentScrapes) "concurrent" else "sequential"}" +
      (if (concurrentScrapes && concurrencyLimit > 0) " (limit $concurrencyLimit)" else "")
}

private const val SCALE_SENTINEL = "scale_sentinel"
private const val CONSOLIDATED_PATH = "scale_consolidated"

// --- container memory caps -----------------------------------------------------------------------
// Testcontainers sets no cgroup memory limit, so an uncapped JVM container sizes its max heap to 25%
// of the *Docker VM's* RAM (≈2 GiB on a stock 8 GB Desktop VM). The connection-stress presets fan out
// to 40+ agent JVMs, so the uncapped heaps over-commit the VM and the kernel OOM-kills the proxy
// mid-scrape (the failure shows up as "server prematurely closed the connection" → "Connection
// refused"). Giving each JVM container an explicit memory limit makes the JVM auto-size its heap to
// the cgroup limit instead, so the whole fan-out fits the host.
//
// The cap must also be large enough that a connected, actively-scraped agent does not GC-thrash: too
// tight (≈ its working set) and the agent stalls, drops its heartbeat stream, and the proxy evicts its
// paths — a scrape then 404s. So the budget is a fraction of the *actual* Docker VM, divided across the
// agents and clamped to a workable range: a 40-agent fan-out on an 8 GB VM lands near the floor, the
// same fan-out on a 16 GB VM gets roomy headroom, and a few-agent / large-payload preset gets ample heap.
private const val BYTES_PER_MIB = 1024L * 1024L
private const val PROXY_MEM_MIB = 512L
private const val AGENT_MIN_MEM_MIB = 160L
private const val AGENT_MAX_MEM_MIB = 768L

/** Fraction of the Docker VM to hand to all proxy + agent JVMs; the rest covers the per-agent nginx
 *  stubs and Docker overhead. */
private const val VM_BUDGET_PERCENT = 70L

/** Fallback Docker VM size (MiB) when the daemon can't be queried — a stock 8 GB Docker Desktop VM. */
private const val DEFAULT_VM_MIB = 8L * 1024L

/** Docker VM RAM (MiB), read once; the per-agent cap scales to this so the fan-out fits whatever host runs it. */
private val dockerVmMib: Long by lazy {
  runCatching { DockerClientFactory.instance().client().infoCmd().exec().memTotal }
    .getOrNull()?.div(BYTES_PER_MIB) ?: DEFAULT_VM_MIB
}

/** Per-agent container memory cap: the non-proxy share of the VM budget split across every agent, clamped
 *  to a range frugal enough for a big fan-out yet large enough for the chunking/gzip payload presets. */
private fun ScalingScenario.agentMemoryMib(): Long =
  ((dockerVmMib * VM_BUDGET_PERCENT / 100 - PROXY_MEM_MIB) / totalAgents.coerceAtLeast(1))
    .coerceIn(AGENT_MIN_MEM_MIB, AGENT_MAX_MEM_MIB)

/** Pin a JVM container's memory so its heap auto-sizes to the cgroup limit instead of 25% of host RAM. */
private fun GenericContainer<*>.withMemoryMib(mib: Long): GenericContainer<*> =
  apply { withCreateContainerCmdModifier { cmd -> cmd.hostConfig?.withMemory(mib * BYTES_PER_MIB) } }

private fun intEnv(
  name: String,
  default: Int,
) = System.getenv(name)?.toIntOrNull() ?: default

private fun boolEnv(
  name: String,
  default: Boolean,
) = System.getenv(name)?.toBooleanStrictOrNull() ?: default

/**
 * A single tuned scenario when any `SCALE_*` override is present; otherwise a small CI-safe default table that
 * touches each dimension (baseline, agents × endpoints, payload size, consolidated fan-out).
 */
private val scaleEnvVars =
  listOf(
    "SCALE_AGENTS",
    "SCALE_ENDPOINTS_PER_AGENT",
    "SCALE_SERIES_PER_ENDPOINT",
    "SCALE_CONSOLIDATED_AGENTS",
    "SCALE_CONCURRENT",
    "SCALE_CONCURRENCY_LIMIT",
  )

private val scalingScenarios: List<ScalingScenario> =
  if (scaleEnvVars.any { System.getenv(it) != null })
    listOf(
      ScalingScenario(
        agentCount = intEnv("SCALE_AGENTS", 2),
        endpointsPerAgent = intEnv("SCALE_ENDPOINTS_PER_AGENT", 2),
        seriesPerEndpoint = intEnv("SCALE_SERIES_PER_ENDPOINT", 1),
        consolidatedAgents = intEnv("SCALE_CONSOLIDATED_AGENTS", 0),
        concurrentScrapes = boolEnv("SCALE_CONCURRENT", true),
        concurrencyLimit = intEnv("SCALE_CONCURRENCY_LIMIT", 0),
      ),
    )
  else
    listOf(
      ScalingScenario(1, 1, 1, 0, false),
      ScalingScenario(2, 3, 1, 0, true),
      ScalingScenario(2, 2, 2000, 0, false),
      ScalingScenario(2, 1, 1, 3, true),
    )

/** `seriesPerEndpoint` filler series plus a sentinel line whose value is unique per (agent, endpoint). */
private fun endpointText(
  agent: Int,
  endpoint: Int,
  series: Int,
): String =
  buildString {
    appendLine("# TYPE $SCALE_SENTINEL gauge")
    repeat(series) { appendLine("scale_filler{a=\"$agent\",e=\"$endpoint\",s=\"$it\"} $it") }
    appendLine("$SCALE_SENTINEL{agent=\"$agent\",endpoint=\"$endpoint\"} ${agent * 1000 + endpoint}")
  }

/** A single metric whose label/value identify which consolidated agent produced it, to prove the merge. */
private fun consolidatedText(idx: Int): String =
  "# TYPE scale_consolidated_metric gauge\nscale_consolidated_metric{agent=\"$idx\"} $idx\n"

/** Build a HOCON agent config with one pathConfig per (proxyPath → nginxFile) entry. */
private fun agentConfig(
  name: String,
  stubAlias: String,
  entries: List<Pair<String, String>>,
  consolidated: Boolean = false,
): String =
  buildString {
    appendLine("agent {")
    appendLine("  name = \"$name\"")
    if (consolidated) appendLine("  consolidated = true")
    appendLine("  proxy {")
    appendLine("    hostname = \"proxy-host\"")
    appendLine("    port = $PROXY_AGENT_PORT")
    appendLine("  }")
    appendLine("  pathConfigs: [")
    entries.forEach { (path, file) ->
      appendLine("    {")
      appendLine("      name: \"$path\"")
      appendLine("      path: $path")
      appendLine("      url: \"http://$stubAlias/$file\"")
      appendLine("    }")
    }
    appendLine("  ]")
    appendLine("  scrapeTimeoutSecs = 15")
    appendLine("  internal {")
    appendLine("    reconnectPauseSecs = 1")
    appendLine("  }")
    appendLine("}")
  }

/** An `nginx:alpine` stub whose document root is populated by [configure]; ready once [waitPath] serves 200. */
private fun nginxStub(
  network: Network,
  alias: String,
  waitPath: String,
  configure: GenericContainer<Nothing>.() -> Unit,
): GenericContainer<*> =
  GenericContainer<Nothing>("nginx:alpine").apply {
    withNetwork(network)
    withNetworkAliases(alias)
    withExposedPorts(NGINX_PORT)
    withLogConsumer(logConsumer(alias))
    configure()
    waitingFor(Wait.forHttp(waitPath).forPort(NGINX_PORT))
  }
