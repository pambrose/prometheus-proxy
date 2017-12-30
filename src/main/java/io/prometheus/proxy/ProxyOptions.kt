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

    constructor(args: List<String>) : this(Iterables.toArray<String>(args, String::class.java))

    @Parameter(names = ["-p", "--port"], description = "Listen port for Prometheus")
    var proxyPort: Int? = null
        private set
    @Parameter(names = ["-a", "--agent_port"], description = "Listen port for agents")
    var agentPort: Int? = null
        private set

    init {
        parseOptions()
    }

    override fun assignConfigVals() {
        if (proxyPort == null)
            proxyPort = PROXY_PORT.getEnv(configVals.proxy.http.port)

        if (agentPort == null)
            agentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port)

        assignAdminEnabled(configVals.proxy.admin.enabled)
        assignAdminPort(configVals.proxy.admin.port)
        assignMetricsEnabled(configVals.proxy.metrics.enabled)
        assignMetricsPort(configVals.proxy.metrics.port)
    }
}