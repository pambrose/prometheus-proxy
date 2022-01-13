/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ScrapeRequest
import kotlinx.coroutines.TimeoutCancellationException
import mu.KLogging
import java.io.IOException
import java.net.URLDecoder
import java.net.http.HttpConnectTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration.Companion.seconds

internal class AgentHttpService(val agent: Agent) {

  suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResults =
    ScrapeResults(
      agentId = request.agentId,
      scrapeId = request.scrapeId
    ).also { scrapeResults ->
      val scrapeMsg = AtomicReference("")
      val path = request.path
      val encodedQueryParams = request.encodedQueryParams
      val authHeader = when {
        request.authHeader.isNullOrBlank() -> null
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
        val url = pathContext.url +
            (if (encodedQueryParams.isNotEmpty())
              "?${URLDecoder.decode(encodedQueryParams, UTF_8.name())}"
            else
              "")

        logger.debug { "Fetching $pathContext" }
        if (encodedQueryParams.isNotEmpty())
          logger.debug { "URL: $url" }

        // Content is fetched here
        try {
          val timeout = agent.configVals.agent.internal.cioTimeoutSecs.seconds
          CIO.create { requestTimeout = timeout.inWholeMilliseconds }
            .use { engine ->
              HttpClient(engine) {
                expectSuccess = false

                install(HttpTimeout)

//                install(HttpRequestRetry) {
//                  retryOnServerErrors(maxRetries = 5)
//                  exponentialDelay()
//                }

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
                .use { client ->
                  client.get(
                    url,
                    {
                      request.accept?.also { if (it.isNotEmpty()) header(ACCEPT, it) }
                      val scrapeTimeout = agent.options.scrapeTimeoutSecs.seconds
                      logger.debug { "Setting scrapeTimeoutSecs = $scrapeTimeout" }
                      timeout { requestTimeoutMillis = scrapeTimeout.inWholeMilliseconds }
                      authHeader?.also { header(io.ktor.http.HttpHeaders.Authorization, it) }
                    },
                    getBlock(url, scrapeResults, scrapeMsg, request.debugEnabled)
                  )
                }
            }
        } catch (e: TimeoutCancellationException) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          scrapeResults.statusCode = HttpStatusCode.RequestTimeout.value
          scrapeResults.failureReason = e.message ?: e.simpleClassName

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } catch (e: HttpConnectTimeoutException) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          scrapeResults.statusCode = HttpStatusCode.RequestTimeout.value
          scrapeResults.failureReason = e.message ?: e.simpleClassName

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } catch (e: SocketTimeoutException) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          scrapeResults.statusCode = HttpStatusCode.RequestTimeout.value
          scrapeResults.failureReason = e.message ?: e.simpleClassName

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } catch (e: HttpRequestTimeoutException) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          scrapeResults.statusCode = HttpStatusCode.RequestTimeout.value
          scrapeResults.failureReason = e.message ?: e.simpleClassName

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } catch (e: IOException) {
          logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
          scrapeResults.statusCode = HttpStatusCode.NotFound.value
          scrapeResults.failureReason = e.message ?: e.simpleClassName

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } catch (e: Throwable) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          scrapeResults.failureReason = e.message ?: e.simpleClassName
          scrapeResults.statusCode = HttpStatusCode.ServiceUnavailable.value

          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        } finally {
          requestTimer?.observeDuration()
        }
      }

      agent.updateScrapeCounter(agent, scrapeMsg.get())
    }

  private fun getBlock(
    url: String,
    responseArg: ScrapeResults,
    scrapeCounterMsg: AtomicReference<String>,
    debugEnabled: Boolean
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