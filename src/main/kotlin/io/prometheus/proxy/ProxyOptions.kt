/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
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
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.*

class ProxyOptions(argv: Array<String>) : BaseOptions(Proxy::class.java.simpleName, argv, PROXY_CONFIG.name) {

  constructor(args: List<String>) : this(args.toTypedArray())

  @Parameter(names = ["-p", "--port"], description = "Proxy listen port")
  var proxyHttpPort = -1
    private set

  @Parameter(names = ["-a", "--agent_port"], description = "gRPC listen port for Agents")
  var proxyAgentPort = -1
    private set

  @Parameter(names = ["--sd_enabled"], description = "Service discovery endpoint enabled")
  var sdEnabled = false
    private set

  @Parameter(names = ["--sd_path"], description = "Service discovery endpoint path")
  var sdPath = ""
    private set

  @Parameter(names = ["--sd_target_prefix"], description = "Service discovery target prefix")
  var sdTargetPrefix = ""
    private set

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

        if (!sdEnabled)
          sdEnabled = SD_ENABLED.getEnv(false)
        logger.info { "sdEnabled: $sdEnabled" }

        if (sdPath.isEmpty())
          sdPath = SD_PATH.getEnv(proxyConfigVals.service.discovery.path)
        if (sdEnabled)
          require(sdPath.isNotEmpty()) { "sdPath is empty" }
        else
          logger.info { "sdPath: $sdPath" }

        if (sdTargetPrefix.isEmpty())
          sdTargetPrefix = SD_TARGET_PREFIX.getEnv(proxyConfigVals.service.discovery.targetPrefix)
        if (sdEnabled)
          require(sdTargetPrefix.isNotEmpty()) { "sdTargetPrefix is empty" }
        else
          logger.info { "sdTargetPrefix: $sdTargetPrefix" }

        assignAdminEnabled(proxyConfigVals.admin.enabled)
        assignAdminPort(proxyConfigVals.admin.port)
        assignMetricsEnabled(proxyConfigVals.metrics.enabled)
        assignMetricsPort(proxyConfigVals.metrics.port)
        assignTransportFilterDisabled(proxyConfigVals.transportFilterDisabled)
        assignDebugEnabled(proxyConfigVals.admin.debugEnabled)

        assignCertChainFilePath(proxyConfigVals.tls.certChainFilePath)
        assignPrivateKeyFilePath(proxyConfigVals.tls.privateKeyFilePath)
        assignTrustCertCollectionFilePath(proxyConfigVals.tls.trustCertCollectionFilePath)

        logger.info { "proxy.internal.scrapeRequestTimeoutSecs: ${proxyConfigVals.internal.scrapeRequestTimeoutSecs}" }
        logger.info { "proxy.internal.staleAgentCheckPauseSecs: ${proxyConfigVals.internal.staleAgentCheckPauseSecs}" }
        logger.info { "proxy.internal.maxAgentInactivitySecs: ${proxyConfigVals.internal.maxAgentInactivitySecs}" }
      }
  }
}