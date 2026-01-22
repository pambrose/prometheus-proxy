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
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.ScrapeRequestWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class AgentContextTest {
  @Test
  fun `constructor should generate unique agent IDs`(): Unit =
    runBlocking {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")
      val context3 = AgentContext("192.168.1.3")

      context1.agentId shouldNotBe context2.agentId
      context2.agentId shouldNotBe context3.agentId
      context1.agentId shouldNotBe context3.agentId
    }

  @Test
  fun `agentId should be numeric string`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      context.agentId.toLongOrNull().shouldNotBeNull()
    }

  @Test
  fun `new context should be valid by default`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      context.isValid().shouldBeTrue()
      context.isNotValid().shouldBeFalse()
    }

  @Test
  fun `assignProperties should set all properties from request`(): Unit =
    runBlocking {
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

  @Test
  fun `desc should return empty string for non-consolidated context`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        consolidated = false
      }
      context.assignProperties(request)

      context.desc shouldBe ""
    }

  @Test
  fun `desc should return 'consolidated ' for consolidated context`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      val request = registerAgentRequest {
        agentId = "test-id"
        consolidated = true
      }
      context.assignProperties(request)

      context.desc shouldBe "consolidated "
    }

  @Test
  fun `scrapeRequestBacklogSize should start at zero`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      context.scrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `writeScrapeRequest should increment backlog size`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)

      context.scrapeRequestBacklogSize shouldBe 1
    }

  @Test
  fun `writeScrapeRequest multiple times should increment backlog`(): Unit =
    runBlocking {
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

  @Test
  fun `readScrapeRequest should return written request`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      val result = context.readScrapeRequest()

      result.shouldNotBeNull()
      result shouldBe mockRequest
    }

  @Test
  fun `readScrapeRequest should decrement backlog size`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      context.scrapeRequestBacklogSize shouldBe 1

      context.readScrapeRequest()
      context.scrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `readScrapeRequest should return null when no requests queued`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      context.invalidate()
      val result = context.readScrapeRequest()

      result.shouldBeNull()
    }

  // Verifies that the scrape request channel maintains FIFO (First-In-First-Out) ordering.
  // This is critical for correct scrape behavior: Prometheus expects responses in the same
  // order as requests. The underlying Channel implementation guarantees this ordering,
  // but this test ensures the AgentContext wrapper doesn't break that guarantee.
  @Test
  fun `readScrapeRequest should return requests in FIFO order`(): Unit =
    runBlocking {
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

  @Test
  fun `invalidate should set valid to false`(): Unit =
    runBlocking {
      AgentContext("192.168.1.1").apply {
        isValid().shouldBeTrue()

        invalidate()

        isValid().shouldBeFalse()
        isNotValid().shouldBeTrue()
      }
    }

  // Tests the graceful shutdown behavior of an agent context.
  // When invalidate() is called, the context is marked invalid but existing queued
  // requests can still be read (allowing in-flight operations to complete).
  // This prevents data loss during agent disconnection while ensuring no new
  // requests are accepted after invalidation.
  @Test
  fun `invalidate should close scrape request channel`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")
      val mockRequest = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(mockRequest)
      context.invalidate()

      // After invalidation, reading should still work for existing items
      val result = context.readScrapeRequest()
      result.shouldNotBeNull()

      // But isValid should be false
      context.isValid().shouldBeFalse()
    }

  @Test
  fun `markActivityTime should reset inactivity duration`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      context.markActivityTime(false)
      val duration2 = context.inactivityDuration

      // After marking activity, inactivity duration should be less than before
      duration2.shouldBeLessThan(duration1)
    }

  @Test
  fun `markActivityTime with isRequest true should reset inactivity duration`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      context.markActivityTime(true)
      val duration2 = context.inactivityDuration

      // After marking activity, inactivity duration should be less than before
      duration2.shouldBeLessThan(duration1)
    }

  @Test
  fun `inactivityDuration should increase over time`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      delay(50.milliseconds)
      val duration1 = context.inactivityDuration

      delay(50.milliseconds)
      val duration2 = context.inactivityDuration

      duration2 shouldBeGreaterThan duration1
    }

  @Test
  fun `toString should contain key properties`(): Unit =
    runBlocking {
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

  @Test
  fun `equals should return true for same instance`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      (context == context).shouldBeTrue()
    }

  @Test
  fun `equals should return false for different agent IDs`(): Unit =
    runBlocking {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      (context1 == context2).shouldBeFalse()
    }

  @Test
  fun `hashCode should be consistent with equals`(): Unit =
    runBlocking {
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      if (context1 == context2) {
        context1.hashCode() shouldBe context2.hashCode()
      } else {
        context1.hashCode() shouldNotBe context2.hashCode()
      }
    }

  @Test
  fun `hashCode should be based on agentId`(): Unit =
    runBlocking {
      val context = AgentContext("192.168.1.1")

      context.hashCode() shouldBe context.agentId.hashCode()
    }
}
