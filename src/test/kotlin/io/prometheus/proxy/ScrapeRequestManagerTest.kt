/*
 * Copyright © 2026 Paul Ambrose
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

import ch.qos.logback.classic.Level
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.ScrapeResults
import io.prometheus.common.captureLogs

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

    "tryAddToScrapeRequestMap should add a request for a new scrapeId" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE) shouldBe true

      manager.scrapeMapSize shouldBe 1
      manager.getScrapeRequest(123L) shouldBe wrapper
    }

    "tryAddToScrapeRequestMap should replace a request with the same scrapeId without taking another slot" {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(123L)

      manager.tryAddToScrapeRequestMap(wrapper1, maxInFlight = 2) shouldBe true
      manager.tryAddToScrapeRequestMap(wrapper2, maxInFlight = 2) shouldBe true

      manager.scrapeMapSize shouldBe 1
      manager.getScrapeRequest(123L) shouldBe wrapper2
      // The replacement gave back the slot it claimed, so one is still free.
      manager.tryAddToScrapeRequestMap(createMockWrapper(456L), maxInFlight = 2) shouldBe true
      manager.tryAddToScrapeRequestMap(createMockWrapper(789L), maxInFlight = 2) shouldBe false
    }

    "tryAddToScrapeRequestMap should handle multiple different scrapeIds" {
      ScrapeRequestManager().apply {
        val wrapper1 = createMockWrapper(123L)
        val wrapper2 = createMockWrapper(456L)
        val wrapper3 = createMockWrapper(789L)

        tryAddToScrapeRequestMap(wrapper1, Int.MAX_VALUE)
        tryAddToScrapeRequestMap(wrapper2, Int.MAX_VALUE)
        tryAddToScrapeRequestMap(wrapper3, Int.MAX_VALUE)

        scrapeMapSize shouldBe 3
      }
    }

    "assignScrapeResults should handle missing scrapeId gracefully" {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)

      every { mockResults.srScrapeId } returns 999L

      // Should not throw exception
      manager.assignScrapeResults(mockResults) { true }

      manager.scrapeMapSize shouldBe 0
    }

    "removeFromScrapeRequestMap should remove and return wrapper" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(123L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
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

        tryAddToScrapeRequestMap(wrapper1, Int.MAX_VALUE)
        tryAddToScrapeRequestMap(wrapper2, Int.MAX_VALUE)
        tryAddToScrapeRequestMap(wrapper3, Int.MAX_VALUE)

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

        tryAddToScrapeRequestMap(createMockWrapper(1L), Int.MAX_VALUE)
        scrapeMapSize shouldBe 1

        tryAddToScrapeRequestMap(createMockWrapper(2L), Int.MAX_VALUE)
        scrapeMapSize shouldBe 2

        tryAddToScrapeRequestMap(createMockWrapper(3L), Int.MAX_VALUE)
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
    "multiple assignScrapeResults should complete each wrapper" {
      val manager = ScrapeRequestManager()
      val wrapper1 = createMockWrapper(123L)
      val wrapper2 = createMockWrapper(456L)

      manager.tryAddToScrapeRequestMap(wrapper1, Int.MAX_VALUE)
      manager.tryAddToScrapeRequestMap(wrapper2, Int.MAX_VALUE)

      val results1 = mockk<ScrapeResults>(relaxed = true)
      val results2 = mockk<ScrapeResults>(relaxed = true)
      every { results1.srScrapeId } returns 123L
      every { results2.srScrapeId } returns 456L

      manager.assignScrapeResults(results1) { true }
      manager.assignScrapeResults(results2) { true }

      verify { wrapper1.complete(results1) }
      verify { wrapper2.complete(results2) }
    }

    // ==================== assignScrapeResults Verification Tests ====================

    "assignScrapeResults should complete the wrapper with the results" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(100L)
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 100L

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.assignScrapeResults(results) { true }

      // Result publication is now folded behind the completion CAS (finding 16).
      verify { wrapper.complete(results) }
    }

    "assignScrapeResults should call markActivityTime on agent context" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(200L)
      val agentContext = wrapper.agentContext
      val results = mockk<ScrapeResults>(relaxed = true)

      every { results.srScrapeId } returns 200L

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.assignScrapeResults(results) { true }

      verify { agentContext.markActivityTime(true) }
    }

    // ==================== failScrapeRequest Tests ====================

    // Bug #2: Chunk/summary validation failures left the HTTP handler waiting until timeout.
    // failScrapeRequest() notifies the waiting handler immediately with an error result.

    "failScrapeRequest should complete the wrapper with error results" {
      val manager = ScrapeRequestManager()
      val resultsSlot = slot<ScrapeResults>()
      val wrapper = createMockWrapper(300L)
      every { wrapper.agentContext.agentId } returns "agent-99"
      every { wrapper.complete(capture(resultsSlot), any()) } returns true

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.failScrapeRequest(300L, "Chunk checksum mismatch", ProxyFailure.INVALID_RESPONSE)

      verify { wrapper.complete(any(), ProxyFailure.INVALID_RESPONSE) }
      verify { wrapper.agentContext.markActivityTime(true) }

      val captured = resultsSlot.captured
      captured.srScrapeId shouldBe 300L
      captured.srAgentId shouldBe "agent-99"
      captured.srStatusCode shouldBe HttpStatusCode.BadGateway.value
      captured.srFailureReason shouldContain "Chunk checksum mismatch"
      captured.srValidResponse shouldBe false
    }

    // Each proxy-made failure carries its own status, so a disconnect or a shutdown reads as the proxy being unable
    // to serve the scrape (503), not as a bad gateway response from the target (502).
    "failScrapeRequest should give the failure the status of its kind" {
      for (failure in ProxyFailure.entries) {
        val manager = ScrapeRequestManager()
        val resultsSlot = slot<ScrapeResults>()
        val wrapper = createMockWrapper(301L)
        every { wrapper.complete(capture(resultsSlot), any()) } returns true

        manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
        manager.failScrapeRequest(301L, "reason", failure)

        withClue(failure) { resultsSlot.captured.srStatusCode shouldBe failure.statusCode.value }
      }
      ProxyFailure.AGENT_DISCONNECTED.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      ProxyFailure.PROXY_STOPPED.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      ProxyFailure.INVALID_RESPONSE.statusCode shouldBe HttpStatusCode.BadGateway
    }

    "failAllScrapeRequests should fail only the agent's requests, with the given failure" {
      val manager = ScrapeRequestManager()
      val mine = createMockWrapper(302L)
      val theirs = createMockWrapper(303L)
      every { mine.agentContext.agentId } returns "agent-a"
      every { theirs.agentContext.agentId } returns "agent-b"

      manager.tryAddToScrapeRequestMap(mine, Int.MAX_VALUE)
      manager.tryAddToScrapeRequestMap(theirs, Int.MAX_VALUE)
      manager.failAllScrapeRequests("agent-a", "Agent disconnected", ProxyFailure.AGENT_DISCONNECTED)

      verify { mine.complete(any(), ProxyFailure.AGENT_DISCONNECTED) }
      verify(exactly = 0) { theirs.complete(any(), any()) }
    }

    "failScrapeRequest should handle missing scrapeId gracefully" {
      val manager = ScrapeRequestManager()

      // Should not throw
      manager.failScrapeRequest(999L, "no such request", ProxyFailure.INVALID_RESPONSE)

      manager.scrapeMapSize shouldBe 0
    }

    "failScrapeRequest log message for a missing scrapeId should include it" {
      val manager = ScrapeRequestManager()

      val relevantLogs =
        captureLogs<ScrapeRequestManager> {
          manager.failScrapeRequest(998L, "no such request", ProxyFailure.INVALID_RESPONSE)
        }.filter { it.message.contains("missing ScrapeRequestWrapper") }
      relevantLogs shouldHaveSize 1
      relevantLogs[0].formattedMessage shouldContain "scrapeId: 998"
    }

    // ==================== Bug #15: Double-assignment protection Tests ====================

    // Bug #15: If assignScrapeResults is called twice with the same scrapeId (e.g., due
    // to gRPC retries or agent bugs), the second call would overwrite scrapeResults and
    // call markComplete() again. markComplete() invokes requestTimer?.observeDuration(),
    // recording a duplicate latency observation and skewing metrics. The fix uses an
    // AtomicBoolean in markComplete() so observeDuration() is only called once.

    "Bug #15: assignScrapeResults should complete each time wrapper is in map" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(400L)
      var completeCallCount = 0

      // Track complete() invocations
      every { wrapper.complete(any()) } answers {
        completeCallCount++
        true
      }

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)

      val results1 = mockk<ScrapeResults>(relaxed = true)
      every { results1.srScrapeId } returns 400L

      val results2 = mockk<ScrapeResults>(relaxed = true)
      every { results2.srScrapeId } returns 400L

      manager.assignScrapeResults(results1) { true }
      manager.assignScrapeResults(results2) { true }

      // Both calls go through the manager (since wrapper is still in the map). The real
      // ScrapeRequestWrapper.complete() CAS makes only the first publish, but the manager itself
      // calls complete() on every assignment.
      completeCallCount shouldBe 2
    }

    "Bug #15: double assignScrapeResults should not throw" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(500L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)

      val results1 = mockk<ScrapeResults>(relaxed = true)
      every { results1.srScrapeId } returns 500L

      val results2 = mockk<ScrapeResults>(relaxed = true)
      every { results2.srScrapeId } returns 500L

      // Neither call should throw
      manager.assignScrapeResults(results1) { true }
      manager.assignScrapeResults(results2) { true }

      // The manager calls complete() each time, but the real ScrapeRequestWrapper's CAS
      // ensures only the first call publishes.
      verify(atLeast = 1) { wrapper.complete(any()) }
    }

    "Bug #15: double failScrapeRequest should not throw" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(600L)
      every { wrapper.agentContext.agentId } returns "agent-42"

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)

      // Call fail twice for the same scrapeId
      manager.failScrapeRequest(600L, "first failure", ProxyFailure.AGENT_DISCONNECTED)
      manager.failScrapeRequest(600L, "second failure", ProxyFailure.AGENT_DISCONNECTED)

      // Both calls should succeed without exceptions; the real complete()
      // CAS ensures only the first publishes.
      verify(exactly = 2) { wrapper.complete(any(), ProxyFailure.AGENT_DISCONNECTED) }
    }

    // ==================== Bug #5: Log level for missing scrape request ====================

    // Bug #5: When a scrape request times out, the proxy removes its ScrapeRequestWrapper
    // from the map. If the agent's late response then arrives, assignScrapeResults() finds
    // no wrapper and previously logged at ERROR level. This is a normal, expected condition
    // during timeouts and should be logged at WARN, not ERROR.

    "Bug #5: assignScrapeResults should log at WARN level for missing scrapeId" {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)
      every { mockResults.srScrapeId } returns 999L

      val relevantLogs =
        captureLogs<ScrapeRequestManager> { manager.assignScrapeResults(mockResults) { true } }
          .filter { it.message.contains("Missing ScrapeRequestWrapper") }
      relevantLogs shouldHaveSize 1
      relevantLogs[0].level shouldBe Level.WARN
    }

    "Bug #5: assignScrapeResults should not log at ERROR level for missing scrapeId" {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)
      every { mockResults.srScrapeId } returns 888L

      val errorLogs =
        captureLogs<ScrapeRequestManager> { manager.assignScrapeResults(mockResults) { true } }
          .filter { it.level == Level.ERROR && it.message.contains("Missing ScrapeRequestWrapper") }
      errorLogs shouldHaveSize 0
    }

    "Bug #5: assignScrapeResults log message should include scrapeId" {
      val manager = ScrapeRequestManager()
      val mockResults = mockk<ScrapeResults>(relaxed = true)
      every { mockResults.srScrapeId } returns 777L

      val relevantLogs =
        captureLogs<ScrapeRequestManager> { manager.assignScrapeResults(mockResults) { true } }
          .filter { it.message.contains("Missing ScrapeRequestWrapper") }
      relevantLogs shouldHaveSize 1
      relevantLogs[0].formattedMessage shouldContain "scrapeId: 777"
    }

    // ==================== getScrapeRequest ====================

    "getScrapeRequest should return the tracked request for a scrapeId" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(1000L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)

      manager.getScrapeRequest(1000L) shouldBeSameInstanceAs wrapper
    }

    "getScrapeRequest should return null for a scrape that is not tracked" {
      ScrapeRequestManager().getScrapeRequest(1001L).shouldBeNull()
    }

    // ==================== ownership checked on the wrapper completed ====================

    // The response RPCs pass the connection's owner check, which assignScrapeResults applies to the wrapper it is about
    // to complete, so no other lookup sits between the check and the completion.

    "assignScrapeResults should give the owner check the wrapper it completes" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(1100L)
      val results = mockk<ScrapeResults>(relaxed = true)
      every { results.srScrapeId } returns 1100L
      val checked = mutableListOf<ScrapeRequestWrapper>()

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.assignScrapeResults(results) {
        checked += it
        true
      }

      checked.single() shouldBeSameInstanceAs wrapper
      verify { wrapper.complete(results) }
    }

    "assignScrapeResults should not complete a wrapper the owner check rejects" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(1101L)
      val agentContext = wrapper.agentContext
      val results = mockk<ScrapeResults>(relaxed = true)
      every { results.srScrapeId } returns 1101L

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.assignScrapeResults(results) { false }

      verify(exactly = 0) { wrapper.complete(any(), any()) }
      verify(exactly = 0) { agentContext.markActivityTime(any()) }
      manager.containsScrapeRequest(1101L) shouldBe true
    }

    // An untracked scrapeId has no owner, so its result is dropped without asking.
    "assignScrapeResults should drop a result for an untracked scrapeId without running the owner check" {
      val manager = ScrapeRequestManager()
      val results = mockk<ScrapeResults>(relaxed = true)
      every { results.srScrapeId } returns 1102L
      var asked = false

      manager.assignScrapeResults(results) {
        asked = true
        true
      }

      asked shouldBe false
    }

    "failScrapeRequest should not fail a wrapper the owner check rejects" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(1103L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.failScrapeRequest(1103L, "bad response", ProxyFailure.INVALID_RESPONSE) { false }

      verify(exactly = 0) { wrapper.complete(any(), any()) }
    }

    "failScrapeRequest should fail a wrapper the owner check accepts" {
      val manager = ScrapeRequestManager()
      val wrapper = createMockWrapper(1104L)

      manager.tryAddToScrapeRequestMap(wrapper, Int.MAX_VALUE)
      manager.failScrapeRequest(1104L, "bad response", ProxyFailure.INVALID_RESPONSE) { it === wrapper }

      verify { wrapper.complete(any(), ProxyFailure.INVALID_RESPONSE) }
    }

    // ==================== in-flight limit ====================

    // The tracking map holds every scrape request in flight across all agents, so admitting through it
    // bounds the proxy's memory when many agents are busy at once.

    "tryAddToScrapeRequestMap should refuse a request once the in-flight limit is reached" {
      val manager = ScrapeRequestManager()

      manager.tryAddToScrapeRequestMap(createMockWrapper(1L), maxInFlight = 2) shouldBe true
      manager.tryAddToScrapeRequestMap(createMockWrapper(2L), maxInFlight = 2) shouldBe true
      manager.tryAddToScrapeRequestMap(createMockWrapper(3L), maxInFlight = 2) shouldBe false

      manager.scrapeMapSize shouldBe 2
      manager.containsScrapeRequest(3L) shouldBe false
    }

    "tryAddToScrapeRequestMap should admit a request again after one is removed" {
      val manager = ScrapeRequestManager()

      manager.tryAddToScrapeRequestMap(createMockWrapper(1L), maxInFlight = 1) shouldBe true
      manager.tryAddToScrapeRequestMap(createMockWrapper(2L), maxInFlight = 1) shouldBe false
      manager.removeFromScrapeRequestMap(1L)

      manager.tryAddToScrapeRequestMap(createMockWrapper(2L), maxInFlight = 1) shouldBe true
    }
  }
}
