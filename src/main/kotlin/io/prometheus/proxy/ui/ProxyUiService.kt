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

package io.prometheus.proxy.ui

import com.google.common.util.concurrent.MoreExecutors
import com.pambrose.common.concurrent.GenericIdleService
import com.pambrose.common.concurrent.genericServiceListener
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Read-only operational web UI, served from its own Ktor server on its own port.
 *
 * Deliberately **not** the admin port: that one is a Jetty servlet container created inside
 * `common-utils`, whose only extension point is a `path -> Servlet` map — Ktor routing, the HTML DSL,
 * and WebSockets cannot attach to it. Deliberately **not** the scrape port either: that is
 * Prometheus-facing, and mixing an operator surface into it would put the UI on a listener that must
 * stay predictable.
 *
 * A separate port also means the UI can be firewalled independently of `/ping` and `/healthcheck`,
 * which Kubernetes probes target on the admin port.
 *
 * ### How updates reach the browser
 *
 * One shared push loop, not one per session. It wakes on either a [io.prometheus.proxy.ProxyEvent] or a
 * timer tick, collects a single [ProxySnapshot], and fans the same rendered fragments out to every
 * connected session. The wake channel is [Channel.CONFLATED], so a burst of events (a fleet
 * reconnecting) collapses into one collect rather than N.
 *
 * The timer exists because the event bus only covers discrete transitions. Backlog depth, map sizes and
 * eviction countdowns drift continuously with no moment to emit from, so they would otherwise sit
 * frozen on screen between topology changes.
 */
