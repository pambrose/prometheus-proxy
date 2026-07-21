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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "UndocumentedPublicProperty")

package io.prometheus.containers.support

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.prometheus.common.TestPorts.NGINX_PORT
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import org.slf4j.LoggerFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * Shared factories and helpers for the Testcontainers end-to-end suite.
 *
 * Every spec in `io.prometheus.containers` is gated on [containerTestsEnabled]; the Makefile and the
 * `container-tests` GitHub workflow set `RUN_CONTAINER_TESTS=true` and build the fat JARs first. The
 * proxy and agent images are built from the `etc/docker` Dockerfiles via [proxyImage] / [agentImage]; Testcontainers
 * content-hashes the Dockerfile + JAR inputs, so building the same image across multiple specs reuses the
 * cached build.
 */
@Suppress("TooManyFunctions")
object ContainerTestSupport {
  private const val PROXY_ALIAS = "proxy-host"
  private const val AGENT_ALIAS = "agent"
  private const val PROMETHEUS_ALIAS = "prometheus"
  const val METRICS_STUB_ALIAS = "metrics-stub"

  /** The default nginx document root path the metrics stub serves its exposition file from. */
  const val NGINX_METRICS_DEST = "/usr/share/nginx/html/metrics"

  /** True only when `RUN_CONTAINER_TESTS=true`; otherwise every spec registers a single disabled placeholder. */
  fun containerTestsEnabled(): Boolean = System.getenv("RUN_CONTAINER_TESTS") == "true"

  // ONE ImageFromDockerfile per JVM, not one per container.
  //
  // Testcontainers gives every ImageFromDockerfile instance its own random
  // `localhost/testcontainers/<hash>:latest` tag. These were previously functions used as default
  // parameter values, so each container built its own instance -- and ContainersScalingTest constructs
  // one agent container per agent, so a single run minted a tag per agent plus one per proxy. The
  // layers are content-hashed and shared (thousands of leaked tags resolve to only a few hundred image
  // IDs), but the tags themselves accumulate: they are reaped only by a JVM shutdown hook, which never
  // fires when a run is killed or times out. Left alone this grows into tens of GB of reclaimable
  // images, and a bloated image store is a plausible trigger for containerd content GC racing a build
  // ("NotFound: content digest ...: not found").
  //
  // Sharing one instance is the documented way to build an image once and reuse it: the value is a
  // Future<String> that resolves on first use, so N containers share one build and one tag.
  val proxyImage: ImageFromDockerfile by lazy {
    ImageFromDockerfile()
      .withFileFromPath("Dockerfile", Path.of("etc/docker/proxy.df"))
      .withFileFromPath("build/libs/prometheus-proxy.jar", Path.of("build/libs/prometheus-proxy.jar"))
  }

  val agentImage: ImageFromDockerfile by lazy {
    ImageFromDockerfile()
      .withFileFromPath("Dockerfile", Path.of("etc/docker/agent.df"))
      .withFileFromPath("build/libs/prometheus-agent.jar", Path.of("build/libs/prometheus-agent.jar"))
  }

  fun logConsumer(name: String): Slf4jLogConsumer =
    Slf4jLogConsumer(LoggerFactory.getLogger("container.$name")).withPrefix(name)

  /**
   * An `nginx:alpine` container serving one or more classpath exposition files.
   *
   * @param files map of classpath resource → container path (defaults to serving `metrics.txt` at `/metrics`).
   */
  fun metricsStub(
    network: Network,
    alias: String = METRICS_STUB_ALIAS,
    files: Map<String, String> = mapOf("containers/metrics.txt" to NGINX_METRICS_DEST),
    waitPath: String = "/metrics",
  ): GenericContainer<*> =
    GenericContainer<Nothing>("nginx:alpine").apply {
      withNetwork(network)
      withNetworkAliases(alias)
      files.forEach { (resource, dest) -> withClasspathResourceMapping(resource, dest, BindMode.READ_ONLY) }
      withExposedPorts(NGINX_PORT)
      withLogConsumer(logConsumer(alias))
      waitingFor(Wait.forHttp(waitPath).forPort(NGINX_PORT))
    }

  /**
   * @param alias network alias this proxy answers to. Two proxies in one spec MUST take distinct
   *   aliases: sharing one makes Docker's embedded DNS round-robin between them non-deterministically,
   *   which silently turns an endpoint-selection test into a coin flip.
   */
  fun proxyContainer(
    network: Network,
    image: ImageFromDockerfile = proxyImage,
    alias: String = PROXY_ALIAS,
    env: Map<String, String> = emptyMap(),
    exposedPorts: List<Int> = [PROXY_HTTP_PORT],
    hostFiles: Map<String, String> = emptyMap(),
    wait: WaitStrategy = Wait.forListeningPort(),
  ): GenericContainer<*> =
    GenericContainer<Nothing>(image).apply {
      withNetwork(network)
      withNetworkAliases(alias)
      withExposedPorts(*exposedPorts.toTypedArray())
      hostFiles.forEach { (hostPath, dest) -> withCopyFileToContainer(MountableFile.forHostPath(hostPath), dest) }
      env.forEach { (key, value) -> withEnv(key, value) }
      withLogConsumer(logConsumer(alias))
      waitingFor(wait)
    }

