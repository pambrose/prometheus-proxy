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

package io.prometheus.agent.dashboard

import com.pambrose.common.util.runCatchingCancellable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import io.prometheus.agent.AgentActivityKind
import io.prometheus.common.agentOptionsFromConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * [AgentDashboardService]'s site as the push loop reads it: a write to the agent's activity feed is one of its changes,
 * each of which wakes the loop and retires the cached snapshot, and the feed's count at the start is not. How a change
 * reaches the pages is `DashboardPushTest`'s, and the pages a real agent serves `AgentWebDashboardTest`'s.
 */
class AgentDashboardServiceTest : StringSpec() {
  init {
    // Replaced with no changes at all, the agent's pages were pushed to on the tick alone, and its page GETs rendered a
    // snapshot up to an interval old, with no test failing.
    "a write to the activity feed should be one of the site's changes, and the feed's count at the start none" {
      val agent =
        Agent(
          agentOptionsFromConfig("agent.dashboard.enabled = true"),
          inProcessServerName = "agent-dashboard-service-${System.nanoTime()}",
          testMode = true,
        )
      try {
        val changes = agent.dashboardService.shouldNotBeNull().site.changes
        coroutineScope {
          // Started at once, so it has read the feed's count at the start, and is waiting, before anything is recorded.
          val first = async(start = CoroutineStart.UNDISPATCHED) { changes.first() }
          first.isCompleted.shouldBeFalse()

          agent.activityLog.shouldNotBeNull().record(AgentActivityKind.CONNECTED)

          withTimeout(5.seconds) { first.await() } shouldBe 1L
        }
      } finally {
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
    }
  }
}
