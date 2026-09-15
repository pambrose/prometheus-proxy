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

package io.prometheus.proxy.dashboard

import com.codahale.metrics.health.HealthCheck
import com.google.common.net.HostAndPort
import com.google.common.net.InetAddresses
import com.google.common.util.concurrent.MoreExecutors
import com.pambrose.common.concurrent.GenericIdleService
import com.pambrose.common.concurrent.genericServiceListener
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.prometheus.BuildConfig
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyEvent
import io.prometheus.proxy.ProxyHttpConfig.configureKtorServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.minusAssign
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Read-only operational dashboard, served from its own Ktor server on its own port.
 *
 * Deliberately **not** the admin port: that one is a Jetty servlet container created inside
 * `common-utils`, whose only extension point is a `path -> Servlet` map — Ktor routing, the HTML DSL,
 * and WebSockets cannot attach to it. Deliberately **not** the scrape port either: that is
 * Prometheus-facing, and mixing an operator surface into it would put the dashboard on a listener that must
 * stay predictable.
 *
 * A separate port also means the dashboard can be firewalled independently of `/ping` and `/healthcheck`,
 * which Kubernetes probes target on the admin port.
 *
 * ### How updates reach the browser
 *
 * One shared push loop, not one per session. It wakes on either a topology
 * [io.prometheus.proxy.ProxyEvent] or a timer tick, collects a single [ProxySnapshot], and fans the same
 * rendered fragments out to every connected session.
 *
 * **Scrape completions deliberately do not wake it.** They arrive at scrape rate — tens per second on a
 * busy proxy — and waking on each would run a snapshot collect that often, taking the same path-map
 * monitor every scrape request takes and defeating the decoupling this service is built around. Scrape
 * history is a drifting value, so the timer covers it, for the same reason backlog depths and eviction
 * countdowns are timer-driven.
 *
 * ### Exposure
 *
 * The port has no authentication, so what the service can bound, it does: a WebSocket handshake from a foreign
 * browser origin is refused, sessions are capped at `proxy.dashboard.maxSessions`, a session that stops reading is
 * closed rather than buffered for, and a browser message re-renders the recent snapshot rather than collecting one.
 * Setting `proxy.dashboard.allowedHosts` also refuses a request naming an unknown host, which stops DNS rebinding.
 * The listen address is `proxy.dashboard.host`; binding it to a private address is what keeps the page private.
 */
