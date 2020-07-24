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

package io.prometheus.proxy

import com.github.pambrose.common.util.isNull
import com.github.pambrose.common.util.unzip
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.ApplicationRequest
import io.ktor.request.header
import io.ktor.request.path
import io.ktor.response.ApplicationResponse
import io.ktor.response.header
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.prometheus.Proxy
import kotlin.time.Duration
import kotlin.time.milliseconds
import kotlin.time.seconds

internal fun Application.configServer(proxy: Proxy) {

  install(DefaultHeaders)

  /*
  install(CallLogging){
    level = Level.INFO
    filter { call -> call.request.path().startsWith("/") }
  }
  */

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

      val proxyConfigVals = proxy.configVals.proxy
      val path = call.request.path().drop(1)
      val encodedQueryParams = call.request.queryParameters.formUrlEncode()
      val agentContext = proxy.pathManager[path]
      val responseResults = ResponseResults()

      ProxyHttpService.logger.debug {
        "Servicing request for path: $path" +
        (if (encodedQueryParams.isNotEmpty()) " with query params $encodedQueryParams" else "")
      }

      when {
        !proxy.isRunning -> {
          ProxyHttpService.logger.error { "Proxy stopped" }
          responseResults.apply {
            updateMsg = "proxy_stopped"
            statusCode = HttpStatusCode.ServiceUnavailable
          }
        }

        path.isEmpty() || path.isBlank() -> {
          val msg = "Request missing path"
          proxy.logActivity(msg)
          ProxyHttpService.logger.info { msg }
          responseResults.apply {
            updateMsg = "missing_path"
            statusCode = HttpStatusCode.NotFound
          }
        }

        path == "favicon.ico" -> {
          //logger.info { "Invalid path request /${path}" }
          responseResults.apply {
            updateMsg = "invalid_path"
            statusCode = HttpStatusCode.NotFound
          }
        }

        proxyConfigVals.internal.blitz.enabled && path == proxyConfigVals.internal.blitz.path ->
          responseResults.contentText = "42"

        agentContext.isNull() -> {
          val msg = "Invalid path request /${path}"
          proxy.logActivity(msg)
          ProxyHttpService.logger.info { msg }
          responseResults.apply {
            updateMsg = "invalid_path"
            statusCode = HttpStatusCode.NotFound
          }
        }

        agentContext.isNotValid() -> {
          val msg = "Invalid AgentContext for /${path}"
          proxy.logActivity(msg)
          ProxyHttpService.logger.error { msg }
          responseResults.apply {
            updateMsg = "invalid_agent_context"
            statusCode = HttpStatusCode.NotFound
          }
        }

        else -> {
          submitScrapeRequest(proxy, path, encodedQueryParams, agentContext, call.request, call.response)
            .also { response ->

              var status = "/$path - ${response.updateMsg} - ${response.statusCode}"
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
        updateScrapeRequests(proxy, updateMsg)
        call.respondWith(contentText, contentType, statusCode)
      }
    }
  }
}

private fun updateScrapeRequests(proxy: Proxy, type: String) {
  if (type.isNotEmpty())
    proxy.metrics { scrapeRequestCount.labels(type).inc() }
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

private suspend fun submitScrapeRequest(proxy: Proxy,
                                        path: String,
                                        encodedQueryParams: String,
                                        agentContext: AgentContext,
                                        request: ApplicationRequest,
                                        response: ApplicationResponse): ScrapeRequestResponse {

  val scrapeRequest = ScrapeRequestWrapper(proxy,
                                           path,
                                           encodedQueryParams,
                                           agentContext,
                                           request.header(com.google.common.net.HttpHeaders.ACCEPT),
                                           proxy.options.debugEnabled)

  try {
    val proxyConfigVals = proxy.configVals.proxy
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
  }
  finally {
    val scrapeId = scrapeRequest.scrapeId
    proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
    ?: ProxyHttpService.logger.error { "Scrape request $scrapeId missing in map" }
  }

  ProxyHttpService.logger.debug { "Results returned from $agentContext for $scrapeRequest" }

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

private class ScrapeRequestResponse(val statusCode: HttpStatusCode,
                                    val updateMsg: String,
                                    var contentType: ContentType = ContentType.Text.Plain,
                                    var contentText: String = "",
                                    val failureReason: String = "",
                                    val url: String = "",
                                    val fetchDuration: Duration)

private class ResponseResults(var statusCode: HttpStatusCode = HttpStatusCode.OK,
                              var contentType: ContentType = ContentType.Text.Plain,
                              var contentText: String = "",
                              var updateMsg: String = "")

