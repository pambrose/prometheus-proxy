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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.network.sockets.SocketTimeoutException
import io.prometheus.common.ScrapeResults.Companion.errorCode
import io.prometheus.common.ScrapeResults.Companion.toScrapeResults
import io.prometheus.grpc.scrapeResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.http.HttpConnectTimeoutException

class ScrapeResultsTest : StringSpec() {
  init {
    // ==================== Constructor Tests ====================

    "constructor should set required fields" {
      val results = ScrapeResults(
        srAgentId = "test-agent",
        srScrapeId = 12345L,
      )

      results.srAgentId shouldBe "test-agent"
      results.srScrapeId shouldBe 12345L
    }

    "constructor should use default values" {
      val results = ScrapeResults(
        srAgentId = "test-agent",
        srScrapeId = 12345L,
      )

      results.srValidResponse.shouldBeFalse()
      results.srStatusCode shouldBe HttpStatusCode.ServiceUnavailable.value
      results.srContentType.shouldBeEmpty()
      results.srZipped.shouldBeFalse()
      results.srContentAsText.shouldBeEmpty()
      results.srContentAsZipped.isEmpty().shouldBeTrue()
      results.srFailureReason.shouldBeEmpty()
      results.srUrl.shouldBeEmpty()
    }

    "constructor should accept all parameters" {
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

    // ==================== Debug Info Constructor Tests ====================

    "constructor should accept url and failure reason" {
      val results = ScrapeResults(
        srAgentId = "agent",
        srScrapeId = 123L,
        srUrl = "http://test.com/metrics",
        srFailureReason = "Connection refused",
      )

      results.srUrl shouldBe "http://test.com/metrics"
      results.srFailureReason shouldBe "Connection refused"
    }

    "constructor should default to empty url and failure reason" {
      val results = ScrapeResults("agent", 123L)

      results.srUrl.shouldBeEmpty()
      results.srFailureReason.shouldBeEmpty()
    }

    // ==================== toScrapeResponse Tests ====================

    "toScrapeResponse should create response with text content" {
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

    "toScrapeResponse should create response with zipped content" {
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

    "toScrapeResponse should include failure reason" {
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

    "toScrapeResponseHeader should create chunked response header" {
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

    "toScrapeResponseHeader should include failure reason" {
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

    "toScrapeResults should convert ScrapeResponse to ScrapeResults" {
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

    "toScrapeResults should handle zipped content" {
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

    "errorCode should return RequestTimeout for TimeoutCancellationException" {
      // TimeoutCancellationException constructor is internal, so we generate it via withTimeout
      val exception = try {
        withTimeout(1) {
          // Use delay instead of Thread.sleep to properly suspend and timeout
          delay(1000)
        }
        null
      } catch (e: Exception) {
        e
      }!!
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.RequestTimeout.value
    }

    "errorCode should return RequestTimeout for SocketTimeoutException" {
      val exception = SocketTimeoutException("socket timeout")
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.RequestTimeout.value
    }

    "errorCode should return ServiceUnavailable for IOException" {
      val exception = IOException("connection refused")
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.ServiceUnavailable.value
    }

    "errorCode should return ServiceUnavailable for IOException subclasses" {
      val connectException = java.net.ConnectException("Connection refused")
      errorCode(connectException, "http://test.com") shouldBe HttpStatusCode.ServiceUnavailable.value

      val unknownHostException = java.net.UnknownHostException("unknown-host.local")
      errorCode(unknownHostException, "http://test.com") shouldBe HttpStatusCode.ServiceUnavailable.value

      val noRouteException = java.net.NoRouteToHostException("No route to host")
      errorCode(noRouteException, "http://test.com") shouldBe HttpStatusCode.ServiceUnavailable.value
    }

    "errorCode should return RequestTimeout for HttpConnectTimeoutException" {
      val exception = HttpConnectTimeoutException("connect timeout")
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.RequestTimeout.value
    }

    "errorCode should return RequestTimeout for HttpRequestTimeoutException" {
      val exception = HttpRequestTimeoutException("http://test.com", 5000L)
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.RequestTimeout.value
    }

    "errorCode should return ServiceUnavailable for other exceptions" {
      val exception = RuntimeException("unexpected error")
      val code = errorCode(exception, "http://test.com")

      code shouldBe HttpStatusCode.ServiceUnavailable.value
    }

    // ==================== Round-Trip Tests ====================

    "toScrapeResponse and toScrapeResults should round-trip text content" {
      val original = ScrapeResults(
        srAgentId = "round-trip-agent",
        srScrapeId = 800L,
        srValidResponse = true,
        srStatusCode = 200,
        srContentType = "text/plain",
        srZipped = false,
        srContentAsText = "metric_name 42.0",
        srFailureReason = "",
        srUrl = "http://localhost:9090/metrics",
      )

      val roundTripped = original.toScrapeResponse().toScrapeResults()

      roundTripped.srAgentId shouldBe original.srAgentId
      roundTripped.srScrapeId shouldBe original.srScrapeId
      roundTripped.srValidResponse shouldBe original.srValidResponse
      roundTripped.srStatusCode shouldBe original.srStatusCode
      roundTripped.srContentType shouldBe original.srContentType
      roundTripped.srZipped shouldBe original.srZipped
      roundTripped.srContentAsText shouldBe original.srContentAsText
      roundTripped.srFailureReason shouldBe original.srFailureReason
      roundTripped.srUrl shouldBe original.srUrl
    }

    "toScrapeResponse should propagate default failure values" {
      val results = ScrapeResults(
        srAgentId = "default-agent",
        srScrapeId = 900L,
      )

      val response = results.toScrapeResponse()

      response.validResponse.shouldBeFalse()
      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable.value
      response.contentType.shouldBeEmpty()
      response.zipped.shouldBeFalse()
      response.contentAsText.shouldBeEmpty()
      response.failureReason.shouldBeEmpty()
      response.url.shouldBeEmpty()
    }

    // ==================== scrapeCounterMsg Tests ====================

    "scrapeCounterMsg should accept value via constructor" {
      val results = ScrapeResults("agent", 123L, scrapeCounterMsg = "new message")

      results.scrapeCounterMsg shouldBe "new message"
    }

    "scrapeCounterMsg should default to empty string" {
      val results = ScrapeResults("agent", 123L)

      results.scrapeCounterMsg.shouldBeEmpty()
    }
  }
}
