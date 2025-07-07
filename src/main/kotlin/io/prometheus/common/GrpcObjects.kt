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

import com.google.protobuf.ByteString
import io.prometheus.grpc.ChunkData
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.SummaryData
import java.util.zip.CRC32

internal object GrpcObjects {
  fun ScrapeResponse.toScrapeResults() =
    ScrapeResults(
      agentId = agentId,
      scrapeId = scrapeId,
      validResponse = validResponse,
      statusCode = statusCode,
      contentType = contentType,
      zipped = zipped,
      failureReason = failureReason,
      url = url,
    ).also { results ->
      if (zipped)
        results.contentAsZipped = contentAsZipped.toByteArray()
      else
        results.contentAsText = contentAsText
    }

  fun newScrapeResponseChunk(
    scrapeId: Long,
    totalChunkCount: Int,
    readByteCount: Int,
    checksum: CRC32,
    buffer: ByteArray,
  ) = ChunkedScrapeResponse
    .newBuilder()
    .apply {
      chunk = ChunkData
        .newBuilder()
        .also {
          it.chunkScrapeId = scrapeId
          it.chunkCount = totalChunkCount
          it.chunkByteCount = readByteCount
          it.chunkChecksum = checksum.value
          it.chunkBytes = ByteString.copyFrom(buffer)
        }
        .build()
    }
    .build()!!

  fun newScrapeResponseSummary(
    scrapeId: Long,
    totalChunkCount: Int,
    totalByteCount: Int,
    checksum: CRC32,
  ) = ChunkedScrapeResponse
    .newBuilder()
    .also { chunkedScrapeResponseBuilder ->
      chunkedScrapeResponseBuilder.summary =
        SummaryData
          .newBuilder()
          .also { summaryDataBuilder ->
            summaryDataBuilder.summaryScrapeId = scrapeId
            summaryDataBuilder.summaryChunkCount = totalChunkCount
            summaryDataBuilder.summaryByteCount = totalByteCount
            summaryDataBuilder.summaryChecksum = checksum.value
          }
          .build()
    }
    .build()!!
}
