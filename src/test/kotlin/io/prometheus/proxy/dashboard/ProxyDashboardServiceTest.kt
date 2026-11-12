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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.hostAllowlist
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.isHostAllowed
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.isOriginAllowed
import io.prometheus.proxy.dashboard.ProxyDashboardService.SnapshotCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The WebSocket Origin rule. The same-origin policy does not cover WebSockets, so a page on another site can open
 * one to the dashboard from an operator's browser; the Origin header is what tells the dashboard's own page apart
 * from a foreign one. The live refusal is covered by `ProxyWebDashboardTest`.
 *
 * Also the snapshot cache every page and WebSocket render reads from, which bounds how often a client can make the
 * proxy collect. `ProxyWebDashboardTest` covers the live routes reading through it.
 */
class ProxyDashboardServiceTest : StringSpec() {
  /** A collect that counts its calls, returns a new snapshot each time, and, given a [gate], waits on it first. */
  private class CountingCollect(
    private val gate: CompletableDeferred<Unit>? = null,
  ) {
    private val calls = AtomicInt(0)
    val count: Int get() = calls.load()

    suspend fun next(): ProxySnapshot {
      val call = calls.incrementAndFetch()
      gate?.await()
      return DashboardFixtures.snapshot(agents = [DashboardFixtures.agentView(agentName = "collect-$call")])
    }
  }

  private fun allowed(
    host: String?,
    allowedHosts: List<String>,
    allowedOrigins: List<String> = emptyList(),
  ) = isHostAllowed(host, hostAllowlist(allowedHosts, allowedOrigins))

  init {
    "a request with no Origin header should be allowed, since only browsers send one" {
      isOriginAllowed(origin = null, host = "proxy:8094", allowedOrigins = emptyList()).shouldBeTrue()
    }

    "an origin naming the dashboard's own host should be allowed" {
      isOriginAllowed("http://proxy:8094", "proxy:8094", emptyList()).shouldBeTrue()
      // Hosts are case-insensitive, and a default port is omitted from both headers.
      isOriginAllowed("https://Proxy.Example.com", "proxy.example.com", emptyList()).shouldBeTrue()
    }

    "a foreign origin should be refused" {
      isOriginAllowed("https://evil.example.com", "proxy:8094", emptyList()).shouldBeFalse()
      // The same host on another port is a different origin.
      isOriginAllowed("http://proxy:9999", "proxy:8094", emptyList()).shouldBeFalse()
    }

    "an origin listed in allowedOrigins should be allowed" {
      // Behind a reverse proxy the browser's origin names the public host, not the Host the proxy receives.
      isOriginAllowed("https://dash.example.com", "10.0.0.5:8094", listOf("https://dash.example.com")).shouldBeTrue()
      // Case and a trailing slash in the configured entry do not matter.
      isOriginAllowed("https://dash.example.com", "10.0.0.5:8094", listOf("HTTPS://Dash.Example.com/")).shouldBeTrue()
      isOriginAllowed("https://other.example.com", "10.0.0.5:8094", listOf("https://dash.example.com")).shouldBeFalse()
    }

    "an opaque or malformed origin should be refused" {
      // Sandboxed iframes and file:// pages send the literal "null".
      isOriginAllowed("null", "proxy:8094", emptyList()).shouldBeFalse()
      isOriginAllowed("not a url", "proxy:8094", emptyList()).shouldBeFalse()
      isOriginAllowed("http://proxy:8094", host = null, allowedOrigins = emptyList()).shouldBeFalse()
    }

    // ==================== Host allowlist: see ProxyDashboardService.isHostAllowed ====================

    // Opt-in: with no allowedHosts, there is no allowlist and every Host is allowed, even with allowedOrigins set.
    "with no allowedHosts configured every Host should be allowed" {
      hostAllowlist(emptyList(), listOf("https://dash.example.com")).shouldBeNull()
      allowed("evil.example.com", emptyList()).shouldBeTrue()
    }

    "with allowedHosts configured an unknown Host name should be refused" {
      allowed("evil.example.com", listOf("dash.internal")).shouldBeFalse()
      allowed("evil.example.com:8094", listOf("dash.internal")).shouldBeFalse()
    }

    "with allowedHosts configured a listed name should be allowed, ignoring port, case, and a trailing dot" {
      val hosts = listOf("Dash.Internal", "metrics.example.com:8094")
      allowed("dash.internal", hosts).shouldBeTrue()
      allowed("DASH.internal.:8094", hosts).shouldBeTrue()
      allowed("metrics.example.com", hosts).shouldBeTrue()
    }

    // A rebound browser sends the attacker's name, never an address, so addresses and localhost stay reachable.
    "with allowedHosts configured IP addresses and localhost should always be allowed" {
      val hosts = listOf("dash.internal")
      allowed("10.0.0.5:8094", hosts).shouldBeTrue()
      allowed("[::1]:8094", hosts).shouldBeTrue()
      allowed("localhost:8094", hosts).shouldBeTrue()
    }

    // Behind a reverse proxy that forwards the public Host, that name is already configured as an allowed origin.
    "with allowedHosts configured the hosts of allowedOrigins should be allowed" {
      allowed("dash.example.com", listOf("dash.internal"), listOf("https://dash.example.com")).shouldBeTrue()
    }

    "an allowedHosts or allowedOrigins entry that does not parse should be ignored" {
      hostAllowlist(listOf("dash.internal", ""), listOf("not a url")) shouldBe setOf("localhost", "dash.internal")
    }

    // Browsers always send Host, so a request without one is not a rebound browser.
    "with allowedHosts configured a request with no Host header should be allowed" {
      allowed(null, listOf("dash.internal")).shouldBeTrue()
    }

    "with allowedHosts configured an empty or malformed Host should be refused" {
      allowed("", listOf("dash.internal")).shouldBeFalse()
      allowed("not a host:port:junk", listOf("dash.internal")).shouldBeFalse()
    }

    // ==================== Snapshot cache: see ProxyDashboardService.SnapshotCache ====================

    // A page GET or a WebSocket message on the unauthenticated port must not be able to drive a collect, which holds
    // the path-map monitor every scrape takes (November #18). A test clock rather than sleeps, so expiry is exact.
    "the snapshot cache should collect once for any number of reads within the refresh interval" {
      val clock = TestTimeSource()
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, clock) { source.next() }

      val first = cache.recent()
      repeat(READS) { cache.recent() shouldBeSameInstanceAs first }
      clock += REFRESH - 1.milliseconds
      cache.recent() shouldBeSameInstanceAs first
      source.count shouldBe 1
    }

