/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.beust.jcommander.Parameter
import com.google.common.collect.Iterables
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.*
import kotlin.time.Duration.Companion.seconds

class ProxyOptions(argv: Array<String>) : BaseOptions(Proxy::class.java.simpleName, argv, PROXY_CONFIG.name) {

  constructor(args: List<String>) : this(Iterables.toArray<String>(args.toMutableList(), String::class.java))

  @Parameter(names = ["-p", "--port"], description = "Proxy listen port")
  var proxyHttpPort = -1
    private set

  @Parameter(names = ["-a", "--agent_port"], description = "gRPC listen port for Agents")
  var proxyAgentPort = -1
    private set

  @Parameter(names = ["--discoverUrl"], description = "URL of this service")
  var discoverUrl = ""
    private set

  protected fun assignDiscoverUrl(defaultVal: String) {
    if (discoverUrl.isEmpty())
      discoverUrl = DISCOVER_URL.getEnv(defaultVal)
    logger.info { "discoverUrl: $privateKeyFilePath" }
  }

  init {
    parseOptions()
  }

  override fun assignConfigVals() {

    if (proxyHttpPort == -1)
      proxyHttpPort = PROXY_PORT.getEnv(configVals.proxy.http.port)
    logger.info { "proxyHttpPort: $proxyHttpPort" }

    if (proxyAgentPort == -1)
      proxyAgentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port)
    logger.info { "proxyAgentPort: $proxyAgentPort" }

    configVals.proxy
      .also { proxyConfigVals ->
        assignAdminEnabled(proxyConfigVals.admin.enabled)
        assignAdminPort(proxyConfigVals.admin.port)
        assignMetricsEnabled(proxyConfigVals.metrics.enabled)
        assignMetricsPort(proxyConfigVals.metrics.port)
        assignDebugEnabled(proxyConfigVals.admin.debugEnabled)
        assignDiscoverUrl(proxyConfigVals.admin.discoverUrl)

        assignCertChainFilePath(proxyConfigVals.tls.certChainFilePath)
        assignPrivateKeyFilePath(proxyConfigVals.tls.privateKeyFilePath)
        assignTrustCertCollectionFilePath(proxyConfigVals.tls.trustCertCollectionFilePath)

        logger.info { "proxy.internal.scrapeRequestTimeoutSecs: ${proxyConfigVals.internal.scrapeRequestTimeoutSecs.seconds}" }
      }
  }
}