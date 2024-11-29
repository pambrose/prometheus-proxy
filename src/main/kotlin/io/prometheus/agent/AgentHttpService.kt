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
import com.google.common.net.HttpHeaders.ACCEPT
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.Utils.decodeParams
import io.prometheus.common.Utils.ifTrue
import io.prometheus.common.Utils.lambda
import io.prometheus.grpc.ScrapeRequest
import kotlin.time.Duration.Companion.seconds

internal class AgentHttpService(
  val agent: Agent,
) {
  suspend fun fetchScrapeUrl(scrapeRequest: ScrapeRequest): ScrapeResults {
    val pathContext = agent.pathManager[scrapeRequest.path]
    return if (pathContext.isNull())
      handleInvalidPath(scrapeRequest)
    else
      fetchContentFromUrl(scrapeRequest, pathContext)
  }

  private suspend fun AgentHttpService.fetchContentFromUrl(
    scrapeRequest: ScrapeRequest,
    pathContext: AgentPathManager.PathContext,
  ): ScrapeResults =
    ScrapeResults(agentId = scrapeRequest.agentId, scrapeId = scrapeRequest.scrapeId).also { scrapeResults ->
      val requestTimer = if (agent.isMetricsEnabled) agent.startTimer(agent) else null
      // Add the incoming query params to the url
      val url = pathContext.url + decodeParams(scrapeRequest.encodedQueryParams)
      logger.debug { "Fetching $pathContext ${if (url.isNotBlank()) "URL: $url" else ""}" }

      try {
        // Content is fetched here
        fetchContent(url, scrapeRequest, scrapeResults)
      } finally {
        requestTimer?.observeDuration()
      }
      agent.updateScrapeCounter(scrapeResults.scrapeCounterMsg.get())
    }

  private suspend fun fetchContent(
    url: String,
    scrapeRequest: ScrapeRequest,
    scrapeResults: ScrapeResults,
  ) {
    runCatching {
      newHttpClient(url).use { client ->
        client.get(
          url = url,
          setUp = prepareRequestHeaders(scrapeRequest),
          block = processHttpResponse(url, scrapeRequest, scrapeResults),
        )
      }
    }.onFailure { e ->
      with(scrapeResults) {
        statusCode = errorCode(e, url)
        failureReason = e.message ?: e.simpleClassName
        if (scrapeRequest.debugEnabled)
          setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
      }
    }
  }

  private fun prepareRequestHeaders(request: ScrapeRequest): HttpRequestBuilder.() -> Unit =
    lambda {
      request.accept.also { if (it.isNotEmpty()) header(ACCEPT, it) }
      val scrapeTimeout = agent.options.scrapeTimeoutSecs.seconds
      logger.debug { "Setting scrapeTimeoutSecs = $scrapeTimeout" }
      timeout { requestTimeoutMillis = scrapeTimeout.inWholeMilliseconds }
      val authHeader = request.authHeader.ifBlank { null }
      authHeader?.also { header(io.ktor.http.HttpHeaders.Authorization, it) }
    }

  private fun processHttpResponse(
    url: String,
    scrapeRequest: ScrapeRequest,
    scrapeResults: ScrapeResults,
  ): suspend (HttpResponse) -> Unit =
    lambda { response ->
      scrapeResults.statusCode = response.status.value
      setScrapeDetailsAndDebugInfo(scrapeRequest, scrapeResults, response, url)
    }

  private suspend fun setScrapeDetailsAndDebugInfo(
    scrapeRequest: ScrapeRequest,
    scrapeResults: ScrapeResults,
    response: HttpResponse,
    url: String,
  ) {
    with(scrapeResults) {
      if (response.status.isSuccess()) {
        contentType = response.headers[CONTENT_TYPE].orEmpty()
        // Zip the content here
        val content = response.bodyAsText()
        zipped = content.length > agent.configVals.agent.minGzipSizeBytes
        if (zipped)
          contentAsZipped = content.zip()
        else
          contentAsText = content
        validResponse = true

        scrapeRequest.debugEnabled.ifTrue { setDebugInfo(url) }
        scrapeCounterMsg.set(SUCCESS_MSG)
      } else {
        scrapeRequest.debugEnabled.ifTrue { setDebugInfo(url, "Unsuccessful response code $statusCode") }
        scrapeCounterMsg.set(UNSUCCESSFUL_MSG)
      }
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

  companion object {
    private val logger = KotlinLogging.logger {}
    private const val INVALID_PATH_MSG = "invalid_path"
    private const val SUCCESS_MSG = "success"
    private const val UNSUCCESSFUL_MSG = "unsuccessful"

    private fun handleInvalidPath(scrapeRequest: ScrapeRequest): ScrapeResults {
      val scrapeResults = with(scrapeRequest) { ScrapeResults(agentId = agentId, scrapeId = scrapeId) }
      logger.warn { "Invalid path in fetchScrapeUrl(): ${scrapeRequest.path}" }
      scrapeResults.scrapeCounterMsg.set(INVALID_PATH_MSG)
      scrapeRequest.debugEnabled.ifTrue { scrapeResults.setDebugInfo("None", "Invalid path: ${scrapeRequest.path}") }
      return scrapeResults
    }
  }
}
