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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.ScrapeResults

class ScrapeRequestManagerTest : StringSpec() {
  private fun createMockWrapper(scrapeId: Long): ScrapeRequestWrapper {
    val mockAgentContext = mockk<AgentContext>(relaxed = true)
    val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
    every { wrapper.scrapeId } returns scrapeId
    every { wrapper.agentContext } returns mockAgentContext
    every { wrapper.scrapeResults = any() } answers { nothing }
    return wrapper
  }

  init {
    "scrapeMapSize should return zero for empty manager" {
      val manager = ScrapeRequestManager()

      manager.scrapeMapSize shouldBe 0
    }

    "addToScrapeRequestMap should add request and return null for new scrapeId" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      val result = manager.addToScrapeRequestMap(wrapper)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 1
    }

    "addToScrapeRequestMap should return old wrapper when replacing" {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper1)
      val result = manager.addToScrapeRequestMap(wrapper2)

      result.shouldNotBeNull()
      result shouldBe wrapper1
      manager.scrapeMapSize shouldBe 1
    }

    "addToScrapeRequestMap should handle multiple different scrapeIds" {
      ScrapeRequestManager().apply {
        val wrapper1 = createMockWrapper(123L)
        val wrapper2 = createMockWrapper(456L)
        val wrapper3 = createMockWrapper(789L)

        addToScrapeRequestMap(wrapper1)
        addToScrapeRequestMap(wrapper2)
        addToScrapeRequestMap(wrapper3)

        scrapeMapSize shouldBe 3
      }
    }

    "assignScrapeResults should handle missing scrapeId gracefully" {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)

      every { mockResults.srScrapeId } returns 999L

      // Should not throw exception
      manager.assignScrapeResults(mockResults)

      manager.scrapeMapSize shouldBe 0
    }

    "removeFromScrapeRequestMap should remove and return wrapper" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper)
      manager.scrapeMapSize shouldBe 1

      val result = manager.removeFromScrapeRequestMap(123L)

      result.shouldNotBeNull()
      result shouldBe wrapper
      manager.scrapeMapSize shouldBe 0
    }

    "removeFromScrapeRequestMap should return null for non-existent scrapeId" {
      val manager = ScrapeRequestManager()

      val result = manager.removeFromScrapeRequestMap(999L)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 0
    }

    "removeFromScrapeRequestMap should only remove specified scrapeId" {
      ScrapeRequestManager().apply {
        val wrapper1 = createMockWrapper(123L)
        val wrapper2 = createMockWrapper(456L)
        val wrapper3 = createMockWrapper(789L)

        addToScrapeRequestMap(wrapper1)
        addToScrapeRequestMap(wrapper2)
        addToScrapeRequestMap(wrapper3)

        removeFromScrapeRequestMap(456L)

        scrapeMapSize shouldBe 2
        containsScrapeRequest(123L) shouldBe true
        containsScrapeRequest(456L) shouldBe false
        containsScrapeRequest(789L) shouldBe true
      }
    }

    "scrapeMapSize should reflect add and remove operations" {
      ScrapeRequestManager().apply {
        scrapeMapSize shouldBe 0

        addToScrapeRequestMap(createMockWrapper(1L))
        scrapeMapSize shouldBe 1

        addToScrapeRequestMap(createMockWrapper(2L))
        scrapeMapSize shouldBe 2

        addToScrapeRequestMap(createMockWrapper(3L))
        scrapeMapSize shouldBe 3

        removeFromScrapeRequestMap(2L)
        scrapeMapSize shouldBe 2

        removeFromScrapeRequestMap(1L)
        scrapeMapSize shouldBe 1

        removeFromScrapeRequestMap(3L)
        scrapeMapSize shouldBe 0
      }
    }

    // Tests the scrape request/response correlation mechanism. When Prometheus sends a
    // scrape request, the proxy tracks it by scrapeId. When the agent returns results,
    // assignScrapeResults matches the response to the original request and calls
    // markComplete() to signal the waiting HTTP handler. This test verifies that
    // multiple concurrent scrapes are correctly tracked and completed independently.
    "multiple assignScrapeResults should call markComplete for each" {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(456L)

      manager.addToScrapeRequestMap(wrapper1)
      manager.addToScrapeRequestMap(wrapper2)

      val results1 = mockk<ScrapeResults>(relaxed = true)
      val results2 = mockk<ScrapeResults>(relaxed = true)
      every { results1.srScrapeId } returns 123L
      every { results2.srScrapeId } returns 456L

      manager.assignScrapeResults(results1)
      manager.assignScrapeResults(results2)

      verify { wrapper1.markComplete() }
      verify { wrapper2.markComplete() }
    }

    // ==================== assignScrapeResults Verification Tests ====================

    "assignScrapeResults should set scrapeResults on wrapper" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(100L)
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 100L

      manager.addToScrapeRequestMap(wrapper)
      manager.assignScrapeResults(results)

      verify { wrapper.scrapeResults = results }
    }

    "assignScrapeResults should call markActivityTime on agent context" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(200L)
      val agentContext = wrapper.agentContext
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 200L

      manager.addToScrapeRequestMap(wrapper)
      manager.assignScrapeResults(results)

      verify { agentContext.markActivityTime(true) }
    }

    // ==================== failScrapeRequest Tests ====================

    // Bug #2: Chunk/summary validation failures left the HTTP handler waiting until timeout.
    // failScrapeRequest() notifies the waiting handler immediately with an error result.

    "failScrapeRequest should set error results and call markComplete" {
      val manager = ScrapeRequestManager()
      val resultsSlot = slot<ScrapeResults>()
      val wrapper = createMockWrapper(300L)
      every { wrapper.agentContext.agentId } returns "agent-99"
      every { wrapper.scrapeResults = capture(resultsSlot) } answers { nothing }

      manager.addToScrapeRequestMap(wrapper)
      manager.failScrapeRequest(300L, "Chunk checksum mismatch")

      verify { wrapper.markComplete() }
      verify { wrapper.agentContext.markActivityTime(true) }

      val captured = resultsSlot.captured
      captured.srScrapeId shouldBe 300L
      captured.srAgentId shouldBe "agent-99"
      captured.srStatusCode shouldBe HttpStatusCode.BadGateway.value
      captured.srFailureReason shouldContain "Chunk checksum mismatch"
      captured.srValidResponse shouldBe false
    }

    "failScrapeRequest should handle missing scrapeId gracefully" {
      val manager = ScrapeRequestManager()

      // Should not throw
      manager.failScrapeRequest(999L, "no such request")

      manager.scrapeMapSize shouldBe 0
    }
  }
}
