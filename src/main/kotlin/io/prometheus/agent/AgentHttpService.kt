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

@file:Suppress(
  "TooGenericExceptionCaught",
  "InstanceOfCheckForException",
)

package io.prometheus.agent

import brave.Span
import io.prometheus.common.MetricBuilders.SUCCESS_LABEL
import io.prometheus.common.MetricBuilders.metricLabel
import io.ktor.util.AttributeKey
import com.google.common.net.HttpHeaders.ACCEPT
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.simpleClassName
import com.pambrose.common.util.zip
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readBuffer
import io.prometheus.Agent
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.agent.filter.FilterOutcome
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.ScrapeTracing.Companion.FETCH_SPAN
import io.prometheus.common.ScrapeTracing.Companion.TAG_LINES_DROPPED
import io.prometheus.common.ScrapeTracing.Companion.TAG_PATH
import io.prometheus.common.ScrapeTracing.Companion.TAG_RETRIES
import io.prometheus.common.ScrapeTracing.Companion.endedAs
import io.prometheus.common.ScrapeTracing.Companion.traced
import io.prometheus.common.Utils.EMPTY_BYTE_ARRAY
import io.prometheus.common.Utils.appendQueryParams
import io.prometheus.common.Utils.causeChain
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.Utils.sanitizeUrlsInText
import io.prometheus.common.hasTimeoutCause
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.scrapeRequest
import io.prometheus.metrics.core.datapoints.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Scrapes actual metrics endpoints via HTTP and builds [ScrapeResults].
 *
 * When the proxy dispatches a scrape request, this service fetches the target URL using
 * a Ktor [HttpClient] obtained from [HttpClientCache]. Handles response parsing, gzip
 * compression for large payloads, content-length limits, timeout/retry logic, and
 * authentication header forwarding. Returns [ScrapeResults] that are sent back to the
 * proxy over gRPC.
 *
 * @param agent the parent [Agent] instance
 * @see AgentGrpcService
 * @see HttpClientCache
 * @see AgentPathManager
 */
