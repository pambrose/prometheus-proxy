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

package io.prometheus.proxy

import brave.Span
import brave.propagation.TraceContext
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.prometheus.Proxy
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.Exposition
import io.prometheus.common.MetricBuilders.SUCCESS_LABEL
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeTracing.Companion.FORWARD_SPAN
import io.prometheus.common.ScrapeTracing.Companion.endedAs
import io.prometheus.common.ScrapeTracing.Companion.traced
import io.prometheus.proxy.ProxyUtils.DecodedContent
import io.prometheus.proxy.ProxyUtils.unzip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends a scrape of a path to its agents through their gRPC streams, awaits their answers, and merges them: what the
 * scrape route and the dashboard's scrape-now share, so a scrape-now gets what Prometheus would. Handles OpenMetrics
 * `# EOF` deduplication when merging the answers of consolidated agents.
 *
 * Records no scrape: no scrape metric, `/debug` line, scrape record, or event. The scrape route ([ProxyHttpRoutes])
 * records Prometheus's scrapes around [submitScrapeRequests], and the scrape-now records only in the activity feed, so
 * a scrape no one at Prometheus asked for leaves no trace in what the proxy reports. Each scrape tells the agent who
 * asked for it ([ScrapeSpec.origin]), so the agent's own scrape metrics leave a scrape-now out too.
 *
 * @see ProxyHttpRoutes
 * @see AgentContext
 * @see ScrapeRequestManager
 */
internal object ScrapeDispatch {
  private val logger = logger {}
  private val authHeaderWithoutTlsWarned = AtomicBoolean(false)

  // The proxy waited out scrapeRequestTimeoutSecs without the agent answering. Distinct from
  // upstream_timed_out, which is the agent answering promptly to report that its own fetch of the
  // target exceeded agent.scrapeTimeoutSecs -- see upstreamErrorLabel.
  internal const val PROXY_TIMEOUT_LABEL = "timed_out"
  internal const val IN_FLIGHT_LIMIT_LABEL = "proxy_in_flight_limit"
  internal const val BACKLOG_FULL_LABEL = "agent_backlog_full"

  // The content types a scrape response is served with; see parseContentType. Matched without parameters.
  private val EXPOSITION_CONTENT_TYPES =
    [
      Text.Plain,
      ContentType("application", "openmetrics-text"),
      ContentType("application", "vnd.google.protobuf"),
    ]

  // Each agent's queue is capped at this multiple of scrapeRequestBacklogUnhealthySize, matching how the agent
  // sizes its own request channel.
  private const val BACKLOG_CAPACITY_MULTIPLIER = 2

  // The most characters /debug keeps, or a log line shows, of an agent's per-scrape failure reason, URL, or content
  // type: what a chunked transfer's header keeps of each, so a unary result, bounded only by gRPC's 4 MiB message
  // limit, is shown no longer than a chunked one (November #12). The scrape route's /debug line uses it too.
  internal const val MAX_SHOWN_RESULT_CHARS = ProxyServiceImpl.MAX_CHUNKED_HEADER_FIELD_LENGTH

  // The most scrape requests one agent's queue holds. One chunked stream's transfers under way are capped at the same
  // number (ProxyServiceImpl's readHeader).
  internal fun maxAgentBacklog(proxy: Proxy): Int =
    proxy.proxyConfigVals.internal.scrapeRequestBacklogUnhealthySize * BACKLOG_CAPACITY_MULTIPLIER

  // Combines the per-agent responses for a (possibly consolidated) path into a single HTTP response:
  // overall OK if any agent returned OK (otherwise the first distinct status code), the contentType of
  // the first OK result (otherwise the first distinct contentType), and the merged body. Extracted as
  // an internal seam so the status/contentType selection is unit-testable without driving real scrapes.
  internal fun mergeResponseResults(results: List<ScrapeRequestResponse>): ResponseResults {
    val statusCodes = results.map { it.statusCode }.distinct()
    val contentTypes = results.map { it.contentType }.distinct()
    val updateMsgs: List<String> = results.map { it.updateMsg }
    // Grab the contentType of the first OK in the list
    val okContentType: ContentType? = results.firstOrNull { it.statusCode == HttpStatusCode.OK }?.contentType

    return ResponseResults(
      statusCode = if (statusCodes.contains(HttpStatusCode.OK)) HttpStatusCode.OK else statusCodes[0],
      contentType = okContentType ?: contentTypes[0],
      contentText = mergeContentTexts(results),
      updateMsgs = updateMsgs,
    )
  }

