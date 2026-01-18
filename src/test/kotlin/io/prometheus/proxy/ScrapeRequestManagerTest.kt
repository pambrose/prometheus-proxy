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

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ScrapeRequestManagerTest {
  private fun createMockWrapper(scrapeId: Long): ScrapeRequestWrapper {
    val mockAgentContext = mockk<AgentContext>(relaxed = true)
    val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
    every { wrapper.scrapeId } returns scrapeId
    every { wrapper.agentContext } returns mockAgentContext
    every { wrapper.scrapeResults = any() } answers { nothing }
    return wrapper
  }

  @Test
  fun `scrapeMapSize should return zero for empty manager`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()

      manager.scrapeMapSize shouldBe 0
    }

  @Test
  fun `addToScrapeRequestMap should add request and return null for new scrapeId`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      val result = manager.addToScrapeRequestMap(wrapper)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 1
    }

  @Test
  fun `addToScrapeRequestMap should return old wrapper when replacing`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper1)
      val result = manager.addToScrapeRequestMap(wrapper2)

      result.shouldNotBeNull()
      result shouldBe wrapper1
      manager.scrapeMapSize shouldBe 1
    }

  @Test
  fun `addToScrapeRequestMap should handle multiple different scrapeIds`(): Unit =
    runBlocking {
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

  @Test
  fun `assignScrapeResults should handle missing scrapeId gracefully`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)

      every { mockResults.srScrapeId } returns 999L

      // Should not throw exception
      manager.assignScrapeResults(mockResults)

      manager.scrapeMapSize shouldBe 0
    }

  @Test
  fun `removeFromScrapeRequestMap should remove and return wrapper`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      manager.addToScrapeRequestMap(wrapper)
      manager.scrapeMapSize shouldBe 1

      val result = manager.removeFromScrapeRequestMap(123L)

      result.shouldNotBeNull()
      result shouldBe wrapper
      manager.scrapeMapSize shouldBe 0
    }

  @Test
  fun `removeFromScrapeRequestMap should return null for non-existent scrapeId`(): Unit =
    runBlocking {
      val manager = ScrapeRequestManager()

      val result = manager.removeFromScrapeRequestMap(999L)

      result.shouldBeNull()
      manager.scrapeMapSize shouldBe 0
    }

  @Test
  fun `removeFromScrapeRequestMap should only remove specified scrapeId`(): Unit =
    runBlocking {
      ScrapeRequestManager().apply {
        val wrapper1 = createMockWrapper(123L)
        val wrapper2 = createMockWrapper(456L)
        val wrapper3 = createMockWrapper(789L)

        addToScrapeRequestMap(wrapper1)
        addToScrapeRequestMap(wrapper2)
        addToScrapeRequestMap(wrapper3)

        removeFromScrapeRequestMap(456L)

        scrapeMapSize shouldBe 2
        scrapeRequestMap[123L].shouldNotBeNull()
        scrapeRequestMap[456L].shouldBeNull()
        scrapeRequestMap[789L].shouldNotBeNull()
      }
    }

  @Test
  fun `scrapeMapSize should reflect add and remove operations`(): Unit =
    runBlocking {
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

  @Test
  fun `multiple assignScrapeResults should call markComplete for each`(): Unit =
    runBlocking {
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
}
