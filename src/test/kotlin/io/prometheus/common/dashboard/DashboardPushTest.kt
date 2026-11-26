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

package io.prometheus.common.dashboard

import ch.qos.logback.classic.Level
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [DashboardServer]'s push loop, against a site the spec stands in for, with no proxy or agent: a change pushes to
 * every page at once, a collect that throws doesn't end the loop, a Log out closes only its own pages and one naming no
 * session wakes nothing, and a page that has stopped reading holds up no other page's pushes, and is closed, its slot
 * given up, once its buffer is full.
 *
 * Every server but the last case's ticks once an hour, so a frame a case waits for can come only from the wake it
 * tests, and a case waits [PATIENCE] for it, failing within seconds when nothing wakes the loop. The pages served by a
 * real proxy and agent are `ProxyWebDashboardTest`'s, `ProxyWebDashboardLoginTest`'s, and `AgentWebDashboardTest`'s.
 */
class DashboardPushTest : StringSpec() {
  // A site the spec stands in for. Its snapshot is its collect's number, each collect is announced on [attempts], and
  // the next [failures] collects throw. A page showing BIG is sent one frame larger than every buffer between the
  // server and the page, and small ones after it, so a page that never reads stalls its socket's writer for good.
  private class CountingSite : DashboardSite<String, Int> {
    val changeFlow = MutableSharedFlow<Unit>()
    override val changes: Flow<Any?> = changeFlow

    override val outsidePages = emptySet<String>()

    override val home: String? = null

    private val collects = AtomicInt(0)
    private val attempts = Channel<Int>(Channel.UNLIMITED)

    @Volatile
    var failures = 0

    // Rendered for a page showing BIG, and queued for it: a push after it knows the send went through.
    val bigRendered = CompletableDeferred<Unit>()
    val bigQueued = CompletableDeferred<Unit>()

    val collectCount: Int get() = collects.load()

    suspend fun nextAttempt(): Int = withTimeout(PATIENCE) { attempts.receive() }

    override suspend fun collect(): Int {
      val attempt = collects.incrementAndFetch()
      attempts.send(attempt)
      if (failures > 0) {
        failures -= 1
        error("Collect $attempt failed")
      }
      return attempt
    }

    override fun Route.pages(server: DashboardServer<String, Int>) = Unit

    override fun parseView(message: String) = message

    override fun frame(
      snapshot: Int,
      view: String?,
      sent: Any?,
      routeBase: String,
    ): DashboardFrame {
      if (view == BIG && sent == null)
        return DashboardFrame(BIG_FRAME, BIG).also { bigRendered.complete(Unit) }
      if (view == BIG)
        bigQueued.complete(Unit)
      return DashboardFrame("snapshot $snapshot", sent)
    }
  }

  // One case's server, its site and sessions, and a client that follows no redirect.
  private class Fixture(
    val port: Int,
    val server: DashboardServer<String, Int>,
    val site: CountingSite,
    val logins: DashboardSessions,
    val client: HttpClient,
  ) {
    val base = "http://$LOOPBACK_HOST:$port"
    val pages = mutableListOf<DefaultClientWebSocketSession>()
    private val cookie = DashboardServer.cookieName(ROLE, port)

    private fun HttpRequestBuilder.session(token: String?) {
      token?.also { header(HttpHeaders.Cookie, "$cookie=$it") }
    }

    // Waits until the server answers through its routes: an engine still installing them answers everything with 404.
    suspend fun awaitServing() =
      withTimeout(STARTUP_PATIENCE) {
        while (runCatching { client.get("$base/dashboard/session").status }.getOrNull() !in SERVING) {
          delay(20.milliseconds)
        }
      }

    // A page's socket, with [token]'s login when there is one.
    suspend fun page(token: String? = null): DefaultClientWebSocketSession =
      client.webSocketSession("ws://$LOOPBACK_HOST:$port/dashboard/events") { session(token) }.also { pages += it }

    suspend fun logOut(token: String?): HttpStatusCode = client.post("$base/dashboard/logout") { session(token) }.status

    // One of the site's changes, once the server watches them: the flow keeps none for a later subscriber.
    suspend fun change() {
      site.changeFlow.subscriptionCount.first { it > 0 }
      site.changeFlow.emit(Unit)
    }

    // A page's socket, with [token]'s login when there is one, that completes the handshake, says it shows BIG, and
    // then never reads. Its receive buffer is a kilobyte, and BIG's frame is larger than every buffer between, so the
    // server's writer for it stalls for good. Returns once that frame is queued for it.
    suspend fun stalledPage(token: String?): Socket {
      // Set before connecting, so the window it offers is that small from the start.
      val socket =
        Socket().apply {
          receiveBufferSize = STALLED_RECEIVE_BUFFER_BYTES
          soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        }
      socket.connect(InetSocketAddress(LOOPBACK_HOST, port), HANDSHAKE_TIMEOUT_MILLIS)
      try {
        socket.handshake(token?.let { "$cookie=$it" })
        socket.getOutputStream().apply {
          write(maskedText(BIG))
          flush()
        }
        withTimeout(PATIENCE) { site.bigRendered.await() }
        // The next push to the page renders after the big frame's send, which is then behind it.
        change()
        withTimeout(PATIENCE) { site.bigQueued.await() }
      } catch (e: Throwable) {
        socket.close()
        throw e
      }
      return socket
    }

    // Opens the WebSocket, reading the server's answer up to its blank line and no further.
    private fun Socket.handshake(cookieValue: String?) {
      val request =
        buildString {
          append("GET /dashboard/events HTTP/1.1\r\n")
          append("Host: $LOOPBACK_HOST:$port\r\n")
          append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
          append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n")
          cookieValue?.also { append("Cookie: $it\r\n") }
          append("\r\n")
        }
      getOutputStream().apply {
        write(request.toByteArray())
        flush()
      }
      val answer = StringBuilder()
      while (!answer.endsWith("\r\n\r\n")) {
        val byte = getInputStream().read()
        check(byte >= 0) { "The server closed the socket during the handshake: $answer" }
        answer.append(byte.toChar())
      }
      answer.toString() shouldStartWith "HTTP/1.1 101"
    }
  }