  // Bug #13: When consolidated paths have multiple agents returning OpenMetrics format,
  // each agent's response ends with "# EOF". Naively joining with "\n" produces
  // "# EOF" markers in the middle of the stream, which is invalid OpenMetrics.
  // This function strips trailing "# EOF" lines from each result and appends a
  // single "# EOF" at the end if any result contained one.
  internal fun mergeContentTexts(results: List<ScrapeRequestResponse>): String {
    // Filter out results with empty content (e.g., failed agents) to avoid
    // extra newlines in the merged output.
    val nonEmpty = results.filter { it.contentText.isNotEmpty() }
    if (nonEmpty.isEmpty()) return ""
    if (nonEmpty.size == 1) return nonEmpty[0].contentText

    val trimmed = nonEmpty.map { it.contentText.trimEnd() }
    val hasEof = trimmed.any { it.endsWith(Exposition.EOF_MARKER) }
    val stripped = trimmed.map { it.removeSuffix(Exposition.EOF_MARKER).trimEnd() }
    val joined = stripped.joinToString("\n")
    return if (hasEof) "$joined\n${Exposition.EOF_MARKER}" else joined
  }

  /**
   * Sends [scrape] to every agent of [agentContextInfo] at once, as [submitScrapeRequest] does to one, and returns
   * their responses in the agents' order. The scrape route and the dashboard's scrape-now both scrape through here, so
   * a scrape-now gets what Prometheus would; neither records anything here.
   */
  internal suspend fun submitScrapeRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    scrape: ScrapeSpec,
  ): List<ScrapeRequestResponse> =
    coroutineScope {
      agentContextInfo.agentContexts
        .map { agentContext -> async { submitScrapeRequest(agentContext, proxy, scrape) } }
        .awaitAll()
    }

  /**
   * Sends [scrape] to [agentContext] and waits for its result, for at most the scrape's timeout. Records nothing: the
   * scrape route records what Prometheus sees, and the dashboard's scrape-now records only in its activity feed.
   */
  internal suspend fun submitScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    scrape: ScrapeSpec,
  ): ScrapeRequestResponse {
    val span = forwardSpan(agentContext, proxy, scrape)
    return span.traced(ended = { endedAs(listOf(it.updateMsg), it.statusCode.value) }) {
      awaitScrapeResponse(agentContext, proxy, scrape, span)
    }
  }

  // The span of [scrape]'s request to [agentContext], in the trace the scrape route began for it; null for a scrape
  // that isn't traced, a dashboard's among them. The agent's identity is its own to report, so it's cut and escaped.
  private fun forwardSpan(
    agentContext: AgentContext,
    proxy: Proxy,
    scrape: ScrapeSpec,
  ): Span? =
    scrape.trace?.let { parent ->
      proxy.tracing?.newChild(parent, FORWARD_SPAN)?.apply {
        tag("agentId", shownValue(agentContext.agentId))
        tag("agentName", shownValue(agentContext.agentName))
      }
    }

  private suspend fun awaitScrapeResponse(
    agentContext: AgentContext,
    proxy: Proxy,
    scrape: ScrapeSpec,
    span: Span?,
  ): ScrapeRequestResponse {
    val scrapeRequest = createScrapeRequest(agentContext, proxy, scrape, span)
    val timeoutTime = scrape.timeout ?: proxy.proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds

    // A terminal response here means the agent disconnected or the request timed out.
    dispatchScrapeRequest(agentContext, proxy, scrapeRequest, timeoutTime)?.let { return it }

    logger.debug { "Results returned from $agentContext for $scrapeRequest" }

    val scrapeResults =
      scrapeRequest.scrapeResults
        ?: return ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "missing_results",
          fetchDuration = scrapeRequest.ageDuration(),
        )

    val statusCode = scrapeResults.httpStatus
    val contentType = parseContentType(scrapeResults.srContentType, scrape.path)

    // Do not return content on error status codes.
    return if (!statusCode.isSuccess())
      ScrapeRequestResponse(
        statusCode = statusCode,
        contentType = contentType,
        failureReason = scrapeResults.srFailureReason,
        url = scrapeResults.srUrl,
        // A failure the proxy made itself names its own cause; only the agent's own results describe the target.
        updateMsg = scrapeRequest.proxyFailure?.label ?: upstreamErrorLabel(statusCode),
        fetchDuration = scrapeRequest.ageDuration(),
      )
    else
      buildSuccessResponse(proxy, scrapeRequest, scrapeResults, contentType)
  }

  // Registers the request, sends it to the agent, and awaits completion, cleaning up the request map, and any chunked
  // transfer still open for the request, in a finally. Returns a terminal ScrapeRequestResponse for the
  // agent-disconnected and timed-out cases, or null when the agent completed and the caller should read
  // scrapeRequest.scrapeResults.
  private suspend fun dispatchScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    scrapeRequest: ScrapeRequestWrapper,
    timeoutTime: Duration,
  ): ScrapeRequestResponse? {
    // Checked before the try: a refused request was never tracked, so the finally must not try to remove it.
    val maxInFlight = proxy.proxyConfigVals.internal.maxInFlightScrapeRequests
    if (!proxy.scrapeRequestManager.tryAddToScrapeRequestMap(scrapeRequest, maxInFlight))
      return unavailable(scrapeRequest, IN_FLIGHT_LIMIT_LABEL)
    return try {
      queueAndAwait(agentContext, proxy, scrapeRequest, timeoutTime)
    } finally {
      scrapeRequest.closeChannel()
      val scrapeId = scrapeRequest.scrapeId
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
        ?: logger.error { "Scrape request $scrapeId missing in map" }
      // Nobody awaits this scrape's chunked transfer anymore, so it goes too, rather than lasting as long as the agent
      // keeps its stream open (November #13). It must go after the request: readHeader stores a transfer and then
      // checks that its request is still tracked, so whichever of the two runs second sees the other's change.
      proxy.agentContextManager.removeChunkedContext(scrapeId)
        ?.also {
          logger.debug { "Dropped the unfinished chunked transfer for scrapeId: $scrapeId" }
          proxy.metrics { chunkedTransfersAbandoned.inc() }
        }
    }
  }

  // Queues an already-tracked request for the agent and waits for its result. Returns a terminal 503 response, or
  // null once the request has completed.
  private suspend fun queueAndAwait(
    agentContext: AgentContext,
    proxy: Proxy,
    scrapeRequest: ScrapeRequestWrapper,
    timeoutTime: Duration,
  ): ScrapeRequestResponse? {
    val queued =
      try {
        agentContext.writeScrapeRequest(scrapeRequest, maxAgentBacklog(proxy))
      } catch (_: ClosedSendChannelException) {
        return unavailable(scrapeRequest, ProxyFailure.AGENT_DISCONNECTED.label)
      }
    // awaitCompleted suspends until the request completes, the agent disconnects, or the timeout expires.
    return when {
      !queued -> unavailable(scrapeRequest, BACKLOG_FULL_LABEL)
      !scrapeRequest.awaitCompleted(timeoutTime) -> unavailable(scrapeRequest, PROXY_TIMEOUT_LABEL)
      else -> null
    }
  }

  private fun unavailable(
    scrapeRequest: ScrapeRequestWrapper,
    updateMsg: String,
  ) = ScrapeRequestResponse(
    statusCode = HttpStatusCode.ServiceUnavailable,
    updateMsg = updateMsg,
    fetchDuration = scrapeRequest.ageDuration(),
  )

  // Parses the agent-reported Content-Type, falling back to text/plain (the correct default for the
  // Prometheus exposition format) with a warning when it is malformed. Only the exposition types pass through: the
  // body is served from the proxy's own origin, so a target answering text/html could otherwise run a script there in
  // an operator's browser. Any other type is served as text/plain, logged at DEBUG since it repeats every scrape. Both
  // log lines show the agent's value cut and escaped (November #12, #16).
  private fun parseContentType(
    rawContentType: String,
    path: String,
  ): ContentType =
    runCatching { ContentType.parse(rawContentType) }
      .getOrElse {
        logger.warn {
          "Error parsing content type for /$path: '${shownValue(rawContentType, MAX_SHOWN_RESULT_CHARS)}' " +
            "(${it.simpleClassName}); falling back to text/plain"
        }
        Text.Plain.withCharset(Charsets.UTF_8)
      }.let { parsed ->
        if (EXPOSITION_CONTENT_TYPES.any { parsed.match(it) }) {
          parsed
        } else {
          logger.debug {
            "Serving /$path's content type '${shownValue(rawContentType, MAX_SHOWN_RESULT_CHARS)}' as text/plain"
          }
          Text.Plain.withCharset(Charsets.UTF_8)
        }
      }

  // The status the target answered the agent with.
  private val ScrapeResults.httpStatus: HttpStatusCode
    get() = HttpStatusCode.fromValue(srStatusCode)

  // Decodes (and size-guards) a successful scrape body and assembles the response, returning a terminal
  // 413/502 response instead when the gzipped payload is a zip bomb or is corrupt.
  private fun buildSuccessResponse(
    proxy: Proxy,
    scrapeRequest: ScrapeRequestWrapper,
    scrapeResults: ScrapeResults,
    contentType: ContentType,
  ): ScrapeRequestResponse {
    val maxSize = proxy.proxyConfigVals.internal.maxUnzippedContentSizeMBytes * 1024L * 1024L
    val decoded =
      try {
        if (scrapeResults.srZipped)
          scrapeResults.srContentAsZipped.unzip(maxSize)
        else
          DecodedContent(scrapeResults.srContentAsText, scrapeResults.srContentAsText.toByteArray().size.toLong())
      } catch (e: ProxyUtils.ZipBombException) {
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.PayloadTooLarge,
          contentType = Text.Plain.withCharset(Charsets.UTF_8),
          failureReason = e.message ?: "Unzipped content too large",
          url = scrapeResults.srUrl,
          updateMsg = "payload_too_large",
          fetchDuration = scrapeRequest.ageDuration(),
        )
      } catch (e: IOException) {
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.BadGateway,
          contentType = Text.Plain.withCharset(Charsets.UTF_8),
          failureReason = e.message ?: "Invalid gzipped content",
          url = scrapeResults.srUrl,
          updateMsg = "invalid_gzip",
          fetchDuration = scrapeRequest.ageDuration(),
        )
      }

    return ScrapeRequestResponse(
      statusCode = scrapeResults.httpStatus,
      contentType = contentType,
      contentText = decoded.text,
      failureReason = scrapeResults.srFailureReason,
      url = scrapeResults.srUrl,
      updateMsg = SUCCESS_LABEL,
      fetchDuration = scrapeRequest.ageDuration(),
      // The byte count already computed during unzip (gzipped) or the small plain payload's length, so the size need
      // not be measured again by re-encoding the decoded text.
      byteCount = decoded.byteCount,
      encoding = if (scrapeResults.srZipped) ProxyMetrics.ENCODING_GZIPPED else ProxyMetrics.ENCODING_PLAIN,
    )
  }

  // Maps a non-success upstream status code (returned by the agent for an already-resolved path) to a
  // metric/activity label, so operators can tell a down target (5xx), a scrape timeout (408/504), an
  // oversized payload (413), and a path not registered on the agent (404) apart -- instead of every
  // failure reading as path_not_found (finding 10).
  //
  // Each label names the agent-side leg, distinct from the proxy-side label for the same fault:
  // upstream_timed_out vs PROXY_TIMEOUT_LABEL (the agent's own scrapeTimeoutSecs elapsed, versus the
  // proxy's scrapeRequestTimeoutSecs elapsed with no answer at all), and content_too_large vs
  // payload_too_large (the agent's maxContentLengthMBytes, versus the proxy's unzip limit). Under
  // stock config the agent's 15s timeout trips well before the proxy's 90s, so collapsing the two
  // timeout legs would have hidden the common case behind the rare one (finding 4).
  internal fun upstreamErrorLabel(statusCode: HttpStatusCode): String =
    when (statusCode) {
      HttpStatusCode.NotFound -> "path_not_found"
      HttpStatusCode.RequestTimeout, HttpStatusCode.GatewayTimeout -> "upstream_timed_out"
      HttpStatusCode.PayloadTooLarge -> "content_too_large"
      else -> "upstream_error"
    }

  private fun createScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    scrape: ScrapeSpec,
    span: Span?,
  ): ScrapeRequestWrapper {
    val headers = scrape.headers
    val authHeader = headers.authorization

    if (authHeader.isNotEmpty() && !proxy.options.isTlsEnabled)
      warnOfAuthHeaderWithoutTls()

    return ScrapeRequestWrapper(
      agentContext = agentContext,
      pathVal = scrape.path,
      encodedQueryParamsVal = scrape.queryParams,
      authHeaderVal = authHeader,
      acceptVal = headers.accept,
      debugEnabledVal = proxy.options.debugEnabled,
      scrapeTimeoutVal = headers.scrapeTimeout,
      dashboardScrapeVal = scrape.origin == ScrapeOrigin.DASHBOARD,
      traceSpan = span,
      traceContextVal = span?.let { proxy.tracing?.contextOf(it) }.orEmpty(),
    )
  }

  // The Authorization header is forwarded to the agent, so over a gRPC connection without TLS the credentials may be
  // exposed in transit. Said once, not on every scrape.
  private fun warnOfAuthHeaderWithoutTls() {
    if (authHeaderWithoutTlsWarned.compareAndSet(expectedValue = false, newValue = true))
      logger.warn {
        "Authorization header is being forwarded to agent over a non-TLS gRPC connection. " +
          "Credentials may be exposed in transit. Configure TLS (--cert, --key) to secure the proxy-agent channel."
      }
  }
}

