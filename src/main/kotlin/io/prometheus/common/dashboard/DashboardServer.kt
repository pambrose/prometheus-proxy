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

package io.prometheus.common.dashboard

import com.google.common.net.HostAndPort
import com.google.common.net.InetAddresses
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.CachingOptions
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
import io.prometheus.common.KeyedThrottle
import io.prometheus.common.dashboard.LoginHtml.renderLogin
import io.prometheus.proxy.ProxyHttpConfig.configureKtorServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.minusAssign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What one dashboard is configured with.
 *
 * @param role the process the dashboard belongs to, `proxy` or `agent`: it names the session cookie and the config keys
 *   a log line points to (`<role>.dashboard.allowedOrigins`)
 * @param path the base path, normalized here once (see [DashboardServer.routeBase])
 * @param login the login every request must carry once a password is set; null leaves the dashboard open
 * @param loginHeading the login page's heading, the dashboard's name
 * @param icons the brand marks served under [DashboardServer.ICON_DIR], by file name, with their classpath resources
 */
internal class DashboardSettings(
  val role: String,
  val host: String,
  val port: Int,
  val path: String,
  val refreshInterval: Duration,
  val maxSessions: Int,
  val allowedOrigins: List<String>,
  val allowedHosts: List<String>,
  val login: DashboardLogin?,
  val loginHeading: String,
  val icons: Map<String, String> = emptyMap(),
)

/**
 * A pushed frame's HTML, and what the site keeps of it for the next push to compare with (see [DashboardSite.frame]).
 */
internal class DashboardFrame(
  val text: String,
  val sent: Any? = null,
)

/**
 * The part of a dashboard that is its own, which [DashboardServer] serves: what it collects, what it shows, and what it
 * does. [V] is what a page's browser says it shows, and [S] the snapshot every page renders from.
 */
internal interface DashboardSite<V : Any, S : Any> {
  /**
   * The changes that retire the cached snapshot and push to every page at once, rather than at the next tick. Read
   * once, as the server starts.
   */
  val changes: Flow<Any?>

  /** Collects a snapshot. The server calls it off the CIO event loop, and caches what it returns ([SnapshotCache]). */
  suspend fun collect(): S

  /** The site's pages and actions, under [DashboardServer.routeBase]. Each action installs its `pageGuard`. */
  fun Route.pages(server: DashboardServer<V, S>)

  /** What a browser's message on the page's WebSocket says the page shows. Never throws: the message is untrusted. */
  fun parseView(message: String): V

  /**
   * The frame to push to a page showing [view], or showing nothing yet when it is null, from [snapshot]. [sent] is what
   * the last frame sent to the page kept, or null when none was.
   */
  fun frame(
    snapshot: S,
    view: V?,
    sent: Any?,
    routeBase: String,
  ): DashboardFrame
}

/**
 * One dashboard's web server, on its own port: a Ktor CIO server serving a [DashboardSite]'s pages, with what every
 * dashboard shares.
 *
 * - **Exposure.** With a password set, every request needs a session from the login page ([LoginHtml]), or, from a
 *   script, the login as HTTP Basic Auth (see [DashboardLogin] and [DashboardSessions]). Without either, before
 *   routing, a page request is sent to the login page and anything else gets a 401. Without a password, the port has no
 *   authentication. Either way, what the server can bound, it does: a foreign browser origin is refused on the
 *   WebSocket and every action ([pageGuard]), sessions are capped, a session that stops reading is closed rather than
 *   buffered for, and a page request or a browser message renders the recent snapshot rather than collecting one (see
 *   [SnapshotCache]). Setting `allowedHosts` also refuses a request naming an unknown host, which stops DNS rebinding.
 * - **Assets.** htmx and its WebSocket extension, and the site's brand marks, from the classpath, under URLs that carry
 *   their WebJar's version, so a year-long cache can never run an old script against new markup.
 * - **Updates.** One shared push loop, not one per session. It wakes on one of the site's [DashboardSite.changes] or a
 *   timer tick, collects a single snapshot, and fans a frame out to every connected session. The tick is what keeps
 *   drifting values live: without it, an idle process would freeze its countdowns until the next change.
 */
