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

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import java.util.zip.CRC32

// Tests for ChunkedContext which manages the state of a chunked scrape response.
// Large scrape responses are split into chunks to avoid gRPC message size limits.
// ChunkedContext accumulates chunks and verifies checksums for data integrity.
class ChunkedContextTest : StringSpec() {
  private fun createHeaderResponse(
    validResponse: Boolean = true,
    scrapeId: Long = 12345L,
    agentId: String = "test-agent",
    statusCode: Int = 200,
    failureReason: String = "",
    url: String = "http://localhost:8080/metrics",
    contentType: String = "text/plain",
    zipped: Boolean = true,
  ) = chunkedScrapeResponse {
    header = headerData {
      headerValidResponse = validResponse
      headerScrapeId = scrapeId
      headerAgentId = agentId
      headerStatusCode = statusCode
      headerFailureReason = failureReason
      headerUrl = url
      headerContentType = contentType
      headerZipped = zipped
    }
  }

  init {
    // ==================== Constructor Tests ====================

    "constructor should initialize with header data" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      context.shouldNotBeNull()
      context.totalChunkCount shouldBe 0
      context.totalByteCount shouldBe 0
    }

    "applySummary should propagate header fields to scrapeResults" {
      val response = createHeaderResponse(
        validResponse = true,
        scrapeId = 999L,
        agentId = "my-agent",
        statusCode = 200,
        url = "http://test/metrics",
        contentType = "text/plain; charset=utf-8",
      )
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)
      scrapeResults.apply {
        srValidResponse.shouldBeTrue()
        srScrapeId shouldBe 999L
        srAgentId shouldBe "my-agent"
        srStatusCode shouldBe 200
        srUrl shouldBe "http://test/metrics"
        srContentType shouldBe "text/plain; charset=utf-8"
        srZipped.shouldBeTrue()
      }
    }

    // ==================== applyChunk Tests ====================

    "applyChunk should increment chunk count" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "test data".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      context.applyChunk(data, data.size, 1, checksum)

      context.totalChunkCount shouldBe 1
    }

    "applyChunk should accumulate byte count" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "test data 12345".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      context.applyChunk(data, data.size, 1, checksum)

      context.totalByteCount shouldBe data.size
    }

    "applyChunk should accumulate multiple chunks" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()

      // First chunk
      val data1 = "first chunk".toByteArray()
      crc.update(data1)
      context.applyChunk(data1, data1.size, 1, crc.value)

      // Second chunk
      val data2 = "second chunk".toByteArray()
      crc.update(data2)
      context.applyChunk(data2, data2.size, 2, crc.value)

      context.totalChunkCount shouldBe 2
      context.totalByteCount shouldBe data1.size + data2.size
    }

    "applyChunk should throw on chunk count mismatch" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "test data".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      // Expect chunk count 1, but provide 2
      shouldThrow<ChunkValidationException> {
        context.applyChunk(data, data.size, 2, checksum)
      }
    }

    "applyChunk should throw on checksum mismatch" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "test data".toByteArray()
      val wrongChecksum = 12345L // Wrong checksum

      shouldThrow<ChunkValidationException> {
        context.applyChunk(data, data.size, 1, wrongChecksum)
      }
    }

    "applyChunk should throw when maxZippedContentSize is exceeded" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 10)

      val data = "test data over 10 bytes".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      shouldThrow<ChunkValidationException> {
        context.applyChunk(data, data.size, 1, checksum)
      }
    }

    // ==================== applySummary Tests ====================

    "applySummary should verify chunk count" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test data".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      // Apply summary with correct counts
      val scrapeResults = context.applySummary(1, data.size, crc.value)

      // Should complete without exception
      scrapeResults.srContentAsZipped.shouldNotBeNull()
    }

    "applySummary should throw on chunk count mismatch" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test data".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      // Apply summary with wrong chunk count
      shouldThrow<ChunkValidationException> {
        context.applySummary(5, data.size, crc.value) // Wrong chunk count
      }
    }

    "applySummary should throw on byte count mismatch" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test data".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      // Apply summary with wrong byte count
      shouldThrow<ChunkValidationException> {
        context.applySummary(1, 9999, crc.value) // Wrong byte count
      }
    }

    "applySummary should throw on checksum mismatch" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test data".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      // Apply summary with wrong checksum
      shouldThrow<ChunkValidationException> {
        context.applySummary(1, data.size, 12345L) // Wrong checksum
      }
    }

    "applySummary should set content in scrapeResults" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "metrics content here".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)

      val content = scrapeResults.srContentAsZipped
      content.shouldNotBeNull()
      content.size shouldBe data.size
      content.contentEquals(data).shouldBeTrue()
    }

    // ==================== Multi-Chunk Integration Tests ====================

    "should handle multiple chunks and verify final summary" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()

      // Simulate chunked transfer
      val chunk1 = "chunk1-data-".toByteArray()
      val chunk2 = "chunk2-data-".toByteArray()
      val chunk3 = "chunk3-data".toByteArray()

      crc.update(chunk1)
      context.applyChunk(chunk1, chunk1.size, 1, crc.value)

      crc.update(chunk2)
      context.applyChunk(chunk2, chunk2.size, 2, crc.value)

      crc.update(chunk3)
      context.applyChunk(chunk3, chunk3.size, 3, crc.value)

      val totalSize = chunk1.size + chunk2.size + chunk3.size
      val scrapeResults = context.applySummary(3, totalSize, crc.value)

      context.totalChunkCount shouldBe 3
      context.totalByteCount shouldBe totalSize
      scrapeResults.srContentAsZipped.shouldNotBeNull()
    }

    // ==================== Header Field Propagation Tests ====================

    "applySummary should propagate failure reason from header" {
      val response = createHeaderResponse(
        validResponse = false,
        failureReason = "Connection timeout",
      )
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)
      scrapeResults.srFailureReason shouldBe "Connection timeout"
    }

    "applySummary should handle empty content type from header" {
      val response = createHeaderResponse(contentType = "")
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)
      scrapeResults.srContentType shouldBe ""
    }

    // ==================== Partial Last Chunk Tests (Bug #2) ====================

    "applyChunk should checksum only chunkByteCount bytes when data array is larger" {
      // This simulates the last chunk of a chunked transfer where the buffer is
      // larger than the actual bytes read (e.g., 1024-byte buffer with only 100 bytes read).
      // Before the fix, checksum was computed on data.size instead of chunkByteCount,
      // which included stale bytes from a previous read.
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val bufferSize = 1024
      val actualBytes = 100
      val buffer = ByteArray(bufferSize)

      // Fill the first actualBytes with real data
      val realData = "A".repeat(actualBytes).toByteArray()
      realData.copyInto(buffer)
      // Remaining bytes in buffer are zeros (stale data)

      // Compute the expected checksum using only the actual bytes
      val expectedChecksum = CRC32().apply { update(buffer, 0, actualBytes) }.value

      // This should succeed: checksum is computed on chunkByteCount (100) bytes, not data.size (1024)
      context.applyChunk(buffer, actualBytes, 1, expectedChecksum)

      context.totalChunkCount shouldBe 1
      context.totalByteCount shouldBe actualBytes
    }

    "applyChunk should reject checksum computed on full buffer when chunkByteCount is smaller" {
      // This verifies that the old buggy behavior (checksumming full data.size) would fail.
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val bufferSize = 1024
      val actualBytes = 100
      val buffer = ByteArray(bufferSize)
      "A".repeat(actualBytes).toByteArray().copyInto(buffer)

      // Compute checksum on the FULL buffer (the old buggy behavior)
      val wrongChecksum = CRC32().apply { update(buffer, 0, bufferSize) }.value

      // This should fail because the receiver now checksums only chunkByteCount bytes
      shouldThrow<ChunkValidationException> {
        context.applyChunk(buffer, actualBytes, 1, wrongChecksum)
      }
    }

    "full chunked transfer should work when last chunk is partial" {
      // Simulates a realistic chunked transfer: two full chunks and one partial last chunk.
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val chunkSize = 16
      // Total data is 40 bytes: two full 16-byte chunks + one 8-byte partial chunk
      val fullData = "ABCDEFGHIJKLMNOP".repeat(2).toByteArray() + "12345678".toByteArray()
      // fullData is 40 bytes total

      val crc = CRC32()
      val buffer = ByteArray(chunkSize)
      var chunkNum = 0

      val bais = java.io.ByteArrayInputStream(fullData)
      var readCount: Int
      while (bais.read(buffer).also { readCount = it } > 0) {
        chunkNum++
        crc.update(buffer, 0, readCount)
        // Pass the full buffer but only readCount as chunkByteCount
        context.applyChunk(buffer.copyOf(), readCount, chunkNum, crc.value)
      }

      // 40 / 16 = 2 full + 1 partial = 3 chunks
      context.totalChunkCount shouldBe 3
      context.totalByteCount shouldBe 40

      val scrapeResults = context.applySummary(3, 40, crc.value)

      // Verify the reassembled content matches the original
      scrapeResults.srContentAsZipped.shouldNotBeNull()
      scrapeResults.srContentAsZipped.size shouldBe 40
      scrapeResults.srContentAsZipped.contentEquals(fullData).shouldBeTrue()
    }

    // ==================== Int Overflow Fix Tests ====================

    "maxZippedContentSize should accept Long values without Int overflow" {
      // Before the fix, maxZippedContentSize was Int. A config value of 2048 MB
      // computed as 2048 * 1024 * 1024 would overflow Int (2,147,483,648 > Int.MAX_VALUE),
      // producing a negative number. This caused the size check to reject all chunks
      // since any positive totalByteCount > negative maxZippedContentSize.
      val largeLimitMBytes = 2048
      val largeLimit = largeLimitMBytes * 1024L * 1024L // 2,147,483,648 — overflows Int

      val response = createHeaderResponse()
      val context = ChunkedContext(response, largeLimit)

      val data = "test data".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      // With the Int overflow bug, this would throw ChunkValidationException because
      // maxZippedContentSize would be negative (-2,147,483,648)
      context.applyChunk(data, data.size, 1, checksum)
      context.totalByteCount shouldBe data.size
    }

    "maxZippedContentSize should work with values larger than Int.MAX_VALUE" {
      val limitMBytes = 4096L
      val limit = limitMBytes * 1024L * 1024L // 4,294,967,296

      val response = createHeaderResponse()
      val context = ChunkedContext(response, limit)

      val data = "metrics content".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      context.applyChunk(data, data.size, 1, checksum)
      context.totalChunkCount shouldBe 1
    }

    // ==================== Edge Case Tests ====================

    "applySummary should throw when called before any chunks" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      // No chunks applied — totalChunkCount is 0, summaryChunkCount is 1
      shouldThrow<ChunkValidationException> {
        context.applySummary(1, 10, 12345L)
      }
    }

    "applySummary should succeed with zero chunks when summary expects zero" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      // Both sides agree: 0 chunks, 0 bytes, initial CRC32 value
      val emptyCrc = CRC32().value
      val scrapeResults = context.applySummary(0, 0, emptyCrc)

      scrapeResults.srContentAsZipped.size shouldBe 0
      context.totalChunkCount shouldBe 0
      context.totalByteCount shouldBe 0
    }

    "applyChunk should handle zero-length data" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val emptyData = ByteArray(0)
      val crc = CRC32().apply { update(emptyData, 0, 0) }
      context.applyChunk(emptyData, 0, 1, crc.value)

      context.totalChunkCount shouldBe 1
      context.totalByteCount shouldBe 0
    }

    // Bug #10: applySummary hardcoded srZipped=true. Now it reads headerZipped
    // from the header, so non-zipped chunked content is correctly represented.
    "Bug #10: applySummary should propagate headerZipped=true from header" {
      val response = createHeaderResponse(zipped = true)
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)
      scrapeResults.srZipped.shouldBeTrue()
    }

    "Bug #10: applySummary should propagate headerZipped=false from header" {
      val response = createHeaderResponse(zipped = false)
      val context = ChunkedContext(response, 1000000)

      val crc = CRC32()
      val data = "test".toByteArray()
      crc.update(data)
      context.applyChunk(data, data.size, 1, crc.value)

      val scrapeResults = context.applySummary(1, data.size, crc.value)
      scrapeResults.srZipped.shouldBeFalse()
    }

    // ==================== Bug #15: totalByteCount Int Overflow Fix ====================

    // Bug #15: totalByteCount was Int, so accumulated bytes exceeding Int.MAX_VALUE (~2.1 GB)
    // would wrap to negative, bypassing the maxZippedContentSize check. The fix changes
    // totalByteCount to Long.

    "Bug #15: totalByteCount should not overflow with large accumulated chunks" {
      val response = createHeaderResponse()
      // Set a limit above Int.MAX_VALUE
      val limit = Int.MAX_VALUE.toLong() + 1000L
      val context = ChunkedContext(response, limit)

      val crc = CRC32()

      // Simulate accumulating just under Int.MAX_VALUE in the first chunk
      // We can't actually allocate 2GB, but we can verify the counter is Long
      // by checking the type behavior with moderate values
      val data1 = ByteArray(1000) { 0x41 }
      crc.update(data1)
      context.applyChunk(data1, data1.size, 1, crc.value)

      val data2 = ByteArray(500) { 0x42 }
      crc.update(data2)
      context.applyChunk(data2, data2.size, 2, crc.value)

      // Verify totalByteCount is correctly accumulated as Long
      context.totalByteCount shouldBe 1500L
    }

    "Bug #15: totalByteCount as Long should reject content exceeding maxZippedContentSize" {
      val response = createHeaderResponse()
      // Use a small limit to test the overflow-safe comparison
      val limit = 100L
      val context = ChunkedContext(response, limit)

      val data = ByteArray(101) { 0x41 }
      val checksum = CRC32().apply { update(data) }.value

      shouldThrow<ChunkValidationException> {
        context.applyChunk(data, data.size, 1, checksum)
      }
    }

    "Bug #15: totalByteCount should correctly compare against Long maxZippedContentSize" {
      // Verify that the comparison totalByteCount > maxZippedContentSize works
      // correctly when maxZippedContentSize exceeds Int.MAX_VALUE
      val response = createHeaderResponse()
      val limit = 3_000_000_000L // ~3 GB, larger than Int.MAX_VALUE
      val context = ChunkedContext(response, limit)

      // Small chunk should pass the size check
      val data = "test data".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      context.applyChunk(data, data.size, 1, checksum)
      context.totalByteCount shouldBe data.size.toLong()
    }

    "applyChunk should throw ChunkValidationException when chunkByteCount exceeds data size" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "short".toByteArray()
      // chunkByteCount (1000) > data.size (5) -- now caught by the validation guard
      val ex = shouldThrow<ChunkValidationException> {
        context.applyChunk(data, 1000, 1, 0)
      }
      ex.message shouldContain "chunkByteCount (1000) is invalid for data size (5)"
    }

    "applyChunk should throw ChunkValidationException when chunkByteCount is negative" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "test data".toByteArray()
      val ex = shouldThrow<ChunkValidationException> {
        context.applyChunk(data, -1, 1, 0)
      }
      ex.message shouldContain "chunkByteCount (-1) is invalid for data size"
    }

    "applyChunk should succeed when chunkByteCount equals data size" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val data = "exact match".toByteArray()
      val checksum = CRC32().apply { update(data) }.value

      context.applyChunk(data, data.size, 1, checksum)
      context.totalByteCount shouldBe data.size
    }

    "applyChunk should succeed when chunkByteCount is less than data size" {
      val response = createHeaderResponse()
      val context = ChunkedContext(response, 1000000)

      val buffer = ByteArray(1024)
      val realData = "partial".toByteArray()
      realData.copyInto(buffer)

      val checksum = CRC32().apply { update(buffer, 0, realData.size) }.value

      context.applyChunk(buffer, realData.size, 1, checksum)
      context.totalByteCount shouldBe realData.size
    }
  }
}
