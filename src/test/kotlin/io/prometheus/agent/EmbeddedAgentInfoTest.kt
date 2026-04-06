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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent

class EmbeddedAgentInfoTest : StringSpec() {
  init {
    "should expose launchId from agent" {
      val agent = mockk<Agent>()
      every { agent.launchId } returns "launch-123"
      every { agent.agentName } returns "test-agent"

      val info = EmbeddedAgentInfo(agent)

      info.launchId shouldBe "launch-123"
    }

    "should expose agentName from agent" {
      val agent = mockk<Agent>()
      every { agent.agentName } returns "test-agent"

      val info = EmbeddedAgentInfo(agent)

      info.agentName shouldBe "test-agent"
    }

    "shutdown should call agent stop" {
      val agent = mockk<Agent>(relaxed = true)

      val info = EmbeddedAgentInfo(agent)
      info.shutdown()

      verify { agent.stop() }
    }
  }
}
