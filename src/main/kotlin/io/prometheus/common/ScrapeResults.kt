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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.github.pambrose.common.util.EMPTY_BYTE_ARRAY
import com.google.protobuf.ByteString
import io.ktor.http.*
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse.ChunkOneOf.Header
import io.prometheus.grpc.krotodc.HeaderData
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsText
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsZipped

internal class ScrapeResults(
  val agentId: String,
  val scrapeId: Long,
  var validResponse: Boolean = false,
  var statusCode: Int = HttpStatusCode.NotFound.value,
  var contentType: String = "",
  var zipped: Boolean = false,
  var contentAsText: String = "",
  var contentAsZipped: ByteArray = EMPTY_BYTE_ARRAY,
  var failureReason: String = "",
  var url: String = "",
) {
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
}
