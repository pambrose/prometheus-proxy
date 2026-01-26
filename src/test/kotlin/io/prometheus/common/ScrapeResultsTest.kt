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

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.ktor.http.HttpStatusCode
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.ScrapeResults.Companion.toScrapeResults
import io.prometheus.grpc.scrapeResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ScrapeResultsTest {
  // ==================== Constructor Tests ====================

  @Test
  fun `constructor should set required fields`() {
    val results = ScrapeResults(
      srAgentId = "test-agent",
      srScrapeId = 12345L,
    )

    results.srAgentId shouldBe "test-agent"
    results.srScrapeId shouldBe 12345L
  }

  @Test
  fun `constructor should use default values`() {
    val results = ScrapeResults(
      srAgentId = "test-agent",
      srScrapeId = 12345L,
    )

    results.srValidResponse.shouldBeFalse()
    results.srStatusCode shouldBe HttpStatusCode.NotFound.value
    results.srContentType.shouldBeEmpty()
    results.srZipped.shouldBeFalse()
    results.srContentAsText.shouldBeEmpty()
    results.srContentAsZipped.isEmpty().shouldBeTrue()
    results.srFailureReason.shouldBeEmpty()
    results.srUrl.shouldBeEmpty()
  }

  @Test
  fun `constructor should accept all parameters`() {
    val zippedContent = "compressed data".toByteArray()
    val results = ScrapeResults(
      srAgentId = "agent-123",
      srScrapeId = 999L,
      srValidResponse = true,
      srStatusCode = 200,
      srContentType = "text/plain",
      srZipped = true,
      srContentAsText = "",
      srContentAsZipped = zippedContent,
      srFailureReason = "",
      srUrl = "http://localhost:8080/metrics",
    )

    results.srValidResponse.shouldBeTrue()
    results.srStatusCode shouldBe 200
    results.srContentType shouldBe "text/plain"
    results.srZipped.shouldBeTrue()
    results.srContentAsZipped shouldBe zippedContent
    results.srUrl shouldBe "http://localhost:8080/metrics"
  }

  // ==================== setDebugInfo Tests ====================

  @Test
  fun `setDebugInfo should set url and failure reason`() {
    val results = ScrapeResults("agent", 123L)

    results.setDebugInfo("http://test.com/metrics", "Connection refused")

    results.srUrl shouldBe "http://test.com/metrics"
    results.srFailureReason shouldBe "Connection refused"
  }

  @Test
  fun `setDebugInfo should use empty failure reason by default`() {
    val results = ScrapeResults("agent", 123L)

    results.setDebugInfo("http://test.com/metrics")

    results.srUrl shouldBe "http://test.com/metrics"
    results.srFailureReason.shouldBeEmpty()
  }

  // ==================== toScrapeResponse Tests ====================

  @Test
  fun `toScrapeResponse should create response with text content`() {
    val results = ScrapeResults(
      srAgentId = "agent-1",
      srScrapeId = 100L,
      srValidResponse = true,
      srStatusCode = 200,
      srContentType = "text/plain",
      srZipped = false,
      srContentAsText = "metrics content here",
    )

    val response = results.toScrapeResponse()

    response.agentId shouldBe "agent-1"
    response.scrapeId shouldBe 100L
    response.validResponse.shouldBeTrue()
    response.statusCode shouldBe 200
    response.contentType shouldBe "text/plain"
    response.zipped.shouldBeFalse()
    response.contentAsText shouldBe "metrics content here"
  }

  @Test
  fun `toScrapeResponse should create response with zipped content`() {
    val zippedContent = byteArrayOf(1, 2, 3, 4, 5)
    val results = ScrapeResults(
      srAgentId = "agent-2",
      srScrapeId = 200L,
      srValidResponse = true,
      srStatusCode = 200,
      srZipped = true,
      srContentAsZipped = zippedContent,
    )

    val response = results.toScrapeResponse()

    response.zipped.shouldBeTrue()
    response.contentAsZipped.toByteArray() shouldBe zippedContent
  }

  @Test
  fun `toScrapeResponse should include failure reason`() {
    val results = ScrapeResults(
      srAgentId = "agent-3",
      srScrapeId = 300L,
      srValidResponse = false,
      srFailureReason = "Connection timeout",
    )

    val response = results.toScrapeResponse()

    response.validResponse.shouldBeFalse()
    response.failureReason shouldBe "Connection timeout"
  }

  // ==================== toScrapeResponseHeader Tests ====================

  @Test
  fun `toScrapeResponseHeader should create chunked response header`() {
    val results = ScrapeResults(
      srAgentId = "agent-chunk",
      srScrapeId = 400L,
      srValidResponse = true,
      srStatusCode = 200,
      srContentType = "text/plain",
      srUrl = "http://localhost/metrics",
    )

    val response = results.toScrapeResponseHeader()

    response.header.headerAgentId shouldBe "agent-chunk"
    response.header.headerScrapeId shouldBe 400L
    response.header.headerValidResponse.shouldBeTrue()
    response.header.headerStatusCode shouldBe 200
    response.header.headerContentType shouldBe "text/plain"
    response.header.headerUrl shouldBe "http://localhost/metrics"
  }

  @Test
  fun `toScrapeResponseHeader should include failure reason`() {
    val results = ScrapeResults(
      srAgentId = "agent-fail",
      srScrapeId = 500L,
      srValidResponse = false,
      srFailureReason = "Service unavailable",
    )

    val response = results.toScrapeResponseHeader()

    response.header.headerValidResponse.shouldBeFalse()
    response.header.headerFailureReason shouldBe "Service unavailable"
  }

  // ==================== toScrapeResults Extension Tests ====================

  @Test
  fun `toScrapeResults should convert ScrapeResponse to ScrapeResults`() {
    val response = scrapeResponse {
      agentId = "converted-agent"
      scrapeId = 600L
      validResponse = true
      statusCode = 200
      contentType = "application/json"
      zipped = false
      contentAsText = """{"metrics": "data"}"""
      failureReason = ""
      url = "http://test/api"
    }

    val results = response.toScrapeResults()

    results.srAgentId shouldBe "converted-agent"
    results.srScrapeId shouldBe 600L
    results.srValidResponse.shouldBeTrue()
    results.srStatusCode shouldBe 200
    results.srContentType shouldBe "application/json"
    results.srZipped.shouldBeFalse()
    results.srContentAsText shouldBe """{"metrics": "data"}"""
    results.srUrl shouldBe "http://test/api"
  }

  @Test
  fun `toScrapeResults should handle zipped content`() {
    val zippedData = byteArrayOf(10, 20, 30)
    val response = scrapeResponse {
      agentId = "zipped-agent"
      scrapeId = 700L
      zipped = true
      contentAsZipped = com.google.protobuf.ByteString.copyFrom(zippedData)
    }

    val results = response.toScrapeResults()

    results.srZipped.shouldBeTrue()
    results.srContentAsZipped shouldBe zippedData
  }

  // ==================== errorCode Tests ====================

  @Test
  fun `errorCode should return RequestTimeout for TimeoutCancellationException`() {
    // TimeoutCancellationException constructor is internal, so we generate it via withTimeout
    val exception = runBlocking {
      try {
        withTimeout(1) {
          // Use delay instead of Thread.sleep to properly suspend and timeout
          delay(1000)
        }
        null
      } catch (e: Exception) {
        e
      }
    }!!
    val code = errorCode(exception, "http://test.com")

    code shouldBe HttpStatusCode.RequestTimeout.value
  }

  @Test
  fun `errorCode should return RequestTimeout for SocketTimeoutException`() {
    val exception = SocketTimeoutException("socket timeout")
    val code = errorCode(exception, "http://test.com")

    code shouldBe HttpStatusCode.RequestTimeout.value
  }

  @Test
  fun `errorCode should return NotFound for IOException`() {
    val exception = IOException("connection refused")
    val code = errorCode(exception, "http://test.com")

    code shouldBe HttpStatusCode.NotFound.value
  }

  @Test
  fun `errorCode should return ServiceUnavailable for other exceptions`() {
    val exception = RuntimeException("unexpected error")
    val code = errorCode(exception, "http://test.com")

    code shouldBe HttpStatusCode.ServiceUnavailable.value
  }

  // ==================== scrapeCounterMsg Tests ====================

  @Test
  fun `scrapeCounterMsg should be mutable`() {
    val results = ScrapeResults("agent", 123L)

    results.scrapeCounterMsg.store("new message")

    results.scrapeCounterMsg.load() shouldBe "new message"
  }

  @Test
  fun `scrapeCounterMsg should default to empty string`() {
    val results = ScrapeResults("agent", 123L)

    results.scrapeCounterMsg.load().shouldBeEmpty()
  }
}
