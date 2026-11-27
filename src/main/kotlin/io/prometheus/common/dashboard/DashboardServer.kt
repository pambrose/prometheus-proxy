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
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.prometheus.BuildConfig
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.KeyedThrottle
import io.prometheus.common.dashboard.LoginHtml.renderLogin
import io.prometheus.proxy.ProxyHttpConfig.configureKtorServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * What one dashboard is configured with.
 *
 * @param role the process the dashboard belongs to, `proxy` or `agent`: it names the session cookie and the config keys
 *   a log line points to (`<role>.dashboard.allowedOrigins`)
 * @param path the base path, normalized here once (see [DashboardServer.routeBase])
 * @param framing which pages may show the dashboard in a frame, from `<role>.dashboard.framingEnabled` ([Framing.of]):
 *   [Framing.ANY] adds no header, [Framing.SAME_ORIGIN] lets only the dashboard's own origin frame it, as the proxy's
 *   `/screens` page does, and [Framing.NONE] refuses framing on every response
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
  val framing: Framing,
  val login: DashboardLogin?,
  val loginHeading: String,
  val icons: Map<String, String> = emptyMap(),
)

/**
 * How a dashboard login went, which the server tells its owner of (`onLogin`), for it to count in its
 * `proxy_dashboard_logins_total` or `agent_dashboard_logins_total`.
 */
internal enum class DashboardLoginOutcome {
  /**
   * A right login on the login page, which starts a session. A script's right Basic Auth isn't one: it logs in on every
   * request.
   */
  SUCCESS,

  /** A wrong login, from the login page or a script's Basic Auth. */
  FAILURE,

  /** A login refused unchecked, its address over the limit on wrong ones. */
  BLOCKED,
}

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

  /**
   * The paths of the site's pages outside [DashboardServer.routeBase], such as the proxy's `/screens`, to which a login
   * may return as to the dashboard's own (see [DashboardServer.safeNext]). Empty for a site with none.
   */
  val outsidePages: Set<String>

  /**
   * Where the bare root sends a browser, unless the dashboard is mounted there: one of [outsidePages], such as the
   * proxy's `/screens`, or null for the dashboard's base path.
   */
  val home: String?

  /**
   * The site's pages and actions, under [DashboardServer.routeBase] but for [outsidePages]. Each action installs its
   * `pageGuard`.
   */
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
 *   buffered for, a page request or a browser message renders the recent snapshot rather than collecting one (see
 *   [SnapshotCache]), and a browser's messages push to its page at most once an interval, and only as they change
 *   what it shows. Setting `allowedHosts` also refuses a request naming an unknown host, which stops DNS rebinding.
 *   With `framingEnabled` false, no response may be framed by another page, and with a password set, none but the
 *   versioned assets may be kept in a browser's cache. The root's redirect to the site's [DashboardSite.home] and a
 *   HEAD asking whether the dashboard is up need no login. A refused Origin or Host, which needs no login, is logged
 *   cut and escaped, at INFO at most once a minute for each address.
 * - **Assets.** htmx and its WebSocket extension, and the site's brand marks, from the classpath, under URLs that carry
 *   their WebJar's version, so a year-long cache can never run an old script against new markup.
 * - **Updates.** One shared push loop, not one per session. It wakes on one of the site's [DashboardSite.changes] or a
 *   timer tick, collects a single snapshot, and fans a frame out to every connected session. The tick is what keeps
 *   drifting values live: without it, an idle process would freeze its countdowns until the next change. Nothing in
 *   the loop waits on a browser, so no one client can hold up the others' pushes: a session is closed on a coroutine of
 *   its own, and one whose send fails, as once its outgoing buffer is full, is dropped then. A collect that throws is
 *   logged, and the loop carries on at its next wake or tick.
 */