/**
 * Who asked for a scrape, which the agent is told: Prometheus, through the scrape route, or someone at the dashboard,
 * through its scrape-now. The agent counts a dashboard scrape apart from its own scrape metrics, so they stay a record
 * of Prometheus's scrapes, as the proxy's do.
 */
internal enum class ScrapeOrigin {
  PROMETHEUS,
  DASHBOARD,
}

/**
 * One scrape of a path, as [ScrapeDispatch.submitScrapeRequests] sends it to each of the path's agents.
 *
 * @param path the path, as the path map keys it
 * @param queryParams the scrape's query parameters, already encoded, which the agent adds to the target's URL; empty
 *   for none
 * @param headers what the request that started the scrape asks for
 * @param timeout how long the proxy waits for each agent's answer; null for `proxy.internal.scrapeRequestTimeoutSecs`
 * @param origin who asked for the scrape, which the agent is told
 * @param trace the trace the scrape route began for the scrape, which each agent's forward span joins; null for a
 *   scrape that isn't traced
 */
internal data class ScrapeSpec(
  val path: String,
  val queryParams: String = "",
  val headers: ScrapeHeaders = ScrapeHeaders(),
  val timeout: Duration? = null,
  val origin: ScrapeOrigin = ScrapeOrigin.PROMETHEUS,
  val trace: TraceContext? = null,
)

