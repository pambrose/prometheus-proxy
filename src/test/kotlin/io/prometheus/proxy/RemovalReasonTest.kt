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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.prometheus.proxy.RemovalReason.EVICTION
import io.prometheus.proxy.RemovalReason.REPLACED
import io.prometheus.proxy.RemovalReason.STREAM_TERMINATED
import io.prometheus.proxy.RemovalReason.TERMINATION

class RemovalReasonTest : StringSpec() {
  init {
    // The reasons were strings, and the log printed them as they were; making them a type mustn't change a log line.
    "each reason should be logged as its string always was" {
      EVICTION.logText shouldBe "Eviction"
      TERMINATION.logText shouldBe "Termination"
      STREAM_TERMINATED.logText shouldBe "Stream terminated (transport filter disabled)"
      REPLACED.logText shouldBe "Replaced by a newer connection of the same agent"
      RemovalReason.entries.map { it.logText }.toSet() shouldHaveSize RemovalReason.entries.size
    }

    // An eviction is the agent going quiet; a closed connection or a stream that ended is an agent stopping or losing
    // its link, which the proxy can't tell apart; and a connection its agent replaced is one the agent has left.
    "only an eviction should be a problem in the activity feed" {
      RemovalReason.entries.associateWith { it.activityKind } shouldBe
        mapOf(
          EVICTION to ActivityKind.AGENT_EVICTED,
          TERMINATION to ActivityKind.AGENT_DISCONNECTED,
          STREAM_TERMINATED to ActivityKind.AGENT_DISCONNECTED,
          REPLACED to ActivityKind.AGENT_DISCONNECTED,
        )
      RemovalReason.entries.filter { it.activityKind.isProblem } shouldBe listOf(EVICTION)
    }

    "each reason should give the activity feed its cause, an eviction naming the inactivity limit" {
      EVICTION.activityCause(45) shouldBe "No contact within proxy.internal.maxAgentInactivitySecs (45s)"
      TERMINATION.activityCause(45) shouldBe "Connection closed"
      STREAM_TERMINATED.activityCause(45) shouldBe "Stream terminated (transport filter disabled)"
      REPLACED.activityCause(45) shouldBe "Replaced by a newer connection of the same agent"
    }
  }
}
