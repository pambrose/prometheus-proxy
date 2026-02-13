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

import com.github.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyConstants.CACHE_CONTROL_VALUE
import io.prometheus.proxy.ProxyConstants.FAVICON_FILENAME
import io.prometheus.proxy.ProxyUtils.incrementScrapeRequestCount
import io.prometheus.proxy.ProxyUtils.respondWith
import io.prometheus.proxy.ProxyUtils.unzip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object ProxyHttpRoutes {
  private val logger = logger {}
  private val format = Json { prettyPrint = true }
  private val authHeaderWithoutTlsWarned = AtomicBoolean(false)

  fun Routing.handleRequests(proxy: Proxy) {
    //      get("/__test__") {
    //        delay(30.seconds)
    //        call.respondWith("Test value", Plain, OK)
    //      }
    handleServiceDiscoveryEndpoint(proxy)
    handleClientRequests(proxy)
  }

  private fun Routing.handleServiceDiscoveryEndpoint(proxy: Proxy) {
    if (proxy.options.sdEnabled) {
      val sdPath = proxy.options.sdPath.ensureLeadingSlash()
      logger.info { "Adding $sdPath service discovery endpoint" }
      get(sdPath) {
        val json = proxy.buildServiceDiscoveryJson()
        val prettyPrint = format.encodeToString(json)
        call.respondWith(prettyPrint, ContentType.Application.Json.withCharset(Charsets.UTF_8))
      }
    } else {
      logger.info { "Not adding /${proxy.options.sdPath} service discovery endpoint" }
    }
  }

  internal fun String.ensureLeadingSlash() = if (startsWith("/")) this else "/$this"

  private fun Routing.handleClientRequests(proxy: Proxy) {
    get("/*") {
      call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)

      val path = call.request.path().drop(1)
      val queryParams = call.request.queryParameters.formUrlEncode()

      logger.debug {
        "Servicing request for path: $path${if (queryParams.isNotEmpty()) " with query params $queryParams" else ""}"
      }

      val responseResults =
        when {
          !proxy.isRunning -> ProxyUtils.proxyNotRunningResponse()
          path.isBlank() -> ProxyUtils.emptyPathResponse(proxy)
          path == FAVICON_FILENAME -> ProxyUtils.invalidPathResponse(path, proxy)
          proxy.isBlitzRequest(path) -> ResponseResults(contentText = "42")
          else -> processRequestsBasedOnPath(proxy, path, queryParams)
        }

      incrementScrapeRequestCount(proxy, responseResults.updateMsg)
//      if (proxy.options.debugEnabled)
//        logger.info { "CT check - handleClientRequests() contentType: ${responseResults.contentType}" }
      call.respondWith(responseResults.contentText, responseResults.contentType, responseResults.statusCode)
    }
  }

  private suspend fun RoutingContext.processRequestsBasedOnPath(
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): ResponseResults {
    val agentContextInfo = proxy.pathManager.getAgentContextInfo(path)
    return when {
      agentContextInfo == null -> ProxyUtils.invalidPathResponse(path, proxy)
      agentContextInfo.isNotValid() -> ProxyUtils.invalidAgentContextResponse(path, proxy)
      else -> processRequests(agentContextInfo, proxy, path, queryParams)
    }
  }

  private suspend fun RoutingContext.processRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
  ): ResponseResults {
    val results: List<ScrapeRequestResponse> = executeScrapeRequests(agentContextInfo, proxy, path, queryParams)

    // Handle case where no results were returned (all agents failed or disconnected)
    if (results.isEmpty()) {
      logger.warn { "No scrape results returned for path: $path" }
      return ResponseResults(
        statusCode = HttpStatusCode.ServiceUnavailable,
        contentType = Text.Plain.withCharset(Charsets.UTF_8),
        contentText = "No agents available to handle request",
        updateMsg = "no_agents",
      )
    }

    val statusCodes = results.map { it.statusCode }.distinct()
    val contentTypes = results.map { it.contentType }.distinct()
    val updateMsgs: String = results.joinToString("\n") { it.updateMsg }
    // Grab the contentType of the first OK in the list
    val okContentType: ContentType? = results.firstOrNull { it.statusCode == HttpStatusCode.OK }?.contentType
//    if (proxy.options.debugEnabled) {
//      logger.info { "CT check - processRequests() contentTypes: ${contentTypes.joinToString(", ")}" }
//      logger.info { "CT check - processRequests() okContentType: $okContentType" }
//    }

    return ResponseResults(
      statusCode = if (statusCodes.contains(HttpStatusCode.OK)) HttpStatusCode.OK else statusCodes[0],
      contentType = okContentType ?: contentTypes[0],
      contentText = mergeContentTexts(results),
      updateMsg = updateMsgs,
    )
  }

  // Bug #13: When consolidated paths have multiple agents returning OpenMetrics format,
  // each agent's response ends with "# EOF". Naively joining with "\n" produces
  // "# EOF" markers in the middle of the stream, which is invalid OpenMetrics.
  // This function strips trailing "# EOF" lines from each result and appends a
  // single "# EOF" at the end if any result contained one.
  internal fun mergeContentTexts(results: List<ScrapeRequestResponse>): String {
    if (results.size == 1) return results[0].contentText

    var hasEof = false
    val stripped = results.map { result ->
      val text = result.contentText
      val trimmed = text.trimEnd()
      if (trimmed.endsWith("# EOF")) {
        hasEof = true
        trimmed.removeSuffix("# EOF").trimEnd()
      } else {
        text
      }
    }

    val joined = stripped.joinToString("\n")
    return if (hasEof) "$joined\n# EOF" else joined
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
          async { submitScrapeRequest(agentContext, proxy, path, queryParams, call.request) }
        }
        .awaitAll()
        .onEach { response -> logActivityForResponse(path, response, proxy) }
    }

  private fun logActivityForResponse(
    path: String,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    val status =
      buildString {
        append("/$path - ${response.updateMsg} - ${response.statusCode}")
        if (!response.statusCode.isSuccess())
          append(" reason: [${response.failureReason}]")
        append(" time: ${response.fetchDuration} url: ${response.url}")
      }
    proxy.logActivity(status)
  }

  @Suppress("ReturnCount")
  internal suspend fun submitScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
  ): ScrapeRequestResponse {
    val scrapeRequest = createScrapeRequest(agentContext, proxy, path, encodedQueryParams, request)

    try {
      val proxyConfigVals = proxy.proxyConfigVals
      val timeoutTime = proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds

      proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
      try {
        agentContext.writeScrapeRequest(scrapeRequest)
      } catch (_: ClosedSendChannelException) {
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          fetchDuration = scrapeRequest.ageDuration(),
        )
      }

      // Suspends until completed, agent disconnects, or timeout expires.
      // The polling loop (Bug #2) is replaced by a single awaitCompleted() call.
      if (!scrapeRequest.awaitCompleted(timeoutTime)) {
        return ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "timed_out",
          fetchDuration = scrapeRequest.ageDuration(),
        )
      }
    } finally {
      scrapeRequest.closeChannel()
      val scrapeId = scrapeRequest.scrapeId
      proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeId)
        ?: logger.error { "Scrape request $scrapeId missing in map" }
    }

    logger.debug { "Results returned from $agentContext for $scrapeRequest" }

    val scrapeResults = scrapeRequest.scrapeResults
      ?: return ScrapeRequestResponse(
        statusCode = HttpStatusCode.ServiceUnavailable,
        updateMsg = "missing_results",
        fetchDuration = scrapeRequest.ageDuration(),
      )

    HttpStatusCode.fromValue(scrapeResults.srStatusCode).also { statusCode ->

      val contentType =
        runCatching {
//          if (proxy.options.debugEnabled)
//            logger.info { "CT check - submitScrapeRequest() contentType: ${scrapeResults.srContentType}" }
          ContentType.parse(scrapeResults.srContentType)
        }.getOrElse {
          logger.debug { "Error parsing content type: ${scrapeResults.srContentType} -- ${it.simpleClassName}" }
          Text.Plain.withCharset(Charsets.UTF_8)
        }
      logger.debug { "Content type: $contentType" }

      // Do not return content on error status codes
      return if (!statusCode.isSuccess())
        scrapeResults.run {
          ScrapeRequestResponse(
            statusCode = statusCode,
            contentType = contentType,
            failureReason = srFailureReason,
            url = srUrl,
            updateMsg = "path_not_found",
            fetchDuration = scrapeRequest.ageDuration(),
          )
        }
      else
        scrapeResults.run {
          val maxSize = proxy.proxyConfigVals.internal.maxUnzippedContentSizeMBytes * 1024L * 1024L
          val contentText =
            try {
              if (srZipped) srContentAsZipped.unzip(maxSize) else srContentAsText
            } catch (e: ProxyUtils.ZipBombException) {
              return ScrapeRequestResponse(
                statusCode = HttpStatusCode.PayloadTooLarge,
                contentType = Text.Plain.withCharset(Charsets.UTF_8),
                failureReason = e.message ?: "Unzipped content too large",
                url = srUrl,
                updateMsg = "payload_too_large",
                fetchDuration = scrapeRequest.ageDuration(),
              )
            }

          ScrapeRequestResponse(
            statusCode = statusCode,
            contentType = contentType,
            contentText = contentText,
            failureReason = srFailureReason,
            url = srUrl,
            updateMsg = "success",
            fetchDuration = scrapeRequest.ageDuration(),
          )
        }
    }
  }

  private fun createScrapeRequest(
    agentContext: AgentContext,
    proxy: Proxy,
    path: String,
    encodedQueryParams: String,
    request: ApplicationRequest,
  ): ScrapeRequestWrapper {
    val authHeader = request.header(HttpHeaders.Authorization).orEmpty()

    if (authHeader.isNotEmpty() && !proxy.options.isTlsEnabled && authHeaderWithoutTlsWarned.compareAndSet(false, true))
      logger.warn {
        "Authorization header is being forwarded to agent over a non-TLS gRPC connection. " +
          "Credentials may be exposed in transit. Configure TLS (--cert, --key) to secure the proxy-agent channel."
      }

    return ScrapeRequestWrapper(
      agentContext = agentContext,
      proxy = proxy,
      pathVal = path,
      encodedQueryParamsVal = encodedQueryParams,
      authHeaderVal = authHeader,
      acceptVal = request.header(HttpHeaders.Accept),
      debugEnabledVal = proxy.options.debugEnabled,
    )
  }
}

data class ScrapeRequestResponse(
  val statusCode: HttpStatusCode,
  val updateMsg: String,
  val contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  val contentText: String = "",
  val failureReason: String = "",
  val url: String = "",
  val fetchDuration: Duration,
)

data class ResponseResults(
  val statusCode: HttpStatusCode = HttpStatusCode.OK,
  val contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  val contentText: String = "",
  val updateMsg: String = "",
)
