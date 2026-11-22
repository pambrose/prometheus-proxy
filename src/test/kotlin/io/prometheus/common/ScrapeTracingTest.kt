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

package io.prometheus.common

import brave.Span
import brave.Tracing
import brave.handler.MutableSpan
import brave.rpc.RpcClientRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.spyk
import io.mockk.verify
import io.prometheus.common.ScrapeTracing.Companion.FETCH_SPAN
import io.prometheus.common.ScrapeTracing.Companion.FORWARD_SPAN
import io.prometheus.common.ScrapeTracing.Companion.SCRAPE_SPAN
import io.prometheus.common.ScrapeTracing.Companion.endedAs
import io.prometheus.common.ScrapeTracing.Companion.traced
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

// Scrapes ride long-lived gRPC streams, whose spans show a connection, not a scrape, so the proxy and the agent trace
// each scrape themselves: the proxy's scrape span, a forward span for each agent, and the agent's fetch span, which
// joins the proxy's trace through the context the proxy sends with the request.
class ScrapeTracingTest : StringSpec() {
  private val finished = CopyOnWriteArrayList<MutableSpan>()

  // A ScrapeTracing at [sampleRate] whose finished spans land in [finished].
  private fun scrapeTracing(sampleRate: Double): ScrapeTracing {
    finished.clear()
    return ScrapeTracing(Tracing.newBuilder().addSpanHandler(collectInto(finished)).build(), sampleRate)
  }

  // A scrape's three spans, as the proxy and the agent make them: the scrape, its forward to an agent, and the agent's
  // fetch, joined through the context the proxy sends. Each is finished, innermost first, and returned outermost first.
  private fun ScrapeTracing.scrapeTrace(): List<Span> {
    val scrape = newTrace(SCRAPE_SPAN)
    val forward = newChild(scrape.context(), FORWARD_SPAN)
    val fetch = joined(contextOf(forward), FETCH_SPAN)
    listOf(fetch, forward, scrape).forEach(Span::finish)
    return listOf(scrape, forward, fetch)
  }

  // A gRPC call to [method], as the sampler sees it.
  private fun call(method: String) =
    object : RpcClientRequest() {
      override fun method() = method

      override fun service() = "io.prometheus.grpc.ProxyService"

      override fun unwrap() = this
    }

  init {
    "a scrape's spans should form one trace, the agent's joining the proxy's through the context it sends" {
      scrapeTracing(1.0).use { tracing ->
        tracing.scrapeTrace()

        finished.map { it.name() } shouldBe [FETCH_SPAN, FORWARD_SPAN, SCRAPE_SPAN]
        val (fetchSpan, forwardSpan, scrapeSpan) = finished
        finished.map { it.traceId() }.distinct() shouldBe [scrapeSpan.traceId()]
        scrapeSpan.parentId().shouldBeNull()
        forwardSpan.parentId() shouldBe scrapeSpan.id()
        fetchSpan.parentId() shouldBe forwardSpan.id()
      }
    }

    // A proxy that doesn't keep a scrape's trace says so in the context it sends, and the agent keeps none either.
    "at a sample rate of 0, neither the proxy's spans nor the agent's that joins them should be kept" {
      scrapeTracing(0.0).use { tracing ->
        tracing.scrapeTrace().map { it.isNoop } shouldBe [true, true, true]
        finished shouldBe emptyList()
      }
    }

    // A proxy predating trace_context, or with tracing off, sends none: the agent begins a trace, kept at its own rate.
    "a context that carries no trace should begin one, kept at the sample rate" {
      scrapeTracing(1.0).use { tracing ->
        tracing.joined(emptyMap(), FETCH_SPAN).finish()
        finished.single().parentId().shouldBeNull()
      }
      scrapeTracing(0.0).use { tracing -> tracing.joined(emptyMap(), FETCH_SPAN).isNoop shouldBe true }
    }

    // The counting sampler keeps exactly its share of each hundred decisions, whatever the trace IDs.
    "a sample rate should keep that share of scrapes" {
      scrapeTracing(0.25).use { tracing ->
        val kept = (1..100).map { tracing.newTrace(SCRAPE_SPAN).also(Span::finish) }.count { !it.isNoop }
        kept shouldBe 25
      }
    }

    // An idle agent heartbeats every few seconds, which buried the calls a trace is wanted for.
    "a heartbeat should never be sampled, and every other gRPC call at the sample rate" {
      ScrapeTracing.HEARTBEAT_METHOD shouldBe "sendHeartBeat"
      scrapeTracing(1.0).use { tracing ->
        tracing.rpcSampler.trySample(call(ScrapeTracing.HEARTBEAT_METHOD)) shouldBe false
        tracing.rpcSampler.trySample(call("registerAgent")) shouldBe true
      }
      scrapeTracing(0.0).use { tracing ->
        tracing.rpcSampler.trySample(call("registerAgent")) shouldBe false
      }
    }

    "a span should be tagged with its scrape's outcomes and status, and as an error unless every one succeeded" {
      scrapeTracing(1.0).use { tracing ->
        tracing.newTrace(SCRAPE_SPAN).endedAs(listOf("success", "success"), 200).finish()
        tracing.newTrace(SCRAPE_SPAN).endedAs(listOf("success", "timed_out", "timed_out")).finish()

        val (succeeded, failed) = finished
        succeeded.tags() shouldBe mapOf("outcome" to "success", "http.status_code" to "200")
        succeeded.error().shouldBeNull()
        failed.tags() shouldBe mapOf("outcome" to "success,timed_out", "error" to "timed_out")
      }
    }

    // Every span's lifecycle goes through traced, so each ends the same way: what it ended with, a cancellation, or
    // the error that ended it.
    "traced should finish its span however the block ends, and run the block alone without one" {
      scrapeTracing(1.0).use { tracing ->
        tracing.newTrace(SCRAPE_SPAN).traced(ended = { tag("result", it) }) { "done" } shouldBe "done"
        shouldThrow<CancellationException> {
          tracing.newTrace(SCRAPE_SPAN).traced<Unit>(ended = {}) { throw CancellationException("hung up") }
        }
        shouldThrow<IllegalStateException> {
          tracing.newTrace(SCRAPE_SPAN).traced<Unit>(ended = {}) { error("broken") }
        }

        val (returned, cancelled, failed) = finished
        returned.tags() shouldBe mapOf("result" to "done")
        cancelled.tags() shouldBe mapOf("outcome" to ScrapeTracing.CANCELLED, "error" to ScrapeTracing.CANCELLED)
        failed.error().shouldNotBeNull().message shouldBe "broken"
      }
      (null as Span?).traced(ended = { error("not called") }) { 42 } shouldBe 42
    }

    "closing should close the process's tracing" {
      val tracing = spyk(Tracing.newBuilder().build())
      ScrapeTracing(tracing, 1.0).close()
      verify(exactly = 1) { tracing.close() }
    }

    "the gRPC interceptors' tracing should be built on the process's tracing" {
      scrapeTracing(1.0).use { tracing -> tracing.grpcTracing.newClientInterceptor().shouldNotBeNull() }
    }
  }
}
