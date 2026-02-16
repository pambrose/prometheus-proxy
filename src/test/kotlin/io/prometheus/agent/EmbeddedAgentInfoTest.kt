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
import io.kotest.matchers.shouldNotBe

class EmbeddedAgentInfoTest : StringSpec() {
  init {
    "should store launchId and agentName" {
      val info = EmbeddedAgentInfo(launchId = "launch-123", agentName = "test-agent")

      info.launchId shouldBe "launch-123"
      info.agentName shouldBe "test-agent"
    }

    "should handle empty values" {
      val info = EmbeddedAgentInfo(launchId = "", agentName = "")

      info.launchId shouldBe ""
      info.agentName shouldBe ""
    }

    "equals should compare by field values" {
      val info1 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info2 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info3 = EmbeddedAgentInfo(launchId = "launch-2", agentName = "agent-1")

      info1 shouldBe info2
      info1 shouldNotBe info3
    }

    "hashCode should be consistent with equals" {
      val info1 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info2 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")

      info1.hashCode() shouldBe info2.hashCode()
    }

    "copy should create independent instance" {
      val original = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val copied = original.copy(agentName = "agent-2")

      original.agentName shouldBe "agent-1"
      copied.agentName shouldBe "agent-2"
      copied.launchId shouldBe "launch-1"
    }

    "toString should include field values" {
      val info = EmbeddedAgentInfo(launchId = "launch-123", agentName = "my-agent")
      val str = info.toString()

      str.contains("launch-123") shouldBe true
      str.contains("my-agent") shouldBe true
    }
  }
}
