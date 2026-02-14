/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.EMPTY_BYTE_ARRAY
import com.github.pambrose.common.util.simpleClassName
import com.google.protobuf.ByteString
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode.Companion.RequestTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.network.sockets.SocketTimeoutException
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
) {
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
      // Walk the cause chain to detect wrapped timeout exceptions.
      // Ktor sometimes wraps HttpRequestTimeoutException in other exceptions.
      if (hasTimeoutCause(e)) {
        logger.warn(e) { "fetchScrapeUrl() $e - $url" }
        return RequestTimeout.value
      }

      return when (e) {
        is IOException -> {
          logger.warn { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
          ServiceUnavailable.value
        }

        else -> {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          ServiceUnavailable.value
        }
      }
    }

    private fun hasTimeoutCause(e: Throwable): Boolean {
      var current: Throwable? = e
      while (current != null) {
        when (current) {
          is TimeoutCancellationException,
          is HttpConnectTimeoutException,
          is SocketTimeoutException,
          is HttpRequestTimeoutException,
            -> return true
        }
        current = current.cause
      }
      return false
    }
  }
}
