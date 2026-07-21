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

import com.pambrose.common.util.ensureLeadingSlash
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.prometheus.Proxy
import io.prometheus.common.ScrapeResults
import io.prometheus.common.Utils.sanitizeQueryParams
import io.prometheus.proxy.ProxyConstants.FAVICON_FILENAME
import io.prometheus.proxy.ProxyUtils.DecodedContent
import io.prometheus.proxy.ProxyUtils.incrementScrapeRequestCount
import io.prometheus.proxy.ProxyUtils.respondWith
import io.prometheus.proxy.ProxyUtils.unzip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * HTTP routing logic for the proxy's Ktor server.
 *
 * Defines the routes that handle incoming Prometheus scrape requests and service discovery.
 * For each scrape request, resolves the path to one or more [AgentContext] instances,
 * dispatches scrape requests through the gRPC stream, awaits responses, and merges results
 * from consolidated agents. Handles OpenMetrics `# EOF` deduplication when merging.
 *
 * @see ProxyHttpService
 * @see ProxyPathManager
 * @see AgentContext
 */
internal object ProxyHttpRoutes {
  private val logger = logger {}
  private val format = Json { prettyPrint = true }
  private val authHeaderWithoutTlsWarned = AtomicBoolean(false)

  // OpenMetrics end-of-stream marker, stripped from each consolidated agent's body and re-appended once.
  private const val EOF_MARKER = "# EOF"

  // The proxy waited out scrapeRequestTimeoutSecs without the agent answering. Distinct from
  // upstream_timed_out, which is the agent answering promptly to report that its own fetch of the
  // target exceeded agent.scrapeTimeoutSecs -- see upstreamErrorLabel.
  internal const val PROXY_TIMEOUT_LABEL = "timed_out"

  fun Routing.handleRequests(proxy: Proxy) {
    handleServiceDiscoveryEndpoint(proxy)
    handleClientRequests(proxy)
  }

  private fun Routing.handleServiceDiscoveryEndpoint(proxy: Proxy) {
    if (proxy.options.sdEnabled) {
      val sdPath = proxy.options.sdPath.ensureLeadingSlash()
      logger.info { "Adding $sdPath service discovery endpoint" }
      get(sdPath) {
        val json = proxy.buildServiceDiscoveryJson()
        val prettyPrint = format.encodeToString(json)
        call.respondWith(prettyPrint, ContentType.Application.Json.withCharset(Charsets.UTF_8))
      }
    } else {
      logger.info { "Not adding ${proxy.options.sdPath.ensureLeadingSlash()} service discovery endpoint" }
    }
  }

  private fun Routing.handleClientRequests(proxy: Proxy) {
    get("/*") {
      // Cache-Control is set once, by respondWith() (the single response site); setting it here too
      // would make Ktor append a second identical header (finding 29).
      val path = call.request.path().drop(1)
      val queryParams = call.request.queryParameters.formUrlEncode()

      logger.debug {
        val safeParams = sanitizeQueryParams(queryParams)
        "Servicing request for path: $path${if (queryParams.isNotEmpty()) " with query params $safeParams" else ""}"
      }

      val responseResults =
        when {
          !proxy.isRunning -> ProxyUtils.proxyNotRunningResponse()
          path.isBlank() -> ProxyUtils.emptyPathResponse(proxy)
          path == FAVICON_FILENAME -> ProxyUtils.invalidPathResponse(path, proxy)
          proxy.isBlitzRequest(path) -> ResponseResults(contentText = "42")
          else -> processRequestsBasedOnPath(proxy, path, queryParams)
        }

      responseResults.updateMsgs.forEach { incrementScrapeRequestCount(proxy, it) }
      call.respondWith(responseResults.contentText, responseResults.contentType, responseResults.statusCode)
    }
  }

  private suspend fun RoutingContext.processRequestsBasedOnPath(
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): ResponseResults {
    val agentContextInfo = proxy.pathManager.getAgentContextInfo(path)
    return when {
      agentContextInfo == null -> ProxyUtils.invalidPathResponse(path, proxy)
      agentContextInfo.isNotValid() -> ProxyUtils.invalidAgentContextResponse(path, proxy)
      else -> processRequests(agentContextInfo, proxy, path, queryParams)
    }
  }

