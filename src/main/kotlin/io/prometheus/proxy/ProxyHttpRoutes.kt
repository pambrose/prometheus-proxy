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

package io.prometheus.proxy

import com.github.pambrose.common.util.isNull
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.unzip
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object ProxyHttpRoutes {
  private val logger = KotlinLogging.logger {}
  private val format = Json { prettyPrint = true }

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
        val json = proxy.buildServiceDiscoveryJson()
        val prettyPrint = format.encodeToString(json)
        call.respondWith(prettyPrint, ContentType.Application.Json.withCharset(Charsets.UTF_8))
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
        else -> processRequestsBasedOnPath(proxy, path, queryParams, responseResults)
      }

      responseResults.apply {
        incrementScrapeRequestCount(proxy, updateMsg)
        if (proxy.options.debugEnabled)
          logger.info { "CT check - handleClientRequests() contentType: $contentType" }
        call.respondWith(contentText, contentType, statusCode)
      }
    }
  }

  private suspend fun RoutingContext.processRequestsBasedOnPath(
    proxy: Proxy,
    path: String,
    queryParams: String,
    responseResults: ResponseResults,
  ) {
    val agentContextInfo = proxy.pathManager.getAgentContextInfo(path)
    when {
      agentContextInfo.isNull() -> invalidPathResponse(path, proxy, logger, responseResults)
      agentContextInfo.isNotValid() -> invalidAgentContextResponse(path, proxy, logger, responseResults)
      else -> processRequests(agentContextInfo, proxy, path, queryParams, responseResults)
    }
  }

  private suspend fun RoutingContext.processRequests(
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
    if (proxy.options.debugEnabled) {
      logger.info { "CT check - processRequests() contentTypes: ${contentTypes.joinToString(", ")}" }
      logger.info { "CT check - processRequests() okContentType: $okContentType" }
    }

    responseResults.apply {
      statusCode = if (statusCodes.contains(HttpStatusCode.OK)) HttpStatusCode.OK else statusCodes[0]
      contentType = okContentType ?: contentTypes[0]
      contentText = results.joinToString("\n") { it.contentText }
      updateMsg = updateMsgs
    }
  }

  private suspend fun RoutingContext.executeScrapeRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): List<ScrapeRequestResponse> =
    coroutineScope {
      agentContextInfo.agentContexts
        .map { agentContext ->
          async { submitScrapeRequest(agentContext, proxy, path, queryParams, call.request, call.response) }
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

        val contentType =
          runCatching {
            if (proxy.options.debugEnabled)
              logger.info { "CT check - submitScrapeRequest() contentType: ${scrapeResults.contentType}" }
            ContentType.parse(scrapeResults.contentType)
          }.getOrElse {
            logger.debug { "Error parsing content type: ${scrapeResults.contentType} -- ${it.simpleClassName}" }
            Text.Plain.withCharset(Charsets.UTF_8)
          }
        logger.debug { "Content type: $contentType" }

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
      authHeader = request.header(HttpHeaders.Authorization).orEmpty(),
      accept = request.header(HttpHeaders.Accept),
      debugEnabled = proxy.options.debugEnabled,
    )
}

class ScrapeRequestResponse(
  val statusCode: HttpStatusCode,
  val updateMsg: String,
  var contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  var contentText: String = "",
  val failureReason: String = "",
  val url: String = "",
  val fetchDuration: Duration,
)

class ResponseResults(
  var statusCode: HttpStatusCode = HttpStatusCode.OK,
  var contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  var contentText: String = "",
  var updateMsg: String = "",
)
