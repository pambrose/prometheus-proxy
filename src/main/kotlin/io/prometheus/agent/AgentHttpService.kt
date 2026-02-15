/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.util.EMPTY_BYTE_ARRAY
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.zip
import com.google.common.net.HttpHeaders.ACCEPT
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.prometheus.Agent
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.Utils.appendQueryParams
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.grpc.ScrapeRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    val requestTimer = if (agent.isMetricsEnabled) agent.startTimer(agent) else null
    // Add the incoming query params to the url
    val url = appendQueryParams(pathContext.url, req.encodedQueryParams)
    val logUrl = sanitizeUrl(url)
    logger.debug { "Fetching $pathContext ${if (url.isNotBlank()) "URL: $logUrl" else ""}" }

    // Content is fetched here
    val results =
      try {
        withTimeout(agent.options.scrapeTimeoutSecs.seconds) {
          fetchContent(url, req)
        }
      } catch (e: Throwable) {
        // Catch regular exceptions and HttpRequestTimeoutException (which is a CancellationException)
        // but let other CancellationExceptions propagate (like system shutdown).
        // Ktor often wraps the timeout exception, so we check the cause as well.
        // We also check class names to handle cases where exceptions might be wrapped or mocked.
        var isTimeout = e is HttpRequestTimeoutException || e is TimeoutCancellationException
        var curr: Throwable? = e
        while (!isTimeout && curr != null) {
          isTimeout = isTimeoutException(curr)
          curr = curr.cause
        }

        if (e is kotlinx.coroutines.CancellationException && !isTimeout) {
          throw e
        }

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
  ): ScrapeResults {
    val clientKey = with(Url(url)) { ClientKey(user, password) }
    val entry = httpClientCache.getOrCreateClient(clientKey) { newHttpClient(clientKey) }
    try {
      var result: ScrapeResults? = null
      entry.client.get(
        url = url,
        setUp = prepareRequestHeaders(scrapeRequest),
      ) { response ->
        result = buildScrapeResults(response, url, scrapeRequest)
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
      val content = response.bodyAsText()

      val contentByteSize = content.encodeToByteArray().size.toLong()
      if (contentByteSize > maxContentLength) {
        val msg = "Content size $contentByteSize bytes exceeds maximum allowed size $maxContentLength"
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
      val zipped = contentByteSize > agent.options.minGzipSizeBytes
      ScrapeResults(
        srAgentId = scrapeRequest.agentId,
        srScrapeId = scrapeRequest.scrapeId,
        srValidResponse = true,
        srStatusCode = statusCode,
        srContentType = contentType,
        srZipped = zipped,
        srContentAsText = if (!zipped) content else "",
        srContentAsZipped = if (zipped) content.zip() else EMPTY_BYTE_ARRAY,
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
        // If internal.cioTimeoutSecs is set to a non-default and httpClientTimeoutSecs is set to the default, then use
        // internal.cioTimeoutSecs value. Otherwise, use httpClientTimeoutSecs.
        val timeoutSecs =
          if (agent.configVals.agent.internal.cioTimeoutSecs != 90 && agent.options.httpClientTimeoutSecs == 90)
            agent.configVals.agent.internal.cioTimeoutSecs
          else
            agent.options.httpClientTimeoutSecs

        requestTimeout = timeoutSecs.seconds.inWholeMilliseconds

        if (agent.options.trustAllX509Certificates) {
          https {
            // trustManager = SslSettings.getTrustManager()
            trustManager = TrustAllX509TrustManager
          }
        }
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
            exponentialDelay(maxDelayMs = 5000L)
          }
        }
      }

      // Setup authentication if username and password are specified
      if (clientKey.hasAuth()) {
        install(Auth) {
          basic {
            credentials {
              // These are known to be non-null because of the hasAuth() check above
              BasicAuthCredentials(clientKey.username!!, clientKey.password!!)
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
    private const val INVALID_PATH_MSG = "invalid_path"
    private const val SUCCESS_MSG = "success"
    private const val UNSUCCESSFUL_MSG = "unsuccessful"

    private fun isTimeoutException(e: Throwable): Boolean =
      e is HttpRequestTimeoutException ||
        e is TimeoutCancellationException ||
        e.javaClass.simpleName == "HttpRequestTimeoutException" ||
        e.simpleClassName == "HttpRequestTimeoutException"

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
