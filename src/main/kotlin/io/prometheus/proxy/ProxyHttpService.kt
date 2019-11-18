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
import io.prometheus.common.delay
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates.notNull
import kotlin.time.milliseconds
import kotlin.time.seconds

class ProxyHttpService(private val proxy: Proxy, val httpPort: Int) : GenericIdleService() {
  private val configVals = proxy.genericConfigVals.proxy
  private var tracing: Tracing by notNull()
  private val idleTimeout =
    if (configVals.http.idleTimeoutSecs == -1) 45.seconds else configVals.http.idleTimeoutSecs.seconds

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
          val agentContext = proxy.pathManager[path]
          val arg = ResponseArg()

          when {
            !proxy.isRunning -> {
              logger.error { "Proxy stopped" }
              arg.apply {
                updateMsg = "proxy_stopped"
                statusCode = HttpStatusCode.ServiceUnavailable
              }
            }

            path.isEmpty() -> {
              logger.info { "Request missing path" }
              arg.apply {
                updateMsg = "missing_path"
                statusCode = HttpStatusCode.NotFound
              }
            }

            configVals.internal.blitz.enabled && path == configVals.internal.blitz.path ->
              arg.contentText = "42"
            agentContext == null -> {
              logger.debug { "Invalid path request /\${path" }
              arg.apply {
                updateMsg = "invalid_path"
                statusCode = HttpStatusCode.NotFound
              }
            }

            !agentContext.isValid() -> {
              logger.error { "Invalid AgentContext" }
              arg.apply {
                updateMsg = "invalid_agent_context"
                statusCode = HttpStatusCode.NotFound
              }
            }

            else -> {
              val resp = submitScrapeRequest(call.request, call.response, agentContext, path)
              arg.apply {
                contentText = resp.contentText
                contentType = resp.contentType
                statusCode = resp.statusCode
                updateMsg = resp.updateMsg
              }
            }
          }

          updateScrapeRequests(arg.updateMsg)

          call.respondWith(text = arg.contentText, contentType = arg.contentType, status = arg.statusCode)
        }
      }
    }

  suspend fun ApplicationCall.respondWith(text: String,
                                          contentType: ContentType = ContentType.Text.Plain,
                                          status: HttpStatusCode = HttpStatusCode.OK) {
    apply {
      response.header("cache-control", "must-revalidate,no-cache,no-store")
      response.status(status)
      respondText(text, contentType, status)
    }
  }

  class ResponseArg(var contentText: String = "",
                    var contentType: ContentType = ContentType.Text.Plain,
                    var statusCode: HttpStatusCode = HttpStatusCode.OK,
                    var updateMsg: String = "")

  init {
    if (proxy.isZipkinEnabled)
      tracing = proxy.zipkinReporterService.newTracing("proxy-http")
    addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    httpServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()

    httpServer.stop(5, 5, TimeUnit.SECONDS)

    runBlocking {
      delay(2.seconds)
    }
  }

  private class ScrapeRequestResponse(var contentText: String = "",
                                      var contentType: ContentType = ContentType.Text.Plain,
                                      val statusCode: HttpStatusCode,
                                      val updateMsg: String)

  private suspend fun submitScrapeRequest(req: ApplicationRequest,
                                          res: ApplicationResponse,
                                          agentContext: AgentContext,
                                          path: String): ScrapeRequestResponse {

    val scrapeRequest = ScrapeRequestWrapper(proxy, agentContext, path, req.header(ACCEPT))

    try {
      val timeoutTime = configVals.internal.scrapeRequestTimeoutSecs.seconds
      val checkTime = configVals.internal.scrapeRequestCheckMillis.milliseconds

      proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
      agentContext.writeScrapeRequest(scrapeRequest)

      // Returns false if timed out
      while (!scrapeRequest.suspendUntilComplete(checkTime)) {
        // Check if agent is disconnected or agent is hung
        if (scrapeRequest.ageDuration() >= timeoutTime || !scrapeRequest.agentContext.isValid() || !proxy.isRunning)
          return ScrapeRequestResponse(statusCode = HttpStatusCode.ServiceUnavailable, updateMsg = "timed_out")
      }
    } finally {
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeRequest)
        ?: logger.error { "Scrape request ${scrapeRequest.scrapeId} missing in map" }
    }

    logger.debug { "Results returned from $agentContext for $scrapeRequest" }

    scrapeRequest.scrapeResponse
      .also { scrapeResponse ->
        HttpStatusCode.fromValue(scrapeResponse.statusCode).also { statusCode ->
          scrapeResponse.contentType.split("/")
            .also { contentTypeElems ->
              val contentType =
                if (contentTypeElems.size == 2)
                  ContentType(contentTypeElems[0], contentTypeElems[1])
                else
                  ContentType.Text.Plain

              // Do not return content on error status codes
              return if (!statusCode.isSuccess()) {
                ScrapeRequestResponse(contentType = contentType,
                                      statusCode = statusCode,
                                      updateMsg = "path_not_found")
              } else {
                ScrapeRequestResponse(contentText = scrapeRequest.scrapeResponse.contentText,
                                      contentType = contentType,
                                      statusCode = statusCode,
                                      updateMsg = "success")
              }
            }
        }
      }
  }

  private fun updateScrapeRequests(type: String) {
    if (proxy.isMetricsEnabled && type.isNotEmpty())
      proxy.metrics.scrapeRequests.labels(type).inc()
  }

  override fun toString() = toStringElements { add("port", httpPort) }

  companion object : KLogging()
}
