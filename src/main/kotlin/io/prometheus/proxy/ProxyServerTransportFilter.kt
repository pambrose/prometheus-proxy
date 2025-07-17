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

import com.github.pambrose.common.dsl.GrpcDsl.attributes
import com.github.pambrose.common.util.isNotNull
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Attributes
import io.grpc.ServerTransportFilter
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyServiceImpl.Companion.UNKNOWN_ADDRESS

internal class ProxyServerTransportFilter(
  private val proxy: Proxy,
) : ServerTransportFilter() {
  override fun transportReady(attributes: Attributes): Attributes {
    val remoteAddress = attributes.get(REMOTE_ADDR_KEY) ?: UNKNOWN_ADDRESS
    val agentContext = AgentContext(remoteAddress)
    proxy.agentContextManager.addAgentContext(agentContext)

    return attributes {
      set(AGENT_ID_KEY, agentContext.agentId)
      setAll(attributes)
    }
  }

  override fun transportTerminated(attributes: Attributes) {
    attributes.get(AGENT_ID_KEY)?.also { agentId ->
      val context = proxy.removeAgentContext(agentId, "Termination")
      if (context.isNotNull())
        logger.info { "Disconnected from $context" }
      else
        logger.error { "Disconnected with invalid agentId: $agentId" }
    } ?: logger.error { "Missing agentId in transportTerminated()" }
    super.transportTerminated(attributes)
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    internal const val AGENT_ID = "agent-id"
    private const val REMOTE_ADDR = "remote-addr"
    internal val AGENT_ID_KEY: Attributes.Key<String> = Attributes.Key.create(AGENT_ID)
    private val REMOTE_ADDR_KEY: Attributes.Key<String> = Attributes.Key.create(REMOTE_ADDR)
  }
}
