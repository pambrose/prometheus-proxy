/*
 * Copyright © 2026 Paul Ambrose
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

import com.pambrose.common.util.ensureLeadingSlash
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.server.request.path
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.prometheus.Proxy
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.Utils.sanitizeQueryParams
import io.prometheus.proxy.ProxyConstants.FAVICON_FILENAME
import io.prometheus.proxy.ProxyUtils.incrementScrapeRequestCount
import io.prometheus.proxy.ProxyUtils.respondWith
import io.prometheus.proxy.ScrapeDispatch.MAX_SHOWN_RESULT_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * HTTP routing logic for the proxy's Ktor server.
 *
 * Defines the routes that handle incoming Prometheus scrape requests and service discovery.
 * For each scrape request, resolves the path to one or more [AgentContext] instances, scrapes them
 * through [ScrapeDispatch], which sends the scrape through each agent's gRPC stream and merges the
 * answers of consolidated agents, and records the outcome: the scrape metrics, the `/debug` activity
 * line, and the dashboard's scrape record and event. Only the route records, so the dashboard's
 * scrape-now, which scrapes through [ScrapeDispatch] too, leaves no trace in what the proxy reports.
 *
 * @see ProxyHttpService
 * @see ProxyPathManager
 * @see ScrapeDispatch
 * @see AgentContext
 */
internal object ProxyHttpRoutes {
  private val logger = logger {}
  private val format = Json { prettyPrint = true }

  // Prometheus hung up before the agent answered, usually because its scrape timeout is shorter than the proxy's.
  internal const val CLIENT_CANCELLED_LABEL = "client_cancelled"

  // nginx's non-standard 499 (Client Closed Request): the client left, so no status ever reached it.
  private val CLIENT_CLOSED_REQUEST = HttpStatusCode(499, "Client Closed Request")

