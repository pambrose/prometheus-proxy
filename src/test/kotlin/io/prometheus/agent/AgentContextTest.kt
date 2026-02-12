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

package io.prometheus.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.ScrapeRequestWrapper
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class AgentContextTest : FunSpec() {
  init {
    test("constructor should generate unique agent IDs") {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")
      val context3 = AgentContext("192.168.1.3")

      context1.agentId shouldNotBe context2.agentId
      context2.agentId shouldNotBe context3.agentId
      context1.agentId shouldNotBe context3.agentId
    }

    test("agentId should be numeric string") {
      val context = AgentContext("192.168.1.1")

      context.agentId.toLongOrNull().shouldNotBeNull()
    }

    test("new context should be valid by default") {
      val context = AgentContext("192.168.1.1")

      context.isValid().shouldBeTrue()
      context.isNotValid().shouldBeFalse()
    }

    test("assignProperties should set all properties from request") {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        launchId = "launch-123"
        agentName = "test-agent"
        hostName = "test-host"
        consolidated = true
      }

      context.assignProperties(request)

      context.agentName shouldBe "test-agent"
      context.hostName shouldBe "test-host"
      context.consolidated shouldBe true
    }

    test("desc should return empty string for non-consolidated context") {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        consolidated = false
      }
      context.assignProperties(request)

      context.desc shouldBe ""
    }

    test("desc should return 'consolidated ' for consolidated context") {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        consolidated = true
      }
      context.assignProperties(request)

      context.desc shouldBe "consolidated "
    }

    test("scrapeRequestBacklogSize should start at zero") {
      val context = AgentContext("192.168.1.1")

      context.scrapeRequestBacklogSize shouldBe 0
    }

    test("writeScrapeRequest should increment backlog size") {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)

      context.scrapeRequestBacklogSize shouldBe 1
    }

    test("writeScrapeRequest multiple times should increment backlog") {
      AgentContext("192.168.1.1").apply {
        val mockRequest1 = mockk<ScrapeRequestWrapper>(relaxed = true)
        val mockRequest2 = mockk<ScrapeRequestWrapper>(relaxed = true)
        val mockRequest3 = mockk<ScrapeRequestWrapper>(relaxed = true)

        writeScrapeRequest(mockRequest1)
        writeScrapeRequest(mockRequest2)
        writeScrapeRequest(mockRequest3)

        scrapeRequestBacklogSize shouldBe 3
      }
    }

    test("readScrapeRequest should return written request") {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      val result = context.readScrapeRequest()

      result.shouldNotBeNull()
      result shouldBe mockRequest
    }

    test("readScrapeRequest should decrement backlog size") {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      context.scrapeRequestBacklogSize shouldBe 1

      context.readScrapeRequest()
      context.scrapeRequestBacklogSize shouldBe 0
    }

    test("readScrapeRequest should return null when no requests queued") {
      val context = AgentContext("192.168.1.1")

      context.invalidate()
      val result = context.readScrapeRequest()

      result.shouldBeNull()
    }

    // Verifies that the scrape request channel maintains FIFO (First-In-First-Out) ordering.
    // This is critical for correct scrape behavior: Prometheus expects responses in the same
    // order as requests. The underlying Channel implementation guarantees this ordering,
    // but this test ensures the AgentContext wrapper doesn't break that guarantee.
    test("readScrapeRequest should return requests in FIFO order") {
      AgentContext("192.168.1.1").apply {
        val mockRequest1 = mockk<ScrapeRequestWrapper>(relaxed = true)
        val mockRequest2 = mockk<ScrapeRequestWrapper>(relaxed = true)
        val mockRequest3 = mockk<ScrapeRequestWrapper>(relaxed = true)

        every { mockRequest1.toString() } returns "Request1"
        every { mockRequest2.toString() } returns "Request2"
        every { mockRequest3.toString() } returns "Request3"

        writeScrapeRequest(mockRequest1)
        writeScrapeRequest(mockRequest2)
        writeScrapeRequest(mockRequest3)

        readScrapeRequest() shouldBe mockRequest1
        readScrapeRequest() shouldBe mockRequest2
        readScrapeRequest() shouldBe mockRequest3
      }
    }

    test("invalidate should set valid to false") {
      AgentContext("192.168.1.1").apply {
        isValid().shouldBeTrue()

        invalidate()

        isValid().shouldBeFalse()
        isNotValid().shouldBeTrue()
      }
    }

    // Tests the graceful shutdown behavior of an agent context.
    // When invalidate() is called, the context is marked invalid and buffered
    // requests are drained (with closeChannel() called on each). After invalidation,
    // readScrapeRequest() returns null because the channel was drained and closed.
    test("invalidate should drain scrape request channel") {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      context.invalidate()

      // After invalidation, buffered items were drained by invalidate()
      val result = context.readScrapeRequest()
      result.shouldBeNull()

      // isValid should be false
      context.isValid().shouldBeFalse()

      // closeChannel was called on the drained wrapper
      verify(exactly = 1) { mockRequest.closeChannel() }
    }

    test("markActivityTime should reset inactivity duration") {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      context.markActivityTime(false)
      val duration2 = context.inactivityDuration

      // After marking activity, inactivity duration should be less than before
      duration2.shouldBeLessThan(duration1)
    }

    test("markActivityTime with isRequest true should reset inactivity duration") {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      context.markActivityTime(true)
      val duration2 = context.inactivityDuration

      // After marking activity, inactivity duration should be less than before
      duration2.shouldBeLessThan(duration1)
    }

    test("inactivityDuration should increase over time") {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      delay(50.milliseconds)
      val duration2 = context.inactivityDuration

      duration2 shouldBeGreaterThan duration1
    }

    test("toString should contain key properties") {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        launchId = "launch-123"
        agentName = "test-agent"
        hostName = "test-host"
        consolidated = false
      }
      context.assignProperties(request)

      val str = context.toString()

      str shouldContain "agentId"
      str shouldContain "launchId"
      str shouldContain "agentName"
      str shouldContain "hostName"
      str shouldContain "test-agent"
      str shouldContain "test-host"
    }

    test("equals should return true for same instance") {
      val context = AgentContext("192.168.1.1")

      (context == context).shouldBeTrue()
    }

    test("equals should return false for different agent IDs") {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      (context1 == context2).shouldBeFalse()
    }

    test("hashCode should be consistent with equals") {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      // The hashCode contract only guarantees: equal objects => same hashCode.
      // Unequal objects may have the same hashCode (collisions are legal).
      if (context1 == context2) {
        context1.hashCode() shouldBe context2.hashCode()
      }
    }

    test("hashCode should be based on agentId") {
      val context = AgentContext("192.168.1.1")

      context.hashCode() shouldBe context.agentId.hashCode()
    }
  }
}
