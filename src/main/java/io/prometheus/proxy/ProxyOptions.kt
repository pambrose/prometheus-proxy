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

import com.beust.jcommander.Parameter
import com.google.common.collect.Iterables
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.AGENT_PORT
import io.prometheus.common.EnvVars.PROXY_CONFIG
import io.prometheus.common.EnvVars.PROXY_PORT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlin.time.ExperimentalTime

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@ExperimentalTime
@ObsoleteCoroutinesApi
class ProxyOptions(argv: Array<String>) : BaseOptions(Proxy::class.java.simpleName, argv, PROXY_CONFIG.name) {

    constructor(args: List<String>) : this(Iterables.toArray<String>(args, String::class.java))

    @Parameter(names = ["-p", "--port"], description = "Proxy listen port")
    var proxyHttpPort = -1
        private set
    @Parameter(names = ["-a", "--agent_port"], description = "gRPC listen port for Agents")
    var proxyAgentPort = -1
        private set

    init {
        parseOptions()
    }

    override fun assignConfigVals() {
        if (proxyHttpPort == -1)
            proxyHttpPort = PROXY_PORT.getEnv(configVals.proxy.http.port)

        if (proxyAgentPort == -1)
            proxyAgentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port)

        assignAdminEnabled(configVals.proxy.admin.enabled)
        assignAdminPort(configVals.proxy.admin.port)
        assignMetricsEnabled(configVals.proxy.metrics.enabled)
        assignMetricsPort(configVals.proxy.metrics.port)
    }
}