  fun Routing.handleRequests(proxy: Proxy) {
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
      logger.info { "Not adding ${proxy.options.sdPath.ensureLeadingSlash()} service discovery endpoint" }
    }
  }

  private fun Routing.handleClientRequests(proxy: Proxy) {
    get("/*") {
      // Cache-Control is set once, by respondWith() (the single response site); setting it here too
      // would make Ktor append a second identical header (finding 29).
      // Ktor skips empty segments when it matches a route, so "//orders_metrics" reaches this one-segment route as
      // "/orders_metrics" does. Every leading slash goes, not only the first, so all that uses the path -- the favicon
      // and blitz checks, the path map, the agent, the per-path metrics, /debug, the dashboard, and the logs -- sees
      // the key it registered under. With one left, the agent, which keys its paths without a slash, answered 404, and
      // the per-path histograms, which record only a registered key, missed the scrape. Registration refuses a key with
      // a slash in it, so stripping them all can't match another path.
      val path = call.request.path().trimStart('/')
      val queryParams = call.request.queryParameters.formUrlEncode()

      // Both are the client's, from the unauthenticated scrape port, and the path isn't yet checked against the path
      // map, so both are logged cut and escaped, as every value a client or agent supplies is: CIO passes control
      // characters other than whitespace in a request target, so a client could otherwise forge lines in the log.
      logger.debug {
        val shownParams = shownValue(sanitizeQueryParams(queryParams))
        val withParams = if (queryParams.isNotEmpty()) " with query params $shownParams" else ""
        "Servicing request for path: ${ProxyPathManager.shownPath(path)}$withParams"
      }

      val responseResults =
        when {
          !proxy.isRunning -> ProxyUtils.proxyNotRunningResponse()
          path.isBlank() -> ProxyUtils.emptyPathResponse(proxy)
          path == FAVICON_FILENAME -> ProxyUtils.invalidPathResponse(path, proxy)
          proxy.isBlitzRequest(path) -> ResponseResults(contentText = "42")
          else -> processRequestsBasedOnPath(proxy, path, queryParams)
        }

      responseResults.updateMsgs.forEach { incrementScrapeRequestCount(proxy, it) }
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
    // One response per agent. The path map never holds an empty agent list, and the caller has already answered
    // invalid_agent_context for one (isNotValid() is true for it), so there is always at least one.
    val headers = ScrapeHeaders.of(call.request)
    val results: List<ScrapeRequestResponse> =
      executeScrapeRequests(agentContextInfo, proxy, path, queryParams, headers)
    return ScrapeDispatch.mergeResponseResults(results)
  }

  private suspend fun executeScrapeRequests(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    queryParams: String,
    headers: ScrapeHeaders,
  ): List<ScrapeRequestResponse> {
    val started = TimeSource.Monotonic.markNow()
    return try {
      // submitScrapeRequests answers in the agents' order, so zipping recovers which agent produced which response.
      // That keeps provenance at the one site that needs it, rather than as a field on the response.
      ScrapeDispatch.submitScrapeRequests(agentContextInfo, proxy, path, queryParams, headers)
          .also { responses ->
            agentContextInfo.agentContexts.zip(responses).forEach { (agentContext, response) ->
              recordScrapeOutcome(path, agentContext, response, proxy)
            }
          }
          .onEach { response ->
            // Record latency labeled with the request outcome. This single site covers every outcome
            // — including the timeout and agent-disconnected early returns the old per-request timer
            // missed — since each branch yields a ScrapeRequestResponse with updateMsg + fetchDuration.
            proxy.metrics {
              val elapsedSecs = response.fetchDuration.toDouble(DurationUnit.SECONDS)
              observeLatency(path, response.updateMsg, elapsedSecs)
              // Here rather than where the body is decoded, so a scrape made from the dashboard, which submits through
              // the same code, adds nothing to the metrics Prometheus reads.
              response.encoding?.also { observeResponseBytes(path, it, response.byteCount.toDouble()) }
            }
          }
    } catch (e: CancellationException) {
      // Ktor cancels the call when Prometheus hangs up, usually at a scrape timeout shorter than the proxy's. The
      // bookkeeping above runs only after awaitAll() returns, so without this the most common real failure -- a
      // target slower than Prometheus but faster than the proxy -- would vanish from the scrape metrics, /debug,
      // and the dashboard. Nothing in it suspends, so it runs to completion in the cancelled coroutine.
      recordClientCancelled(agentContextInfo, proxy, path, started.elapsedNow())
      throw e
    }
  }

  private fun recordClientCancelled(
    agentContextInfo: ProxyPathManager.AgentContextInfo,
    proxy: Proxy,
    path: String,
    elapsed: Duration,
  ) {
    val response =
      ScrapeRequestResponse(
        statusCode = CLIENT_CLOSED_REQUEST,
        updateMsg = CLIENT_CANCELLED_LABEL,
        failureReason = "client closed the connection before the agent answered",
        fetchDuration = elapsed,
      )
    agentContextInfo.agentContexts.forEach { agentContext ->
      recordScrapeOutcome(path, agentContext, response, proxy)
      incrementScrapeRequestCount(proxy, CLIENT_CANCELLED_LABEL)
      proxy.metrics {
        observeLatency(path, CLIENT_CANCELLED_LABEL, elapsed.toDouble(DurationUnit.SECONDS))
      }
    }
  }

  // Named for what it now does: this is where a completed scrape becomes observable -- as a text line
  // on /debug, as a structured record for the dashboard, and as an event on the bus. Takes the agent's context, not
  // its id and name, so the record carries all the dashboard shows of the agent after it has gone, its identity too.
  internal fun recordScrapeOutcome(
    path: String,
    agentContext: AgentContext,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    // Each consumer is optional and off by default: the activity line feeds only /debug, and the scrape record and
    // ScrapeCompleted event feed only the dashboard. Without these checks every agent on every scrape paid for a
    // formatted status string, two process-wide locks, and an event that nothing reads.
    if (proxy.options.debugEnabled) {
      // The agent's failure reason and URL are cut, and escaped so each entry stays one line (November #12, #16).
      val status =
        buildString {
          append("/$path - ${response.updateMsg} - ${response.statusCode}")
          if (!response.statusCode.isSuccess())
            append(" reason: [${shownValue(response.failureReason, MAX_SHOWN_RESULT_CHARS)}]")
          append(" time: ${response.fetchDuration} url: ${shownValue(response.url, MAX_SHOWN_RESULT_CHARS)}")
        }
      proxy.logActivity(status)
    }
    if (proxy.options.dashboardEnabled) {
      proxy.recordScrape(
        ScrapeRecord(
          agentId = agentContext.agentId,
          agentName = agentContext.agentName,
          identity = agentContext.identityName,
          path = path,
          statusCode = response.statusCode.value,
          outcome = response.updateMsg,
          durationMillis = response.fetchDuration.inWholeMilliseconds,
          contentLength = response.contentText.length,
        ),
      )
      proxy.eventBus.emit(
        ProxyEvent.ScrapeCompleted(agentContext.agentId, path, response.statusCode.isSuccess()),
      )
    }
  }
}

internal data class ResponseResults(
  val statusCode: HttpStatusCode = HttpStatusCode.OK,
  val contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  val contentText: String = "",
  val updateMsgs: List<String> = emptyList(),
)
