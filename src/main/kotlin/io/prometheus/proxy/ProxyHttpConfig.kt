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

import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.isNull
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.unzip
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.ContentType.Text.Plain
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.prometheus.Proxy
import kotlinx.coroutines.async
import mu.KLogging
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.milliseconds
import kotlin.time.seconds

internal object ProxyHttpConfig : KLogging() {

  fun Application.configServer(proxy: Proxy, isTestMode: Boolean) {

    install(DefaultHeaders) {
      header("X-Engine", "Ktor")
    }

    if (!isTestMode) {
      install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
          when (val status = call.response.status()) {
            HttpStatusCode.Found -> {
              "$status: ${call.request.toLogString()} -> ${call.response.headers[HttpHeaders.Location]} - ${call.request.origin.remoteHost}"
            }
            else -> "$status: ${call.request.toLogString()} - ${call.request.origin.remoteHost}"
          }
        }
      }
    }

    install(Compression) {
      gzip {
        priority = 1.0
      }
      deflate {
        priority = 10.0
        minimumSize(1024) // condition
      }
    }

    install(StatusPages) {
      // Catch all
      exception<Throwable> { cause ->
        logger.info(cause) { " Throwable caught: ${cause.simpleClassName}" }
        call.respond(NotFound)
      }

      status(NotFound) {
        call.respond(TextContent("${it.value} ${it.description}", Plain.withCharset(Charsets.UTF_8), it))
      }
    }

    routing {
      get("/*") {
        call.response.header(HttpHeaders.CacheControl, "must-revalidate,no-cache,no-store")

        val proxyConfigVals = proxy.configVals.proxy
        val path = call.request.path().drop(1)
        val queryParams = call.request.queryParameters.formUrlEncode()
        val responseResults = ResponseResults()
        val logger = ProxyHttpService.logger

        logger.debug { "Servicing request for path: $path${if (queryParams.isNotEmpty()) " with query params $queryParams" else ""}" }

        when {
          !proxy.isRunning -> {
            logger.error { "Proxy stopped" }
            responseResults
              .apply {
                updateMsg = "proxy_stopped"
                statusCode = HttpStatusCode.ServiceUnavailable
              }
          }

          path.isEmpty() || path.isBlank() -> {
            val msg = "Request missing path"
            proxy.logActivity(msg)
            logger.info { msg }
            responseResults.apply { updateMsg = "missing_path"; statusCode = NotFound }
          }

          path == "favicon.ico" -> {
            responseResults.apply { updateMsg = "invalid_path"; statusCode = NotFound }
          }

          proxyConfigVals.internal.blitz.enabled && path == proxyConfigVals.internal.blitz.path ->
            responseResults.contentText = "42"

          else -> {
            val agentContextInfo = proxy.pathManager.getAgentContextInfo(path)
            if (agentContextInfo.isNull()) {
              val msg = "Invalid path request /${path}"
              proxy.logActivity(msg)
              logger.info { msg }
              responseResults.apply { updateMsg = "invalid_path"; statusCode = NotFound }
            }
            else {
              if (!agentContextInfo.consolidated && agentContextInfo.agentContexts[0].isNotValid()) {
                val msg = "Invalid AgentContext for /${path}"
                proxy.logActivity(msg)
                logger.error { msg }
                responseResults.apply { updateMsg = "invalid_agent_context"; statusCode = NotFound }
              }
              else {
                val jobs =
                  agentContextInfo.agentContexts
                    .map { async { submitScrapeRequest(it, proxy, path, queryParams, call.request, call.response) } }
                    .map { it.await() }
                    .onEach { response ->
                      var status = "/$path - ${response.updateMsg} - ${response.statusCode}"
                      if (!response.statusCode.isSuccess())
                        status += " reason: [${response.failureReason}]"
                      status += " time: ${response.fetchDuration} url: ${response.url}"

                      proxy.logActivity(status)
                    }

                val statusCodes = jobs.map { it.statusCode }.toSet().toList()
                val contentTypes = jobs.map { it.contentType }.toSet().toList()
                val updateMsgs = jobs.joinToString("\n") { it.updateMsg }
                // Grab the contentType of the first OK in the lit
                val okContentType = jobs.firstOrNull { it.statusCode == OK }?.contentType

                responseResults
                  .apply {
                    statusCode = if (statusCodes.contains(OK)) OK else statusCodes[0]
                    contentType = if (okContentType.isNotNull()) okContentType else contentTypes[0]
                    contentText = jobs.joinToString("\n") { it.contentText }
                    updateMsg = updateMsgs
                  }
              }
            }
          }
        }

        responseResults
          .apply {
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
                                                  contentType: ContentType = Plain,
                                                  status: HttpStatusCode = OK) {
    response.header(HttpHeaders.CacheControl, "must-revalidate,no-cache,no-store")
    response.status(status)
    respondText(text, contentType, status)
  }

  private suspend fun submitScrapeRequest(agentContext: AgentContext,
                                          proxy: Proxy,
                                          path: String,
                                          encodedQueryParams: String,
                                          request: ApplicationRequest,
                                          response: ApplicationResponse): ScrapeRequestResponse {

    val scrapeRequest = ScrapeRequestWrapper(agentContext,
                                             proxy,
                                             path,
                                             encodedQueryParams,
                                             request.header(HttpHeaders.Accept),
                                             proxy.options.debugEnabled)
    val logger = ProxyHttpService.logger

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
                    Plain

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
}

private class ScrapeRequestResponse(val statusCode: HttpStatusCode,
                                    val updateMsg: String,
                                    var contentType: ContentType = Plain,
                                    var contentText: String = "",
                                    val failureReason: String = "",
                                    val url: String = "",
                                    val fetchDuration: Duration)

private class ResponseResults(var statusCode: HttpStatusCode = OK,
                              var contentType: ContentType = Plain,
                              var contentText: String = "",
                              var updateMsg: String = "")