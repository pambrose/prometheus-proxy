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

package io.prometheus.common

import brave.Span
import brave.Tracing
import brave.grpc.GrpcTracing
import brave.propagation.Propagation
import brave.propagation.TraceContext
import brave.rpc.RpcRequest
import brave.rpc.RpcRequestMatchers.methodEquals
import brave.rpc.RpcRuleSampler
import brave.rpc.RpcTracing
import brave.sampler.CountingSampler
import brave.sampler.Matchers
import brave.sampler.Sampler
import brave.sampler.SamplerFunction
import com.pambrose.common.service.GenericService
import io.prometheus.common.MetricBuilders.SUCCESS_LABEL
import io.prometheus.grpc.ProxyServiceGrpc
import kotlin.coroutines.cancellation.CancellationException

/**
 * A proxy's or an agent's tracing: of its gRPC calls, and of each scrape, which rides a long-lived stream that the gRPC
 * spans can't show one scrape of.
 *
 * Both are kept at [sampleRate], except heartbeats, which never are: an idle agent sends one every few seconds, and
 * they buried the calls a trace is wanted for. Nor is the path notices stream, which lasts the connection and carries
 * nothing a trace is wanted for. A scrape's spans are passed from call to call, never put in scope:
 * Brave's current span is thread-local, which a coroutine that moves between threads breaks.
 *
 * @param tracing the process's tracing, from its Zipkin reporter, which [close] closes
 * @param sampleRate the share of traces kept: 0, or from 0.01 to 1, as the options check
 */
internal class ScrapeTracing(
  private val tracing: Tracing,
  sampleRate: Double,
) : AutoCloseable {
  // CountingSampler, not Sampler.create: below 0.01 that samples by trace ID, which isn't known yet when a rule or a
  // scrape's span decides, so every decision would have been the same.
  private val sampler: Sampler = CountingSampler.create(sampleRate.toFloat())

  // Decides a span that begins a trace, or whose parent hasn't decided; a child of one that has follows it.
  private val scrapeSampler = SamplerFunction<Unit> { sampler.isSampled(0L) }
  private val tracer = tracing.tracer()
  private val injector =
    tracing.propagation().injector(
      Propagation.Setter<MutableMap<String, String>, String> { context, key, value -> context[key] = value },
    )
  private val extractor =
    tracing.propagation().extractor(Propagation.Getter<Map<String, String>, String> { context, key -> context[key] })

  /** How each gRPC call is sampled, alike for the calls the process makes and those it serves. */
  internal val rpcSampler: SamplerFunction<RpcRequest> =
    RpcRuleSampler.newBuilder()
      .putRule(methodEquals<RpcRequest>(HEARTBEAT_METHOD), Sampler.NEVER_SAMPLE)
      .putRule(methodEquals<RpcRequest>(PATH_NOTICES_METHOD), Sampler.NEVER_SAMPLE)
      .putRule(Matchers.alwaysMatch<RpcRequest>(), sampler)
      .build()

  /** The tracing the gRPC interceptors use. */
  val grpcTracing: GrpcTracing =
    GrpcTracing.create(RpcTracing.newBuilder(tracing).clientSampler(rpcSampler).serverSampler(rpcSampler).build())

  /** A started span named [name] that begins a trace, kept at the sample rate. */
  fun newTrace(name: String): Span = started(null, name)

  /** A started span named [name] in [parent]'s trace, kept when that trace is. */
  fun newChild(
    parent: TraceContext,
    name: String,
  ): Span = started(parent, name)

  /**
   * A started span named [name] in the trace [context] carries, as [contextOf] gives it, kept when that trace is; or,
   * when it carries none, one that begins a trace, as [newTrace] does.
   */
  fun joined(
    context: Map<String, String>,
    name: String,
  ): Span = started(extractor.extract(context).context(), name)

  /** [span]'s trace, as the propagation headers a ScrapeRequest's trace_context carries to the agent. */
  fun contextOf(span: Span): Map<String, String> =
    mutableMapOf<String, String>().also { injector.inject(span.context(), it) }

  override fun close() = tracing.close()

  private fun started(
    parent: TraceContext?,
    name: String,
  ): Span = tracer.nextSpanWithParent(scrapeSampler, Unit, parent).name(name).start()

  companion object {
    // Brave's gRPC instrumentation matches the bare method name, as the generated stub gives it.
    internal val HEARTBEAT_METHOD: String = checkNotNull(ProxyServiceGrpc.getSendHeartBeatMethod().bareMethodName)
    internal val PATH_NOTICES_METHOD: String =
      checkNotNull(ProxyServiceGrpc.getReadPathNoticesFromProxyMethod().bareMethodName)

    /** The proxy's span of a scrape, from Prometheus's request to its answer. */
    const val SCRAPE_SPAN = "scrape"

    /** The proxy's span of a scrape's request to one agent, until its result arrives; one for each of the path's. */
    const val FORWARD_SPAN = "forward"

    /** The agent's span of its fetch of the target. */
    const val FETCH_SPAN = "fetch"

    const val TAG_PATH = "path"

    /** On a forward span: how long, in milliseconds, the request waited in the agent's queue for its stream. */
    const val TAG_QUEUE_WAIT = "queue.wait_ms"

    /** On a fetch span the agent retried: how many retries it made (`agent.scrapeMaxRetries`). */
    const val TAG_RETRIES = "retries"

    /** On a fetch span of a path with a metric filter: how many lines the filter dropped. */
    const val TAG_LINES_DROPPED = "filter.lines_dropped"

    /** A span's outcome when its scrape was cancelled before it ended. */
    const val CANCELLED = "cancelled"

    /**
     * Tags this span with how its scrape ended: [outcomes], the scrape counters' labels of its results, more than one
     * for a path several agents serve, and [statusCode]; and as an error, which Zipkin shows in red, unless every one
     * succeeded.
     */
    fun Span.endedAs(
      outcomes: Collection<String>,
      statusCode: Int? = null,
    ): Span =
      apply {
        val distinct = outcomes.distinct()
        tag("outcome", distinct.joinToString(","))
        statusCode?.also { tag("http.status_code", it.toString()) }
        val failed = distinct - SUCCESS_LABEL
        if (failed.isNotEmpty())
          tag("error", failed.joinToString(","))
      }

    /**
     * Runs [block] in this span, and finishes the span however [block] ends: tagged by [ended] with what it returned,
     * as [cancelledAs] when it was cancelled, or with the error it threw. Without a span, runs [block] alone.
     */
    inline fun <T> Span?.traced(
      cancelledAs: String = CANCELLED,
      ended: Span.(T) -> Unit,
      block: () -> T,
    ): T {
      if (this == null)
        return block()
      return runCatching(block)
        .onSuccess { ended(it) }
        .onFailure { if (it is CancellationException) endedAs(listOf(cancelledAs)) else error(it) }
        .also { finish() }
        .getOrThrow()
    }
  }
}

/**
 * The holder of a proxy's or an agent's tracing, null with Zipkin off, named for its zipkin.serviceName and kept at
 * [sampleRate]. Built on first use: each builds its gRPC server or channel, with its tracing interceptor, before
 * initServletService assigns zipkinReporterService, so the interceptor reads this on the first call (November #7).
 */
internal fun GenericService<*>.scrapeTracingHolder(sampleRate: Double): Lazy<ScrapeTracing?> =
  lazy { if (isZipkinEnabled) ScrapeTracing(zipkinReporterService.newTracing(), sampleRate) else null }