/**
 * What a scrape asks the agent for, from the request that started it: the Prometheus scrape's headers on the scrape
 * route, or the dashboard's own for a scrape-now, which has no request of its own to forward.
 *
 * @param authorization the Authorization header, forwarded to the target; empty for none
 * @param accept the Accept header, which picks the exposition format
 * @param scrapeTimeout how long the client waits, which the agent stops the target's fetch at; null for no limit
 */
internal data class ScrapeHeaders(
  val authorization: String = "",
  val accept: String? = null,
  val scrapeTimeout: Duration? = null,
) {
  companion object {
    // Prometheus's scrape timeout. The proxy forwards it to the agent, which stops a scrape no one is still waiting
    // for.
    internal const val SCRAPE_TIMEOUT_HEADER = "X-Prometheus-Scrape-Timeout-Seconds"

    fun of(request: ApplicationRequest) =
      ScrapeHeaders(
        authorization = request.header(HttpHeaders.Authorization).orEmpty(),
        accept = request.header(HttpHeaders.Accept),
        scrapeTimeout = scrapeTimeoutFromHeader(request.header(SCRAPE_TIMEOUT_HEADER)),
      )

    // Prometheus sends its scrape timeout as a decimal number of seconds. A missing, malformed, or non-positive value
    // means the client set no limit.
    internal fun scrapeTimeoutFromHeader(value: String?): Duration? =
      value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.seconds
  }
}

internal data class ScrapeRequestResponse(
  val statusCode: HttpStatusCode,
  val updateMsg: String,
  val contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  val contentText: String = "",
  val failureReason: String = "",
  val url: String = "",
  val fetchDuration: Duration,
  // For a body that was decoded: its size, and whether it came gzipped (ProxyMetrics.ENCODING_GZIPPED) or plain.
  val byteCount: Long = 0,
  val encoding: String? = null,
)
