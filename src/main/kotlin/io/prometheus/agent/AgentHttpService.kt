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

import com.google.common.net.HttpHeaders.ACCEPT
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.pambrose.common.dsl.KtorDsl.get
import com.pambrose.common.util.EMPTY_BYTE_ARRAY
import com.pambrose.common.util.simpleClassName
import com.pambrose.common.util.zip
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.prometheus.Agent
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.Utils.appendQueryParams
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.hasTimeoutCause
import io.prometheus.grpc.ScrapeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import java.nio.charset.CharacterCodingException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

  // Resolved once on first use: the HTTPS-scrape trust manager is fixed for the agent's lifetime, so
  // it must not be rebuilt (re-reading the trust store from disk) on every cached-client recreation.
  private val httpsTrustManager: X509TrustManager? by lazy {
    resolveHttpsTrustManager(
      trustAllX509Certificates = agent.options.trustAllX509Certificates,
      trustStorePath = agent.options.httpsTrustStorePath,
      trustStorePassword = agent.options.httpsTrustStorePassword,
    )
  }

  // Paths already warned about an unfilterable content type. A scrape recurs every scrape interval
  // forever, so an unguarded warn here would be a permanent log-spam source.
  private val contentTypeWarnedPaths = ConcurrentHashMap.newKeySet<String>()

  // Paths already warned about a non-UTF-8 body on a filterable content type. Kept separate from
  // contentTypeWarnedPaths (distinct condition, distinct message, and independently testable) --
  // a path that already tripped one guard must still be able to warn once for the other.
  private val invalidUtf8WarnedPaths = ConcurrentHashMap.newKeySet<String>()

  suspend fun fetchScrapeUrl(scrapeRequest: ScrapeRequest): ScrapeResults {
    val pathContext = agent.pathManager[scrapeRequest.path]
    return if (pathContext != null)
      fetchContentFromUrl(scrapeRequest, pathContext)
    else
      handleInvalidPath(scrapeRequest)
  }

  private suspend fun fetchContentFromUrl(
    req: ScrapeRequest,
    pathContext: AgentPathManager.PathContext,
  ): ScrapeResults {
    val requestTimer = if (agent.isMetricsEnabled) agent.startTimer() else null
    // Add the incoming query params to the url
    val url = appendQueryParams(pathContext.url, req.encodedQueryParams)
    val logUrl = sanitizeUrl(url)
    logger.debug { "Fetching $pathContext ${if (url.isNotBlank()) "URL: $logUrl" else ""}" }

    // Content is fetched here
    val results =
      try {
        withTimeout(agent.options.scrapeTimeoutSecs.seconds) {
          fetchContent(url, req, pathContext.filter)
        }
      } catch (e: Throwable) {
        // Catch regular exceptions and HttpRequestTimeoutException (which is a CancellationException)
        // but let other CancellationExceptions propagate (like system shutdown). Uses the shared
        // cause-walk predicate so the catch branch and the status-code mapping agree (finding 34).
        if (e is CancellationException && !e.hasTimeoutCause())
          throw e

        // Re-throw JVM Errors (OutOfMemoryError, StackOverflowError, etc.) so the agent terminates
        // instead of swallowing them into a routine 503 result and running in a corrupted state.
        // Mirrors the Error-rethrow policy at Agent.handleConnectionFailure() and connectAgent().
        if (e is Error)
          throw e

        ScrapeResults(
          srAgentId = req.agentId,
          srScrapeId = req.scrapeId,
          srStatusCode = errorCode(e, logUrl),
          srFailureReason =
            if (req.debugEnabled) "${e.simpleClassName} - ${e.message}" else (e.message ?: e.simpleClassName),
          srUrl = if (req.debugEnabled) logUrl else "",
        )
      } finally {
        requestTimer?.observeDuration()
      }
    agent.updateScrapeCounter(results.scrapeCounterMsg)
    return results
  }

  internal suspend fun fetchContent(
    url: String,
    scrapeRequest: ScrapeRequest,
    filter: MetricFilter? = null,
  ): ScrapeResults {
    val clientKey = with(Url(url)) { ClientKey(user, password) }
    val entry = httpClientCache.getOrCreateClient(clientKey) { newHttpClient(clientKey) }
    try {
      var result: ScrapeResults? = null
      entry.client.get(
        url = url,
        setUp = prepareRequestHeaders(scrapeRequest),
      ) { response ->
        result = buildScrapeResults(response, url, scrapeRequest, filter)
      }
      return requireNotNull(result) { "Response handler was not called for ${sanitizeUrl(url)}" }
    } finally {
      httpClientCache.onFinishedWithClient(entry)
    }
  }

  private fun prepareRequestHeaders(request: ScrapeRequest): HttpRequestBuilder.() -> Unit =
    {
      val scrapeTimeout = agent.options.scrapeTimeoutSecs.seconds
      logger.debug { "Setting scrapeTimeoutSecs = $scrapeTimeout" }
      timeout { requestTimeoutMillis = scrapeTimeout.inWholeMilliseconds }

      // Set non-default headers
      if (request.accept.isNotEmpty()) header(ACCEPT, request.accept)
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
      val maxContentLength = agent.options.maxContentLengthMBytes * 1024L * 1024L
      val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
      if (contentLength != null && contentLength > maxContentLength) {
        val msg = "Content length $contentLength exceeds maximum allowed size $maxContentLength"
        logger.warn { msg }
        return ScrapeResults(
          srAgentId = scrapeRequest.agentId,
          srScrapeId = scrapeRequest.scrapeId,
          srStatusCode = HttpStatusCode.PayloadTooLarge.value,
          srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
          srFailureReason = if (scrapeRequest.debugEnabled) msg else "",
          scrapeCounterMsg = UNSUCCESSFUL_MSG,
        )
      }

      val contentType = response.headers[CONTENT_TYPE].orEmpty()

      // Read at most maxContentLength + 1 bytes straight from the response channel. A response with
      // no Content-Length header (e.g. chunked transfer encoding) or an understated one bypasses the
      // header guard above, and bodyAsText() would buffer the entire body into the heap before the
      // size check below — a memory-exhaustion vector. readRemaining caps the read, so the guard
      // runs against a bounded buffer. The bytes are reused for both the size check and gzip;
      // ByteArray.zip() avoids the second UTF-8 encode that String.zip() would do.
      val contentBytes = response.bodyAsChannel().readRemaining(maxContentLength + 1).readByteArray()
      val contentByteSize = contentBytes.size.toLong()
      if (contentByteSize > maxContentLength) {
        val msg = "Content size exceeds maximum allowed size of $maxContentLength bytes"
        logger.warn { msg }
        return ScrapeResults(
          srAgentId = scrapeRequest.agentId,
          srScrapeId = scrapeRequest.scrapeId,
          srStatusCode = HttpStatusCode.PayloadTooLarge.value,
          srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
          srFailureReason = if (scrapeRequest.debugEnabled) msg else "",
          scrapeCounterMsg = UNSUCCESSFUL_MSG,
        )
      }

      // Filter before the gzip decision so gzip and chunking both see the reduced payload. The
      // maxContentLength guard above deliberately runs against the RAW bytes: it is a memory bound,
      // and filtering must not become a way around it.
      val filteredBytes =
        if (filter == null) {
          contentBytes // Unfiltered paths keep the pure byte path -- no decode, no cost.
        } else if (!isFilterableContentType(contentType)) {
          // Warn once per path -- this recurs on every scrape, so an unguarded warn would spam forever.
          if (contentTypeWarnedPaths.add(scrapeRequest.path)) {
            logger.warn {
              "Skipping metric filter for /${scrapeRequest.path}: content type \"$contentType\" is not text"
            }
          }
          contentBytes
        } else {
          // Fail open on undecodable bytes, mirroring the non-filterable-content-type branch above.
          // Lenient decodeToString() replaces each malformed byte with U+FFFD, which encodes back to
          // 3 bytes -- silently corrupting the payload, and (if the expansion outgrows what filtering
          // removed) driving contentBytes.size - bytes.size negative, which Counter.inc() rejects with
          // IllegalArgumentException, turning a routine scrape into a 503. A strict decode sidesteps
          // both: on malformed input it throws instead of substituting, so we can detect it and pass
          // the raw bytes through untouched rather than ever recording a corrupted/negative delta.
          val decoded =
            try {
              contentBytes.decodeToString(throwOnInvalidSequence = true)
            } catch (expected: CharacterCodingException) {
              null
            }
          if (decoded == null) {
            // Warn once per path -- this recurs on every scrape, so an unguarded warn would spam forever.
            if (invalidUtf8WarnedPaths.add(scrapeRequest.path)) {
              logger.warn {
                "Skipping metric filter for /${scrapeRequest.path}: response body is not valid UTF-8"
              }
            }
            contentBytes
          } else {
            val result = filter.filterText(decoded)
            // filtered.size <= raw.size is a provable invariant from here on: filterText only removes
            // whole lines from a successfully strict-decoded string, and re-encoding a string that came
            // from valid UTF-8 reproduces the exact bytes it was decoded from -- so no clamp is needed.
            result.text.encodeToByteArray().also { bytes ->
              // Only filtered paths ever create series here, so cardinality stays bounded.
              agent.metrics {
                val path = scrapeRequest.path
                filterLinesDropped.labels(agent.launchId, path).inc(result.linesDropped.toDouble())
                filterBytesSaved.labels(agent.launchId, path).inc((contentBytes.size - bytes.size).toDouble())
              }
            }
          }
        }

      val zipped = filteredBytes.size > agent.options.minGzipSizeBytes
      ScrapeResults(
        srAgentId = scrapeRequest.agentId,
        srScrapeId = scrapeRequest.scrapeId,
        srValidResponse = true,
        srStatusCode = statusCode,
        srContentType = contentType,
        srZipped = zipped,
        srContentAsText = if (!zipped) filteredBytes.decodeToString() else "",
        srContentAsZipped = if (zipped) filteredBytes.zip() else EMPTY_BYTE_ARRAY,
        srUrl = if (scrapeRequest.debugEnabled) safeUrl else "",
        scrapeCounterMsg = SUCCESS_MSG,
      )
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

  private fun newHttpClient(clientKey: ClientKey): HttpClient =
    HttpClient(CIO) {
      expectSuccess = false
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
            modifyRequest { it.headers.append("x-retry-count", retryCount.toString()) }
            exponentialDelay(maxDelayMs = MAX_RETRY_DELAY_MS)
          }
        }
      }

      // Setup authentication if username and password are specified
      clientKey.credentials?.let { creds ->
        install(Auth) {
          basic {
            credentials {
              BasicAuthCredentials(creds.username, creds.password)
            }
          }
        }
      }
    }

  suspend fun close() {
    httpClientCache.close()
  }

  companion object {
    private val logger = logger {}

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

    /**
     * Whether a payload of [contentType] is safe to filter line-by-line. Blank, `text/plain`, and
     * `application/openmetrics-text` are; anything else (protobuf, an HTML error body) is passed
     * through untouched so a misconfigured filter can never corrupt a payload.
     */
    internal fun isFilterableContentType(contentType: String): Boolean =
      contentType.substringBefore(';').trim().lowercase()
        .let { it.isEmpty() || it == "text/plain" || it == "application/openmetrics-text" }

    private const val INVALID_PATH_MSG = "invalid_path"
    private const val SUCCESS_MSG = "success"
    private const val UNSUCCESSFUL_MSG = "unsuccessful"

    // Cap on the retry backoff so total retry time stays bounded within the scrape timeout (finding 28).
    private const val MAX_RETRY_DELAY_MS = 5000L

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