internal class ProxyDashboardService(
  private val proxy: Proxy,
  private val dashboardHost: String,
  private val dashboardPort: Int,
  dashboardPath: String,
) : GenericIdleService() {
  private val basePath = "/" + dashboardPath.trim('/')

  // The base with no trailing slash, so every sub-route below joins with a single separator. Empty at a
  // root mount, where basePath is "/" and plain interpolation would otherwise yield "//events" -- which a
  // browser reads as a protocol-relative URL and resolves to a host named "events". Normalized here, once,
  // rather than at each use site: the routes registered below and the links ProxyDashboardHtml renders are
  // the same URLs, so a rule applied in only one of the two layers is a rule that can disagree with itself.
  private val routeBase = basePath.trimEnd('/')

  /** One connected browser: how to reach it, which layout it is on, and which agent it is viewing. */
  private class Session(
    val send: suspend (String) -> Unit,
  ) {
    // Both written by this session's socket coroutine and read by the shared push loop, hence @Volatile.
    @Volatile
    var selectedId: String? = null

    @Volatile
    var layout: DashboardLayout = DashboardLayout.AGENT
  }

  private val sessions = ConcurrentHashMap.newKeySet<Session>()
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val refreshInterval = proxy.proxyConfigVals.dashboard.refreshIntervalSecs.seconds
  private val maxSessions = proxy.proxyConfigVals.dashboard.maxSessions
  private val allowedOrigins: List<String> = proxy.proxyConfigVals.dashboard.allowedOrigins

  // Normalized once. Null when proxy.dashboard.allowedHosts is empty, which turns the Host check off.
  private val hostAllowlist: Set<String>? =
    hostAllowlist(proxy.proxyConfigVals.dashboard.allowedHosts, allowedOrigins)

  // Claimed before a session joins [sessions] and released after it leaves, so the cap holds under concurrent
  // connects; the set's own size is only an estimate while another thread is adding to it.
  private val sessionCount = AtomicInt(0)

  /** A collected snapshot and when it was taken. */
  private class TimedSnapshot(
    val snapshot: ProxySnapshot,
    val taken: TimeMark,
  )

  // Written by every collect and cleared by topology events; read by sessions. See recentSnapshot.
  @Volatile
  private var latestSnapshot: TimedSnapshot? = null

  // Runs before the WebSocket upgrade, so a refused page never receives a frame. See isOriginAllowed.
  private val originCheck =
    createRouteScopedPlugin("DashboardOriginCheck") {
      onCall { call ->
        val origin = call.request.headers[HttpHeaders.Origin]
        if (!isOriginAllowed(origin, call.request.headers[HttpHeaders.Host], allowedOrigins)) {
          logger.info {
            "Refused a dashboard WebSocket from origin $origin; add it to proxy.dashboard.allowedOrigins if expected"
          }
          call.respondText("Origin not allowed", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        }
      }
    }

  // Application-wide rather than route-scoped, so it runs before routing and covers every page, asset, and the
  // WebSocket. See isHostAllowed.
  private val hostCheck =
    createApplicationPlugin("DashboardHostCheck") {
      onCall { call ->
        val host = call.request.headers[HttpHeaders.Host]
        if (!isHostAllowed(host, hostAllowlist)) {
          logger.info {
            "Refused a dashboard request for host $host; add it to proxy.dashboard.allowedHosts if expected"
          }
          call.respondText("Host not allowed", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        }
      }
    }

  private val server =
    embeddedServer(
      factory = CIO,
      configure = {
        connector {
          host = dashboardHost
          port = dashboardPort
        }
      },
    ) {
      // Shares the proxy's own Ktor configuration: compression (htmx.min.js is ~51 KB), StatusPages so
      // a failure returns a logged 500 rather than a bare one, and DefaultHeaders. Request logging is
      // off -- a dashboard polling its own socket would drown the proxy's logs.
      configureKtorServer(isLoggingEnabled = false)
      install(hostCheck)
      install(WebSockets) {
        // Ktor's defaults send no pings and buffer outgoing frames without limit, so a client that stops reading
        // would pile up a frame per push until the proxy ran out of memory. Pings find a dead peer, and a full
        // buffer closes that session instead of growing -- so the shared push loop never waits on a slow one.
        pingPeriodMillis = PING_PERIOD_MILLIS
        timeoutMillis = PING_TIMEOUT_MILLIS
        // A browser message is a few dozen bytes; the default accepts, and buffers whole, a frame of any size.
        maxFrameSize = MAX_INCOMING_FRAME_BYTES
        channels { outgoing = bounded(OUTGOING_FRAME_BUFFER, ChannelOverflow.CLOSE) }
      }
      install(CachingHeaders) {
        // Webjar paths are version-pinned, so their content is immutable by construction.
        options { _, outgoing ->
          if (outgoing.contentType?.match(ContentType.Application.JavaScript) == true)
            CachingOptions(CacheControl.MaxAge(ASSET_MAX_AGE_SECS, visibility = CacheControl.Visibility.Public))
          else
            null
        }
      }

      routing {
        // The dashboard lives under basePath (default /dashboard), so the bare root would otherwise 404. Send it to
        // the dashboard. Skipped when basePath is already "/" -- there the page route below owns the root, and a
        // second handler would be a duplicate. Temporary rather than permanent: basePath is configurable,
        // so a cached 301 would be wrong if it ever changed.
        if (routeBase.isNotEmpty()) {
          get("/") { call.respondRedirect(basePath, permanent = false) }
        }

        // Explicit allowlist rather than staticResources: the webjar layout embeds a version in the
        // path, and an allowlist means there is no path-traversal surface and no dependence on how Ktor
        // interprets a basePackage. Two entries is not worth a generic static handler.
        get("$routeBase/assets/{file}") {
          val bytes = assetBytes[call.parameters["file"]]
          if (bytes == null)
            call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
          else
            call.respondBytes(bytes, ContentType.Application.JavaScript, HttpStatusCode.OK)
        }

        get(basePath) {
          val snapshot = snapshot()
          call.respondHtml { with(ProxyDashboardHtml) { renderPage(snapshot, null, routeBase, DashboardLayout.AGENT) } }
        }

        // A real page rather than a fragment: switching layout is a navigation, so the browser gets a
        // fresh document whose region ids match the layout it is now showing.
        get("$routeBase/paths") {
          val snapshot = snapshot()
          call.respondHtml { with(ProxyDashboardHtml) { renderPage(snapshot, null, routeBase, DashboardLayout.PATH) } }
        }

        get("$routeBase/agents/{agentId}") {
          val agentId = call.parameters["agentId"]
          val snapshot = snapshot()
          // hx-push-url makes this URL the address bar's, so it must survive a reload or a shared link.
          // A row click is an htmx swap and wants just the detail fragment; a full navigation to the same
          // URL wants the whole dashboard with the agent selected. htmx marks its own requests with
          // HX-Request -- except a history-restore, which fetches the full URL expecting a full page.
          val htmxSwap =
            call.request.headers["HX-Request"] == "true" &&
              call.request.headers["HX-History-Restore-Request"] != "true"
          if (htmxSwap)
            call.respondText(
              ProxyDashboardHtml.detailFragment(snapshot, agentId),
              ContentType.Text.Html,
              HttpStatusCode.OK,
            )
          else
            call.respondHtml {
              with(ProxyDashboardHtml) { renderPage(snapshot, agentId, routeBase, DashboardLayout.AGENT) }
            }
        }

        route("$routeBase/events") {
          install(originCheck)
          webSocket {
            if (!reserveSession()) {
              close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many dashboard sessions"))
              return@webSocket
            }
            val session = Session { text -> outgoing.send(Frame.Text(text)) }
            sessions.add(session)
            try {
              // Render immediately rather than making the browser wait for the first event or tick.
              push(session, recentSnapshot())
              for (frame in incoming) {
                if (frame is Frame.Text) {
                  val text = frame.readText()
                  session.selectedId = parseSelection(text)
                  session.layout = parseLayout(text)
                  push(session, recentSnapshot())
                }
              }
            } finally {
              sessions.remove(session)
              sessionCount -= 1
            }
          }
        }
      }
    }

  val healthCheck =
    healthCheck {
      if (isRunning)
        HealthCheck.Result.healthy()
      else
        HealthCheck.Result.unhealthy("$simpleClassName is not running")
    }

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    server.start()
    scope.launch { pushLoop() }
    scope.launch {
      // Topology changes wake the loop; scrape completions do not -- see the class KDoc. Conflated, so
      // a burst (a fleet reconnecting at once) collapses into a single collect.
      proxy.eventBus.flow
        .filter { it !is ProxyEvent.ScrapeCompleted }
        .collect {
          // Clear the cache so a session never renders a topology older than the change it is reacting to.
          latestSnapshot = null
          wake.trySend(Unit)
        }
    }
    logger.info { "Started $simpleClassName on $dashboardHost:$dashboardPort at $basePath" }
  }

  override fun shutDown() {
    scope.cancel()
    server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
  }

  /**
   * Wakes on a topology change or, failing that, on the refresh interval; collects once; fans out.
   *
   * The timeout is what keeps drifting values live. Without it, an idle proxy would freeze its
   * countdowns until the next agent connected.
   */
  private suspend fun pushLoop() {
    while (scope.isActive) {
      withTimeoutOrNull(refreshInterval) { wake.receive() }
      if (sessions.isEmpty()) continue
      val snapshot = freshSnapshot()
      sessions.forEach { push(it, snapshot) }
    }
  }

  private suspend fun push(
    session: Session,
    snapshot: ProxySnapshot,
  ) {
    runCatching {
      session.send(ProxyDashboardHtml.pushFragment(snapshot, session.selectedId, routeBase, session.layout))
    }
      .onFailure { logger.debug { "Dropping dashboard session: ${it.simpleClassName}" } }
  }

  /**
   * Collects a snapshot off the CIO event loop.
   *
   * `ProxyPathManager` guards its map with `synchronized`, and Kotlin's `synchronized` parks the
   * carrier thread rather than suspending the coroutine — collecting inline would couple this dashboard to
   * scrape latency, and worse, couple scrape latency to this dashboard.
   */
  private suspend fun snapshot(): ProxySnapshot = withContext(Dispatchers.IO) { ProxySnapshot.collect(proxy) }

  /** Collects a snapshot and records it as the most recent one. */
  private suspend fun freshSnapshot(): ProxySnapshot =
    snapshot().also { latestSnapshot = TimedSnapshot(it, TimeSource.Monotonic.markNow()) }

  /**
   * The most recent snapshot if it is younger than the refresh interval, otherwise a fresh collect.
   *
   * What a WebSocket session renders from. A client decides how fast its messages arrive, so collecting per
   * message would reintroduce the scrape-latency coupling [snapshot] is built to avoid. The price is data up to
   * one refresh interval old, the staleness the push loop already accepts; a topology change clears the cache,
   * so a session never waits a full interval to see a new agent.
   */
  private suspend fun recentSnapshot(): ProxySnapshot =
    latestSnapshot?.takeIf { it.taken.elapsedNow() < refreshInterval }?.snapshot ?: freshSnapshot()

  /** Claims a session slot, or returns false when [maxSessions] sessions are already connected. */
  private fun reserveSession(): Boolean {
    while (true) {
      val current = sessionCount.load()
      if (current >= maxSessions) return false
      if (sessionCount.compareAndSet(current, current + 1)) return true
    }
  }

  /**
   * The static assets, read from the classpath once.
   *
   * Read once rather than per request: the port has no authentication, so a request loop would
   * otherwise make the proxy inflate ~51 KB out of the JAR without bound.
   */
  private val assetBytes: Map<String, ByteArray> by lazy {
    ASSETS.mapNotNull { (name, resource) ->
      javaClass.classLoader.getResourceAsStream(resource)?.use { name to it.readBytes() }
    }.toMap()
  }

  override fun toString() =
    toStringElements {
      add("host", dashboardHost)
      add("port", dashboardPort)
      add("path", basePath)
      add("sessions", sessions.size)
    }

  companion object {
    private val logger = logger {}
    private val json = Json { ignoreUnknownKeys = true }

    // Generated from libs.versions.toml, since the WebJar classpath layout embeds the version. Reading
    // them rather than restating them means a dependency bump cannot leave the asset route pointing at
    // a path that is no longer on the classpath.
    internal const val HTMX_VERSION = BuildConfig.HTMX_VERSION
    internal const val HTMX_WS_VERSION = BuildConfig.HTMX_WS_VERSION

    /** The only classpath resources this server will serve, by request filename. */
    internal val ASSETS =
      mapOf(
        "htmx.min.js" to "META-INF/resources/webjars/htmx.org/$HTMX_VERSION/dist/htmx.min.js",
        "ws.js" to "META-INF/resources/webjars/htmx-ext-ws/$HTMX_WS_VERSION/dist/ws.js",
      )

    private const val ASSET_MAX_AGE_SECS = 31_536_000
    private const val GRACE_MILLIS = 2_000L
    private const val TIMEOUT_MILLIS = 5_000L
    private const val PING_PERIOD_MILLIS = 15_000L
    private const val PING_TIMEOUT_MILLIS = 15_000L
    private const val MAX_INCOMING_FRAME_BYTES = 64L * 1024

    // Frames a session may have waiting before it is closed. A reading browser drains each push long before the next.
    private const val OUTGOING_FRAME_BUFFER = 32

    /**
     * Reads `{"select": "<agentId>"}` from a session message.
     *
     * Returns null on anything unexpected: this is browser-supplied input on a port that has no auth,
     * so malformed content must degrade to "no selection" rather than propagate an exception into the
     * WebSocket loop and drop the session.
     */
    internal fun parseSelection(text: String): String? =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["select"]
          ?.jsonPrimitive
          ?.takeIf { it.isString }
          ?.content
          ?.takeIf { it.isNotEmpty() }
      }.getOrNull()

    /**
     * Reads `{"layout": "PATH"}` from a session message, defaulting to [DashboardLayout.AGENT].
     *
     * Same contract as [parseSelection]: anything unexpected -- garbage, a missing field, an unknown
     * layout name, or a browser predating the field -- resolves to the default rather than throwing
     * inside the WebSocket loop. Falling back to the agent layout is the safe direction, since that is
     * what the page served before layouts existed.
     */
    internal fun parseLayout(text: String): DashboardLayout =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["layout"]
          ?.jsonPrimitive
          ?.takeIf { it.isString }
          ?.content
          ?.let { name -> DashboardLayout.entries.firstOrNull { it.name == name } }
      }.getOrNull() ?: DashboardLayout.AGENT

    /**
     * Whether a WebSocket handshake carrying [origin] may proceed, given the request's [host] header.
     *
     * No Origin is allowed: browsers send one on every WebSocket handshake, so its absence means a non-browser
     * client, and this check exists to stop a browser being turned against the dashboard. Otherwise the origin
     * must name the host the request was addressed to -- the dashboard's own page -- or match an [allowedOrigins]
     * entry, for a dashboard behind a reverse proxy that rewrites Host. Anything unparseable, including the opaque
     * `null` origin, is refused.
     *
     * This does not stop DNS rebinding, where the attacker's origin and the Host header agree; see [isHostAllowed].
     */
    internal fun isOriginAllowed(
      origin: String?,
      host: String?,
      allowedOrigins: Collection<String>,
    ): Boolean {
      if (origin == null) return true
      val authority = runCatching { URI(origin).rawAuthority }.getOrNull()
      return allowedOrigins.any { normalizeOrigin(it) == normalizeOrigin(origin) } ||
        (authority != null && host != null && authority.equals(host, ignoreCase = true))
    }

    private fun normalizeOrigin(origin: String) = origin.trim().trimEnd('/').lowercase()

    private const val LOCALHOST = "localhost"

    /**
     * Whether a request whose Host header is [host] may proceed, given the [allowlist] built by [hostAllowlist].
     *
     * Opt-in protection against DNS rebinding, where an attacker points their own name at the dashboard's address: a
     * rebound browser sends that name as both Origin and Host, so [isOriginAllowed] lets it through. A null
     * [allowlist] -- `proxy.dashboard.allowedHosts` empty, the default -- allows every Host. Otherwise the dashboard
     * answers only to an IP address or a name in [allowlist], ignoring the port, case, and a trailing dot. A request
     * with no Host is allowed, since browsers always send one; an empty or unparseable Host is refused.
     * `X-Forwarded-Host` is never consulted: a rebound page is same-origin and can set it.
     */
    internal fun isHostAllowed(
      host: String?,
      allowlist: Set<String>?,
    ): Boolean {
      if (allowlist == null || host == null) return true
      val name = hostName(host) ?: return false
      return InetAddresses.isInetAddress(name) || name in allowlist
    }

    /**
     * The names the dashboard answers to when [allowedHosts] is set: those names, `localhost`, and the host of each
     * [allowedOrigins] entry (a reverse proxy's public name), normalized as [isHostAllowed] normalizes a Host header.
     * Null when [allowedHosts] is empty, which turns the Host check off. An entry that does not parse is ignored.
     */
    internal fun hostAllowlist(
      allowedHosts: Collection<String>,
      allowedOrigins: Collection<String>,
    ): Set<String>? =
      if (allowedHosts.isEmpty())
        null
      else
        buildSet {
          add(LOCALHOST)
          allowedHosts.mapNotNullTo(this) { hostName(it) }
          allowedOrigins.mapNotNullTo(this) { origin ->
            runCatching { URI(origin.trim()).host }.getOrNull()?.let(::hostName)
          }
        }

    // The host part of a Host header, an allowedHosts entry, or an origin's host: port and IPv6 brackets removed,
    // lowercased, and without a trailing dot. Null when empty or unparseable.
    private fun hostName(value: String): String? =
      runCatching { HostAndPort.fromString(value.trim()).host }
        .getOrNull()
        ?.trimEnd('.')
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
  }
}
