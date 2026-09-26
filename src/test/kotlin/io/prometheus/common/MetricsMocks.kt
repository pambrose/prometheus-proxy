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

package io.prometheus.common

import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.HttpClientCache
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
import kotlin.concurrent.atomics.AtomicInt

// Mocks that stub just what ProxyMetrics and AgentMetrics read when they are built and scraped: the members behind
// their sampled gauges, and the agent's launch ID.

internal fun mockProxyForMetrics(): Proxy {
  val mockAgentContextManager = AgentContextManager(isTestMode = true)
  val mockPathManager = mockk<ProxyPathManager>(relaxed = true)
  val mockScrapeRequestManager = ScrapeRequestManager()

  every { mockPathManager.pathMapSize } returns 0

  val mockProxy = mockk<Proxy>(relaxed = true)
  every { mockProxy.agentContextManager } returns mockAgentContextManager
  every { mockProxy.pathManager } returns mockPathManager
  every { mockProxy.scrapeRequestManager } returns mockScrapeRequestManager

  return mockProxy
}

internal fun mockAgentForMetrics(): Agent {
  val mockHttpClientCache = mockk<HttpClientCache>(relaxed = true)
  every { mockHttpClientCache.currentCacheSize() } returns 0

  val mockAgentHttpService = mockk<AgentHttpService>(relaxed = true)
  every { mockAgentHttpService.httpClientCache } returns mockHttpClientCache

  val mockAgent = mockk<Agent>(relaxed = true)
  every { mockAgent.launchId } returns "test-launch-id"
  every { mockAgent.scrapeRequestBacklogSize } returns AtomicInt(0)
  every { mockAgent.agentHttpService } returns mockAgentHttpService

  return mockAgent
}