internal class ProxyUiService(
  private val proxy: Proxy,
  private val uiPort: Int,
  uiPath: String,
) : GenericIdleService() {
  private val basePath = "/" + uiPath.trim('/')

  // Selection per session: which agent's detail this browser tab is showing. Empty string means none
  // chosen -- NOT null, because ConcurrentHashMap rejects null values and would throw on insert.
  private val sessions = ConcurrentHashMap<SessionKey, String>()
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val refreshInterval = proxy.proxyConfigVals.ui.refreshIntervalSecs.seconds

  private class SessionKey(
    val send: suspend (String) -> Unit,
  )

  private val server =
    embeddedServer(
      factory = CIO,
      configure = {
        connector {
          host = "0.0.0.0"
          port = uiPort
        }
      },
    ) {
      install(WebSockets)
      routing {
        // Served from the classpath, so the fat JAR is self-contained and the UI works with no outbound
        // network access -- which is the normal condition for a proxy bridging a firewall.
        // Explicit allowlist rather than staticResources: the webjar layout embeds a version in the
        // path, and an allowlist means there is no path-traversal surface and no dependence on how Ktor
        // interprets a basePackage. Two entries is not worth a generic static handler.
        get("$basePath/assets/{file}") {
          val resource = ASSETS[call.parameters["file"]]
          val bytes = resource?.let { javaClass.classLoader.getResourceAsStream(it)?.readBytes() }
          if (bytes == null)
            call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
          else
            call.respondBytes(bytes, ContentType.Application.JavaScript, HttpStatusCode.OK)
        }

        get(basePath) {
          val snapshot = snapshot()
          call.respondHtml { with(ProxyUiHtml) { renderPage(snapshot, null, basePath) } }
        }

        get("$basePath/agents/{agentId}") {
          val agentId = call.parameters["agentId"]
          call.respondText(
            ProxyUiHtml.detailFragment(snapshot(), agentId),
            ContentType.Text.Html,
            HttpStatusCode.OK,
          )
        }

        webSocket("$basePath/events") {
          val key = SessionKey { text -> outgoing.send(Frame.Text(text)) }
          sessions[key] = NO_SELECTION
          try {
            // Render immediately rather than making the browser wait for the first event or tick.
            push(key, snapshot())
            for (frame in incoming) {
              if (frame is Frame.Text) {
                sessions[key] = parseSelection(frame.readText()) ?: NO_SELECTION
                push(key, snapshot())
              }
            }
          } finally {
            sessions.remove(key)
          }
        }
      }
    }

  val healthCheck =
    healthCheck {
      if (isRunning) com.codahale.metrics.health.HealthCheck.Result.healthy() else unhealthyResult()
    }

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    server.start()
    scope.launch { pushLoop() }
    scope.launch {
      // Any topology change wakes the loop. Conflated, so a burst collapses into one collect.
      proxy.eventBus.flow.collect { wake.trySend(Unit) }
    }
    logger.info { "Started $simpleClassName on port $uiPort at $basePath" }
  }

  override fun shutDown() {
    scope.cancel()
    server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
  }

  /**
   * Wakes on an event or, failing that, on the refresh interval; collects once; fans out to all sessions.
   *
   * The timeout is what keeps drifting values live. Without it, an idle proxy would freeze its
   * countdowns until the next agent connected.
   */
  private suspend fun pushLoop() {
    while (scope.isActive) {
      withTimeoutOrNull(refreshInterval) { wake.receive() }
      if (sessions.isEmpty()) continue
      val snapshot = snapshot()
        sessions.forEach { (key, selectedId) -> push(key, snapshot, selectedId.ifEmpty { null }) }
    }
  }

  private suspend fun push(
    key: SessionKey,
    snapshot: ProxySnapshot,
    selectedId: String? = sessions[key]?.ifEmpty { null },
  ) {
    runCatching { key.send(ProxyUiHtml.pushFragment(snapshot, selectedId, basePath)) }
      .onFailure { logger.debug { "Dropping UI session: ${it.simpleClassName}" } }
  }

  /**
   * Collects a snapshot off the CIO event loop.
   *
   * `ProxyPathManager` guards its map with `synchronized`, and Kotlin's `synchronized` parks the
   * carrier thread rather than suspending the coroutine — collecting inline would couple this UI to
   * scrape latency, and worse, couple scrape latency to this UI.
   */
  private suspend fun snapshot(): ProxySnapshot = withContext(Dispatchers.IO) { ProxySnapshot.collect(proxy) }

  private fun unhealthyResult() =
    com.codahale.metrics.health.HealthCheck.Result.unhealthy("$simpleClassName is not running")

  override fun toString() = "$simpleClassName{port=$uiPort, path=$basePath, sessions=${sessions.size}}"

  companion object {
    private val logger = logger {}
    private val json = Json { ignoreUnknownKeys = true }

    // Must match the WebJar coordinates in libs.versions.toml -- the classpath layout embeds the version.
    internal const val HTMX_VERSION = "2.0.10"
    internal const val HTMX_WS_VERSION = "2.0.4"

    /** The only classpath resources this server will serve, by request filename. */
    internal val ASSETS =
      mapOf(
        "htmx.min.js" to "META-INF/resources/webjars/htmx.org/$HTMX_VERSION/dist/htmx.min.js",
        "ws.js" to "META-INF/resources/webjars/htmx-ext-ws/$HTMX_WS_VERSION/dist/ws.js",
      )

    /** Sentinel for "this session has no agent selected"; ConcurrentHashMap forbids null values. */
    private const val NO_SELECTION = ""

    private const val GRACE_MILLIS = 2_000L
    private const val TIMEOUT_MILLIS = 5_000L

    /**
     * Reads `{"select": "<agentId>"}` from a session message.
     *
     * Returns null on anything unexpected: this is browser-supplied input on a port that has no auth,
     * so malformed content must degrade to "no selection" rather than propagate an exception into the
     * WebSocket loop and drop the session.
     */
    internal fun parseSelection(text: String): String? =
      runCatching {
        json.parseToJsonElement(text).jsonObject["select"]?.jsonPrimitive?.contentOrNull
      }.getOrNull()

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
      get() = if (isString) content.takeIf { it.isNotEmpty() } else null
  }
}
