/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.agent

import com.beust.jcommander.Parameter
import com.google.common.collect.Iterables
import io.prometheus.Agent
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars
import io.prometheus.common.EnvVars.AGENT_CONFIG
import io.prometheus.common.EnvVars.PROXY_HOSTNAME

class AgentOptions(argv: Array<String>, exitOnMissingConfig: Boolean) : BaseOptions(Agent::class.java.name, argv, AGENT_CONFIG.name, exitOnMissingConfig) {

    @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname")
    var proxyHostname: String? = null
        private set
    @Parameter(names = ["-n", "--name"], description = "Agent name")
    var agentName: String? = null
        private set

    constructor(args: List<String>, exitOnMissingConfig: Boolean) : this(Iterables.toArray<String>(args,
                                                                                                   String::class.java),
                                                                         exitOnMissingConfig)

    init {
        this.parseOptions()
    }

    override fun assignConfigVals() {
        if (this.proxyHostname.isNullOrEmpty()) {
            val configHostname = this.configVals!!.agent.proxy.hostname
            this.proxyHostname = PROXY_HOSTNAME.getEnv(if (configHostname.contains(":"))
                                                           configHostname
                                                       else
                                                           "$configHostname:${this.configVals!!.agent.proxy.port}")
        }

        if (this.agentName.isNullOrEmpty())
            this.agentName = EnvVars.AGENT_NAME.getEnv(this.configVals!!.agent.name)

        this.assignAdminEnabled(this.configVals!!.agent.admin.enabled)
        this.assignAdminPort(this.configVals!!.agent.admin.port)
        this.assignMetricsEnabled(this.configVals!!.agent.metrics.enabled)
        this.assignMetricsPort(this.configVals!!.agent.metrics.port)
    }
}