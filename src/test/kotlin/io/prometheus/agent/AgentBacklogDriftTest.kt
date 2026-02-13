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

@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign

class AgentBacklogDriftTest : StringSpec() {
  private fun createTestAgent(): Agent =
    Agent(
      options = AgentOptions(listOf("--proxy", "localhost:50051"), exitOnMissingConfig = false),
      inProcessServerName = "backlog-drift-test",
      testMode = true,
    )

  init {
    "scrapeRequestBacklogSize should drift if sendScrapeRequestAction fails" {
      val agent = createTestAgent()
      val connectionContext = AgentConnectionContext(1)

      agent.scrapeRequestBacklogSize.load() shouldBe 0

      // First one succeeds
      agent.scrapeRequestBacklogSize += 1
      connectionContext.sendScrapeRequestAction {
        io.prometheus.common.ScrapeResults(
          srScrapeId = 0,
          srAgentId = "",
          srStatusCode = 200,
        )
      }
      agent.scrapeRequestBacklogSize.load() shouldBe 1

      // Close the channel to simulate disconnect
      connectionContext.close()

      // Second one fails
      try {
        agent.scrapeRequestBacklogSize += 1
        runBlocking {
          try {
            connectionContext.sendScrapeRequestAction {
              io.prometheus.common.ScrapeResults(
                srScrapeId = 0,
                srAgentId = "",
                srStatusCode = 200,
              )
            }
          } catch (e: Exception) {
            agent.scrapeRequestBacklogSize -= 1
            throw e
          }
        }
      } catch (e: Exception) {
        // Expected (ClosedSendChannelException or similar)
      }

      // Backlog should now be 1 (only the first one is "in flight")
      agent.scrapeRequestBacklogSize.load() shouldBe 1

      // Now simulate a coroutine processing the first one finishing
      agent.scrapeRequestBacklogSize -= 1

      // Backlog is now 0
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }
  }
}
