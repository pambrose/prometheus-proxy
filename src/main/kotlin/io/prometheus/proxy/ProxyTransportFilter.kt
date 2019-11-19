/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import io.grpc.Attributes
import io.grpc.ServerTransportFilter
import io.prometheus.Proxy
import mu.KLogging

class ProxyTransportFilter(private val proxy: Proxy) : ServerTransportFilter() {

  private fun getRemoteAddr(attributes: Attributes) =
    attributes.get(REMOTE_ADDR_KEY)?.toString() ?: "Unknown"

  override fun transportReady(attributes: Attributes): Attributes {
    val agentContext = AgentContext(getRemoteAddr(attributes))
    proxy.agentContextManager.addAgentContext(agentContext)
    logger.info { "Connected to $agentContext" }
    return attributes {
      set(Proxy.ATTRIB_AGENT_ID, agentContext.agentId)
      setAll(attributes)
    }
  }

  override fun transportTerminated(attributes: Attributes?) {
    if (attributes == null) {
      logger.error { "Null attributes" }
    } else {
      val agentId = attributes.get(Proxy.ATTRIB_AGENT_ID)
      proxy.pathManager.removePathByAgentId(agentId)
      val context = proxy.removeAgentContext(agentId)
      logger.info { "Disconnected ${if (context != null) "from $context" else "with invalid agentId: $agentId"}" }
    }
    super.transportTerminated(attributes)
  }

  companion object : KLogging() {
    private val REMOTE_ADDR_KEY: Attributes.Key<String> = Attributes.Key.create("remote-addr")
  }
}