  private suspend fun RoutingContext.processRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): ResponseResults {
    val results: List<ScrapeRequestResponse> = executeScrapeRequests(agentContextInfo, proxy, path, queryParams)

    // Handle case where no results were returned (all agents failed or disconnected)
    if (results.isEmpty()) {
      logger.warn { "No scrape results returned for path: $path" }
      return ResponseResults(
        statusCode = HttpStatusCode.ServiceUnavailable,
        contentType = Text.Plain.withCharset(Charsets.UTF_8),
        contentText = "No agents available to handle request",
        updateMsgs = ["no_agents"],
      )
    }

    return mergeResponseResults(results)
  }

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
    val hasEof = trimmed.any { it.endsWith(EOF_MARKER) }
    val stripped = trimmed.map { it.removeSuffix(EOF_MARKER).trimEnd() }
    val joined = stripped.joinToString("\n")
    return if (hasEof) "$joined\n$EOF_MARKER" else joined
  }

  private suspend fun RoutingContext.executeScrapeRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): List<ScrapeRequestResponse> =
    coroutineScope {
      // map and awaitAll both preserve order, so zipping recovers which agent produced which response.
      // That keeps provenance at the one site that needs it, rather than as a field on the response.
      agentContextInfo.agentContexts
        .map { agentContext ->
          async { submitScrapeRequest(agentContext, proxy, path, queryParams, call.request) }
        }
        .awaitAll()
        .also { responses ->
          agentContextInfo.agentContexts.zip(responses).forEach { (agentContext, response) ->
            recordScrapeOutcome(path, agentContext.agentId, response, proxy)
          }
        }
        .onEach { response ->
          // Record latency labeled with the request outcome. This single site covers every outcome
          // — including the timeout and agent-disconnected early returns the old per-request timer
          // missed — since each branch yields a ScrapeRequestResponse with updateMsg + fetchDuration.
          proxy.metrics {
            val elapsedSecs = response.fetchDuration.toDouble(DurationUnit.SECONDS)
            scrapeRequestLatency.labels(path, response.updateMsg).observe(elapsedSecs)
          }
        }
    }

  // Named for what it now does: this is where a completed scrape becomes observable -- as a text line
  // on /debug, as a structured record for the web UI, and as an event on the bus.
  private fun recordScrapeOutcome(
    path: String,
    agentId: String,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    val status =
      buildString {
        append("/$path - ${response.updateMsg} - ${response.statusCode}")
        if (!response.statusCode.isSuccess())
          append(" reason: [${response.failureReason}]")
        append(" time: ${response.fetchDuration} url: ${response.url}")
      }
    proxy.logActivity(status)
    proxy.recordScrape(
      ScrapeRecord(
        agentId = agentId,
        path = path,
        statusCode = response.statusCode.value,
        outcome = response.updateMsg,
        durationMillis = response.fetchDuration.inWholeMilliseconds,
        contentLength = response.contentText.length,
      ),
    )
    proxy.eventBus.emit(
      ProxyEvent.ScrapeCompleted(agentId, path, response.statusCode.isSuccess()),
    )
  }

  internal suspend fun submitScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
  ): ScrapeRequestResponse {
    val scrapeRequest = createScrapeRequest(agentContext, proxy, path, encodedQueryParams, request)
    val timeoutTime = proxy.proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds

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

    val statusCode = HttpStatusCode.fromValue(scrapeResults.srStatusCode)
    val contentType = parseContentType(scrapeResults.srContentType, path)

    // Do not return content on error status codes.
    return if (!statusCode.isSuccess())
      ScrapeRequestResponse(
        statusCode = statusCode,
        contentType = contentType,
        failureReason = scrapeResults.srFailureReason,
        url = scrapeResults.srUrl,
        updateMsg = upstreamErrorLabel(statusCode),
        fetchDuration = scrapeRequest.ageDuration(),
      )
    else
      buildSuccessResponse(proxy, path, scrapeRequest, scrapeResults, statusCode, contentType)
  }

  // Registers the request, sends it to the agent, and awaits completion, cleaning up the request map in
  // a finally. Returns a terminal ScrapeRequestResponse for the agent-disconnected and timed-out cases,
  // or null when the agent completed and the caller should read scrapeRequest.scrapeResults.
  private suspend fun dispatchScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    scrapeRequest: ScrapeRequestWrapper,
    timeoutTime: Duration,
  ): ScrapeRequestResponse? {
    try {
      proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
      try {
        agentContext.writeScrapeRequest(scrapeRequest)
      } catch (_: ClosedSendChannelException) {
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          fetchDuration = scrapeRequest.ageDuration(),
        )
      }

      // Suspends until completed, agent disconnects, or timeout expires.
      if (!scrapeRequest.awaitCompleted(timeoutTime))
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = PROXY_TIMEOUT_LABEL,
          fetchDuration = scrapeRequest.ageDuration(),
        )

      return null
    } finally {
      scrapeRequest.closeChannel()
      val scrapeId = scrapeRequest.scrapeId
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
        ?: logger.error { "Scrape request $scrapeId missing in map" }
    }
  }

  // Parses the agent-reported Content-Type, falling back to text/plain (the correct default for the
  // Prometheus exposition format) with a warning when it is malformed.
  private fun parseContentType(
    rawContentType: String,
    path: String,
  ): ContentType =
    runCatching { ContentType.parse(rawContentType) }
      .getOrElse {
        logger.warn {
          "Error parsing content type for /$path: '$rawContentType' " +
            "(${it.simpleClassName}); falling back to text/plain"
        }
        Text.Plain.withCharset(Charsets.UTF_8)
      }

  // Decodes (and size-guards) a successful scrape body and assembles the response, returning a terminal
  // 413/502 response instead when the gzipped payload is a zip bomb or is corrupt.
  private fun buildSuccessResponse(
    proxy: Proxy,
    path: String,
    scrapeRequest: ScrapeRequestWrapper,
    scrapeResults: ScrapeResults,
    statusCode: HttpStatusCode,
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

    proxy.metrics {
      // Observe the byte count already computed during unzip (gzipped) or the small plain payload's
      // length, instead of re-encoding the decoded text just to measure it.
      val encoding = if (scrapeResults.srZipped) ProxyMetrics.ENCODING_GZIPPED else ProxyMetrics.ENCODING_PLAIN
      scrapeResponseBytes.labels(path, encoding).observe(decoded.byteCount.toDouble())
    }

    return ScrapeRequestResponse(
      statusCode = statusCode,
      contentType = contentType,
      contentText = decoded.text,
      failureReason = scrapeResults.srFailureReason,
      url = scrapeResults.srUrl,
      updateMsg = "success",
      fetchDuration = scrapeRequest.ageDuration(),
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
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
  ): ScrapeRequestWrapper {
    val authHeader = request.header(HttpHeaders.Authorization).orEmpty()

    if (authHeader.isNotEmpty() &&
      !proxy.options.isTlsEnabled &&
      authHeaderWithoutTlsWarned.compareAndSet(expectedValue = false, newValue = true)
    )
      logger.warn {
        "Authorization header is being forwarded to agent over a non-TLS gRPC connection. " +
          "Credentials may be exposed in transit. Configure TLS (--cert, --key) to secure the proxy-agent channel."
      }

    return ScrapeRequestWrapper(
      agentContext = agentContext,
      pathVal = path,
      encodedQueryParamsVal = encodedQueryParams,
      authHeaderVal = authHeader,
      acceptVal = request.header(HttpHeaders.Accept),
      debugEnabledVal = proxy.options.debugEnabled,
    )
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
)

internal data class ResponseResults(
  val statusCode: HttpStatusCode = HttpStatusCode.OK,
  val contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  val contentText: String = "",
  val updateMsgs: List<String> = emptyList(),
)
