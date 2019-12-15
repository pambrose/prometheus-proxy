@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import com.github.pambrose.common.util.unzip
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.NonChunkedScrapeResponse
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class ChunkedContext(response: ChunkedScrapeResponse) {
  var totalChunkCount = 0
  var totalByteCount = 0
  val crcChecksum = CRC32()
  val baos = ByteArrayOutputStream()
  val responseBuilder: NonChunkedScrapeResponse.Builder = NonChunkedScrapeResponse.newBuilder()

  init {
    responseBuilder.apply {
      response.header.apply {
        validResponse = headerValidResponse
        agentId = headerAgentId
        scrapeId = headerScrapeId
        statusCode = headerStatusCode
        failureReason = headerFailureReason
        url = headerUrl
        contentType = headerContentType
      }
    }
  }

  fun addChunk(data: ByteArray, chunkByteCount: Int, chunkCount: Int, chunkChecksum: Long) {
    totalChunkCount++
    totalByteCount += chunkByteCount
    crcChecksum.update(data, 0, data.size)
    baos.write(data, 0, chunkByteCount)

    check(totalChunkCount == chunkCount)
    check(crcChecksum.value == chunkChecksum)
  }

  fun summary(summaryChunkCount: Int, summaryByteCount: Int, summaryChecksum: Long): NonChunkedScrapeResponse {
    check(totalChunkCount == summaryChunkCount)
    check(totalByteCount == summaryByteCount)
    check(crcChecksum.value == summaryChecksum)

    return responseBuilder.run {
      baos.flush()
      contentText = baos.toByteArray().unzip()
      build()
    }
  }
}