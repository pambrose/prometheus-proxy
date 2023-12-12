/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
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
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyConstants.CACHE_CONTROL_VALUE
import io.prometheus.proxy.ProxyConstants.FAVICON_FILENAME
import io.prometheus.proxy.ProxyUtils.emptyPathResponse
import io.prometheus.proxy.ProxyUtils.incrementScrapeRequestCount
import io.prometheus.proxy.ProxyUtils.invalidAgentContextResponse
import io.prometheus.proxy.ProxyUtils.invalidPathResponse
import io.prometheus.proxy.ProxyUtils.proxyNotRunningResponse
import io.prometheus.proxy.ProxyUtils.respondWith
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.two.KLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object ProxyHttpRoutes : KLogging() {
  fun Application.configureHttpRoutes(proxy: Proxy) {
    routing {
      handleRequests(proxy)
    }
  }

  private fun Routing.handleRequests(proxy: Proxy) {
    //      get("/__test__") {
    //        delay(30.seconds)
    //        call.respondWith("Test value", Plain, OK)
    //      }
    handleServiceDiscoveryEndpoint(proxy)
    handleClientRequests(proxy)
  }

  private fun Routing.handleServiceDiscoveryEndpoint(proxy: Proxy) {
    if (proxy.options.sdEnabled) {
      logger.info { "Adding /${proxy.options.sdPath} service discovery endpoint" }
      get(proxy.options.sdPath) {
        val json = proxy.buildSdJson()
        val format = Json { prettyPrint = true }
        val prettyPrint = format.encodeToString(json)
        call.respondWith(prettyPrint, ContentType.Application.Json)
      }
    } else {
      logger.info { "Not adding /${proxy.options.sdPath} service discovery endpoint" }
    }
  }

  private fun Routing.handleClientRequests(proxy: Proxy) {
    get("/*") {
      call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)

      val path = call.request.path().drop(1)
      val queryParams = call.request.queryParameters.formUrlEncode()
      val responseResults = ResponseResults()

      logger.debug {
        "Servicing request for path: $path${if (queryParams.isNotEmpty()) " with query params $queryParams" else ""}"
      }

      when {
        !proxy.isRunning -> proxyNotRunningResponse(logger, responseResults)
        path.isBlank() -> emptyPathResponse(proxy, logger, responseResults)
        path == FAVICON_FILENAME -> invalidPathResponse(path, proxy, logger, responseResults)
        proxy.isBlitzRequest(path) -> responseResults.contentText = "42"
        else -> processRequestsBasedOnPath(proxy, path, responseResults, queryParams)
      }

      responseResults.apply {
        incrementScrapeRequestCount(proxy, updateMsg)
        call.respondWith(contentText, contentType, statusCode)
      }
    }
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.processRequestsBasedOnPath(
    proxy: Proxy,
    path: String,
    responseResults: ResponseResults,
    queryParams: String,
  ) {
    val agentContextInfo = proxy.pathManager.getAgentContextInfo(path)
    when {
      agentContextInfo.isNull() -> invalidPathResponse(path, proxy, logger, responseResults)
      agentContextInfo.isNotValid() -> invalidAgentContextResponse(path, proxy, logger, responseResults)
      else -> processRequests(agentContextInfo, proxy, path, queryParams, responseResults)
    }
  }

  private suspend fun PipelineCall.processRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
    responseResults: ResponseResults,
  ) {
    val results: List<ScrapeRequestResponse> = executeScrapeRequests(agentContextInfo, proxy, path, queryParams)
    val statusCodes: List<HttpStatusCode> = results.map { it.statusCode }.toSet().toList()
    val contentTypes: List<ContentType> = results.map { it.contentType }.toSet().toList()
    val updateMsgs: String = results.joinToString("\n") { it.updateMsg }
    // Grab the contentType of the first OK in the list
    val okContentType: ContentType? = results.firstOrNull { it.statusCode == HttpStatusCode.OK }?.contentType

    responseResults.apply {
      statusCode = if (statusCodes.contains(HttpStatusCode.OK)) HttpStatusCode.OK else statusCodes[0]
      contentType = okContentType ?: contentTypes[0]
      contentText = results.joinToString("\n") { it.contentText }
      updateMsg = updateMsgs
    }
  }

  private suspend fun PipelineCall.executeScrapeRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): List<ScrapeRequestResponse> =
    coroutineScope {
      agentContextInfo.agentContexts
        .map { agentContext ->
          async {
            submitScrapeRequest(agentContext, proxy, path, queryParams, call.request, call.response)
          }
        }
        .map { deferred -> deferred.await() }
        .onEach { response -> logActivityForResponse(path, response, proxy) }
    }

  private fun logActivityForResponse(
    path: String,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    var status = "/$path - ${response.updateMsg} - ${response.statusCode}"
    if (!response.statusCode.isSuccess())
      status += " reason: [${response.failureReason}]"
    status += " time: ${response.fetchDuration} url: ${response.url}"
    proxy.logActivity(status)
  }

  private suspend fun submitScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
    response: ApplicationResponse,
  ): ScrapeRequestResponse {
    val scrapeRequest = createScrapeRequest(agentContext, proxy, path, encodedQueryParams, request)

    try {
      val proxyConfigVals = proxy.proxyConfigVals
      val timeoutTime = proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds
      val checkTime = proxyConfigVals.internal.scrapeRequestCheckMillis.milliseconds

      proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
      agentContext.writeScrapeRequest(scrapeRequest)

      // Returns false if timed out
      while (!scrapeRequest.suspendUntilComplete(checkTime)) {
        // Check if agent is disconnected or agent is hung
        if (scrapeRequest.ageDuration() >= timeoutTime || !scrapeRequest.agentContext.isValid() || !proxy.isRunning)
          return ScrapeRequestResponse(
            statusCode = HttpStatusCode.ServiceUnavailable,
            updateMsg = "timed_out",
            fetchDuration = scrapeRequest.ageDuration(),
          )
      }
    } finally {
      val scrapeId = scrapeRequest.scrapeId
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
        ?: logger.error { "Scrape request $scrapeId missing in map" }
    }

    logger.debug { "Results returned from $agentContext for $scrapeRequest" }

    scrapeRequest.scrapeResults.also { scrapeResults ->
      HttpStatusCode.fromValue(scrapeResults.statusCode).also { statusCode ->
        scrapeResults.contentType.split("/").also { contentTypeElems ->

          val contentType =
            if (contentTypeElems.size == 2)
              ContentType(contentTypeElems[0], contentTypeElems[1])
            else
              ContentType.Text.Plain

          // Do not return content on error status codes
          return if (!statusCode.isSuccess())
            scrapeRequest.scrapeResults.run {
              ScrapeRequestResponse(
                statusCode = statusCode,
                contentType = contentType,
                failureReason = failureReason,
                url = url,
                updateMsg = "path_not_found",
                fetchDuration = scrapeRequest.ageDuration(),
              )
            }
          else
            scrapeRequest.scrapeResults.run {
              // Unzip content here
              ScrapeRequestResponse(
                statusCode = statusCode,
                contentType = contentType,
                contentText = if (zipped) contentAsZipped.unzip() else contentAsText,
                failureReason = failureReason,
                url = url,
                updateMsg = "success",
                fetchDuration = scrapeRequest.ageDuration(),
              )
            }
        }
      }
    }
  }

  private fun createScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
  ): ScrapeRequestWrapper =
    ScrapeRequestWrapper(
      agentContext = agentContext,
      proxy = proxy,
      path = path,
      encodedQueryParams = encodedQueryParams,
      authHeader = request.header(HttpHeaders.Authorization) ?: "",
      accept = request.header(HttpHeaders.Accept),
      debugEnabled = proxy.options.debugEnabled,
    )
}

typealias PipelineCall = PipelineContext<*, ApplicationCall>

class ScrapeRequestResponse(
  val statusCode: HttpStatusCode,
  val updateMsg: String,
  var contentType: ContentType = ContentType.Text.Plain,
  var contentText: String = "",
  val failureReason: String = "",
  val url: String = "",
  val fetchDuration: Duration,
)

class ResponseResults(
  var statusCode: HttpStatusCode = HttpStatusCode.OK,
  var contentType: ContentType = ContentType.Text.Plain,
  var contentText: String = "",
  var updateMsg: String = "",
)
