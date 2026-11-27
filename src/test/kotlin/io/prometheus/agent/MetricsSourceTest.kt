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

import ch.qos.logback.classic.Level
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
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
import io.prometheus.common.captureLogs
import io.prometheus.common.testConfigVals
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import website.EmbeddedAgentSourceJavaExample
import website.runWorker
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
      every { agent.collectsMetrics } returns false
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
    path: String = "metrics",
    timeout: Duration = Duration.ZERO,
  ) = scrapeRequest {
    agentId = "agent-1"
    scrapeId = 1L
    this.path = path
    this.accept = accept
    encodedQueryParams = params
    debugEnabled = true
    scrapeTimeoutMillis = timeout.inWholeMilliseconds
  }

  // A source stuck in a call that ignores interrupts, as a socket read does, until [release] counts down, which a
  // daemon thread does after 15 seconds in any case, so a regression fails its test rather than hanging it. [calls]
  // counts its calls.
  private fun stuckSource(
    release: CountDownLatch,
    calls: AtomicInteger = AtomicInteger(),
  ): MetricsSource {
    thread(isDaemon = true) {
      Thread.sleep(15_000)
      release.countDown()
    }
    return MetricsSource { _, _, out ->
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
    }
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

    // The agent made no HTTP request, so its log names the target, in-process, not how it was fetched.
    "a source that throws should be logged as its scrape's failure, not an HTTP request's" {
      val lines =
        captureLogs<ScrapeResults>(Level.WARN) {
          scraped { _, _, _ -> throw IOException("registry closed") }
        }.map { it.formattedMessage }

      lines.any { it.startsWith("Scrape of $IN_PROCESS_TARGET failed") && "registry closed" in it }.shouldBeTrue()
      lines.none { "HTTP request" in it }.shouldBeTrue()
    }

    // A source may write from several threads at once, as a registry's collectors can; each write is atomic, so every
    // byte arrives.
    "a source that writes from several threads at once should have every byte it wrote" {
      val results =
        scraped { _, _, out ->
          List(WRITERS) { writer -> thread { repeat(WRITES) { out.write('a'.code + writer) } } }.forEach { it.join() }
          ""
        }

      results.srStatusCode shouldBe 200
      results.rawSize shouldBe WRITERS * WRITES
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
    // other scrapes, or its shutdown (the next test): the scrape times out on time, another path's source still
    // answers, and the source isn't called again until it returns.
    "a source that ignores the timeout's interrupt should time out, leave other paths served, and not be called again" {
      val release = CountDownLatch(1)
      val calls = AtomicInteger()
      val agent = agent(scrapeTimeoutSecs = 1)
      agent.pathManager.registerPath("metrics", IN_PROCESS_TARGET, metricsSource = stuckSource(release, calls))
      agent.pathManager.registerPath(
        OTHER_PATH,
        IN_PROCESS_TARGET,
        metricsSource = { _, _, out ->
          out.write("other_metric 1\n".toByteArray())
          TEXT_FORMAT
        },
      )
      val service = AgentHttpService(agent)
      try {
        val started = TimeSource.Monotonic.markNow()
        service.fetchScrapeUrl(request()).srStatusCode shouldBe 408
        started.elapsedNow() shouldBeLessThan 5.seconds

        // The stuck call holds a thread of its own, so another path's source, called on another, answers while it's
        // stuck, rather than waiting behind it until its own scrape times out.
        val other = service.fetchScrapeUrl(request(path = OTHER_PATH))
        other.srStatusCode shouldBe 200
        other.srContentAsText shouldContain "other_metric 1"
        release.count shouldBe 1L

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

    // A source may be called by overlapping scrapes, two of Prometheus's or one and a Scrape now. With two of its
    // calls abandoned, the one that answered the interrupt cleared the mark the other, still stuck, had set, so the
    // source was called again, and each abandoned call held another thread.
    "a source shouldn't be called again until every call its scrapes gave up on has returned" {
      val release = CountDownLatch(1)
      val calls = AtomicInteger()
      val agent = agent(scrapeTimeoutSecs = 5)
      val source =
        MetricsSource { _, _, out ->
          when (calls.incrementAndGet()) {
            // Ignores the interrupt, as a socket read does
            1 -> stuckSource(release).write("", emptySet(), out)

            // Ends at the interrupt
            2 -> Thread.sleep(30_000)

            else -> out.write(BODY.toByteArray())
          }
          TEXT_FORMAT
        }
      agent.pathManager.registerPath("metrics", IN_PROCESS_TARGET, metricsSource = source)
      val service = AgentHttpService(agent)
      try {
        // The stuck call's scrape gives up first, then the other's, whose call returns at the interrupt.
        coroutineScope {
          val stuck = async { service.fetchScrapeUrl(request(timeout = 500.milliseconds)) }
          val interrupted = async { service.fetchScrapeUrl(request(timeout = 1500.milliseconds)) }
          stuck.await().srStatusCode shouldBe 408
          interrupted.await().srStatusCode shouldBe 408
        }
        calls.get() shouldBe 2

        val refused = service.fetchScrapeUrl(request())
        refused.srStatusCode shouldBe 503
        refused.srFailureReason shouldContain "hasn't returned"
        calls.get() shouldBe 2

        release.countDown()
        eventually(5.seconds) { service.fetchScrapeUrl(request()).srStatusCode shouldBe 200 }
      } finally {
        release.countDown()
        service.close()
      }
    }

    // The agent's shutdown closes the service, which cancels the source's call and waits for none of it: a call that
    // ignores the interrupt holds only its own thread, so the agent stops while the call is still stuck.
    "a source that ignores the timeout's interrupt shouldn't hold the agent's shutdown" {
      val release = CountDownLatch(1)
      val agent = agent(scrapeTimeoutSecs = 1)
      agent.pathManager.registerPath("metrics", IN_PROCESS_TARGET, metricsSource = stuckSource(release))
      val service = AgentHttpService(agent)
      try {
        service.fetchScrapeUrl(request()).srStatusCode shouldBe 408

        val closing = TimeSource.Monotonic.markNow()
        service.close()
        closing.elapsedNow() shouldBeLessThan 5.seconds
        // Closed while the call was still stuck, not once the daemon thread let it go
        release.count shouldBe 1L
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

    // A VirtualMachineError leaves the JVM unfit to go on, so it's the one thing a source throws that doesn't fail only
    // its scrape, as any other Error does (above): it propagates, as it does from a target's fetch
    // (AgentHttpServiceTest).
    "a source that throws a VirtualMachineError should let it propagate, not fail only its scrape" {
      listOf(OutOfMemoryError("source heap exhausted"), StackOverflowError("source recursed")).forEach { error ->
        val thrown = shouldThrow<VirtualMachineError> { scraped { _, _, _ -> throw error } }

        thrown::class shouldBe error::class
        thrown.message shouldBe error.message
      }
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

    // The docs site's worker, which serves its registry in-process: it does its work whether or not the agent starts,
    // as where the tests run, with no PROMETHEUS_PROXY. RegistryMetricsSourceTest covers what a registry source writes.
    "the docs site's worker should do its work, in Kotlin and in Java" {
      val ran = mutableListOf<String>()

      runWorker(version = "1.0") { ran += "Kotlin" }
      EmbeddedAgentSourceJavaExample.runWorker("1.0") { ran += "Java" }

      ran shouldBe listOf("Kotlin", "Java")
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

    // A second in-process path, beside /metrics
    const val OTHER_PATH = "other_metrics"

    // The threads a source writes from at once, and the single-byte writes each makes
    const val WRITERS = 8
    const val WRITES = 5_000

    val BODY =
      """
      # TYPE kept_metric gauge
      kept_metric 1
      # TYPE denied_metric gauge
      denied_metric 2

      """.trimIndent()
  }
}