    "the snapshot cache should collect again once the refresh interval has passed" {
      val clock = TestTimeSource()
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, clock) { source.next() }

      val first = cache.recent()
      clock += REFRESH
      cache.recent() shouldNotBeSameInstanceAs first
      source.count shouldBe 2
    }

    // The push loop's timer tick: it must sample drifting values even when a read has just filled the cache, and what
    // it collects is what the next read renders.
    "a fresh collect should always collect, and serve the reads after it" {
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      cache.recent()
      val pushed = cache.fresh()
      source.count shouldBe 2
      cache.recent() shouldBeSameInstanceAs pushed
      source.count shouldBe 2
    }

    // A new agent or path must show on the next page, not up to a refresh interval later.
    "a topology change should make the next read collect" {
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      val before = cache.recent()
      cache.invalidate()
      cache.recent() shouldNotBeSameInstanceAs before
      source.count shouldBe 2
    }

    // A collect that began before a topology change may have read the map before it, so when it finishes after the
    // change its snapshot must not be served as current. Clearing the cache on the change alone lost this race: the
    // late collect refilled it, and with no session open, nothing else would collect for a whole interval.
    "a collect overtaken by a topology change should not be reused" {
      val gate = CompletableDeferred<Unit>()
      val source = CountingCollect(gate)
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      coroutineScope {
        // Undispatched, so the read is parked inside its collect before the change below lands.
        val overtaken = async(start = CoroutineStart.UNDISPATCHED) { cache.recent() }
        source.count shouldBe 1
        cache.invalidate()
        gate.complete(Unit)
        val stale = overtaken.await()

        cache.recent() shouldNotBeSameInstanceAs stale
        source.count shouldBe 2
      }
    }

    // A burst of requests arriving while the cache is empty must not each run a collect: the first collects, and the
    // rest wait for it and render what it collected.
    "concurrent reads of an empty cache should share one collect" {
      val gate = CompletableDeferred<Unit>()
      val source = CountingCollect(gate)
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      coroutineScope {
        // Undispatched, so every read has reached the cache before the first collect is let finish.
        val reads = List(READS) { async(start = CoroutineStart.UNDISPATCHED) { cache.recent() } }
        gate.complete(Unit)
        val snapshots = reads.awaitAll()

        source.count shouldBe 1
        snapshots.forEach { it shouldBeSameInstanceAs snapshots.first() }
      }
    }
  }

  companion object {
    // The default proxy.dashboard.refreshIntervalSecs.
    private val REFRESH = 2.seconds
    private const val READS = 10
  }
}
