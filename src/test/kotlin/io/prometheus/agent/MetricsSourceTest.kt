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

package io.prometheus.agent

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.common.testConfigVals
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import website.registrySource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A path an embedded host serves from code, through a [MetricsSource], rather than from a URL: [AgentHttpService] asks
 * the source in-process, in a text format the scrape accepts, and treats what it writes as it treats a target's body,
 * with the path's filter, the size limit, and the scrape timeout. The builder's `source()` is covered in
 * `EmbeddedAgentConfigTest`, and a scrape through a proxy in `EmbeddedAgentApiTest`.
 */
class MetricsSourceTest : StringSpec() {
  // A mock agent with a real path manager, whose filter on /metrics drops denied_metric.
  private fun agent(
    maxContentLengthMBytes: Int = 10,
    scrapeTimeoutSecs: Int = 10,
  ): Agent {
    val options = mockk<AgentOptions>(relaxed = true)
    every { options.maxCacheSize } returns 100
    every { options.maxCacheAgeMins } returns 30
    every { options.maxCacheIdleMins } returns 10
    every { options.cacheCleanupIntervalMins } returns 5
    every { options.scrapeTimeoutSecs } returns scrapeTimeoutSecs
    every { options.minGzipSizeBytes } returns 1_000_000
    every { options.maxContentLengthMBytes } returns maxContentLengthMBytes
    every { options.httpClientTimeoutSecs } returns 90
    every { options.chunkContentSizeBytes } returns 32 * 1024
    every { options.metricsSources } returns emptyList()
    val grpc = mockk<AgentGrpcService>(relaxed = true)
    coEvery { grpc.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    return mockk<Agent>(relaxed = true).also { agent ->
      every { agent.options } returns options
      every { agent.configVals } returns testConfigVals(
        """
        agent {
          pathConfigs = []
          filters = [{ path = "metrics", metricNameAllow = [], metricNameDeny = ["denied_metric"] }]
          internal { cioTimeoutSecs = 90 }
        }
        proxy { auth = [] }
        """,
      )
      every { agent.isMetricsEnabled } returns false
      every { agent.isTestMode } returns true
      every { agent.grpcService } returns grpc
      every { agent.scrapeHistory } returns null
      val pathManager = AgentPathManager(agent)
      every { agent.pathManager } returns pathManager
    }
  }

  private fun request(
    accept: String = "",
    params: String = "",
  ) = scrapeRequest {
    agentId = "agent-1"
    scrapeId = 1L
    path = "metrics"
    this.accept = accept
    encodedQueryParams = params
    debugEnabled = true
  }

  // A scrape of /metrics, served by [source], as the proxy asks for it with [request].
  private suspend fun scraped(
    agent: Agent = agent(),
    request: ScrapeRequest = request(),
    source: MetricsSource,
  ): ScrapeResults {
    agent.pathManager.registerPath("metrics", IN_PROCESS_TARGET, metricsSource = source)
    val service = AgentHttpService(agent)
    return try {
      service.fetchScrapeUrl(request)
    } finally {
      service.close()
    }
  }

  init {
    "a path served by a MetricsSource should be scraped in-process, in the content type the source wrote" {
      val results =
        scraped { _, _, out ->
          out.write(BODY.toByteArray())
          OPEN_METRICS
        }

      results.apply {
        srStatusCode shouldBe 200
        srValidResponse.shouldBeTrue()
        srContentType shouldBe OPEN_METRICS
        srContentAsText shouldContain "kept_metric 1"
        srUrl shouldBe IN_PROCESS_TARGET
        scrapeCounterMsg shouldBe "success"
      }
    }

    "a source that names no content type should be taken to have written the Prometheus text format" {
      scraped { _, _, out ->
        out.write(BODY.toByteArray())
        ""
      }.srContentType shouldBe TEXT_FORMAT
    }

    // Prometheus asks for protobuf first when native histograms are on, which the agent can't carry, and asks an
    // exporter for some families only with name[] parameters, as the Prometheus client libraries read them.
    "the source should be asked for a text format the scrape accepts, and for the families named with name[]" {
      var accept = "?"
      var names = setOf("?")
      scraped(
        request =
          request(
            accept = "$PROTOBUF;q=0.7,text/plain;version=0.0.4;q=0.3",
            params = "name%5B%5D=kept_metric&debug=1&name%5B%5D=jvm_info",
          ),
      ) { a, n, out ->
        accept = a
        names = n
        out.write(BODY.toByteArray())
        TEXT_FORMAT
      }.srStatusCode shouldBe 200

      accept shouldBe "text/plain;version=0.0.4;q=0.3"
      names shouldBe setOf("kept_metric", "jvm_info")
    }

    "a scrape that names no families should ask the source for all of them" {
      var names = setOf("?")
      scraped { _, n, out ->
        names = n
        out.write(BODY.toByteArray())
        TEXT_FORMAT
      }
      names shouldBe emptySet()
    }

    "the path's metric filter should apply to what the source wrote" {
      val results =
        scraped { _, _, out ->
          out.write(BODY.toByteArray())
          TEXT_FORMAT
        }

      results.srContentAsText shouldContain "kept_metric"
      results.srContentAsText shouldNotContain "denied_metric"
      results.filteredSize shouldBeLessThan results.rawSize
    }

    "a source that throws should fail the scrape, saying why" {
      val results = scraped { _, _, _ -> throw IOException("registry closed") }

      results.apply {
        srStatusCode shouldBe 503
        srValidResponse.shouldBeFalse()
        srFailureReason shouldContain "registry closed"
        scrapeCounterMsg shouldBe "failure"
      }
    }

    // The limit is a memory bound, as it is on a target's body, so the source is stopped as it passes it.
    "a source that writes more than maxContentLengthMBytes should be stopped, and the scrape refused" {
      var written = 0L
      val results =
        scraped(agent(maxContentLengthMBytes = 1)) { _, _, out ->
          val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
          repeat(100) {
            out.write(chunk)
            written += chunk.size
          }
          TEXT_FORMAT
        }

      results.srStatusCode shouldBe 413
      results.srFailureReason shouldContain "maximum"
      written shouldBeLessThan 2L * 1024 * 1024
    }

    "a source that outlasts the scrape timeout should be interrupted, and the scrape time out" {
      val interrupted = AtomicBoolean()
      val started = TimeSource.Monotonic.markNow()
      val results =
        scraped(agent(scrapeTimeoutSecs = 1)) { _, _, _ ->
          try {
            Thread.sleep(30_000)
          } catch (e: InterruptedException) {
            interrupted.set(true)
            throw e
          }
          TEXT_FORMAT
        }

      results.srStatusCode shouldBe 408
      results.scrapeCounterMsg shouldBe "timed_out"
      started.elapsedNow() shouldBeLessThan 10.seconds
      // The scrape answers without waiting for the call it gave up on, which the interrupt then ends.
      eventually(5.seconds) { interrupted.get().shouldBeTrue() }
    }

    // A source stuck in a call that ignores interrupts, such as a socket read, mustn't hold its scrape, the agent's
    // other scrapes, or its shutdown: the scrape times out on time, and the source isn't called again until it returns.
    "a source that ignores the timeout's interrupt should time out on time, and not be called again until it returns" {
      val release = CountDownLatch(1)
      val calls = AtomicInteger()
      // Let go in any case, so a regression fails the test rather than hanging it.
      thread(isDaemon = true) {
        Thread.sleep(15_000)
        release.countDown()
      }
      val agent = agent(scrapeTimeoutSecs = 1)
      agent.pathManager.registerPath(
        "metrics",
        IN_PROCESS_TARGET,
        metricsSource = { _, _, out ->
          calls.incrementAndGet()
          while (release.count > 0) {
            try {
              release.await()
            } catch (_: InterruptedException) {
              // Ignored, as a socket read ignores it
            }
          }
          out.write(BODY.toByteArray())
          TEXT_FORMAT
        },
      )
      val service = AgentHttpService(agent)
      try {
        val started = TimeSource.Monotonic.markNow()
        service.fetchScrapeUrl(request()).srStatusCode shouldBe 408
        started.elapsedNow() shouldBeLessThan 5.seconds

        val refused = service.fetchScrapeUrl(request())
        refused.srStatusCode shouldBe 503
        refused.srFailureReason shouldContain "hasn't returned"
        calls.get() shouldBe 1

        release.countDown()
        eventually(5.seconds) { service.fetchScrapeUrl(request()).srStatusCode shouldBe 200 }
      } finally {
        release.countDown()
        service.close()
      }
    }

    // A host's metrics code can throw an Error, such as a Kotlin TODO() or a NoSuchMethodError from a library's
    // version: it fails the scrape, as an exception does, and the agent goes on.
    "a source that throws an Error should fail only its scrape" {
      val results = scraped { _, _, _ -> throw NotImplementedError("gauge not done") }

      results.srStatusCode shouldBe 503
      results.srFailureReason shouldContain "NotImplementedError: gauge not done"
      results.scrapeCounterMsg shouldBe "failure"
    }

    // Thrown by the source itself, as CompletableFuture.join() or BlockingQueue.take() can, not by the scrape's
    // timeout.
    "a source that throws its own CancellationException or InterruptedException should fail its scrape" {
      listOf(
        java.util.concurrent.CancellationException("future cancelled"),
        InterruptedException("take interrupted"),
      ).forEach { thrown ->
        val results = scraped { _, _, _ -> throw thrown }

        results.srStatusCode shouldBe 503
        results.srFailureReason shouldContain "${thrown::class.simpleName}: ${thrown.message}"
      }
    }

    "a source that writes a byte at a time should be stopped at maxContentLengthMBytes too" {
      var written = 0L
      val results =
        scraped(agent(maxContentLengthMBytes = 1)) { _, _, out ->
          repeat(2 * 1024 * 1024) {
            out.write('a'.code)
            written++
          }
          TEXT_FORMAT
        }

      results.srStatusCode shouldBe 413
      written shouldBe 1024L * 1024
    }

    // The docs site's example, run against a real registry: what an embedder copies writes what the scrape asks for.
    "the docs site's registry source should write in the format asked for, and only the families named" {
      val registry = PrometheusRegistry()
      Counter.builder().name("orders").register(registry).inc()
      Gauge.builder().name("queue_depth").register(registry).set(3.0)
      val source = registrySource(registry)

      val text = ByteArrayOutputStream()
      source.write("", emptySet(), text) shouldContain "version=0.0.4"
      text.toString() shouldContain "orders_total 1.0"
      text.toString() shouldContain "queue_depth 3.0"

      val openMetrics = ByteArrayOutputStream()
      source.write(OPEN_METRICS, setOf("queue_depth"), openMetrics) shouldContain "openmetrics"
      openMetrics.toString() shouldContain "queue_depth 3.0"
      openMetrics.toString() shouldNotContain "orders"
    }

    // As for a URL: a click on the dashboard's Scrape now isn't a scrape Prometheus made.
    "a dashboard fetch of an in-process path should go through its source, filtered, and count nothing" {
      val agent = agent()
      val filter = agent.pathManager.filtersByPath.getValue("metrics")
      val service = AgentHttpService(agent)

      val results =
        try {
          service.fetchForDashboard(IN_PROCESS_TARGET, filter, 5.seconds) { _, _, out ->
            out.write(BODY.toByteArray())
            TEXT_FORMAT
          }
        } finally {
          service.close()
        }

      results.srStatusCode shouldBe 200
      results.srContentAsText shouldContain "kept_metric"
      results.srContentAsText shouldNotContain "denied_metric"
      filter.linesDropped shouldBe 0
      verify(exactly = 0) { agent.updateScrapeCounter(any()) }
      verify(exactly = 0) { agent.metrics(any()) }
    }
  }

  private companion object {
    const val PROTOBUF = "application/vnd.google.protobuf;proto=io.prometheus.client.MetricFamily;encoding=delimited"
    const val OPEN_METRICS = "application/openmetrics-text; version=1.0.0; charset=utf-8"
    const val TEXT_FORMAT = "text/plain; version=0.0.4; charset=utf-8"

    val BODY =
      """
      # TYPE kept_metric gauge
      kept_metric 1
      # TYPE denied_metric gauge
      denied_metric 2

      """.trimIndent()
  }
}