internal class DashboardServer<V : Any, S : Any>(
  val settings: DashboardSettings,
  private val site: DashboardSite<V, S>,
  // The login page's sessions. A spec passes its own, with a time source it moves, to end one without waiting.
  private val logins: DashboardSessions = DashboardSessions(),
  // Told how each login went, for the owner's metrics; see DashboardLoginOutcome for what counts as one.
  private val onLogin: (DashboardLoginOutcome) -> Unit = {},
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
    // Closes the socket, with a close frame when given a reason, and drops its connection when not, or when the
    // browser doesn't take the frame in time (see closer). Can wait that long, so called only through end().
    val close: suspend (CloseReason?) -> Unit,
  ) {
    // Written by this session's socket coroutine and read by the shared push loop, hence @Volatile. Null until the
    // browser's first message says which page it shows. The browser sends that message as soon as the socket opens.
    @Volatile
    var view: V? = null

    // Whether this session's last push failed to render, so a run of failures is logged at ERROR once rather than every
    // refresh.
    @Volatile
    var renderFailed = false

    // Held for the whole of a push to this session, from reading its view to recording what was sent. The push loop
    // and the session's own coroutines both push, and without it the two could record in one order and send in
    // the other, leaving [sent] naming a frame the page never got.
    val pushing = Mutex()

    // What the last frame sent to this page kept for the next to compare with (DashboardFrame.sent). Guarded by
    // pushing.
    var sent: Any? = null
  }

  private val sessions = ConcurrentHashMap.newKeySet<Session<V>>()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  // The push loop's, the change watch's, and each closing socket's. A failure in one is logged through the logger,
  // rather than printed to stderr by the default handler, outside the process's log.
  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> logger.error(e) { "A ${settings.role} dashboard task failed" } },
    )

  // Whether the push loop's last collect failed, so a run of failures is logged at ERROR once. The push loop's alone.
  private var collectFailing = false

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
    logRefusal(originRefusals, request.origin.remoteAddress) {
      "Refused a dashboard request from origin ${shownHeader(origin)}; " +
        "add it to ${settings.role}.dashboard.allowedOrigins if expected"
    }
    return "Origin not allowed"
  }

  // Which addresses' refused Origins, and which addresses' refused Hosts, may be logged at INFO now.
  private val originRefusals = KeyedThrottle<String>(NOTICE_INTERVAL, MAX_NOTICED_ADDRESSES)
  private val hostRefusals = KeyedThrottle<String>(NOTICE_INTERVAL, MAX_NOTICED_ADDRESSES)

  // A request from [address] refused for its Origin or Host, which needs no login: logged at INFO at most once a minute
  // for each address, by [notices], and at DEBUG otherwise, so a client refused in a loop can't flood the log, and with
  // the header cut and escaped (shownHeader), so it can't write control characters or a request line's worth into it.
  private fun logRefusal(
    notices: KeyedThrottle<String>,
    address: String,
    message: () -> String,
  ) {
    if (notices.tryAcquire(address))
      logger.info(message)
    else
      logger.debug(message)
  }

  // The cookie that carries a login page's session: named for the role and the port, since a browser sends a host's
  // cookies to every port on it, and an HA pair can run on one host.
  private val sessionCookie = cookieName(settings.role, settings.port)
  private val loginPath = "$routeBase/login"
  private val logoutPath = "$routeBase/logout"

  // Which addresses' wrong logins may be logged at WARN now.
  private val badLoginWarnings = KeyedThrottle<String>(NOTICE_INTERVAL, MAX_NOTICED_ADDRESSES)

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
    // skip the page and outlast Log out. So a browser's Basic login is ignored, and only a script's is read: one with
    // none of the headers a browser sends (see isBrowser).
    val header = if (isBrowser(call.request)) null else call.request.headers[HttpHeaders.Authorization]
    if (header == null || !basicLoginAnswered(call, header, login))
      refuseWithoutLogin(call)
  }

  // Whether [request] may proceed without a login: one for the login or logout route, a liveness check, the root's
  // redirect, or with a valid session.
  private fun needsNoLogin(request: ApplicationRequest): Boolean {
    val path = request.path()
    if (path == loginPath || path == logoutPath)
      return true
    if (isLivenessCheck(request) || isRootRedirect(request))
      return true
    return logins.isValid(request.cookies[sessionCookie])
  }

  // A GET of the bare root while the dashboard isn't mounted there, which only redirects (see rootRedirect), so it
  // needs no login: the page it leads to asks for one, and a login asked for here would return to the dashboard,
  // never to the proxy's /screens, the root's home while screens are listed.
  private fun isRootRedirect(request: ApplicationRequest) =
    routeBase.isNotEmpty() && request.httpMethod == HttpMethod.Get && request.path() == "/"

  // A HEAD of the base path or of /session, which the proxy's screens page sends every few seconds to see whether a
  // dashboard answers. It says only that the dashboard is up, as the login page does to anyone, so it needs no login,
  // and the routes answer it with a 204, where it was a 405, or a 401 with a login set, in every log that counts them.
  private fun isLivenessCheck(request: ApplicationRequest) =
    request.httpMethod == HttpMethod.Head && request.path() in livenessPaths

  private val livenessPaths = setOf(basePath, "$routeBase/session")

  // A script's Basic login, [header]: true when it is let in, or refused for the limit on wrong logins, which answers
  // it here; false when it is wrong, which counts against the limit and is answered as no login is.
  private suspend fun basicLoginAnswered(
    call: ApplicationCall,
    header: String,
    login: DashboardLogin,
  ): Boolean {
    val address = call.request.origin.remoteAddress
    if (limiter.isBlocked(address)) {
      onLogin(DashboardLoginOutcome.BLOCKED)
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

  // The login page, for a page asked for without a session or after a wrong login. Like every response with a login
  // set, never cached, and like every response, never framed while framingEnabled is false (see noFraming and caching).
  private suspend fun ApplicationCall.respondLogin(
    next: String,
    username: String = "",
    alert: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = respondHtml(status) { renderLogin(LoginHtml.Form(settings.loginHeading, routeBase, next, username, alert)) }

  // The login form's POST, behind originGuard: a body over MAX_LOGIN_BODY_BYTES (or of unknown length) refused unread,
  // any login from an address past the limit on wrong ones refused, and a right one given a session and sent on to
  // next.
  private suspend fun ApplicationCall.logIn(login: DashboardLogin) {
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (length == null || length > MAX_LOGIN_BODY_BYTES)
      return respondText("Login form too large", ContentType.Text.Plain, HttpStatusCode.PayloadTooLarge)
    val form = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
    val username = form[LoginHtml.USERNAME_FIELD].orEmpty().take(LoginHtml.MAX_FIELD_LENGTH)
    val next = safeNext(form[LoginHtml.NEXT_FIELD], routeBase, site.outsidePages)
    val address = request.origin.remoteAddress
    if (limiter.isBlocked(address)) {
      onLogin(DashboardLoginOutcome.BLOCKED)
      response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECS)
      respondLogin(next, username, LoginHtml.LIMITED, HttpStatusCode.TooManyRequests)
    } else if (login.accepts(username, form[LoginHtml.PASSWORD_FIELD].orEmpty())) {
      // Secure when the browser reached the dashboard over https, as through a TLS-terminating reverse proxy.
      val secure = request.headers[HttpHeaders.Origin]?.startsWith("https://", ignoreCase = true) == true
      setSessionCookie(logins.create(), secure)
      onLogin(DashboardLoginOutcome.SUCCESS)
      seeOther(next)
    } else {
      recordBadLogin(address)
      respondLogin(next, username, LoginHtml.FAILED, HttpStatusCode.Unauthorized)
    }
  }

  // Sets the session cookie to [token], kept for a session's longest life; a null token expires it. At the base path,
  // and at each of the site's pages outside it, as the proxy's /screens, so a browser sends it to them too, with the
  // same token; at a root mount the root covers them. Not at the root otherwise: a browser would send the token to
  // every other app on the host, and after a rollback to a dashboard that reads only the base path's, a root cookie
  // left from this one, dead after the restart, would be the one it read, sending every sign-in back to the login page.
  private fun ApplicationCall.setSessionCookie(
    token: String?,
    secure: Boolean = false,
  ) {
    for (path in cookiePaths) {
      response.header(HttpHeaders.SetCookie, sessionCookieHeader(token, path, secure))
    }
  }

  // Where the session cookie is set: the base path, and each page outside it but at a root mount.
  private val cookiePaths = if (basePath == "/") listOf("/") else listOf(basePath) + site.outsidePages

  // The Set-Cookie value for [token] at [path]; a null token expires the cookie there.
  private fun sessionCookieHeader(
    token: String?,
    path: String,
    secure: Boolean,
  ): String {
    val maxAge = if (token == null) 0 else DashboardSessions.MAX_AGE_SECS
    val flags = if (secure) "; Secure" else ""
    return "$sessionCookie=${token.orEmpty()}; Max-Age=$maxAge; Path=$path; HttpOnly; SameSite=Lax$flags"
  }

  // A wrong login, from the form or Basic Auth: counted against the limit, and logged at WARN at most once a minute
  // for each address and at DEBUG otherwise, so a client guessing in a loop can't flood the log, never with the
  // username it gave, which is the client's own text. The one that reaches the limit is logged at WARN too.
  private fun recordBadLogin(address: String) {
    onLogin(DashboardLoginOutcome.FAILURE)
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
      logRefusal(hostRefusals, call.request.origin.remoteAddress) {
        "Refused a dashboard request for host ${shownHeader(host)}; " +
          "add it to ${settings.role}.dashboard.allowedHosts if expected"
      }
      call.respondText("Host not allowed", ContentType.Text.Plain, HttpStatusCode.Forbidden)
    }
  }

  // Installed unless settings.framing is ANY, the default, where any page can frame the dashboard. Then
  // application-wide, on every response: a page, a fragment, an action's result, an asset, a refusal, a redirect, an
  // error page, and the WebSocket's handshake. With NONE no page may frame the dashboard (frame-ancestors 'none',
  // X-Frame-Options DENY), and with SAME_ORIGIN, for the proxy's /screens, only a page of its own origin may
  // (frame-ancestors 'self', SAMEORIGIN), so another site's page can't lay itself over the dashboard's and steer a
  // viewer's clicks, the WebSocket and the actions passing the Origin check as the dashboard's own. X-Frame-Options is
  // the same rule for a browser that predates frame-ancestors. A response with a policy of its own, as a brand mark's,
  // gets this one beside it, since a browser enforces every policy a response carries. Each header is added once,
  // however many times the response is answered: StatusPages answers a bare 404 again.
  private val noFraming =
    createApplicationPlugin("DashboardNoFraming") {
      val framing = settings.framing
      onCallRespond { call ->
        val headers = call.response.headers
        if (headers.values(CONTENT_SECURITY_POLICY).none { it.contains(FRAME_ANCESTORS) })
          framing.frameAncestors?.also { call.response.header(CONTENT_SECURITY_POLICY, it) }
        if (X_FRAME_OPTIONS !in headers)
          framing.xFrameOptions?.also { call.response.header(X_FRAME_OPTIONS, it) }
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
      if (settings.framing != Framing.ANY)
        install(noFraming)
      install(hostCheck)
      settings.login?.also { install(authCheck(it)) }
      install(WebSockets) {
        // Ktor's defaults send no pings and buffer outgoing frames without limit, so a client that stops reading
        // would pile up a frame per push until the process ran out of memory. Pings find a dead peer, and a full
        // buffer closes the session's channel instead of growing -- so a send never waits on a slow client, and the
        // next push to it fails, which drops it (see push). Ktor's pinger doesn't: once the buffer has overflowed, it
        // stops without closing the session.
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
        options { _, outgoing -> caching(outgoing.contentType) }
      }

      routing {
        rootRedirect()
        settings.login?.also { loginRoutes(it) }
        // Whether this request may use the dashboard: the auth check answers first, so this is a 204 only when it may.
        // The page's script asks when its WebSocket closes, so an expired session lands on the login page.
        get("$routeBase/session") { call.respond(HttpStatusCode.NoContent) }
        // Whether the dashboard is up, for the screens page's checks, before any login (see isLivenessCheck).
        livenessPaths.forEach { path -> head(path) { call.respond(HttpStatusCode.NoContent) } }
        assetRoutes()
        with(site) { pages(this@DashboardServer) }
        events()
      }
    }

  // The dashboard lives under basePath (default /dashboard), so the bare root would otherwise 404. Send it to the
  // site's home, the proxy's /screens while it lists screens, else the dashboard. Skipped when basePath is already "/"
  // -- there the site's page route owns the root, and a second handler would be a duplicate. Temporary rather than
  // permanent: basePath and the screens are configurable, so a cached 301 would be wrong if either changed. It needs no
  // login (see isRootRedirect).
  private fun Route.rootRedirect() {
    if (routeBase.isNotEmpty())
      get("/") { call.respondRedirect(site.home ?: basePath, permanent = false) }
  }

  // A year for what ASSET_DIR and ICON_DIR serve (see CachingHeaders above). With a login set, no-store for every other
  // response, the login page's included, so a page behind the login isn't kept to be shown again after Log out, as from
  // the browser's back button; without one, nothing.
  private fun caching(contentType: ContentType?): CachingOptions? =
    when {
      CACHED_TYPES.any { contentType?.match(it) == true } -> CachingOptions(YEAR_LONG)
      loginEnabled -> CachingOptions(NO_STORE)
      else -> null
    }

  // The login page and its form, and Log out, only with a password set. A browser that already has a session, as one
  // signed in from another tab, goes on to the login page's next, which the page would have sent it to.
  private fun Route.loginRoutes(credentials: DashboardLogin) {
    route(loginPath) {
      install(originGuard)
      get {
        val next = safeNext(call.request.queryParameters[LoginHtml.NEXT_FIELD], routeBase, site.outsidePages)
        if (logins.isValid(call.request.cookies[sessionCookie]))
          call.seeOther(next)
        else
          call.respondLogin(next)
      }
      post { call.logIn(credentials) }
    }
    route(logoutPath) {
      install(originGuard)
      post {
        endLogin(call.request.cookies[sessionCookie])
        call.setSessionCookie(null)
        call.seeOther(loginPath)
      }
    }
  }

  // Ends [token]'s login, and closes its open pages now, rather than at the push loop's next tick, without waking the
  // loop. Log out needs no login, so anyone can post it in a loop: one naming no session costs a lookup and nothing
  // more, and a page's token is compared only with a token that named a session, by the client that holds it.
  private fun endLogin(token: String?) {
    if (logins.remove(token))
      sessions.filter { it.login == token }.forEach { end(it, SESSION_ENDED) }
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
        call.response.header(CONTENT_SECURITY_POLICY, "sandbox")
        call.respondBytes(bytes, ContentType.Image.SVG, HttpStatusCode.OK)
      }
    }
  }

  // The page's WebSocket: the frame for what it shows at once, and again as its messages change what it shows.
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
            // This handler's own coroutine is the connection's: Ktor runs it in a child of the socket's job.
            close = closer(this, currentCoroutineContext().job),
          )
        sessions.add(session)
        try {
          // A frame at once, rather than making the browser wait for the first change or tick. The regions that depend
          // on what the page shows follow its first message (see readViews).
          push(session, snapshots.recent())
          readViews(session)
        } finally {
          sessions.remove(session)
          sessionCount -= 1
        }
      }
    }
  }

  // Reads what [session]'s page shows from its messages, and pushes it a frame for each change: the first at once, and
  // the rest of a burst at most once a VIEW_PUSH_INTERVAL, the latest last. A message is a few bytes, and its frame can
  // be the page's whole table, so a push for every message would let a client sending them in a loop have the process
  // render as fast as it sends; a message that changes nothing pushes nothing. The view is recorded as it comes, so a
  // push from the loop in between renders it too.
  private suspend fun DefaultWebSocketServerSession.readViews(session: Session<V>) =
    coroutineScope {
      val changed = Channel<Unit>(Channel.CONFLATED)
      val pusher =
        launch {
          changed.receiveAsFlow().spaced(VIEW_PUSH_INTERVAL).collect { push(session, snapshots.recent()) }
        }
      try {
        for (frame in incoming) {
          val view = (frame as? Frame.Text)?.let { site.parseView(it.readText()) }
          if (view != null && view != session.view) {
            session.view = view
            changed.trySend(Unit)
          }
        }
      } finally {
        pusher.cancel()
      }
    }

  // How [socket], run by the coroutine [connection], is closed when its session ends. With a reason, a close frame,
  // which a reading browser answers at once, waited for at most CLOSE_TIMEOUT; without one, or past that, the
  // connection is cancelled, which ends this handler, freeing the session's slot, and Ktor's coroutines for the socket,
  // discarding the frames queued for it. Ktor's close() alone would wait, in its flush, for every frame queued ahead of
  // the close to be written, which for a browser that has stopped reading is for as long as its TCP connection lives.
  // (CIO still holds that connection, with the bytes it had already buffered for it, until the browser reads them or
  // the connection fails: Ktor gives an app no way to drop it sooner.)
  private fun closer(
    socket: DefaultWebSocketServerSession,
    connection: Job,
  ): suspend (CloseReason?) -> Unit =
    { reason ->
      val closed =
        reason != null &&
          withTimeoutOrNull(CLOSE_TIMEOUT) {
            socket.close(reason)
            connection.join()
          } != null
      if (!closed)
        connection.cancel()
    }

  // Ends [session], once however many find it ended: out of the pushes at once, and closed (Session.close) on a
  // coroutine of its own, so no caller, the push loop or a Log out, waits on its browser.
  private fun end(
    session: Session<V>,
    reason: CloseReason?,
  ) {
    if (sessions.remove(session))
      scope.launch { session.close(reason) }
  }

  /** How many browsers have the page open. */
  val sessionsOpen: Int get() = sessions.size

  /** Starts the server, the push loop, and the watch on the site's changes. */
  fun start() {
    server.start()
    scope.launch { pushLoop() }
    scope.launch {
      site.changes
        // Retire the cached snapshot at each change, so no page renders a state older than it.
        .onEach { snapshots.invalidate() }
        // A burst, such as a fleet reconnecting at once, or an agent registering thousands of paths as it connects,
        // wakes the loop at once and then at most once an interval, rather than once a change: each wake renders and
        // sends every open page.
        .spaced(CHANGE_PUSH_INTERVAL)
        .collect { wake.trySend(Unit) }
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
      // Always a collect, never the cache: a tick exists to sample the values that drift.
      if (sessions.isNotEmpty())
        collectForPush()?.also { pushToEvery(it) }
    }
  }

  // Pushes [snapshot] to every open page, or closes it when its login has ended.
  private suspend fun pushToEvery(snapshot: S) {
    sessions.forEach {
      // A page whose login session has ended, by expiring or a restart's losing it, gets no more pushes: its socket
      // closes, and the page, asking for its session, goes to the login page. Log out closes its pages itself. A
      // check, not a request, so an open page doesn't keep its session from going idle.
      if (it.login != null && !logins.isValid(it.login, counts = false))
        end(it, SESSION_ENDED)
      else
        push(it, snapshot)
    }
  }

  // The push loop's snapshot, or null when the collect threw. The loop carries on at its next wake or tick: ending it
  // would leave every page showing live with nothing pushed until a restart. Only the loop's own cancellation, as the
  // server stops, is passed on. The first failure of a run is logged at ERROR, and the rest at DEBUG.
  private suspend fun collectForPush(): S? =
    runCatching { snapshots.fresh() }
      .onSuccess { collectFailing = false }
      .onFailure { e ->
        currentCoroutineContext().ensureActive()
        if (collectFailing)
          logger.debug { "Dashboard snapshot not collected again: ${e.simpleClassName}" }
        else
          logger.error(e) {
            "Failed to collect the ${settings.role} dashboard's snapshot; trying again at its next change or tick"
          }
        collectFailing = true
      }.getOrNull()

  private suspend fun push(
    session: Session<V>,
    snapshot: S,
  ) {
    session.pushing.withLock {
      // An ended session, still in a loop's pass over the sessions, is rendered for no more.
      if (session !in sessions) return
      val frame = render(session, snapshot) ?: return
      // A send fails when the browser has gone, or has stopped reading and filled its buffer, which closes the
      // session's channel. Either way the session is ended now, rather than left to Ktor's pinger, which doesn't close
      // one whose buffer overflowed: left open, it held a slot of maxSessions and was rendered for on every push. Only
      // this coroutine's own cancellation, as the server stops, is passed on: a gone browser's cancelled channel fails
      // a send with a CancellationException too, and passing that on would end the loop that pushes to every other
      // session.
      runCatching { session.send(frame.text) }
        .onSuccess { session.sent = frame.sent }
        .onFailure {
          currentCoroutineContext().ensureActive()
          logger.debug { "Dashboard push not sent, so its session is ended: ${it.simpleClassName}" }
          end(session, reason = null)
        }
    }
  }

  // The frame for [session] from [snapshot], or null when the render threw. Rendered apart from the send: a send fails
  // when the browser has gone, which is routine, but a render that throws is a bug in the page; caught with the sends,
  // it froze every session on that page with nothing logged above DEBUG. The first failure of a run is logged at ERROR,
  // and the rest at DEBUG, as the push loop's collects are. Callers hold the session's pushing lock.
  private fun render(
    session: Session<V>,
    snapshot: S,
  ): DashboardFrame? =
    runCatching { site.frame(snapshot, session.view, session.sent, routeBase) }
      .onSuccess { session.renderFailed = false }
      .onFailure { e ->
        if (session.renderFailed)
          logger.debug { "Dashboard push failed to render again: ${e.simpleClassName}" }
        else
          logger.error(e) { "Failed to render a dashboard push" }
        session.renderFailed = true
      }.getOrNull()

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
    // How often a burst of changes wakes the push loop at most, after the first change, which wakes it at once.
    private val CHANGE_PUSH_INTERVAL = 250.milliseconds

    // How often a burst of a page's messages pushes to it at most, after the first, which pushes at once: well under
    // what a viewer notices, since a message is a click or a filter's change.
    private val VIEW_PUSH_INTERVAL = 100.milliseconds

    /**
     * [this] with its values spaced at least [interval] apart: the first at once, then, after each interval, the latest
     * that came during it, the ones between dropped. Never blocks the emitter, which a dashboard's change source, such
     * as an event bus, must not wait on.
     */
    internal fun <T> Flow<T>.spaced(interval: Duration): Flow<T> =
      conflate().transform {
        emit(it)
        delay(interval)
      }

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
    private val YEAR_LONG = CacheControl.MaxAge(ASSET_MAX_AGE_SECS, visibility = CacheControl.Visibility.Public)
    private val NO_STORE = CacheControl.NoStore(visibility = null)
    private const val GRACE_MILLIS = 2_000L
    private const val TIMEOUT_MILLIS = 5_000L
    private const val PING_PERIOD_MILLIS = 15_000L
    private const val PING_TIMEOUT_MILLIS = 15_000L
    private const val MAX_INCOMING_FRAME_BYTES = 64L * 1024

    // Frames a session may have waiting before it is closed. A reading browser drains each push long before the next.
    private const val OUTGOING_FRAME_BUFFER = 32

    // How long a session's close frame may wait to be written, before its connection is dropped instead. A reading
    // browser takes it at once.
    private val CLOSE_TIMEOUT = 5.seconds

    // Why a page's socket is closed when its login ends. The page then asks for its session and goes to the login page.
    private val SESSION_ENDED = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "The dashboard session ended")

    // How often each address's wrong logins, and its refused Origins and Hosts, may each be logged above DEBUG, and how
    // many addresses each of those throttles remembers.
    private val NOTICE_INTERVAL = 1.minutes
    private const val MAX_NOTICED_ADDRESSES = 1_000

    // How much of a refused Origin or Host its log line shows: room for any real one, a 253-character DNS name with a
    // scheme and a port, so the line still names what to allow, and no more of a client's text.
    private const val SHOWN_HEADER_CHARS = 270

    // A refused request's Origin or Host as its log line shows it: cut and escaped (see AgentValues.shownValue).
    private fun shownHeader(value: String?) = shownValue(value.orEmpty(), SHOWN_HEADER_CHARS)

    private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    private const val FRAME_ANCESTORS = "frame-ancestors"
    private const val X_FRAME_OPTIONS = "X-Frame-Options"

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
     * Where a login sends the browser: [next] when it is a page of the dashboard at [routeBase], or one of the site's
     * [outsidePages] (with any query), else the dashboard's base. A path only, not a URL, not a protocol-relative
     * `//host`, nor one a browser reads as one (`/\host`), with no `.` or `..` segment, and not the login page itself,
     * so a link to the login page can't send a browser off-site.
     */
    internal fun safeNext(
      next: String?,
      routeBase: String,
      outsidePages: Set<String> = emptySet(),
    ): String {
      val path = next.orEmpty()
      return if (isPlainPath(path) && isReturnable(path.substringBefore('?'), routeBase, outsidePages))
        path
      else
        routeBase.ifEmpty { "/" }
    }

    // Whether a login may return to [pagePath], the part of a path before its query: one of the site's pages outside
    // its base, or a page of the dashboard at [routeBase].
    private fun isReturnable(
      pagePath: String,
      routeBase: String,
      outsidePages: Set<String>,
    ) = pagePath in outsidePages || isDashboardPage(pagePath, routeBase)

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
    // but only to an https or localhost origin, and a script such as curl doesn't.
    internal const val SEC_FETCH_MODE = "Sec-Fetch-Mode"

    // Sent by a browser with each page navigation, over plain http too.
    internal const val UPGRADE_INSECURE_REQUESTS = "Upgrade-Insecure-Requests"

    // The headers a browser sends and a script such as curl doesn't (see isBrowser).
    private val BROWSER_HEADERS =
      listOf(SEC_FETCH_MODE, UPGRADE_INSECURE_REQUESTS, HttpHeaders.Origin, HttpHeaders.Referrer)

    /**
     * Whether [request] came from a browser, which must log in on the login page rather than with Basic Auth: it
     * carries a header a browser sends and a script doesn't. Fetch Metadata's [SEC_FETCH_MODE] alone missed every
     * browser on the dashboard's plain http at any address but localhost, where a browser doesn't send it. So also a
     * page navigation ([UPGRADE_INSECURE_REQUESTS], or [wantsPage]'s HTML), an Origin, which a browser sends with every
     * WebSocket handshake and every POST, and a Referer, which it sends with the page's own requests.
     */
    private fun isBrowser(request: ApplicationRequest) =
      BROWSER_HEADERS.any { it in request.headers } || wantsPage(request)

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
     * origin must be one a browser sends, an `http` or `https` scheme and a host with an optional port, and match an
     * [allowedOrigins] entry, for a dashboard behind a reverse proxy that rewrites Host, or name the host the request
     * was addressed to -- the dashboard's own page. That host check takes either scheme, since a dashboard behind a
     * reverse proxy that terminates TLS and passes the browser's Host on can't tell which one the browser used; an
     * [allowedOrigins] entry for the origin's host and port pins it, so with `https://dash.example.com` listed,
     * `http://dash.example.com` is refused, though its Host matches. Anything unparseable, including the opaque `null`
     * origin, is refused, and an entry that doesn't parse is ignored.
     *
     * This does not stop DNS rebinding, where the attacker's origin and the Host header agree; see [isHostAllowed].
     */
    internal fun isOriginAllowed(
      origin: String?,
      host: String?,
      allowedOrigins: Collection<String>,
    ): Boolean {
      if (origin == null) return true
      val sent = webOrigin(origin) ?: return false
      val allowed = allowedOrigins.mapNotNull { webOrigin(it.trim().trimEnd('/')) }
      return when {
        sent in allowed -> true
        allowed.any { authorityOf(it) == authorityOf(sent) } -> false
        else -> host != null && authorityOf(sent) == host.lowercase()
      }
    }

    // The default port of each scheme a browser's page can have, which a browser leaves out of an Origin.
    private val DEFAULT_PORTS = mapOf("http" to ":80", "https" to ":443")

    // [value] as a browser writes an origin, <scheme>://<host>[:<port>], lowercased and without its scheme's default
    // port; null unless it is an http or https URL of a host and an optional port alone, with no user info, path,
    // query, or fragment. The authority is read raw, so a host name a browser takes, with an underscore, isn't refused.
    private fun webOrigin(value: String): String? {
      val uri = runCatching { URI(value) }.getOrNull() ?: return null
      val scheme = uri.scheme?.lowercase()
      val defaultPort = DEFAULT_PORTS[scheme] ?: return null
      val authority = uri.rawAuthority?.lowercase().orEmpty()
      val hostOnly = authority.isNotEmpty() && '@' !in authority
      val nothingElse = uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
      return if (hostOnly && nothingElse) "$scheme://${authority.removeSuffix(defaultPort)}" else null
    }

    // The host and port of an origin webOrigin wrote.
    private fun authorityOf(origin: String) = origin.substringAfter("://")

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
