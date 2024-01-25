/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.isNull
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.zip
import com.google.common.net.HttpHeaders
import com.google.common.net.HttpHeaders.ACCEPT
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.common.Utils.lambda
import io.prometheus.grpc.krotodc.ScrapeRequest
import kotlinx.coroutines.TimeoutCancellationException
import mu.two.KLogging
import java.io.IOException
import java.net.URLDecoder
import java.net.http.HttpConnectTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration.Companion.seconds

internal class AgentHttpService(val agent: Agent) {
  suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResults {
    val scrapeResults = ScrapeResults(agentId = request.agentId, scrapeId = request.scrapeId)
    val scrapeMsg = AtomicReference("")
    val path = request.path
    val encodedQueryParams = request.encodedQueryParams
    val authHeader =
      when {
        request.authHeader.isBlank() -> null
        else -> request.authHeader
      }

    val pathContext = agent.pathManager[path]

    if (pathContext.isNull()) {
      logger.warn { "Invalid path in fetchScrapeUrl(): $path" }
      scrapeMsg.set("invalid_path")
      if (request.debugEnabled)
        scrapeResults.setDebugInfo("None", "Invalid path: $path")
    } else {
      val requestTimer = if (agent.isMetricsEnabled) agent.startTimer(agent) else null
      // Add the incoming query params to the url
      val url = pathContext.url + decodeParams(encodedQueryParams)
      logger.debug { "Fetching $pathContext ${if (url.isNotBlank()) "URL: $url" else ""}" }

      // Content is fetched here
      try {
        runCatching {
          newHttpClient(url).use { client ->
            client.get(
              url = url,
              setUp = lambda {
                request.accept.also { if (it.isNotEmpty()) header(ACCEPT, it) }
                val scrapeTimeout = agent.options.scrapeTimeoutSecs.seconds
                logger.debug { "Setting scrapeTimeoutSecs = $scrapeTimeout" }
                timeout { requestTimeoutMillis = scrapeTimeout.inWholeMilliseconds }
                authHeader?.also { header(io.ktor.http.HttpHeaders.Authorization, it) }
              },
              block = getBlock(url, scrapeResults, scrapeMsg, request.debugEnabled),
            )
          }
        }.onFailure { e ->
          scrapeResults.statusCode = errorCode(e, url)
          scrapeResults.failureReason = e.message ?: e.simpleClassName
          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        }
      } finally {
        requestTimer?.observeDuration()
      }
    }
    agent.updateScrapeCounter(agent, scrapeMsg.get())
    return scrapeResults
  }

  private fun decodeParams(encodedQueryParams: String): String =
    if (encodedQueryParams.isNotBlank()) "?${URLDecoder.decode(encodedQueryParams, UTF_8.name())}" else ""

  private fun errorCode(
    e: Throwable,
    url: String,
  ): Int =
    when (e) {
      is TimeoutCancellationException,
      is HttpConnectTimeoutException,
      is SocketTimeoutException,
      is HttpRequestTimeoutException,
      -> {
        logger.warn(e) { "fetchScrapeUrl() $e - $url" }
        HttpStatusCode.RequestTimeout.value
      }

      is IOException -> {
        logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
        HttpStatusCode.NotFound.value
      }

      else -> {
        logger.warn(e) { "fetchScrapeUrl() $e - $url" }
        HttpStatusCode.ServiceUnavailable.value
      }
    }

  private fun newHttpClient(url: String): HttpClient =
    HttpClient(CIO) {
      expectSuccess = false
      engine {
        val timeout = agent.configVals.agent.internal.cioTimeoutSecs.seconds
        requestTimeout = timeout.inWholeMilliseconds

        val enableTrustAllX509Certificates = agent.configVals.agent.http.enableTrustAllX509Certificates
        if (enableTrustAllX509Certificates) {
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
              !response.status.isSuccess() && response.status != HttpStatusCode.NotFound
            }
            modifyRequest { it.headers.append("x-retry-count", retryCount.toString()) }
            exponentialDelay()
          }
        }
      }

      val urlObj = Url(url)
      val user = urlObj.user
      val passwd = urlObj.password
      if (user.isNotNull() && passwd.isNotNull()) {
        install(Auth) {
          basic {
            credentials {
              BasicAuthCredentials(user, passwd)
            }
          }
        }
      }
    }

  private fun getBlock(
    url: String,
    responseArg: ScrapeResults,
    scrapeCounterMsg: AtomicReference<String>,
    debugEnabled: Boolean,
  ): suspend (HttpResponse) -> Unit =
    { response ->
      responseArg.statusCode = response.status.value

      if (response.status.isSuccess()) {
        responseArg.apply {
          contentType = response.headers[HttpHeaders.CONTENT_TYPE].orEmpty()
          // Zip the content here
          val content = response.bodyAsText()
          zipped = content.length > agent.configVals.agent.minGzipSizeBytes
          if (zipped)
            contentAsZipped = content.zip()
          else
            contentAsText = content
          validResponse = true
        }
        if (debugEnabled)
          responseArg.setDebugInfo(url)
        scrapeCounterMsg.set("success")
      } else {
        if (debugEnabled)
          responseArg.setDebugInfo(url, "Unsuccessful response code ${responseArg.statusCode}")
        scrapeCounterMsg.set("unsuccessful")
      }
    }

  companion object : KLogging()
}
