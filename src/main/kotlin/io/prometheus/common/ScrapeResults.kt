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
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.RequestTimeout
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeaderData
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.TimeoutCancellationException
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
    ScrapeResponse
      .newBuilder()
      .also {
        it.agentId = agentId
        it.scrapeId = scrapeId
        it.validResponse = validResponse
        it.statusCode = statusCode
        it.contentType = contentType
        it.zipped = zipped
        if (zipped)
          it.contentAsZipped = ByteString.copyFrom(contentAsZipped)
        else
          it.contentAsText = contentAsText
        it.failureReason = failureReason
        it.url = url
      }
      .build()!!

  fun toScrapeResponseHeader() =
    ChunkedScrapeResponse
      .newBuilder()
      .apply {
        header = HeaderData
          .newBuilder()
          .also {
            it.headerValidResponse = validResponse
            it.headerAgentId = agentId
            it.headerScrapeId = scrapeId
            it.headerStatusCode = statusCode
            it.headerFailureReason = failureReason
            it.headerUrl = url
            it.headerContentType = contentType
          }
          .build()
      }.build()!!

  companion object {
    private val logger = KotlinLogging.logger {}

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
