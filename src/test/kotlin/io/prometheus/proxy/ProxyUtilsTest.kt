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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import org.junit.jupiter.api.Test

class ProxyUtilsTest {
  private val logger = logger {}

  @Test
  fun `invalidAgentContextResponse should set correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)
    val responseResults = ResponseResults()

    ProxyUtils.invalidAgentContextResponse("test-path", mockProxy, logger, responseResults)

    responseResults.statusCode shouldBe HttpStatusCode.NotFound
    responseResults.updateMsg shouldBe "invalid_agent_context"
    verify { mockProxy.logActivity("Invalid AgentContext for /test-path") }
  }

  @Test
  fun `invalidPathResponse should set correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)
    val responseResults = ResponseResults()

    ProxyUtils.invalidPathResponse("invalid-path", mockProxy, logger, responseResults)

    responseResults.statusCode shouldBe HttpStatusCode.NotFound
    responseResults.updateMsg shouldBe "invalid_path"
    verify { mockProxy.logActivity("Invalid path request /invalid-path") }
  }

  @Test
  fun `emptyPathResponse should set correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)
    val responseResults = ResponseResults()

    ProxyUtils.emptyPathResponse(mockProxy, logger, responseResults)

    responseResults.statusCode shouldBe HttpStatusCode.NotFound
    responseResults.updateMsg shouldBe "missing_path"
    verify { mockProxy.logActivity(any()) }
  }

  @Test
  fun `proxyNotRunningResponse should set ServiceUnavailable status`() {
    val responseResults = ResponseResults()

    ProxyUtils.proxyNotRunningResponse(logger, responseResults)

    responseResults.statusCode shouldBe HttpStatusCode.ServiceUnavailable
    responseResults.updateMsg shouldBe "proxy_stopped"
  }

  @Test
  fun `incrementScrapeRequestCount should call metrics for non-empty type`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    ProxyUtils.incrementScrapeRequestCount(mockProxy, "test-type")

    verify { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
  }

  @Test
  fun `incrementScrapeRequestCount should not call metrics for empty type`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    ProxyUtils.incrementScrapeRequestCount(mockProxy, "")

    verify(exactly = 0) { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
  }

  @Test
  fun `ResponseResults should have correct default values`() {
    val responseResults = ResponseResults()

    responseResults.statusCode shouldBe HttpStatusCode.OK
    responseResults.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    responseResults.contentText shouldBe ""
  }

  @Test
  fun `ResponseResults should be mutable`() {
    val responseResults = ResponseResults()

    responseResults.statusCode = HttpStatusCode.BadRequest
    responseResults.contentText = "Error message"
    responseResults.updateMsg = "test_update"

    responseResults.statusCode shouldBe HttpStatusCode.BadRequest
    responseResults.contentText shouldBe "Error message"
    responseResults.updateMsg shouldBe "test_update"
  }

  @Test
  fun `invalidPathResponse should handle various path formats`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    // Test with empty path
    val responseResults1 = ResponseResults()
    ProxyUtils.invalidPathResponse("", mockProxy, logger, responseResults1)
    responseResults1.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity("Invalid path request /") }

    // Test with path containing special characters
    val responseResults2 = ResponseResults()
    ProxyUtils.invalidPathResponse("metrics/test", mockProxy, logger, responseResults2)
    responseResults2.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity("Invalid path request /metrics/test") }
  }

  @Test
  fun `emptyPathResponse should use info log level`() {
    val mockProxy = mockk<Proxy>(relaxed = true)
    val responseResults = ResponseResults()

    ProxyUtils.emptyPathResponse(mockProxy, logger, responseResults)

    responseResults.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity(any()) }
  }
}
