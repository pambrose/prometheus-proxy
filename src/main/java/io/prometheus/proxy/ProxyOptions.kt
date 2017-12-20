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

package io.prometheus.proxy

import com.beust.jcommander.Parameter
import com.google.common.collect.Iterables
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.*

class ProxyOptions(argv: Array<String>) : BaseOptions(Proxy::class.java.simpleName, argv, PROXY_CONFIG.name, false) {

    @Parameter(names = arrayOf("-p", "--port"), description = "Listen port for Prometheus")
    var proxyPort: Int? = null
        private set
    @Parameter(names = arrayOf("-a", "--agent_port"), description = "Listen port for agents")
    var agentPort: Int? = null
        private set

    constructor(args: List<String>?) : this(Iterables.toArray<String>(args ?: emptyList<String>(), String::class.java))

    init {
        this.parseOptions()
    }

    override fun assignConfigVals() {
        if (this.proxyPort == null)
            this.proxyPort = PROXY_PORT.getEnv(this.configVals!!.proxy.http.port)

        if (this.agentPort == null)
            this.agentPort = AGENT_PORT.getEnv(this.configVals!!.proxy.agent.port)

        this.assignAdminEnabled(this.configVals!!.proxy.admin.enabled)
        this.assignAdminPort(this.configVals!!.proxy.admin.port)
        this.assignMetricsEnabled(this.configVals!!.proxy.metrics.enabled)
        this.assignMetricsPort(this.configVals!!.proxy.metrics.port)
    }
}