  // A server with a login when [login], a site, and a client, for [block]; all stopped after it.
  private suspend fun withServer(
    port: Int,
    refresh: Duration = 1.hours,
    login: Boolean = false,
    logins: DashboardSessions = DashboardSessions(),
    maxSessions: Int = 10,
    block: suspend Fixture.() -> Unit,
  ) {
    val site = CountingSite()
    val settings =
      DashboardSettings(
        role = ROLE,
        host = LOOPBACK_HOST,
        port = port,
        path = "dashboard",
        refreshInterval = refresh,
        maxSessions = maxSessions,
        allowedOrigins = emptyList(),
        allowedHosts = emptyList(),
        framing = Framing.NONE,
        login = if (login) DashboardLogin("admin", "not used: the spec makes its sessions itself") else null,
        loginHeading = "Push test",
      )
    val server = DashboardServer(settings, site, logins)
    val client =
      HttpClient(CIO) {
        install(WebSockets)
        followRedirects = false
      }
    server.start()
    val fixture = Fixture(port, server, site, logins, client)
    try {
      fixture.awaitServing()
      fixture.block()
    } finally {
      fixture.pages.forEach { runCatching { it.cancel() } }
      client.close()
      server.stop()
    }
  }

  init {
    // A change, such as an agent connecting, reaches every open page at once, collected afresh. The hour's tick can't
    // bring it.
    "a change should push a fresh snapshot to every open page at once, not at the next tick" {
      withServer(TestPorts.DASHBOARD_PUSH_CHANGE_PORT) {
        val open = [page(), page()]
        open.forEach { it.nextText() shouldBe "snapshot 1" }

        change()

        open.forEach { it.nextText() shouldBe "snapshot 2" }
      }
    }

    // A collect that threw ended the loop for good, printed to stderr: no page got another push until a restart, and
    // each kept showing live.
    "a collect that throws should be logged once at ERROR, and the loop should push at its next wake" {
      withServer(TestPorts.DASHBOARD_PUSH_COLLECT_FAILURE_PORT) {
        val page = page()
        page.nextText() shouldBe "snapshot 1"
        site.nextAttempt() shouldBe 1

        val logged =
          captureLogs("io.prometheus.common.dashboard") {
            site.failures = 2
            change()
            site.nextAttempt() shouldBe 2
            change()
            site.nextAttempt() shouldBe 3
            change()
            page.nextText() shouldBe "snapshot 4"
          }

        val errors = logged.filter { it.level == Level.ERROR }
        errors.size shouldBe 1
        errors.single().formattedMessage shouldContain "Failed to collect the proxy dashboard's snapshot"
      }
    }

    // Log out needs no login. It woke the push loop whatever it named, so anyone who could reach the port could make
    // the dashboard collect a snapshot and push to every page once per request.
    "a Log out naming no session should cost nothing, and one naming a session should close its pages and only its" {
      withServer(TestPorts.DASHBOARD_PUSH_LOGOUT_PORT, login = true) {
        val staying = page(logins.create())
        val leavingLogin = logins.create()
        val leaving = page(leavingLogin)
        [staying, leaving].forEach { it.nextText() shouldBe "snapshot 1" }

        repeat(LOGOUT_FLOOD) { logOut(if (it % 2 == 0) null else "never-issued") shouldBe HttpStatusCode.SeeOther }
        logOut(leavingLogin) shouldBe HttpStatusCode.SeeOther

        leaving.closedWith() shouldBe CloseReason.Codes.VIOLATED_POLICY
        server.sessionsOpen shouldBe 1
        // Neither the flood nor the Log out collected, or pushed to the page that stays, which still gets a change.
        site.collectCount shouldBe 1
        staying.incoming.tryReceive().getOrNull().shouldBeNull()
        change()
        staying.nextText() shouldBe "snapshot 2"
      }
    }

    // Closing a page waited, in Ktor's flush, for every frame queued ahead of the close to be written, which for a page
    // that has stopped reading is for as long as its TCP connection lives: every other page froze, still showing live.
    "a Log out of a page that has stopped reading should hold up no other page's pushes" {
      withServer(TestPorts.DASHBOARD_PUSH_STALLED_LOGOUT_PORT, login = true) {
        val stalledLogin = logins.create()
        stalledPage(stalledLogin).use {
          val reader = page(logins.create())
          reader.nextText() shouldStartWith "snapshot"

          logOut(stalledLogin) shouldBe HttpStatusCode.SeeOther

          repeat(3) {
            change()
            reader.nextText() shouldStartWith "snapshot"
          }
          server.sessionsOpen shouldBe 1
        }
      }
    }

    // The push loop closes a page whose login has ended by itself, by expiring or a restart's losing it, and must not
    // wait on that close either.
    "a page that has stopped reading should hold up no other page's pushes when the push loop finds its login ended" {
      val clock = TestTimeSource()
      val logins = DashboardSessions(maxAge = 1.hours, timeSource = clock)
      withServer(TestPorts.DASHBOARD_PUSH_STALLED_EXPIRY_PORT, login = true, logins = logins) {
        val stalledLogin = logins.create()
        clock += 30.minutes
        val readerLogin = logins.create()
        stalledPage(stalledLogin).use {
          val reader = page(readerLogin)
          reader.nextText() shouldStartWith "snapshot"

          // The stalled page's login is now 70 minutes old, past its hour, and the reader's 40 minutes.
          clock += 40.minutes
          repeat(3) {
            change()
            reader.nextText() shouldStartWith "snapshot"
          }

          server.sessionsOpen shouldBe 1
        }
      }
    }

    // Once a page's outgoing buffer overflowed, Ktor's pinger stopped without closing it, and nothing else did: it held
    // a slot of maxSessions, and was rendered for on every push, for as long as its TCP connection lived. Its pinger's
    // first ping is 15 seconds away, so this case can't pass by it.
    "a page that stops reading should be closed once its buffer is full, giving up its slot" {
      withServer(TestPorts.DASHBOARD_PUSH_OVERFLOW_PORT, refresh = 50.milliseconds, maxSessions = 1) {
        stalledPage(token = null).use {
          eventually(10.seconds) { server.sessionsOpen shouldBe 0 }
          // Its slot is free, so the one page maxSessions allows is let in, and pushed to.
          eventually(10.seconds) { page().nextText() shouldStartWith "snapshot" }
        }
      }
    }
  }

