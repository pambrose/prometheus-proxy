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

@file:Suppress("UndocumentedPublicFunction")

package io.prometheus.common

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic

// Bind test servers to loopback explicitly rather than taking Ktor's default wildcard (0.0.0.0).
//
// With a wildcard bind, `port = 0` asks the OS for a port free on *0.0.0.0*, which does not conflict
// with another process already listening on 127.0.0.1 at that same port -- different addresses, so
// both binds succeed. This box routinely has a dozen-plus JVMs (Gradle daemons among them) holding
// ephemeral loopback ports, and when the OS hands us one of those, BSD delivers every localhost
// connection to the *more specific* listener. Our server is then live on a port it can never receive
// traffic on: connections are accepted by the other process and dropped, so the readiness probe sees
// a clean EOF forever and the spec fails after the full timeout.
//
// Binding 127.0.0.1 makes the OS pick a port free on loopback itself, so the collision cannot happen.
// Measured over 6000 server starts on this machine: 8 failures with the wildcard bind, 0 with this.
const val LOOPBACK_HOST = "127.0.0.1"

// Start an embeddedServer with start(wait = false) and poll until it serves HTTP. A bare
// TCP-connect probe isn't enough: CIO's listening socket can accept and immediately close
// while the routing pipeline is still being installed, surfacing as EOFException or empty
// bodies on the next real request. Sending a real HTTP/1.0 line and waiting for the "HTTP/"
// reply means the request pipeline is live before we hand back the port.
//
// A *single* HTTP reply is still not enough under a full-suite load spike: the engine can answer
// one request and then briefly stall or reset the next. [stableProbes] *consecutive* successful
// replies, each on a fresh connection with a short gap, ride that out; any failure resets the
// streak. This only ever delays the return (never advances it), so it cannot make a healthy server
// look unready.
//
// Note what the streak can NOT do: detect whether routing is installed. A probe counts any HTTP
// status line as success, and an engine that is accepting connections but has not yet installed
// the application module answers *every* request with 404 -- byte-identical to the 404 a fully
// configured server returns for the probe's own unrouted /__readiness__ path. Three probes against
// an unrouted engine are three indistinguishable successes, so the streak alone let specs race the
// module install and see a 404 with their route handler never invoked. startSuspend() above is what
// actually closes that window.
//
// The deadline is generous because it is only a ceiling, not a wait: a healthy server satisfies
// the streak in tens of milliseconds. The extra headroom absorbs CPU contention during a full
// test-suite run, where many embedded servers and the gRPC harness compete for cores and a fresh
// CIO server can take several seconds to start answering. Raised to 60s because on heavily loaded /
// fewer-core machines the 30s ceiling could still be exceeded before the server answered stably;
// since this only ever delays the return on a struggling server, the extra headroom is free for
// healthy runs.
suspend fun EmbeddedServer<*, *>.startAndAwaitReady(
  timeout: Duration = 60.seconds,
  stableProbes: Int = 3,
): Int {
  // startSuspend() -- unlike start() -- does not return until the application module has been
  // installed, so routing is guaranteed present before the first probe below.
  //
  // This matters because probeServesHttp() cannot detect routing readiness on its own: it accepts any
  // HTTP status line, and an engine that is accepting connections but has not yet installed routing
  // answers *every* request with 404 -- indistinguishable from the 404 a fully-configured server
  // returns for the probe's own unrouted /__readiness__ path. With start(wait = false), a spec could
  // therefore pass readiness and still race the module install, and its first real request would 404
  // with the route handler never invoked. That is the intermittent failure behind assertions like
  // `requestCount shouldBe 1` seeing 0 and `srValidResponse` being false.
  startSuspend(wait = false)
  val port = engine.resolvedConnectors().first().port
  val deadline = Monotonic.markNow() + timeout
  var consecutive = 0
  while (Monotonic.markNow() < deadline) {
    if (probeServesHttp(port)) {
      consecutive++
      if (consecutive >= stableProbes) return port
    } else {
      consecutive = 0
    }
    delay(20.milliseconds)
  }
  error("Embedded server on port $port did not stably serve HTTP within $timeout")
}

// Single readiness probe: open a fresh connection, send a real HTTP/1.0 request line, and report
// whether the server answers with an HTTP status line. Any connect/read failure (the accept-then-
// close window, a reset, a read timeout) returns false so the caller's streak resets.
private fun probeServesHttp(port: Int): Boolean =
  try {
    Socket().use { sock ->
      sock.connect(InetSocketAddress("localhost", port), 200)
      sock.soTimeout = 200
      sock.getOutputStream().apply {
        write("GET /__readiness__ HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
        flush()
      }
      sock.getInputStream().readNBytes(12).decodeToString().startsWith("HTTP/")
    }
  } catch (_: IOException) {
    false
  }
