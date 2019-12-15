@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ChunkedScrapeResponse
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class ChunkedContext(response: ChunkedScrapeResponse) {
  var totalChunkCount = 0
  var totalByteCount = 0
  val checksum = CRC32()
  val baos = ByteArrayOutputStream()
  val scrapeResults =
      response.header.run {
        ScrapeResults(
            validResponse = headerValidResponse,
            scrapeId = headerScrapeId,
            agentId = headerAgentId,
            statusCode = headerStatusCode,
            failureReason = headerFailureReason,
            url = headerUrl,
            contentType = headerContentType
        )
      }

  fun applyChunk(data: ByteArray, chunkByteCount: Int, chunkCount: Int, chunkChecksum: Long) {
    totalChunkCount++
    totalByteCount += chunkByteCount
    checksum.update(data, 0, data.size)
    baos.write(data, 0, chunkByteCount)

    check(totalChunkCount == chunkCount)
    check(checksum.value == chunkChecksum)
  }

  fun applySummary(summaryChunkCount: Int, summaryByteCount: Int, summaryChecksum: Long) {
    check(totalChunkCount == summaryChunkCount)
    check(totalByteCount == summaryByteCount)
    check(checksum.value == summaryChecksum)

    baos.flush()
    scrapeResults.contentZipped = baos.toByteArray()
  }
}