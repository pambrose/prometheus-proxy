@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class EmbeddedAgentInfoTest : FunSpec() {
  init {
    test("should store launchId and agentName") {
      val info = EmbeddedAgentInfo(launchId = "launch-123", agentName = "test-agent")

      info.launchId shouldBe "launch-123"
      info.agentName shouldBe "test-agent"
    }

    test("should handle empty values") {
      val info = EmbeddedAgentInfo(launchId = "", agentName = "")

      info.launchId shouldBe ""
      info.agentName shouldBe ""
    }

    test("equals should compare by field values") {
      val info1 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info2 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info3 = EmbeddedAgentInfo(launchId = "launch-2", agentName = "agent-1")

      info1 shouldBe info2
      info1 shouldNotBe info3
    }

    test("hashCode should be consistent with equals") {
      val info1 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val info2 = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")

      info1.hashCode() shouldBe info2.hashCode()
    }

    test("copy should create independent instance") {
      val original = EmbeddedAgentInfo(launchId = "launch-1", agentName = "agent-1")
      val copied = original.copy(agentName = "agent-2")

      original.agentName shouldBe "agent-1"
      copied.agentName shouldBe "agent-2"
      copied.launchId shouldBe "launch-1"
    }

    test("toString should include field values") {
      val info = EmbeddedAgentInfo(launchId = "launch-123", agentName = "my-agent")
      val str = info.toString()

      str.contains("launch-123") shouldBe true
      str.contains("my-agent") shouldBe true
    }
  }
}
