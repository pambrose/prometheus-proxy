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

package io.prometheus.proxy

import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ChunkedScrapeResponse
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Accumulates chunked scrape response data with CRC32 integrity validation.
 *
 * When a scrape response exceeds the gRPC message size limit, the agent sends it as a
 * sequence of header, chunk, and summary messages. This class reassembles the chunks into
 * a single byte stream, validating running CRC32 checksums and byte/chunk counts at each
 * step. On successful summary validation, it produces a complete [ScrapeResults].
 *
 * @param response the initial chunked response containing the header
 * @param maxZippedContentSize maximum allowed total zipped content size in bytes
 * @see AgentContextManager
 * @see ProxyServiceImpl
 */
internal class ChunkedContext(
  response: ChunkedScrapeResponse,
  private val maxZippedContentSize: Long,
) {
  private val checksum = CRC32()
  private val baos = ByteArrayOutputStream()
  private val header = response.header

  var totalChunkCount = 0
    private set
  var totalByteCount = 0L
    private set

  @Suppress("ThrowsCount")
  fun applyChunk(
    data: ByteArray,
    chunkByteCount: Int,
    chunkCount: Int,
    chunkChecksum: Long,
  ) {
    if (chunkByteCount < 0 || chunkByteCount > data.size)
      throw ChunkValidationException(
        "chunkByteCount ($chunkByteCount) is invalid for data size (${data.size})",
      )

    totalChunkCount++
    totalByteCount += chunkByteCount

    if (totalByteCount > maxZippedContentSize)
      throw ChunkValidationException(
        "Zipped content size $totalByteCount exceeds maximum allowed size $maxZippedContentSize",
      )

    checksum.update(data, 0, chunkByteCount)
    baos.write(data, 0, chunkByteCount)

    if (totalChunkCount != chunkCount)
      throw ChunkValidationException("Chunk count mismatch: expected $chunkCount, got $totalChunkCount")
    if (checksum.value != chunkChecksum)
      throw ChunkValidationException(
        "Chunk checksum mismatch for chunk $chunkCount: expected $chunkChecksum, got ${checksum.value}",
      )
  }

  @Suppress("ThrowsCount")
  fun applySummary(
    summaryChunkCount: Int,
    summaryByteCount: Int,
    summaryChecksum: Long,
  ): ScrapeResults {
    if (totalChunkCount != summaryChunkCount)
      throw ChunkValidationException("Summary chunk count mismatch: expected $summaryChunkCount, got $totalChunkCount")
    if (totalByteCount != summaryByteCount.toLong())
      throw ChunkValidationException("Summary byte count mismatch: expected $summaryByteCount, got $totalByteCount")
    if (checksum.value != summaryChecksum)
      throw ChunkValidationException("Summary checksum mismatch: expected $summaryChecksum, got ${checksum.value}")

    baos.flush()
    return header.run {
      ScrapeResults(
        srValidResponse = headerValidResponse,
        srScrapeId = headerScrapeId,
        srAgentId = headerAgentId,
        srStatusCode = headerStatusCode,
        srZipped = headerZipped,
        srContentAsZipped = baos.toByteArray(),
        srFailureReason = headerFailureReason,
        srUrl = headerUrl,
        srContentType = headerContentType,
      )
    }
  }
}

internal class ChunkValidationException(
  message: String,
) : Exception(message)
