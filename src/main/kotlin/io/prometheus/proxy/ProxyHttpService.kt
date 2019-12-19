/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import brave.Tracing
import com.github.pambrose.common.concurrent.GenericIdleService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.util.sleep
import com.github.pambrose.common.util.unzip
import com.google.common.net.HttpHeaders.ACCEPT
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.Compression
import io.ktor.features.DefaultHeaders
import io.ktor.features.deflate
import io.ktor.features.gzip
import io.ktor.features.minimumSize
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.request.ApplicationRequest
import io.ktor.request.header
import io.ktor.request.path
import io.ktor.response.ApplicationResponse
import io.ktor.response.header
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.prometheus.Proxy
import mu.KLogging
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Duration
import kotlin.time.milliseconds
import kotlin.time.seconds

class ProxyHttpService(private val proxy: Proxy, val httpPort: Int) : GenericIdleService() {
  private val proxyConfigVals = proxy.configVals.proxy
  private val idleTimeout =
      if (proxyConfigVals.http.idleTimeoutSecs == -1) 45.seconds else proxyConfigVals.http.idleTimeoutSecs.seconds

  private lateinit var tracing: Tracing

  private val httpServer =
      embeddedServer(CIO,
                     port = httpPort,
                     configure = { connectionIdleTimeoutSeconds = idleTimeout.inSeconds.toInt() }) {

        install(DefaultHeaders)
        //install(CallLogging)
        install(Compression) {
          gzip {
            priority = 1.0
          }
          deflate {
            priority = 10.0
            minimumSize(1024) // condition
          }
        }

        routing {
          get("/*") {
            call.response.header(HttpHeaders.CacheControl, "must-revalidate,no-cache,no-store")

            val path = call.request.path().drop(1)
            logger.debug { "Servicing request for path: $path" }
            val agentContext = proxy.pathManager[path]
            val responseResults = ResponseResults()

            when {
              !proxy.isRunning -> {
                logger.error { "Proxy stopped" }
                responseResults.apply {
                  updateMsg = "proxy_stopped"
                  statusCode = HttpStatusCode.ServiceUnavailable
                }
              }

              path.isEmpty() || path.isBlank() -> {
                val msg = "Request missing path"
                proxy.logActivity(msg)
                logger.info { msg }
                responseResults.apply {
                  updateMsg = "missing_path"
                  statusCode = HttpStatusCode.NotFound
                }
              }

              path == "favicon.ico" -> {
                //logger.info { "Invalid path request /${path}" }
                responseResults.apply {
                  updateMsg = "invalid_request"
                  statusCode = HttpStatusCode.NotFound
                }
              }

              proxyConfigVals.internal.blitz.enabled && path == proxyConfigVals.internal.blitz.path ->
                responseResults.contentText = "42"

              agentContext == null -> {
                val msg = "Invalid path request /${path}"
                proxy.logActivity(msg)
                logger.info { msg }
                responseResults.apply {
                  updateMsg = "invalid_path"
                  statusCode = HttpStatusCode.NotFound
                }
              }

              agentContext.isNotValid() -> {
                val msg = "Invalid AgentContext for /${path}"
                proxy.logActivity(msg)
                logger.error { msg }
                responseResults.apply {
                  updateMsg = "invalid_agent_context"
                  statusCode = HttpStatusCode.NotFound
                }
              }

              else -> {
                submitScrapeRequest(path, agentContext, call.request, call.response)
                    .also { response ->

                      var status = "/${path} - ${response.updateMsg} - ${response.statusCode}"
                      if (!response.statusCode.isSuccess())
                        status += " reason: [${response.failureReason}]"
                      status += " time: ${response.fetchDuration} url: ${response.url}"

                      proxy.logActivity(status)

                      responseResults.apply {
                        statusCode = response.statusCode
                        contentType = response.contentType
                        contentText = response.contentText
                        updateMsg = response.updateMsg
                      }
                    }
              }
            }

            responseResults.apply {
              updateScrapeRequests(updateMsg)
              call.respondWith(contentText, contentType, statusCode)
            }
          }
        }
      }

