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
import io.prometheus.grpc.krotodc.ChunkData
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse.ChunkOneOf.Chunk
import io.prometheus.grpc.krotodc.ChunkedScrapeResponse.ChunkOneOf.Summary
import io.prometheus.grpc.krotodc.ScrapeResponse
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsText
import io.prometheus.grpc.krotodc.ScrapeResponse.ContentOneOf.ContentAsZipped
import io.prometheus.grpc.krotodc.SummaryData
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
        results.contentAsZipped = (contentOneOf as ContentAsZipped).contentAsZipped.toByteArray()
      else
        results.contentAsText = (contentOneOf as ContentAsText).contentAsText
    }

  fun newScrapeResponseChunk(
    scrapeId: Long,
    totalChunkCount: Int,
    readByteCount: Int,
    checksum: CRC32,
    buffer: ByteArray,
  ) = ChunkedScrapeResponse(
    chunkOneOf = Chunk(
      chunk = ChunkData(
        chunkScrapeId = scrapeId,
        chunkCount = totalChunkCount,
        chunkByteCount = readByteCount,
        chunkChecksum = checksum.value,
        chunkBytes = ByteString.copyFrom(buffer),
      ),
    ),
  )

  fun newScrapeResponseSummary(
    scrapeId: Long,
    totalChunkCount: Int,
    totalByteCount: Int,
    checksum: CRC32,
  ) = ChunkedScrapeResponse(
    chunkOneOf = Summary(
      SummaryData(
        summaryScrapeId = scrapeId,
        summaryChunkCount = totalChunkCount,
        summaryByteCount = totalByteCount,
        summaryChecksum = checksum.value,
      ),
    ),
  )
}
