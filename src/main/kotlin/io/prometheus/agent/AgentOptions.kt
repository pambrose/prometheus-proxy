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

package io.prometheus.agent

import com.beust.jcommander.Parameter
import com.google.common.collect.Iterables
import io.prometheus.Agent
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars
import io.prometheus.common.EnvVars.AGENT_CONFIG
import io.prometheus.common.EnvVars.PROXY_HOSTNAME
import kotlin.properties.Delegates

class AgentOptions(argv: Array<String>, exitOnMissingConfig: Boolean) :
    BaseOptions(Agent::class.java.name, argv, AGENT_CONFIG.name, exitOnMissingConfig) {

  constructor(args: List<String>, exitOnMissingConfig: Boolean) :
      this(Iterables.toArray<String>(args, String::class.java), exitOnMissingConfig)

  @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname")
  var proxyHostname = ""
    private set

  @Parameter(names = ["-n", "--name"], description = "Agent name")
  var agentName = ""
    private set

  @Parameter(names = ["--over", "--override"], description = "Override Authority")
  var overrideAuthority = ""
    private set

  var chunkThresholdKbs: Int by Delegates.notNull()
    private set

  var chunkBufferSizeKbs: Int by Delegates.notNull()
    private set

  init {
    parseOptions()
  }


  override fun assignConfigVals() {

    configVals.agent.also { agent ->
      if (proxyHostname.isEmpty()) {
        val configHostname = agent.proxy.hostname
        proxyHostname = PROXY_HOSTNAME.getEnv(if (":" in configHostname)
                                                configHostname
                                              else
                                                "$configHostname:${agent.proxy.port}")
      }

      if (agentName.isEmpty())
        agentName = EnvVars.AGENT_NAME.getEnv(agent.name)

      assignAdminEnabled(agent.admin.enabled)
      assignAdminPort(agent.admin.port)
      assignMetricsEnabled(agent.metrics.enabled)
      assignMetricsPort(agent.metrics.port)
      assignDebugEnabled(agent.admin.debugEnabled)

      assignCertChainFilePath(agent.tls.certChainFilePath)
      assignPrivateKeyFilePath(agent.tls.privateKeyFilePath)
      assignTrustCertCollectionFilePath(agent.tls.trustCertCollectionFilePath)

      chunkThresholdKbs = EnvVars.CHUNK_THRESHOLD_KBS.getEnv(agent.chunkThresholdKbs) * 1024
      chunkBufferSizeKbs = EnvVars.CHUNK_BUFFER_KBS.getEnv(agent.chunkBufferSizeKbs) * 1024

      if (overrideAuthority.isEmpty())
        overrideAuthority = EnvVars.OVERRIDE_AUTHORITY.getEnv(agent.tls.overrideAuthority)
    }
  }
}