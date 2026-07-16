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

// Start an embeddedServer with start(wait = false) and poll until it serves HTTP. A bare
// TCP-connect probe isn't enough: CIO's listening socket can accept and immediately close
// while the routing pipeline is still being installed, surfacing as EOFException or empty
// bodies on the next real request. Sending a real HTTP/1.0 line and waiting for the "HTTP/"
// reply means the request pipeline is live before we hand back the port.
//
// A *single* HTTP reply is still not enough under a full-suite load spike: there is a narrow
// window where the engine answers one request and then briefly stalls or resets the next while
// the application module finishes installing, so the first real scrape can land in that gap and
// see a transient non-200 (e.g. a default 404 before the test's route is matchable). To close
// that window we require [stableProbes] *consecutive* successful HTTP replies, each on a fresh
// connection with a short gap; any failure resets the streak. This only ever delays the return
// (never advances it), so it cannot make a healthy server look unready — it just refuses to hand
// back the port until the server has answered cleanly several times in a row.
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
  start(wait = false)
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
