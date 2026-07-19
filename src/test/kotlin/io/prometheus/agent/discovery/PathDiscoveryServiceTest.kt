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

package io.prometheus.agent.discovery

import io.kotest.core.spec.style.StringSpec
import io.mockk.coVerify
import io.mockk.mockk
import io.prometheus.agent.AgentPathManager
import java.io.IOException

class PathDiscoveryServiceTest : StringSpec() {
  init {
    "a read failure skips reconcile so the live set is kept as last-known-good" {
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { throw IOException("boom") }, 30)

      service.reconcileOnce()

      coVerify(exactly = 0) { pathManager.reconcileDiscoveredPaths(any()) }
    }

    "a successful read reconciles the desired set" {
      val desired = [DiscoveredPath("a", "a_metrics", "http://a/m", "{}")]
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { desired }, 30)

      service.reconcileOnce()

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(desired) }
    }

    "a successful empty read reconciles to empty (removes all discovered)" {
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { emptyList() }, 30)

      service.reconcileOnce()

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(emptyList()) }
    }
  }
}
