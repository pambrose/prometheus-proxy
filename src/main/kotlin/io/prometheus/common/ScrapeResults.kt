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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.pambrose.common.util.simpleClassName
import io.prometheus.common.Utils.causeChain
import com.google.protobuf.ByteString
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode.Companion.RequestTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.common.Utils.EMPTY_BYTE_ARRAY
import io.prometheus.common.Utils.sanitizeUrlsInText
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.scrapeResponse
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.http.HttpConnectTimeoutException

internal class ScrapeResults(
  val srAgentId: String,
  val srScrapeId: Long,
  val srValidResponse: Boolean = false,
  val srStatusCode: Int = ServiceUnavailable.value,
  val srContentType: String = "",
  val srZipped: Boolean = false,
  val srContentAsText: String = "",
  val srContentAsZipped: ByteArray = EMPTY_BYTE_ARRAY,
  val srFailureReason: String = "",
  val srUrl: String = "",
  val scrapeCounterMsg: String = "",
  // Whether the proxy's dashboard asked for the scrape rather than Prometheus (the request's dashboard_scrape). Never
  // sent to the proxy: it tells the agent to leave the result out of agent_scrape_result_count_total.
  val dashboardScrape: Boolean = false,
  // The body's size as the target sent it, before the agent's metric filter, and after it, before gzip, or 0 for a
  // scrape that got no body. Never sent to the proxy: the agent's dashboard records them (AgentScrapeRecord).
  val rawSize: Int = 0,
  val filteredSize: Int = 0,
  // How many lines the path's metric filter dropped, or null when no filter ran: the path has none, or it let the
  // body through unfiltered. Never sent to the proxy: the agent's fetch span is tagged with it.
  val filterLinesDropped: Int? = null,
) {
  // Whether these results go to the proxy in chunks: a gzipped body of at least [chunkContentSizeBytes]. The one rule
  // for both AgentGrpcService, which sends them so, and the agent's dashboard, which records it (AgentScrapeRecord).
  fun isChunked(chunkContentSizeBytes: Int) = srZipped && srContentAsZipped.size >= chunkContentSizeBytes

  // These results, marked as a scrape the proxy's dashboard asked for. Every other field is carried over as it is.
  fun asDashboardScrape() =
    ScrapeResults(
      srAgentId = srAgentId,
      srScrapeId = srScrapeId,
      srValidResponse = srValidResponse,
      srStatusCode = srStatusCode,
      srContentType = srContentType,
      srZipped = srZipped,
      srContentAsText = srContentAsText,
      srContentAsZipped = srContentAsZipped,
      srFailureReason = srFailureReason,
      srUrl = srUrl,
      scrapeCounterMsg = scrapeCounterMsg,
      dashboardScrape = true,
      rawSize = rawSize,
      filteredSize = filteredSize,
      filterLinesDropped = filterLinesDropped,
    )

  fun toScrapeResponse() =
    scrapeResponse {
      agentId = srAgentId
      scrapeId = srScrapeId
      validResponse = srValidResponse
      statusCode = srStatusCode
      contentType = srContentType
      zipped = srZipped
      if (zipped)
        contentAsZipped = ByteString.copyFrom(srContentAsZipped)
      else
        contentAsText = srContentAsText
      failureReason = srFailureReason
      url = srUrl
    }

  fun toScrapeResponseHeader() =
    chunkedScrapeResponse {
      header = headerData {
        headerValidResponse = srValidResponse
        headerAgentId = srAgentId
        headerScrapeId = srScrapeId
        headerStatusCode = srStatusCode
        headerFailureReason = srFailureReason
        headerUrl = srUrl
        headerContentType = srContentType
        headerZipped = srZipped
      }
    }

  companion object {
    private val logger = logger {}

    fun ScrapeResponse.toScrapeResults() =
      ScrapeResults(
        srAgentId = agentId,
        srScrapeId = scrapeId,
        srValidResponse = validResponse,
        srStatusCode = statusCode,
        srContentType = contentType,
        srZipped = zipped,
        srContentAsText = if (!zipped) contentAsText else "",
        srContentAsZipped = if (zipped) contentAsZipped.toByteArray() else EMPTY_BYTE_ARRAY,
        srFailureReason = failureReason,
        srUrl = url,
      )

    fun errorCode(
      e: Throwable,
      url: String,
    ): Int {
      // Exception messages embed the request URL, credentials included, so the logs carry only the exception type and
      // its redacted message. The throwable itself is never logged: a rendered stack trace would print the raw message.
      val detail = "[${e.simpleClassName}: ${e.message?.let { sanitizeUrlsInText(it) }}]"

      // Detect wrapped timeout exceptions (Ktor sometimes wraps them) via the shared cause-walk.
      if (e.hasTimeoutCause()) {
        logger.warn { "fetchScrapeUrl() timed out - $url $detail" }
        return RequestTimeout.value
      }

      return when (e) {
        is IOException -> {
          logger.warn { "Failed HTTP request: $url $detail" }
          ServiceUnavailable.value
        }

        else -> {
          logger.warn { "fetchScrapeUrl() - $url $detail" }
          ServiceUnavailable.value
        }
      }
    }
  }
}

// Shared timeout-cause predicate used by both the status-code mapping here and the agent's scrape-fetch
// catch branch, so they can no longer disagree about the same throwable (finding 34).
internal fun Throwable.hasTimeoutCause(): Boolean =
  causeChain().any {
    it is TimeoutCancellationException ||
      // Ktor's CIO engine raises its own connect-timeout type, a java.net.ConnectException.
      it is ConnectTimeoutException ||
      it is HttpConnectTimeoutException ||
      it is SocketTimeoutException ||
      it is HttpRequestTimeoutException
  }