internal class AgentHttpService(
  val agent: Agent,
) {
  internal val httpClientCache =
    agent.run {
      HttpClientCache(
        maxCacheSize = options.maxCacheSize,
        maxAge = options.maxCacheAgeMins.minutes,
        maxIdleTime = options.maxCacheIdleMins.minutes,
        cleanupInterval = options.cacheCleanupIntervalMins.minutes,
      )
    }

  // Resolved once, as the agent is built: the HTTPS-scrape trust manager is fixed for the agent's lifetime, so it must
  // not be rebuilt (re-reading the trust store from disk) on every cached-client recreation. Resolved on first use, a
  // trust store that can't be loaded let the agent connect, then failed every scrape, HTTP targets included, with an
  // error that named the target; it now fails the agent's startup, naming the setting.
  private val httpsTrustManager: X509TrustManager? =
    agent.options.run {
      runCatching {
        resolveHttpsTrustManager(
          trustAllX509Certificates = trustAllX509Certificates,
          trustStorePath = httpsTrustStorePath,
          trustStorePassword = httpsTrustStorePassword,
        )
      }.getOrElse { e ->
        throw IllegalArgumentException(
          "Unable to load agent.http.trustStorePath '$httpsTrustStorePath': ${e.message ?: e.simpleClassName}",
          e,
        )
      }
    }

  // Where a MetricsSource is called, apart from the scrape that asks, so a call that won't return holds only its own
  // thread (see callSource); cancelled by close().
  private val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // The sources whose last call outlasted its scrape and hasn't returned, which aren't called again until it does.
  private val stuckSources: MutableSet<MetricsSource> = ConcurrentHashMap.newKeySet()

  suspend fun fetchScrapeUrl(scrapeRequest: ScrapeRequest): ScrapeResults {
    val pathContext = agent.pathManager[scrapeRequest.path]
    val span = fetchSpan(scrapeRequest, pathContext)
    return span.traced(ended = { tagged(it) }) {
      fetchAndRecord(scrapeRequest, pathContext, span)
    }
  }

  // The span of a fetch the proxy asked for, in the trace the proxy began for the scrape, or in one of its own from a
  // proxy that traces nothing. None for a scrape the proxy's dashboard asked for, which the metrics leave out too, or
  // one whose trace isn't kept: nothing propagates from it, so it's left out, and its tags aren't made.
  private fun fetchSpan(
    request: ScrapeRequest,
    pathContext: AgentPathManager.PathContext?,
  ): Span? =
    if (request.dashboardScrape)
      null
    else
      agent.tracing?.joined(request.traceContextMap, FETCH_SPAN)?.takeUnless { it.isNoop }?.apply {
        kind(Span.Kind.CLIENT)
        tag(TAG_PATH, request.path)
        pathContext?.also { tag("http.url", sanitizeUrl(it.url)) }
      }

  // Tags a fetch span with what the fetch got: its outcome, the target's status, the payload's size before and after
  // the path's filter, as it went to the proxy, and the lines the filter dropped. Its retries are tagged as each is
  // made (see newHttpClient), so a fetch that failed after them has them too.
  private fun Span.tagged(results: ScrapeResults) {
    endedAs(listOf(results.scrapeCounterMsg), results.srStatusCode)
    tag("gzipped", results.srZipped.toString())
    tag("chunked", results.isChunked(agent.options.chunkContentSizeBytes).toString())
    tag("bytes.raw", results.rawSize.toString())
    tag("bytes.filtered", results.filteredSize.toString())
    results.filterLinesDropped?.also { tag(TAG_LINES_DROPPED, it.toString()) }
  }

  // Fetches the scrape of [pathContext], null for a path the agent doesn't serve, and records it, in the metrics and
  // the path's history.
  private suspend fun fetchAndRecord(
    scrapeRequest: ScrapeRequest,
    pathContext: AgentPathManager.PathContext?,
    span: Span?,
  ): ScrapeResults {
    val started = TimeSource.Monotonic.markNow()
    val fetched =
      if (pathContext != null)
        fetchContentFromUrl(scrapeRequest, pathContext, span)
      else
        handleInvalidPath(scrapeRequest)
    // Counted here, so every scrape the proxy asks for is counted under its type, however it ends. A scrape the proxy's
    // dashboard asked for is counted only as one, and its results are marked so the agent doesn't count them either:
    // the agent_scrape_* metrics count only Prometheus's scrapes, whose rates they report.
    val results =
      if (scrapeRequest.dashboardScrape) {
        agent.updateDashboardScrapeCounter()
        fetched.asDashboardScrape()
      } else {
        agent.updateScrapeCounter(fetched.scrapeCounterMsg)
        fetched
      }
    // Recorded for the agent's dashboard, marked when the proxy's dashboard asked for it, and only for a path the agent
    // serves: a ring for each path a proxy names would grow without end.
    if (pathContext != null)
      agent.scrapeHistory?.record(
        pathContext.path,
        AgentScrapeRecord.of(results, started.elapsedNow(), agent.options.chunkContentSizeBytes),
      )
    return results
  }

  /**
   * One scrape of [url], filtered by [filter], for the agent's dashboard (its Scrape now), through the code a scrape
   * the proxy asks for takes, so its result is what Prometheus would get. Counted and recorded nowhere: not in the
   * `agent_scrape_*` metrics, `agent_dashboard_scrapes_total`, the filter's counts, or the path's history, which stay a
   * record of the scrapes the proxy asked for. It waits at most [timeout] for the target, which is told so, asks it for
   * text, since the dashboard shows the body, and fills in the failure reason, as a scrape with debug on does. A path
   * served in-process is read from its [source], in place of [url].
   */
  internal suspend fun fetchForDashboard(
    url: String,
    filter: MetricFilter?,
    timeout: Duration,
    source: MetricsSource? = null,
  ): ScrapeResults {
    // Marked as a dashboard's, so the filter counts nothing; the request goes to no proxy, so nothing else reads it.
    val request =
      scrapeRequest {
        accept = DASHBOARD_ACCEPT
        scrapeTimeoutMillis = timeout.inWholeMilliseconds
        debugEnabled = true
        dashboardScrape = true
      }
    return fetchGuarded(ScrapeTarget(url, source), request, filter)
  }

  // Times a scrape for agent_scrape_request_latency_seconds, unless the metrics are off or the proxy's dashboard asked
  // for the scrape.
  private fun latencyTimer(request: ScrapeRequest): Timer? =
    if (agent.isMetricsEnabled && !request.dashboardScrape) agent.startTimer() else null

  private suspend fun fetchContentFromUrl(
    req: ScrapeRequest,
    pathContext: AgentPathManager.PathContext,
    span: Span?,
  ): ScrapeResults {
    val requestTimer = latencyTimer(req)
    // Add the incoming query params to the url; a source gets those it takes from the request (see fetchFromSource).
    val target = pathContext.target
    val url = if (target.source == null) appendQueryParams(target.url, req.encodedQueryParams) else target.url
    logger.debug { "Fetching $pathContext ${if (url.isNotBlank()) "URL: ${sanitizeUrl(url)}" else ""}" }
    return try {
      fetchGuarded(target.copy(url = url), req, pathContext.filter, span)
    } finally {
      requestTimer?.observeDuration()
    }
  }

  // Fetches [target], its URL or its source, within the scrape's timeout, a failure becoming results that say why.
  private suspend fun fetchGuarded(
    target: ScrapeTarget,
    req: ScrapeRequest,
    filter: MetricFilter?,
    span: Span? = null,
  ): ScrapeResults =
    try {
      withTimeout(scrapeTimeout(req)) {
        val source = target.source
        if (source == null)
          fetchContent(target.url, req, filter, span)
        else
          fetchFromSource(source, target.url, req, filter)
      }
    } catch (e: Throwable) {
      failedResults(e, req, sanitizeUrl(target.url))
    }

  // Reads [source], a path served in-process, whose target shows as [label], as a target's body is read: on a thread
  // it may block, which the scrape's timeout interrupts, in a text format the scrape accepts, up to maxContentLength,
  // and through [filter]. A source that fails fails the scrape, as a target that can't be reached does, unless it
  // throws a VirtualMachineError.
  private suspend fun fetchFromSource(
    source: MetricsSource,
    label: String,
    req: ScrapeRequest,
    filter: MetricFilter?,
  ): ScrapeResults {
    val out = CappedBytes(maxContentLength)
    val written = runCatchingCancellable { callSource(source, req, out) }
    // Checked first, so a source that caught the cap's exception, or wrapped it in its own, is refused all the same.
    if (out.exceeded) {
      val msg = "Content size exceeds maximum allowed size of $maxContentLength bytes"
      logger.warn { "$msg for $label (scrapeId: ${req.scrapeId})" }
      return tooLarge(req, label, msg)
    }
    val contentType = written.getOrThrow().ifBlank { TEXT_FORMAT }
    return scrapedResults(ScrapedBody(out.toByteArray(), contentType, HttpStatusCode.OK.value), label, req, filter)
  }

  // Calls [source] for [req], writing to [out], apart from the scrape: its timeout interrupts the call and stops
  // waiting for it, so a source stuck in a call that ignores interrupts, such as a socket read, holds a thread of its
  // own, but not the scrape, the agent's other scrapes, or its shutdown. Until that call returns, the source isn't
  // called again, so stuck calls don't pile up. What the source throws, short of a VirtualMachineError, fails only the
  // scrape (see sourceFailure).
  private suspend fun callSource(
    source: MetricsSource,
    req: ScrapeRequest,
    out: CappedBytes,
  ): String {
    if (source in stuckSources)
      throw IOException("The source's last call, which outlasted its scrape's timeout, hasn't returned")
    val call =
      sourceScope.async {
        try {
          runInterruptible { source.write(textOnlyAccept(req.accept), requestedNames(req.encodedQueryParams), out) }
        } catch (e: Throwable) {
          throw sourceFailure(e, cancelled = !isActive)
        }
      }
    try {
      return call.await()
    } finally {
      // Abandoned, the scrape having timed out or been cancelled: interrupt the call, and wait no longer for it.
      if (!call.isCompleted) {
        stuckSources += source
        call.invokeOnCompletion { stuckSources -= source }
        call.cancel()
      }
    }
  }

  // The results of a fetch of [logUrl] that failed with [e], unless [e] must propagate.
  private fun failedResults(
    e: Throwable,
    req: ScrapeRequest,
    logUrl: String,
  ): ScrapeResults {
    // Catch regular exceptions, Ktor's HttpRequestTimeoutException (an IOException) among them, and the scrape
    // timeout's TimeoutCancellationException, but let other CancellationExceptions propagate (like system shutdown).
    // Uses the shared cause-walk predicate so the catch branch and the status-code mapping agree (finding 34).
    if (e is CancellationException && !e.hasTimeoutCause())
      throw e

    // Re-throw JVM Errors (OutOfMemoryError, StackOverflowError, etc.) so the agent terminates
    // instead of swallowing them into a routine 503 result and running in a corrupted state.
    // Mirrors the Error-rethrow policy at Agent.handleConnectionFailure() and connectAgent().
    if (e is Error)
      throw e

    return ScrapeResults(
      srAgentId = req.agentId,
      srScrapeId = req.scrapeId,
      srStatusCode = errorCode(e, logUrl),
      srFailureReason = scrapeFailureReason(e, req.debugEnabled),
      srUrl = if (req.debugEnabled) logUrl else "",
      // A fetch that got no answer: the agent's own timeout, or a refused connection, DNS failure, and the like.
      scrapeCounterMsg = if (e.hasTimeoutCause()) TIMED_OUT_MSG else FAILURE_MSG,
    )
  }

  // A scrape stops at the agent's scrapeTimeoutSecs, or sooner when the proxy forwards a shorter timeout from the
  // scraping client (Prometheus's X-Prometheus-Scrape-Timeout-Seconds), so the agent does not finish a scrape nobody is
  // still waiting for. 0 means the client sent none, or the proxy predates the field.
  internal fun scrapeTimeout(request: ScrapeRequest): Duration {
    val configured = agent.options.scrapeTimeoutSecs.seconds
    return if (request.scrapeTimeoutMillis > 0)
      minOf(configured, request.scrapeTimeoutMillis.milliseconds)
    else
      configured
  }

  internal suspend fun fetchContent(
    url: String,
    scrapeRequest: ScrapeRequest,
    filter: MetricFilter? = null,
    span: Span? = null,
  ): ScrapeResults {
    val clientKey = with(Url(url)) { ClientKey(user, password) }
    val entry = httpClientCache.getOrCreateClient(clientKey) { newHttpClient(clientKey) }
    try {
      // A streaming request, so the body is read only by buildScrapeResults, which stops at the size limit. A plain
      // request reads the whole body into the heap before its response block runs, however large it is.
      return entry.client.prepareGet(url, prepareRequestHeaders(scrapeRequest, span)).execute { response ->
        buildScrapeResults(response, url, scrapeRequest, filter)
      }
    } finally {
      httpClientCache.onFinishedWithClient(entry)
    }
  }

  private fun prepareRequestHeaders(
    request: ScrapeRequest,
    span: Span?,
  ): HttpRequestBuilder.() -> Unit =
    {
      val limit = scrapeTimeout(request)
      logger.debug { "Setting scrape timeout = $limit" }
      timeout { requestTimeoutMillis = limit.inWholeMilliseconds }
      // Exporters such as blackbox_exporter and snmp_exporter size their work by this header, as they do when
      // Prometheus scrapes them directly.
      header(SCRAPE_TIMEOUT_HEADER, limit.toHeaderSeconds())
      if (!request.dashboardScrape)
        attributes.put(PROMETHEUS_SCRAPE, Unit)
      span?.also { attributes.put(FETCH_TRACE_SPAN, it) }

      // Set non-default headers
      // The scrape travels to Prometheus as text, so the target is asked only for a text format; see textOnlyAccept.
      textOnlyAccept(request.accept).takeIf { it.isNotEmpty() }?.let { header(ACCEPT, it) }
      val authHeader = request.authHeader.ifBlank { null }
      authHeader?.also { header(HttpHeaders.Authorization, it) }
    }

  private suspend fun buildScrapeResults(
    response: HttpResponse,
    url: String,
    scrapeRequest: ScrapeRequest,
    filter: MetricFilter? = null,
  ): ScrapeResults {
    val statusCode = response.status.value
    val safeUrl = sanitizeUrl(url)
    return if (response.status.isSuccess()) {
      val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
      if (contentLength != null && contentLength > maxContentLength) {
        val msg = "Content length $contentLength exceeds maximum allowed size $maxContentLength"
        // Only the log line names the target and scrape; msg is also the failure reason returned to the proxy.
        logger.warn { "$msg for $safeUrl (scrapeId: ${scrapeRequest.scrapeId})" }
        // The body is never read, so cancel the call: a streaming request's cleanup otherwise waits for the body to
        // finish arriving, which a target that stalls after its headers drags out to the scrape timeout.
        response.cancel()
        return tooLarge(scrapeRequest, safeUrl, msg)
      }

      val contentType = response.headers[CONTENT_TYPE].orEmpty()

      // Read at most maxContentLength + 1 bytes straight from the response channel. A response with
      // no Content-Length header (e.g. chunked transfer encoding) or an understated one bypasses the
      // header guard above, and bodyAsText() would buffer the entire body into the heap before the
      // size check below — a memory-exhaustion vector. readBuffer caps the read, so the guard
      // runs against a bounded buffer; fetchContent's request streams, so nothing read the body before
      // this. The bytes are reused for both the size check and gzip;
      // ByteArray.zip() avoids the second UTF-8 encode that String.zip() would do.
      val contentBytes = response.bodyAsChannel().readBuffer(maxContentLength + 1).readByteArray()
      val contentByteSize = contentBytes.size.toLong()
      if (contentByteSize > maxContentLength) {
        val msg = "Content size exceeds maximum allowed size of $maxContentLength bytes"
        logger.warn { "$msg for $safeUrl (scrapeId: ${scrapeRequest.scrapeId})" }
        return tooLarge(scrapeRequest, safeUrl, msg)
      }
      scrapedResults(ScrapedBody(contentBytes, contentType, statusCode), safeUrl, scrapeRequest, filter)
    } else {
      ScrapeResults(
        srAgentId = scrapeRequest.agentId,
        srScrapeId = scrapeRequest.scrapeId,
        srStatusCode = statusCode,
        srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
        srFailureReason = if (scrapeRequest.debugEnabled) "Unsuccessful response code $statusCode" else "",
        scrapeCounterMsg = UNSUCCESSFUL_MSG,
      )
    }
  }

  // agent.http.maxContentLengthMBytes, in bytes: the most of a target's body, or of what a source writes, that's read.
  private val maxContentLength: Long get() = agent.options.maxContentLengthMBytes * 1024L * 1024L

  // The results of a scrape that got [body] from the target at [safeUrl].
  private fun scrapedResults(
    body: ScrapedBody,
    safeUrl: String,
    scrapeRequest: ScrapeRequest,
    filter: MetricFilter?,
  ): ScrapeResults {
    val contentBytes = body.bytes
    // Filter before the gzip decision so gzip and chunking both see the reduced payload. The
    // maxContentLength guard deliberately runs against the RAW bytes: it is a memory bound,
    // and filtering must not become a way around it. A null outcome means the filter deliberately
    // passed the payload through (unfilterable content type, or a body that is not valid UTF-8), so
    // no metrics are recorded and the raw bytes are used verbatim.
    val outcome = filter?.let { filtered(it, contentBytes, body.contentType, scrapeRequest) }
    val filteredBytes = outcome?.bytes ?: contentBytes

    val zipped = filteredBytes.size > agent.options.minGzipSizeBytes
    return ScrapeResults(
      srAgentId = scrapeRequest.agentId,
      srScrapeId = scrapeRequest.scrapeId,
      srValidResponse = true,
      srStatusCode = body.statusCode,
      srContentType = body.contentType,
      srZipped = zipped,
      // A filtered payload is already in hand as text -- don't decode the bytes it was just encoded to.
      srContentAsText = if (zipped) "" else (outcome?.text ?: filteredBytes.decodeToString()),
      srContentAsZipped = if (zipped) filteredBytes.zip() else EMPTY_BYTE_ARRAY,
      srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
      scrapeCounterMsg = SUCCESS_LABEL,
      rawSize = contentBytes.size,
      filteredSize = filteredBytes.size,
      filterLinesDropped = outcome?.linesDropped,
    )
  }

  // A scrape refused for a body over agent.http.maxContentLengthMBytes, as [msg] says; only debug sends the proxy why.
  private fun tooLarge(
    scrapeRequest: ScrapeRequest,
    safeUrl: String,
    msg: String,
  ) = ScrapeResults(
    srAgentId = scrapeRequest.agentId,
    srScrapeId = scrapeRequest.scrapeId,
    srStatusCode = HttpStatusCode.PayloadTooLarge.value,
    srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
    srFailureReason = if (scrapeRequest.debugEnabled) msg else "",
    scrapeCounterMsg = UNSUCCESSFUL_MSG,
  )

  // [bytes] filtered by [filter]. What it drops is counted in the filter's counts and agent_filter_* only for a scrape
  // Prometheus asked for: a dashboard's, the proxy's or the agent's own, counts nothing, as it counts in no
  // agent_scrape_* metric.
  private fun filtered(
    filter: MetricFilter,
    bytes: ByteArray,
    contentType: String,
    scrapeRequest: ScrapeRequest,
  ): FilterOutcome? {
    if (scrapeRequest.dashboardScrape)
      return filter.previewBytes(bytes, contentType)
    // Only paths with a filter ever create series here, so cardinality stays bounded. Labeled by the path's key, which
    // fetchScrapeUrl looked the path up by, so a path's series is the same whichever proxy asked.
    val path = AgentPathManager.requestedPathKey(scrapeRequest.path)
    val outcome =
      filter.filterBytes(bytes, contentType) { reason ->
        agent.metrics { filterFailOpenCount.labelValues(agent.launchId, path, reason.metricLabel).inc() }
      }
    outcome?.also {
      agent.metrics {
        filterLinesDropped.labelValues(agent.launchId, path).inc(it.linesDropped.toDouble())
        filterBytesSaved.labelValues(agent.launchId, path).inc(it.bytesSaved.toDouble())
      }
    }
    return outcome
  }

  private fun newHttpClient(clientKey: ClientKey): HttpClient =
    HttpClient(CIO) {
      expectSuccess = false
      // Redirects are followed by followSameOriginRedirects, installed below, rather than by Ktor.
      followRedirects = false
      engine {
        val timeoutSecs =
          resolveTimeoutSecs(
            cioTimeoutSecs = agent.configVals.agent.internal.cioTimeoutSecs,
            httpClientTimeoutSecs = agent.options.httpClientTimeoutSecs,
          )

        requestTimeout = timeoutSecs.seconds.inWholeMilliseconds

        // Trust-all (insecure, all-or-nothing) takes precedence; otherwise a custom JKS/PKCS12 trust
        // store (e.g. a private CA); otherwise the JDK default trust store. Resolved once — see the
        // httpsTrustManager field — rather than re-read from disk on every client (re)creation.
        httpsTrustManager?.also { https { trustManager = it } }
      }

      install(HttpTimeout)

      install(HttpRequestRetry) {
        agent.options.scrapeMaxRetries.also { maxRetries ->
          if (maxRetries <= 0) {
            noRetry()
          } else {
            retryOnException(maxRetries)
            retryIf(maxRetries) { _, response ->
              response.status.value in 500..599
            }
            modifyRequest { request ->
              request.headers.append("x-retry-count", retryCount.toString())
              if (request.attributes.contains(PROMETHEUS_SCRAPE))
                agent.metrics { scrapeRetryCount.labelValues(agent.launchId).inc() }
              // Each retry's count replaces the last's, so the fetch's span ends with how many there were.
              request.attributes.getOrNull(FETCH_TRACE_SPAN)?.tag(TAG_RETRIES, retryCount.toString())
            }
            exponentialDelay(maxDelayMs = MAX_RETRY_DELAY_MS)
          }
        }
      }

      // Setup authentication if username and password are specified. The credentials go with the first request rather
      // than in answer to a 401, since a target that answers an unauthenticated request with 403 or a login redirect
      // never sends one. An Authorization header forwarded from Prometheus is sent instead, as it always was, and the
      // URL's credentials answer a 401 to it. Only same-origin redirects are followed, so they reach only the target.
      clientKey.credentials?.let { creds ->
        install(Auth) {
          basic {
            sendWithoutRequest { request -> !request.headers.contains(HttpHeaders.Authorization) }
            credentials {
              BasicAuthCredentials(creds.username, creds.password)
            }
          }
        }
      }
    }.apply { plugin(HttpSend).intercept { request -> followSameOriginRedirects(request) } }

  // Follows a redirect only to the request's own origin: the same scheme, host, and port. Ktor's HttpRedirect followed
  // any redirect and sent every hop back through the Auth plugin, whose Basic provider answers a 401 challenge from
  // whatever host sent it -- so a target, or an open redirect reached through query parameters any HTTP caller can
  // forward, could collect the credentials configured for the target, or point the agent at an internal address
  // whose body came back through the proxy's scrape port. With no other origin reachable, the Auth plugin only ever
  // answers the target itself. A redirect elsewhere is returned as it is, so the scrape reports its 3xx status.
  // Opts in to Ktor's internal takeFromWithExecutionContext, which Ktor's own HttpRedirect uses to build each hop; if
  // Ktor changes it, the upgrade fails to compile here rather than silently losing the request timeout.
  @OptIn(InternalAPI::class)
  private suspend fun Sender.followSameOriginRedirects(request: HttpRequestBuilder): HttpClientCall {
    var call = execute(request)
    repeat(MAX_REDIRECTS) {
      val location = call.response.headers[HttpHeaders.Location]
      if (call.response.status !in REDIRECT_STATUSES || location == null)
        return call
      val from = call.request.url
      val to = URLBuilder(from).apply { parameters.clear() }.takeFrom(location).build()
      if (!from.hasSameOrigin(to)) {
        logger.warn {
          "Not following redirect from ${sanitizeUrl(from.toString())} to another origin: " +
            sanitizeUrl(to.toString())
        }
        return call
      }
      // WithExecutionContext: HttpTimeout arms its request timeout on the original request's execution context, and
      // closing the client cancels through it; a plain takeFrom gave each hop a fresh one that neither reached.
      call = execute(HttpRequestBuilder().takeFromWithExecutionContext(request).apply { url.takeFrom(to) })
    }
    return call
  }

  private fun Url.hasSameOrigin(other: Url) =
    protocol == other.protocol && host.equals(other.host, ignoreCase = true) && port == other.port

  suspend fun close() {
    sourceScope.cancel()
    httpClientCache.close()
  }

  companion object {
    private val logger = logger {}

    // On a request for Prometheus's scrape, not a dashboard's: agent_scrape_retries_total counts its retries.
    private val PROMETHEUS_SCRAPE = AttributeKey<Unit>("PrometheusScrape")

    // On a request for a traced fetch: its span, which each retry tags. A retry's request copies its attributes.
    private val FETCH_TRACE_SPAN = AttributeKey<Span>("FetchTraceSpan")

    // The failure reason is sent to the proxy -- which writes it to its activity log -- even with debug off,
    // and HTTP client exception messages embed the request URL, credentials included.
    internal fun scrapeFailureReason(
      e: Throwable,
      debugEnabled: Boolean,
    ): String {
      val safeMessage = e.message?.let { sanitizeUrlsInText(it) }
      return if (debugEnabled) "${e.simpleClassName} - $safeMessage" else (safeMessage ?: e.simpleClassName)
    }

    // Factory default (in seconds) shared by both http.clientTimeoutSecs and the deprecated
    // internal.cioTimeoutSecs in config/config.conf. Used to detect whether the deprecated
    // cioTimeoutSecs was explicitly overridden while clientTimeoutSecs was left at its default.
    // MUST stay in sync with the defaults in config/config.conf.
    private const val DEFAULT_HTTP_TIMEOUT_SECS = 90

    /**
     * Resolves the effective HTTP client request timeout.
     *
     * Honors the deprecated `internal.cioTimeoutSecs` only when it was explicitly overridden (set
     * to a non-[default] value) while the replacement `http.clientTimeoutSecs` was left at its
     * [default]. In every other case `http.clientTimeoutSecs` wins.
     */
    internal fun resolveTimeoutSecs(
      cioTimeoutSecs: Int,
      httpClientTimeoutSecs: Int,
      default: Int = DEFAULT_HTTP_TIMEOUT_SECS,
    ): Int =
      if (cioTimeoutSecs != default && httpClientTimeoutSecs == default)
        cioTimeoutSecs
      else
        httpClientTimeoutSecs

    /**
     * Selects the [X509TrustManager] for the HTTPS scrape client, or `null` to use the JDK default
     * trust store. [trustAllX509Certificates] (insecure, all-or-nothing) takes precedence over a
     * custom [trustStorePath]; an empty path with trust-all disabled yields `null`.
     */
    internal fun resolveHttpsTrustManager(
      trustAllX509Certificates: Boolean,
      trustStorePath: String,
      trustStorePassword: String,
    ): X509TrustManager? =
      when {
        trustAllX509Certificates -> TrustAllX509TrustManager
        trustStorePath.isNotEmpty() -> SslSettings.getTrustManager(trustStorePath, trustStorePassword)
        else -> null
      }

    private const val INVALID_PATH_MSG = "invalid_path"
    private const val UNSUCCESSFUL_MSG = "unsuccessful"
    private const val TIMED_OUT_MSG = "timed_out"
    private const val FAILURE_MSG = "failure"

    // Cap on each retry's backoff delay; the scrape timeout bounds the retries' total time (finding 28).
    private const val MAX_RETRY_DELAY_MS = 5000L

    private const val PROTOBUF_MEDIA_TYPE = "application/vnd.google.protobuf"

    // What the agent dashboard's Scrape now asks a target for: text, which the dashboard shows, as the proxy's does.
    private const val DASHBOARD_ACCEPT = "text/plain"

    // Prometheus's scrape timeout, which the proxy also reads from the scrape request.
    private const val SCRAPE_TIMEOUT_HEADER = "X-Prometheus-Scrape-Timeout-Seconds"

    // Seconds in the format Prometheus sends them: "10", "2.5".
    private fun Duration.toHeaderSeconds(): String =
      BigDecimal(inWholeMilliseconds).movePointLeft(3).stripTrailingZeros().toPlainString()

    /**
     * [accept], Prometheus's `Accept` header, without the protobuf media type.
     *
     * The agent and proxy carry a scrape body as text: the agent decodes a small body with `decodeToString()`, and the
     * proxy turns every body into a `String`. A target answering in binary protobuf -- which Prometheus asks for first
     * when native histograms are enabled -- was therefore delivered corrupted, and Prometheus failed the scrape.
     * Dropping protobuf leaves the text and OpenMetrics offers, so the target answers in one of those. Returns an empty
     * string when nothing else was offered, in which case no `Accept` header is sent and the target uses its default.
     */
    internal fun textOnlyAccept(accept: String): String =
      accept.split(',')
        .filterNot { it.substringBefore(';').trim().equals(PROTOBUF_MEDIA_TYPE, ignoreCase = true) }
        .joinToString(",") { it.trim() }

    // The content type of a source that names none: the Prometheus text format, which a scrape always accepts.
    private const val TEXT_FORMAT = "text/plain; version=0.0.4; charset=utf-8"

    // What the agent makes of [e], which a source's call threw: the call's own cancellation, when it was [cancelled],
    // or a VirtualMachineError, as they are, and an exception as it is; but an Error, or a CancellationException or
    // InterruptedException the source threw itself, as an IOException, so it fails the scrape and not the agent.
    private fun sourceFailure(
      e: Throwable,
      cancelled: Boolean,
    ): Throwable {
      // runInterruptible gives an InterruptedException as a CancellationException caused by it, which the coroutines'
      // stack trace recovery can wrap again
      val interrupted = (e as? CancellationException)?.causeChain()?.firstOrNull { it is InterruptedException }
      val thrown = interrupted ?: e
      return when {
        e is CancellationException && cancelled -> {
          e
        }

        thrown is VirtualMachineError -> {
          thrown
        }

        thrown is Error || thrown is CancellationException || thrown is InterruptedException -> {
          IOException("The source threw ${thrown.simpleClassName}: ${thrown.message}", thrown)
        }

        else -> {
          thrown
        }
      }
    }

    // The metric families a scrape asks for with name[] parameters, as the Prometheus client libraries read them; empty
    // for all of them.
    private fun requestedNames(encodedQueryParams: String): Set<String> =
      if (encodedQueryParams.isBlank())
        emptySet()
      else
        parseQueryString(encodedQueryParams).getAll("name[]").orEmpty().toSet()

    // Ktor's own default is 20; a metrics endpoint needs a hop or two at most.
    private const val MAX_REDIRECTS = 5

    private val REDIRECT_STATUSES =
      setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
      )

    private fun handleInvalidPath(scrapeRequest: ScrapeRequest): ScrapeResults {
      logger.warn { "Invalid path in fetchScrapeUrl(): ${scrapeRequest.path}" }
      return ScrapeResults(
        srAgentId = scrapeRequest.agentId,
        srScrapeId = scrapeRequest.scrapeId,
        srStatusCode = HttpStatusCode.NotFound.value,
        scrapeCounterMsg = INVALID_PATH_MSG,
        srUrl = if (scrapeRequest.debugEnabled) "None" else "",
        srFailureReason = if (scrapeRequest.debugEnabled) "Invalid path: ${scrapeRequest.path}" else "",
      )
    }
  }
}

