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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.common.ScrapeResults

class ScrapeRequestManagerTest : FunSpec() {
  private fun createMockWrapper(scrapeId: Long): ScrapeRequestWrapper {
    val mockAgentContext = mockk<AgentContext>(relaxed = true)
    val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
    every { wrapper.scrapeId } returns scrapeId
    every { wrapper.agentContext } returns mockAgentContext
    every { wrapper.scrapeResults = any() } answers { nothing }
    return wrapper
  }

  init {
    test("scrapeMapSize should return zero for empty manager") {
      val manager = ScrapeRequestManager()

      manager.scrapeMapSize shouldBe 0
    }

    test("addToScrapeRequestMap should add request and return null for new scrapeId") {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      val result = manager.addToScrapeRequestMap(wrapper)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 1
    }

    test("addToScrapeRequestMap should return old wrapper when replacing") {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper1)
      val result = manager.addToScrapeRequestMap(wrapper2)

      result.shouldNotBeNull()
      result shouldBe wrapper1
      manager.scrapeMapSize shouldBe 1
    }

    test("addToScrapeRequestMap should handle multiple different scrapeIds") {
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

    test("assignScrapeResults should handle missing scrapeId gracefully") {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)

      every { mockResults.srScrapeId } returns 999L

      // Should not throw exception
      manager.assignScrapeResults(mockResults)

      manager.scrapeMapSize shouldBe 0
    }

    test("removeFromScrapeRequestMap should remove and return wrapper") {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper)
      manager.scrapeMapSize shouldBe 1

      val result = manager.removeFromScrapeRequestMap(123L)

      result.shouldNotBeNull()
      result shouldBe wrapper
      manager.scrapeMapSize shouldBe 0
    }

    test("removeFromScrapeRequestMap should return null for non-existent scrapeId") {
      val manager = ScrapeRequestManager()

      val result = manager.removeFromScrapeRequestMap(999L)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 0
    }

    test("removeFromScrapeRequestMap should only remove specified scrapeId") {
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

    test("scrapeMapSize should reflect add and remove operations") {
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
    test("multiple assignScrapeResults should call markComplete for each") {
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

    test("assignScrapeResults should set scrapeResults on wrapper") {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(100L)
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 100L

      manager.addToScrapeRequestMap(wrapper)
      manager.assignScrapeResults(results)

      verify { wrapper.scrapeResults = results }
    }

    test("assignScrapeResults should call markActivityTime on agent context") {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(200L)
      val agentContext = wrapper.agentContext
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 200L

      manager.addToScrapeRequestMap(wrapper)
      manager.assignScrapeResults(results)

      verify { agentContext.markActivityTime(true) }
    }
  }
}
