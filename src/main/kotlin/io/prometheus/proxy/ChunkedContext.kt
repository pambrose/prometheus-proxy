/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ChunkedScrapeResponse
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

internal class ChunkedContext(response: ChunkedScrapeResponse) {
  private val checksum = CRC32()
  private val baos = ByteArrayOutputStream()

  var totalChunkCount = 0
    private set
  var totalByteCount = 0
    private set

  val scrapeResults =
    response.header.run {
      ScrapeResults(validResponse = headerValidResponse,
                    scrapeId = headerScrapeId,
                    agentId = headerAgentId,
                    statusCode = headerStatusCode,
                    zipped = true,
                    failureReason = headerFailureReason,
                    url = headerUrl,
                    contentType = headerContentType)
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
    scrapeResults.contentAsZipped = baos.toByteArray()
  }
}