// A successful scrape's body, within maxContentLength: its [bytes], their [contentType], and the [statusCode] the
// target answered with.
private class ScrapedBody(
  val bytes: ByteArray,
  val contentType: String,
  val statusCode: Int,
)

// What a MetricsSource writes, up to [limit] bytes, as a target's body is read only up to the limit: a write past it
// throws, and [exceeded] records it, so a source that catches the exception is refused all the same. The bytes go into
// a segmented buffer, as a target's body does, not an array that doubles as it grows, so a body near the limit takes
// about twice the limit with its copy into toByteArray()'s, not three times. Each write is atomic, so a source writing
// from several threads at once neither loses bytes nor passes the limit between one write's check and its bytes.
private class CappedBytes(
  private val limit: Long,
) : OutputStream() {
  private val buffer = Buffer()

  // Read on the scrape's thread, set on whichever thread the source wrote from
  @Volatile
  var exceeded = false
    private set

  @Synchronized
  override fun write(b: Int) {
    reserve(1)
    buffer.writeByte(b.toByte())
  }

  @Synchronized
  override fun write(
    b: ByteArray,
    off: Int,
    len: Int,
  ) {
    Objects.checkFromIndexSize(off, len, b.size)
    reserve(len)
    buffer.write(b, off, off + len)
  }

  // The bytes written, which this takes out
  @Synchronized
  fun toByteArray(): ByteArray = buffer.readByteArray()

  private fun reserve(len: Int) {
    if (buffer.size + len > limit) {
      exceeded = true
      throw IOException("Metrics exceed the maximum allowed size of $limit bytes")
    }
  }
}