  private suspend fun ApplicationCall.respondWith(text: String,
                                                  contentType: ContentType = ContentType.Text.Plain,
                                                  status: HttpStatusCode = HttpStatusCode.OK) {
    apply {
      response.header("cache-control", "must-revalidate,no-cache,no-store")
      response.status(status)
      respondText(text, contentType, status)
    }
  }

  class ResponseResults(var statusCode: HttpStatusCode = HttpStatusCode.OK,
                        var contentType: ContentType = ContentType.Text.Plain,
                        var contentText: String = "",
                        var updateMsg: String = "")

  init {
    if (proxy.isZipkinEnabled)
      tracing = proxy.zipkinReporterService.newTracing("proxy-http")
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    httpServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()

    httpServer.stop(5, 5, SECONDS)

    sleep(2.seconds)
  }

  private class ScrapeRequestResponse(val statusCode: HttpStatusCode,
                                      val updateMsg: String,
                                      var contentType: ContentType = ContentType.Text.Plain,
                                      var contentText: String = "",
                                      val failureReason: String = "",
                                      val url: String = "",
                                      val fetchDuration: Duration)

  private suspend fun submitScrapeRequest(path: String,
                                          agentContext: AgentContext,
                                          request: ApplicationRequest,
                                          response: ApplicationResponse): ScrapeRequestResponse {

    val scrapeRequest = ScrapeRequestWrapper(proxy,
                                             path,
                                             agentContext,
                                             request.header(ACCEPT),
                                             proxy.options.debugEnabled)

    try {
      val timeoutTime = proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds
      val checkTime = proxyConfigVals.internal.scrapeRequestCheckMillis.milliseconds

      proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
      agentContext.writeScrapeRequest(scrapeRequest)

      // Returns false if timed out
      while (!scrapeRequest.suspendUntilComplete(checkTime)) {
        // Check if agent is disconnected or agent is hung
        if (scrapeRequest.ageDuration() >= timeoutTime || !scrapeRequest.agentContext.isValid() || !proxy.isRunning)
          return ScrapeRequestResponse(statusCode = HttpStatusCode.ServiceUnavailable,
                                       updateMsg = "timed_out",
                                       fetchDuration = scrapeRequest.ageDuration())
      }
    } finally {
      val scrapeId = scrapeRequest.scrapeId
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
          ?: logger.error { "Scrape request $scrapeId missing in map" }
    }

    logger.debug { "Results returned from $agentContext for $scrapeRequest" }

    scrapeRequest.scrapeResults
        .also { scrapeResults ->
          HttpStatusCode.fromValue(scrapeResults.statusCode)
              .also { statusCode ->
                scrapeResults.contentType.split("/")
                    .also { contentTypeElems ->

                      val contentType =
                          if (contentTypeElems.size == 2)
                            ContentType(contentTypeElems[0], contentTypeElems[1])
                          else
                            ContentType.Text.Plain

                      // Do not return content on error status codes
                      return if (!statusCode.isSuccess()) {
                        scrapeRequest.scrapeResults.run {
                          ScrapeRequestResponse(statusCode = statusCode,
                                                contentType = contentType,
                                                failureReason = failureReason,
                                                url = url,
                                                updateMsg = "path_not_found",
                                                fetchDuration = scrapeRequest.ageDuration())
                        }
                      }
                      else {
                        scrapeRequest.scrapeResults.run {
                          // Unzip content here
                          ScrapeRequestResponse(statusCode = statusCode,
                                                contentType = contentType,
                                                contentText = if (zipped) contentAsZipped.unzip() else contentAsText,
                                                failureReason = failureReason,
                                                url = url,
                                                updateMsg = "success",
                                                fetchDuration = scrapeRequest.ageDuration())
                        }
                      }
                    }
              }
        }
  }

  private fun updateScrapeRequests(type: String) {
    if (type.isNotEmpty())
      proxy.metrics { scrapeRequestCount.labels(type).inc() }
  }

  override fun toString() = toStringElements { add("port", httpPort) }

  companion object : KLogging()
}