internal class DashboardServer<V : Any, S : Any>(
  val settings: DashboardSettings,
  private val site: DashboardSite<V, S>,
) {
  /** The base path, with a leading slash; `/` at a root mount. */
  val basePath = "/" + settings.path.trim('/')

  /**
   * The base with no trailing slash, so every sub-route joins with a single separator. Empty at a root mount, where
   * plain interpolation of [basePath] would yield "//events" -- which a browser reads as a protocol-relative URL and
   * resolves to a host named "events". Normalized here, once: the routes registered and the links the site renders are
   * the same URLs, so a rule applied in only one of the two layers is a rule that can disagree with itself.
   */
  val routeBase = basePath.trimEnd('/')

  /** Whether a login is required. */
  val loginEnabled = settings.login != null

  /** One connected browser: how to reach it, and what its page shows. */
  private class Session<V : Any>(
    // The login session the page's socket was opened with, which the push loop ends it with; null without a login, or
    // for a script's socket, which logs in with Basic Auth on every request rather than keeping a session.
    val login: String?,
    val send: suspend (String) -> Unit,
    // Closes the socket.
    val end: suspend () -> Unit,
  ) {
    // Written by this session's socket coroutine and read by the shared push loop, hence @Volatile. Null until the
    // browser's first message says which page it shows. The browser sends that message as soon as the socket opens.
    @Volatile
    var view: V? = null

    // Whether a push for this session has failed to render, which is logged once rather than every refresh.
    @Volatile
    var renderFailed = false

    // Held for the whole of a push to this session, from reading its view to recording what was sent. The push loop
    // and the session's own socket coroutine both push, and without it the two could record in one order and send in
    // the other, leaving [sent] naming a frame the page never got.
    val pushing = Mutex()

    // What the last frame sent to this page kept for the next to compare with (DashboardFrame.sent). Guarded by
    // pushing.
    var sent: Any? = null
  }

  private val sessions = ConcurrentHashMap.newKeySet<Session<V>>()
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Normalized once. Null when allowedHosts is empty, which turns the Host check off.
  private val hostAllowlist: Set<String>? = hostAllowlist(settings.allowedHosts, settings.allowedOrigins)

  // Claimed before a session joins [sessions] and released after it leaves, so the cap holds under concurrent
  // connects; the set's own size is only an estimate while another thread is adding to it.
  private val sessionCount = AtomicInt(0)

  /**
   * What every page and session renders from; the push loop refills it on each wake.
   *
   * Collected off the CIO event loop: the state a site reads may be guarded with `synchronized` (the proxy's path map
   * is), and Kotlin's `synchronized` parks the carrier thread rather than suspending the coroutine, which would couple
   * the dashboard to the process's own work, and the work to the dashboard.
   */
  val snapshots = SnapshotCache(settings.refreshInterval) { withContext(Dispatchers.IO) { site.collect() } }

  /**
   * The guard on every route the page's script calls, the WebSocket and the actions, so a page on another site can't
   * use one through a viewer's browser. It refuses a foreign Origin (see [isOriginAllowed]). An action, any request but
   * a GET (the WebSocket handshake is one), must also carry the header htmx and the page's script send, which another
   * site's page can't add without a CORS preflight the dashboard never answers; the origin check alone lets through a
   * request with no Origin. Runs before the WebSocket upgrade, so a refused page never receives a frame.
   */
  val pageGuard = guard("DashboardPageGuard", ::pageRefusal)

  // The login and logout forms' guard, which the browser posts itself and so sends no HX-Request header: the Origin
  // check alone.
  private val originGuard = guard("DashboardOriginGuard", ::originRefusal)

  // A route-scoped plugin answering a 403 with whatever [refusal] gives a request, which proceeds when it gives null.
  private fun guard(
    name: String,
    refusal: (ApplicationRequest) -> String?,
  ) = createRouteScopedPlugin(name) {
    onCall { call ->
      refusal(call.request)?.also { call.respondText(it, ContentType.Text.Plain, HttpStatusCode.Forbidden) }
    }
  }

  // Why pageGuard refuses [request], or null when it may proceed.
  private fun pageRefusal(request: ApplicationRequest): String? {
    val notThePage = request.httpMethod != HttpMethod.Get && request.headers[HX_REQUEST_HEADER] != "true"
    return originRefusal(request) ?: "Only the dashboard's page may ask for this".takeIf { notThePage }
  }

  // Why a request from [request]'s Origin is refused, or null when it may proceed (see isOriginAllowed).
  private fun originRefusal(request: ApplicationRequest): String? {
    val origin = request.headers[HttpHeaders.Origin]
    if (isOriginAllowed(origin, request.headers[HttpHeaders.Host], settings.allowedOrigins))
      return null
    logger.info {
      "Refused a dashboard request from origin $origin; add it to ${settings.role}.dashboard.allowedOrigins if expected"
    }
    return "Origin not allowed"
  }

  // The login page's sessions, and the cookie that carries one: named for the role and the port, since a browser sends
  // a host's cookies to every port on it, and an HA pair can run on one host.
  private val logins = DashboardSessions()
  private val sessionCookie = cookieName(settings.role, settings.port)
  private val loginPath = "$routeBase/login"
  private val logoutPath = "$routeBase/logout"

  // Which addresses' wrong logins may be logged at WARN now.
  private val badLoginWarnings = KeyedThrottle<String>(BAD_LOGIN_WARNING_INTERVAL, MAX_BAD_LOGIN_HOSTS)

  // The limit on wrong logins, which refuses every login from an address past it, or from any past the overall one,
  // before its password is looked at, from the form or as Basic Auth.
  private val limiter = LoginLimiter(MAX_BAD_LOGINS_PER_ADDRESS, MAX_BAD_LOGINS_OVERALL)

  // Application-wide, like hostCheck, and installed after it, so it runs before routing and covers every page, asset,
  // and action, the WebSocket handshake, and a path that would 404: a route added later can't miss it. Installed only
  // with a password set. Lets through a valid session, or a script's right Basic login; sends a page request to the
  // login page and answers anything else with a 401. Never with WWW-Authenticate, which would have a browser show its
  // own prompt. The login and logout routes need no session. Basic Auth is for scripts only (see isBrowser).
  private fun authCheck(login: DashboardLogin) =
    createApplicationPlugin("DashboardAuthCheck") { onCall { checkLogin(it, login) } }

  private suspend fun checkLogin(
    call: ApplicationCall,
    login: DashboardLogin,
  ) {
    if (needsNoLogin(call.request))
      return
    // A browser must use the login page: one that once saved a Basic login sends it with every request, which would
    // skip the page and outlast Log out. So a browser's Basic login is ignored, and only a script's is read.
    val header = if (isBrowser(call.request)) null else call.request.headers[HttpHeaders.Authorization]
    if (header == null || !basicLoginAnswered(call, header, login))
      refuseWithoutLogin(call)
  }

  // Whether [request] may proceed without a login: one for the login or logout route, or with a valid session.
  private fun needsNoLogin(request: ApplicationRequest): Boolean {
    val path = request.path()
    if (path == loginPath || path == logoutPath)
      return true
    return logins.isValid(request.cookies[sessionCookie])
  }

  // A script's Basic login, [header]: true when it is let in, or refused for the limit on wrong logins, which answers
  // it here; false when it is wrong, which counts against the limit and is answered as no login is.
  private suspend fun basicLoginAnswered(
    call: ApplicationCall,
    header: String,
    login: DashboardLogin,
  ): Boolean {
    val address = call.request.origin.remoteAddress
    if (limiter.isBlocked(address)) {
      call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECS)
      call.respondText(LoginHtml.LIMITED, ContentType.Text.Plain, HttpStatusCode.TooManyRequests)
      return true
    }
    if (login.accepts(header))
      return true
    recordBadLogin(address)
    return false
  }

  // A request without a login: a page request goes to the login page, and anything else gets a 401.
  private suspend fun refuseWithoutLogin(call: ApplicationCall) {
    if (wantsPage(call.request))
      call.seeOther("$loginPath?${LoginHtml.NEXT_FIELD}=${call.request.uri.encodeURLParameter()}")
    else
      call.respondText("Login required", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
  }

  // The login page, never cached or framed, for a page asked for without a session or after a wrong login.
  private suspend fun ApplicationCall.respondLogin(
    next: String,
    username: String = "",
    alert: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Content-Security-Policy", "frame-ancestors 'none'")
    respondHtml(status) { renderLogin(LoginHtml.Form(settings.loginHeading, routeBase, next, username, alert)) }
  }

  // The login form's POST, behind originGuard: a body over MAX_LOGIN_BODY_BYTES (or of unknown length) refused unread,
  // any login from an address past the limit on wrong ones refused, and a right one given a session and sent on to
  // next.
  private suspend fun ApplicationCall.logIn(login: DashboardLogin) {
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (length == null || length > MAX_LOGIN_BODY_BYTES)
      return respondText("Login form too large", ContentType.Text.Plain, HttpStatusCode.PayloadTooLarge)
    val form = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
    val username = form[LoginHtml.USERNAME_FIELD].orEmpty().take(LoginHtml.MAX_FIELD_LENGTH)
    val next = safeNext(form[LoginHtml.NEXT_FIELD], routeBase)
    val address = request.origin.remoteAddress
    if (limiter.isBlocked(address)) {
      response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECS)
      respondLogin(next, username, LoginHtml.LIMITED, HttpStatusCode.TooManyRequests)
    } else if (login.accepts(username, form[LoginHtml.PASSWORD_FIELD].orEmpty())) {
      // Secure when the browser reached the dashboard over https, as through a TLS-terminating reverse proxy.
      val secure = request.headers[HttpHeaders.Origin]?.startsWith("https://", ignoreCase = true) == true
      response.header(HttpHeaders.SetCookie, sessionCookieHeader(logins.create(), secure))
      seeOther(next)
    } else {
      recordBadLogin(address)
      respondLogin(next, username, LoginHtml.FAILED, HttpStatusCode.Unauthorized)
    }
  }

  // The Set-Cookie value for [token], kept for a session's longest life; a null token expires the cookie.
  private fun sessionCookieHeader(
    token: String?,
    secure: Boolean = false,
  ): String {
    val maxAge = if (token == null) 0 else DashboardSessions.MAX_AGE_SECS
    val flags = if (secure) "; Secure" else ""
    return "$sessionCookie=${token.orEmpty()}; Max-Age=$maxAge; Path=$basePath; HttpOnly; SameSite=Lax$flags"
  }

  // A wrong login, from the form or Basic Auth: counted against the limit, and logged at WARN at most once a minute
  // for each address and at DEBUG otherwise, so a client guessing in a loop can't flood the log, never with the
  // username it gave, which is the client's own text. The one that reaches the limit is logged at WARN too.
  private fun recordBadLogin(address: String) {
    val limited = limiter.recordFailure(address)
    val message = "Refused a dashboard request from $address with a wrong login"
    if (badLoginWarnings.tryAcquire(address))
      logger.warn { message }
    else
      logger.debug { message }
    if (limited)
      logger.warn {
        "Too many wrong dashboard logins, from $address or in all: refusing them for up to a minute " +
          "($MAX_BAD_LOGINS_PER_ADDRESS from one address, or $MAX_BAD_LOGINS_OVERALL in all, within a minute)"
      }
  }

  // Application-wide rather than route-scoped, so it runs before routing and covers every page, asset, and the
  // WebSocket. See isHostAllowed.
  private val hostCheck = createApplicationPlugin("DashboardHostCheck") { onCall { checkHost(it) } }

  private suspend fun checkHost(call: ApplicationCall) {
    val host = call.request.headers[HttpHeaders.Host]
    if (!isHostAllowed(host, hostAllowlist)) {
      logger.info {
        "Refused a dashboard request for host $host; add it to ${settings.role}.dashboard.allowedHosts if expected"
      }
      call.respondText("Host not allowed", ContentType.Text.Plain, HttpStatusCode.Forbidden)
    }
  }

  private val server = buildServer()

  private fun buildServer() =
    embeddedServer(
      factory = CIO,
      configure = {
        connector {
          host = settings.host
          port = settings.port
        }
      },
    ) {
      // Shares the proxy's own Ktor configuration: compression (htmx.min.js is ~37 KB), StatusPages so a failure
      // returns a logged 500 rather than a bare one, and DefaultHeaders. Request logging is off -- a dashboard polling
      // its own socket would drown the process's logs.
      configureKtorServer(isLoggingEnabled = false)
      install(hostCheck)
      settings.login?.also { install(authCheck(it)) }
      install(WebSockets) {
        // Ktor's defaults send no pings and buffer outgoing frames without limit, so a client that stops reading
        // would pile up a frame per push until the process ran out of memory. Pings find a dead peer, and a full
        // buffer closes that session instead of growing -- so the shared push loop never waits on a slow one.
        pingPeriodMillis = PING_PERIOD_MILLIS
        timeoutMillis = PING_TIMEOUT_MILLIS
        // A browser message is a few dozen bytes; the default accepts, and buffers whole, a frame of any size.
        maxFrameSize = MAX_INCOMING_FRAME_BYTES
        channels { outgoing = bounded(OUTGOING_FRAME_BUFFER, ChannelOverflow.CLOSE) }
      }
      install(CachingHeaders) {
        // The served asset URLs carry their WebJar's version (ASSET_DIR, ICON_DIR), so a URL's content never changes
        // and a year-long max-age is safe. An unversioned URL would let a browser keep running a cached htmx after an
        // upgrade.
        options { _, outgoing -> assetCaching(outgoing.contentType) }
      }

      routing {
        rootRedirect()
        settings.login?.also { loginRoutes(it) }
        // Whether this request may use the dashboard: the auth check answers first, so this is a 204 only when it may.
        // The page's script asks when its WebSocket closes, so an expired session lands on the login page.
        get("$routeBase/session") { call.respond(HttpStatusCode.NoContent) }
        assetRoutes()
        with(site) { pages(this@DashboardServer) }
        events()
      }
    }

  // The dashboard lives under basePath (default /dashboard), so the bare root would otherwise 404. Send it to the
  // dashboard. Skipped when basePath is already "/" -- there the site's page route owns the root, and a second handler
  // would be a duplicate. Temporary rather than permanent: basePath is configurable, so a cached 301 would be wrong if
  // it ever changed.
  private fun Route.rootRedirect() {
    if (routeBase.isNotEmpty())
      get("/") { call.respondRedirect(basePath, permanent = false) }
  }

  // A year for what ASSET_DIR and ICON_DIR serve (see CachingHeaders above), and nothing for anything else.
  private fun assetCaching(contentType: ContentType?): CachingOptions? {
    val cached = CACHED_TYPES.any { contentType?.match(it) == true }
    val yearLong = CacheControl.MaxAge(ASSET_MAX_AGE_SECS, visibility = CacheControl.Visibility.Public)
    return CachingOptions(yearLong).takeIf { cached }
  }

  // The login page and its form, and Log out, only with a password set.
  private fun Route.loginRoutes(credentials: DashboardLogin) {
    route(loginPath) {
      install(originGuard)
      get {
        if (logins.isValid(call.request.cookies[sessionCookie]))
          call.seeOther(basePath)
        else
          call.respondLogin(safeNext(call.request.queryParameters[LoginHtml.NEXT_FIELD], routeBase))
      }
      post { call.logIn(credentials) }
    }
    route(logoutPath) {
      install(originGuard)
      post {
        logins.remove(call.request.cookies[sessionCookie])
        // So the push loop closes this session's open pages now rather than at its next tick.
        wake.trySend(Unit)
        call.response.header(HttpHeaders.SetCookie, sessionCookieHeader(null))
        call.seeOther(loginPath)
      }
    }
  }

  // Explicit allowlists rather than staticResources: the WebJar layout embeds a version in the path, and an allowlist
  // means there is no path-traversal surface and no dependence on how Ktor interprets a basePackage.
  private fun Route.assetRoutes() {
    get("$routeBase/$ASSET_DIR/{file}") {
      val bytes = assetBytes[call.parameters["file"]]
      if (bytes == null)
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
      else
        call.respondBytes(bytes, ContentType.Application.JavaScript, HttpStatusCode.OK)
    }

    // The site's brand marks, the same way. An SVG is a document that can run script, so one opened directly runs
    // sandboxed, with no access to the dashboard's origin, and a browser may not take it for another type; as a mask
    // image, a page's only use of one, neither header changes anything.
    get("$routeBase/$ICON_DIR/{file}") {
      val bytes = iconBytes[call.parameters["file"]]
      if (bytes == null) {
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
      } else {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", "sandbox")
        call.respondBytes(bytes, ContentType.Image.SVG, HttpStatusCode.OK)
      }
    }
  }

  // The page's WebSocket: the frame for what it shows at once, and again with each message saying what it shows.
  private fun Route.events() {
    route("$routeBase/events") {
      install(pageGuard)
      webSocket {
        if (!reserveSession()) {
          close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many dashboard sessions"))
          return@webSocket
        }
        val session =
          Session<V>(
            login = if (loginEnabled) call.request.cookies[sessionCookie] else null,
            send = { text -> outgoing.send(Frame.Text(text)) },
            end = { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "The dashboard session ended")) },
          )
        sessions.add(session)
        try {
          // A frame at once, rather than making the browser wait for the first change or tick. The regions that depend
          // on what the page shows follow its first message, below.
          push(session, snapshots.recent())
          for (frame in incoming) {
            if (frame is Frame.Text) {
              session.view = site.parseView(frame.readText())
              push(session, snapshots.recent())
            }
          }
        } finally {
          sessions.remove(session)
          sessionCount -= 1
        }
      }
    }
  }

  /** How many browsers have the page open. */
  val sessionsOpen: Int get() = sessions.size

  /** Starts the server, the push loop, and the watch on the site's changes. */
  fun start() {
    server.start()
    scope.launch { pushLoop() }
    scope.launch {
      // Conflated, so a burst (a fleet reconnecting at once) collapses into a single collect.
      site.changes.collect {
        // Retire the cached snapshot so no page or session renders a state older than the change.
        snapshots.invalidate()
        wake.trySend(Unit)
      }
    }
  }

  /** Stops the push loop and the server. */
  fun stop() {
    scope.cancel()
    server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
  }

  /** Wakes on a change or, failing that, on the refresh interval; collects once; fans out. */
  private suspend fun pushLoop() {
    while (scope.isActive) {
      withTimeoutOrNull(settings.refreshInterval) { wake.receive() }
      if (sessions.isEmpty()) continue
      // Always a collect, never the cache: a tick exists to sample the values that drift.
      val snapshot = snapshots.fresh()
      sessions.forEach {
        // A page whose login session has ended, by Log out, expiring, or a restart's losing it, gets no more pushes:
        // its socket closes, and the page, asking for its session, goes to the login page. A check, not a request, so
        // an open page doesn't keep its session from going idle.
        if (it.login != null && !logins.isValid(it.login, counts = false))
          it.end()
        else
          push(it, snapshot)
      }
    }
  }

  private suspend fun push(
    session: Session<V>,
    snapshot: S,
  ) {
    session.pushing.withLock {
      // Rendered apart from the send. A send fails when the browser has gone, which is routine, but a render that
      // throws is a bug in the page; caught with the sends, it froze every session on that page with nothing logged
      // above DEBUG.
      val frame =
        runCatching { site.frame(snapshot, session.view, session.sent, routeBase) }
          .onFailure { e ->
            if (!session.renderFailed) logger.error(e) { "Failed to render a dashboard push" }
            session.renderFailed = true
          }.getOrNull() ?: return
      // A send fails when the browser has gone, which is routine. Only this coroutine's own cancellation, as the
      // server stops, is passed on: a gone browser's cancelled channel fails a send with a CancellationException too,
      // and passing that on would end the loop that pushes to every other session.
      runCatching { session.send(frame.text) }
        .onSuccess { session.sent = frame.sent }
        .onFailure {
          currentCoroutineContext().ensureActive()
          logger.debug { "Dashboard push not sent: ${it.simpleClassName}" }
        }
    }
  }

  /** Claims a session slot, or returns false when maxSessions sessions are already connected. */
  private fun reserveSession(): Boolean {
    while (true) {
      val current = sessionCount.load()
      if (current >= settings.maxSessions) return false
      if (sessionCount.compareAndSet(current, current + 1)) return true
    }
  }

  /**
   * The static assets, read from the classpath once.
   *
   * Read once rather than per request: the port may have no authentication, so a request loop would otherwise make
   * the process inflate ~37 KB out of the JAR without bound.
   */
  private val assetBytes: Map<String, ByteArray> by lazy { readResources(ASSETS) }

  /** The site's brand marks, by file name, read from the classpath once, for the reason [assetBytes] is. */
  private val iconBytes: Map<String, ByteArray> by lazy { readResources(settings.icons) }

  private fun readResources(resources: Map<String, String>): Map<String, ByteArray> =
    resources.mapNotNull { (name, resource) ->
      javaClass.classLoader.getResourceAsStream(resource)?.use { name to it.readBytes() }
    }.toMap()

  companion object {
    private val logger = logger {}

    // Generated from libs.versions.toml, since the WebJar classpath layout embeds the version. Reading
    // it rather than restating it means a dependency bump cannot leave the asset route pointing at
    // a path that is no longer on the classpath.
    internal const val HTMX_VERSION = BuildConfig.HTMX_VERSION

    /**
     * Where the assets are served under the route base. It carries the WebJar version because the assets are cached
     * for a year: with a fixed URL, a browser that cached one htmx would keep running it against the markup of the
     * next. The route and the page's script tags both read it, so they cannot disagree.
     */
    internal const val ASSET_DIR = "assets/$HTMX_VERSION"

    /** The only classpath resources this server will serve, by request filename. */
    internal val ASSETS =
      mapOf(
        "htmx.min.js" to "META-INF/resources/webjars/htmx.org/$HTMX_VERSION/dist/htmx.min.js",
        // htmx 4 ships its WebSocket extension in the same WebJar.
        "hx-ws.min.js" to "META-INF/resources/webjars/htmx.org/$HTMX_VERSION/dist/ext/hx-ws.min.js",
      )

    // The version of the Simple Icons WebJar the build extracts the brand marks from; see HTMX_VERSION.
    internal const val SIMPLE_ICONS_VERSION = BuildConfig.SIMPLE_ICONS_VERSION

    /**
     * Where a site's brand marks ([DashboardSettings.icons]) are served under the route base. It carries the Simple
     * Icons version for the reason [ASSET_DIR] carries htmx's. The route and the page's icon stylesheet both read it.
     */
    internal const val ICON_DIR = "assets/icons/$SIMPLE_ICONS_VERSION"

    // The types of what ASSET_DIR and ICON_DIR serve, which CachingHeaders lets a browser keep for a year.
    private val CACHED_TYPES = listOf(ContentType.Application.JavaScript, ContentType.Image.SVG)

    private const val ASSET_MAX_AGE_SECS = 31_536_000
    private const val GRACE_MILLIS = 2_000L
    private const val TIMEOUT_MILLIS = 5_000L
    private const val PING_PERIOD_MILLIS = 15_000L
    private const val PING_TIMEOUT_MILLIS = 15_000L
    private const val MAX_INCOMING_FRAME_BYTES = 64L * 1024

    // Frames a session may have waiting before it is closed. A reading browser drains each push long before the next.
    private const val OUTGOING_FRAME_BUFFER = 32

    private val BAD_LOGIN_WARNING_INTERVAL = 1.minutes
    private const val MAX_BAD_LOGIN_HOSTS = 1_000

    /** The largest login form the login route reads; a browser's is a few hundred bytes. */
    internal const val MAX_LOGIN_BODY_BYTES = 4_096

    /** The most wrong logins one address may make in a minute; past it, its logins are refused for the minute. */
    internal const val MAX_BAD_LOGINS_PER_ADDRESS = 10

    // The most from every address together, past which every login is refused for the minute.
    private const val MAX_BAD_LOGINS_OVERALL = 100

    // How long a refused login is told to wait, the limit's window, in seconds.
    private const val RETRY_AFTER_SECS = "60"

    // The longest next the login page carries; a longer one goes to the base.
    private const val MAX_NEXT_LENGTH = 2_048

    /** The session cookie's name, for the dashboard of [role] (`proxy` or `agent`) on [port]. */
    internal fun cookieName(
      role: String,
      port: Int,
    ) = "prometheus_${role}_dashboard_$port"

    /**
     * Where a login sends the browser: [next] when it is a page of the dashboard at [routeBase], else the dashboard's
     * base. A path only, not a URL, not a protocol-relative `//host`, nor one a browser reads as one (`/\host`), with
     * no `.` or `..` segment, and not the login page itself, so a link to the login page can't send a browser off-site.
     */
    internal fun safeNext(
      next: String?,
      routeBase: String,
    ): String {
      val path = next.orEmpty()
      return if (isPlainPath(path) && isDashboardPage(path.substringBefore('?'), routeBase))
        path
      else
        routeBase.ifEmpty { "/" }
    }

    // A path, and only a path: not too long, absolute, not protocol-relative, and with no character a browser could
    // read as starting a host (a backslash), nor a control character or whitespace.
    private fun isPlainPath(path: String): Boolean {
      val shaped = path.length <= MAX_NEXT_LENGTH && path.startsWith("/") && !path.startsWith("//")
      return shaped && path.none { it == '\\' || it.isISOControl() || it.isWhitespace() }
    }

    // A page of the dashboard at [routeBase], by the part of a path before its query: under the base, with no . or ..
    // segment, and not the login page.
    private fun isDashboardPage(
      pagePath: String,
      routeBase: String,
    ): Boolean {
      val underBase = routeBase.isEmpty() || pagePath == routeBase || pagePath.startsWith("$routeBase/")
      return underBase && pagePath != "$routeBase/login" && pagePath.split('/').none { it == "." || it == ".." }
    }

    // Fetch Metadata's header, which every current browser sends with every request, a WebSocket handshake included,
    // and a script such as curl doesn't.
    internal const val SEC_FETCH_MODE = "Sec-Fetch-Mode"

    /** Whether [request] came from a browser, which must log in on the login page rather than with Basic Auth. */
    private fun isBrowser(request: ApplicationRequest) = request.headers[SEC_FETCH_MODE] != null

    // A browser's navigation to a page, which the auth check sends to the login page rather than refusing: a GET that
    // takes HTML and isn't htmx's own request.
    private fun wantsPage(request: ApplicationRequest) =
      request.httpMethod == HttpMethod.Get &&
        request.headers[HX_REQUEST_HEADER] == null &&
        request.headers[HttpHeaders.Accept].orEmpty().contains("text/html")

    // A 303, so the browser follows a POST's redirect with a GET.
    private suspend fun ApplicationCall.seeOther(location: String) {
      response.header(HttpHeaders.Location, location)
      respond(HttpStatusCode.SeeOther)
    }

    // Sent by htmx, and by a page's script with each action.
    internal const val HX_REQUEST_HEADER = "HX-Request"

    // Sent by htmx when it fetches a page to restore from its history, which wants the whole page.
    private const val HX_HISTORY_RESTORE_HEADER = "HX-History-Restore-Request"

    /**
     * Whether [request] is htmx swapping a fragment into the page, as a row click does, rather than a navigation, a
     * reload, or a shared link, which want the whole page. htmx marks its own requests with `HX-Request`, except a
     * history restore, which fetches the full URL expecting a full page.
     */
    internal fun isHtmxSwap(request: ApplicationRequest) =
      request.headers[HX_REQUEST_HEADER] == "true" && request.headers[HX_HISTORY_RESTORE_HEADER] != "true"

    /**
     * Whether a WebSocket handshake or an action carrying [origin] may proceed, given the request's [host] header.
     *
     * No Origin is allowed: browsers send one on every WebSocket handshake and every POST, so its absence means a
     * non-browser client, and this check exists to stop a browser being turned against the dashboard. Otherwise the
     * origin must name the host the request was addressed to -- the dashboard's own page -- or match an [allowedOrigins]
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
     * [allowlist] -- `allowedHosts` empty, the default -- allows every Host. Otherwise the dashboard answers only to an
     * IP address or a name in [allowlist], ignoring the port, case, and a trailing dot. A request with no Host is
     * allowed, since browsers always send one; an empty or unparseable Host is refused. `X-Forwarded-Host` is never
     * consulted: a rebound page is same-origin and can set it.
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
    ): Set<String>? {
      if (allowedHosts.isEmpty())
        return null
      return buildSet {
        add(LOCALHOST)
        allowedHosts.mapNotNullTo(this) { hostName(it) }
        allowedOrigins.mapNotNullTo(this) { origin ->
          runCatching { URI(origin.trim()).host }.getOrNull()?.let(::hostName)
        }
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