  /**
   * An agent container driven by a mounted HOCON config (path topology is config-file-only) plus env-var knobs.
   *
   * @param waitLogRegex log line the agent prints once it has registered a path (`Registered <url> as /<path> …`).
   */
  fun agentContainer(
    network: Network,
    image: ImageFromDockerfile = agentImage,
    alias: String = AGENT_ALIAS,
    configResource: String = "containers/agent.conf",
    configText: String? = null,
    env: Map<String, String> = emptyMap(),
    exposedPorts: List<Int> = emptyList(),
    classpathFiles: Map<String, String> = emptyMap(),
    hostFiles: Map<String, String> = emptyMap(),
    waitLogRegex: String = ".*Registered .* as /.*",
    waitTimes: Int = 1,
    wait: WaitStrategy? = null,
  ): GenericContainer<*> =
    GenericContainer<Nothing>(image).apply {
      withNetwork(network)
      withNetworkAliases(alias)
      withEnv("AGENT_CONFIG", "/config/agent.conf")
      // A runtime-generated config (configText) is injected directly; otherwise mount the classpath resource.
      // The trailing Unit keeps the SELF-returning builder call out of value position (avoids a Nothing cast).
      @Suppress("UNUSED_EXPRESSION")
      if (configText != null) {
        withCopyToContainer(transferable(configText), "/config/agent.conf")
        Unit
      } else {
        withClasspathResourceMapping(configResource, "/config/agent.conf", BindMode.READ_ONLY)
        Unit
      }
      classpathFiles.forEach { (resource, dest) -> withClasspathResourceMapping(resource, dest, BindMode.READ_ONLY) }
      hostFiles.forEach { (hostPath, dest) -> withCopyFileToContainer(MountableFile.forHostPath(hostPath), dest) }
      env.forEach { (key, value) -> withEnv(key, value) }
      if (exposedPorts.isNotEmpty()) withExposedPorts(*exposedPorts.toIntArray().toTypedArray())
      withLogConsumer(logConsumer(alias))
      waitingFor(wait ?: Wait.forLogMessage(waitLogRegex, waitTimes))
    }

  fun prometheusContainer(
    network: Network,
    ymlResource: String = "containers/prometheus.yml",
  ): GenericContainer<*> =
    GenericContainer<Nothing>("prom/prometheus:latest").apply {
      withNetwork(network)
      withNetworkAliases(PROMETHEUS_ALIAS)
      withClasspathResourceMapping(ymlResource, "/etc/prometheus/prometheus.yml", BindMode.READ_ONLY)
      withExposedPorts(PROMETHEUS_PORT)
      withLogConsumer(logConsumer("prometheus"))
      waitingFor(Wait.forHttp("/-/ready").forPort(PROMETHEUS_PORT))
    }

  /** A Ktor client that returns non-2xx responses instead of throwing (so 404/503 can be asserted). */
  fun httpClient(): HttpClient = HttpClient(CIO) { expectSuccess = false }

  /**
   * Generate a synthetic Prometheus exposition payload of roughly [seriesCount] series, ending in a
   * recognizable [SENTINEL_METRIC] line. Used to force the agent's chunking + gzip path end-to-end.
   *
   * @return the exposition text and the number of metric (non-comment) lines it contains.
   */
  fun largeMetricsText(seriesCount: Int = LARGE_PAYLOAD_SERIES): Pair<String, Int> {
    val text =
      buildString {
        appendLine("# HELP synthetic_metric Synthetic gauge used to exercise chunked, gzipped scrape responses.")
        appendLine("# TYPE synthetic_metric gauge")
        repeat(seriesCount) { i -> appendLine("synthetic_metric{series=\"$i\"} $i") }
        appendLine("# HELP $SENTINEL_METRIC Final series; proves the whole payload survived chunking.")
        appendLine("# TYPE $SENTINEL_METRIC gauge")
        appendLine("$SENTINEL_METRIC $SENTINEL_VALUE")
      }
    return text to (seriesCount + 1)
  }

  fun transferable(text: String): Transferable = Transferable.of(text)

  const val SENTINEL_METRIC = "sentinel_metric"
  const val SENTINEL_VALUE = 999999
  const val LARGE_PAYLOAD_SERIES = 6000
}

/** Host base URL (`http://<host>:<mappedPort>`) for a container's exposed [port]. */
fun GenericContainer<*>.baseUrl(port: Int): String = "http://$host:${getMappedPort(port)}"

/** HTTP status code for a GET against [url]. */
suspend fun HttpClient.statusOf(url: String): Int = get(url).status.value

/** Response body text for a GET against [url]. */
suspend fun HttpClient.bodyOf(url: String): String = get(url).bodyAsText()

/** Best-effort stop of every supplied container, swallowing teardown failures. */
fun stopQuietly(vararg containers: GenericContainer<*>) {
  containers.forEach { container ->
    try {
      container.stop()
    } catch (_: Exception) {
      // best-effort teardown
    }
  }
}

/** Best-effort close of a Testcontainers [Network], swallowing teardown failures. */
fun closeQuietly(network: Network) {
  try {
    network.close()
  } catch (_: Exception) {
    // best-effort teardown
  }
}
