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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.github.pambrose.common.util.EMPTY_BYTE_ARRAY
import com.github.pambrose.common.util.simpleClassName
import com.google.protobuf.ByteString
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.RequestTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse.ChunkOneOf.Header
import io.prometheus.grpc.krotodc.HeaderData
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsText
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsZipped
import kotlinx.coroutines.TimeoutCancellationException
import mu.two.KLogging
import java.io.IOException
import java.net.http.HttpConnectTimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class ScrapeResults(
  val agentId: String,
  val scrapeId: Long,
  var validResponse: Boolean = false,
  var statusCode: Int = NotFound.value,
  var contentType: String = "",
  var zipped: Boolean = false,
  var contentAsText: String = "",
  var contentAsZipped: ByteArray = EMPTY_BYTE_ARRAY,
  var failureReason: String = "",
  var url: String = "",
) {
  val scrapeCounterMsg = AtomicReference("")

  fun setDebugInfo(
    url: String,
    failureReason: String = "",
  ) {
    this.url = url
    this.failureReason = failureReason
  }

  fun toScrapeResponse() =
    io.prometheus.grpc.krotodc.ScrapeResponse(
      agentId = agentId,
      scrapeId = scrapeId,
      validResponse = validResponse,
      statusCode = statusCode,
      contentType = contentType,
      zipped = zipped,
      contentOneOf =
      if (zipped)
        ContentAsZipped(ByteString.copyFrom(contentAsZipped))
      else
        ContentAsText(contentAsText),
      failureReason = failureReason,
      url = url,
    )

  fun toScrapeResponseHeader() =
    ChunkedScrapeResponse(
      chunkOneOf = Header(
        header = HeaderData(
          headerValidResponse = validResponse,
          headerAgentId = agentId,
          headerScrapeId = scrapeId,
          headerStatusCode = statusCode,
          headerFailureReason = failureReason,
          headerUrl = url,
          headerContentType = contentType,
        ),
      ),
    )

  companion object : KLogging() {
    fun errorCode(
      e: Throwable,
      url: String,
    ): Int =
      when (e) {
        is TimeoutCancellationException,
        is HttpConnectTimeoutException,
        is SocketTimeoutException,
        is HttpRequestTimeoutException,
        -> {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          RequestTimeout.value
        }

        is IOException -> {
          logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
          NotFound.value
        }

        else -> {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          ServiceUnavailable.value
        }
      }
  }
}
