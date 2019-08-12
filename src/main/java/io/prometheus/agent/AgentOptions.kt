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
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Agent
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars
import io.prometheus.common.EnvVars.AGENT_CONFIG
import io.prometheus.common.EnvVars.PROXY_HOSTNAME

@KtorExperimentalAPI
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

    init {
        parseOptions()
    }

    override fun assignConfigVals() {
        if (proxyHostname.isEmpty()) {
            val configHostname = configVals.agent.proxy.hostname
            proxyHostname = PROXY_HOSTNAME.getEnv(if (configHostname.contains(":"))
                configHostname
            else
                "$configHostname:${configVals.agent.proxy.port}")
        }

        if (agentName.isEmpty())
            agentName = EnvVars.AGENT_NAME.getEnv(configVals.agent.name)

        assignAdminEnabled(configVals.agent.admin.enabled)
        assignAdminPort(configVals.agent.admin.port)
        assignMetricsEnabled(configVals.agent.metrics.enabled)
        assignMetricsPort(configVals.agent.metrics.port)
    }
}