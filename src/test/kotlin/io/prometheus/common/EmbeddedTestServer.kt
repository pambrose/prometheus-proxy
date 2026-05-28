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
// reply means routing is live before we hand back the port.
//
// The deadline is generous because it is only a ceiling, not a wait: the loop returns the
// instant the server responds, so a healthy server still returns in milliseconds. The extra
// headroom absorbs CPU contention during a full test-suite run, where many embedded servers
// and the gRPC harness compete for cores and a fresh CIO server can take several seconds to
// start answering. A tighter default (5s) caused intermittent "did not start serving HTTP"
// failures on whichever embedded-server test happened to run during a load spike.
suspend fun EmbeddedServer<*, *>.startAndAwaitReady(timeout: Duration = 30.seconds): Int {
  start(wait = false)
  val port = engine.resolvedConnectors().first().port
  val deadline = Monotonic.markNow() + timeout
  while (Monotonic.markNow() < deadline) {
    try {
      Socket().use { sock ->
        sock.connect(InetSocketAddress("localhost", port), 200)
        sock.soTimeout = 200
        sock.getOutputStream().apply {
          write("GET /__readiness__ HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
          flush()
        }
        val reply = sock.getInputStream().readNBytes(12).decodeToString()
        if (reply.startsWith("HTTP/")) return port
      }
    } catch (_: IOException) {
      // not ready yet
    }
    delay(20.milliseconds)
  }
  error("Embedded server on port $port did not start serving HTTP within $timeout")
}