  companion object {
    private const val ROLE = "proxy"
    private const val BIG = "big"
    private const val LOGOUT_FLOOD = 100
    private const val STALLED_RECEIVE_BUFFER_BYTES = 1024
    private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000

    // How long a case waits for a frame or a close it expects, well under the hour's tick.
    private val PATIENCE = 5.seconds
    private val STARTUP_PATIENCE = 30.seconds

    // What /dashboard/session answers once the routes are in, without a login and with one.
    private val SERVING = setOf(HttpStatusCode.NoContent, HttpStatusCode.Unauthorized)

    // Larger than a socket's send and receive buffers and Ktor's own together, which come to a few MB at most.
    private val BIG_FRAME by lazy { "x".repeat(16 * 1024 * 1024) }

    // The next frame's text, within PATIENCE.
    private suspend fun DefaultClientWebSocketSession.nextText(): String =
      withTimeout(PATIENCE) { (incoming.receive() as Frame.Text).readText() }

    // Waits, within PATIENCE, for the server to close this socket, and returns why it did.
    private suspend fun DefaultClientWebSocketSession.closedWith(): CloseReason.Codes? {
      withTimeout(PATIENCE) {
        while (!incoming.receiveCatching().isClosed) {
          // A frame pushed before the close; the close follows.
        }
      }
      return closeReason.await()?.knownReason
    }

    // A client's text frame, masked as every frame a client sends must be.
    private fun maskedText(text: String): ByteArray {
      val payload = text.toByteArray()
      require(payload.size < 126) { "a frame this short has a one-byte length" }
      val mask = byteArrayOf(0x1d, 0x2c, 0x3b, 0x4a)
      val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
      return byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask + masked
    }
  }
}
