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

import com.beust.jcommander.Parameter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.AGENT_PORT
import io.prometheus.common.EnvVars.PROXY_CONFIG
import io.prometheus.common.EnvVars.PROXY_PORT
import io.prometheus.common.EnvVars.SD_ENABLED
import io.prometheus.common.EnvVars.SD_PATH
import io.prometheus.common.EnvVars.SD_TARGET_PREFIX

class ProxyOptions(
  argv: Array<String>,
) : BaseOptions(Proxy::class.java.simpleName, argv, PROXY_CONFIG.name) {
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
    with(configVals.proxy) {
      if (proxyHttpPort == -1)
        proxyHttpPort = PROXY_PORT.getEnv(http.port)
      logger.info { "proxyHttpPort: $proxyHttpPort" }

      if (proxyAgentPort == -1)
        proxyAgentPort = AGENT_PORT.getEnv(agent.port)
      logger.info { "proxyAgentPort: $proxyAgentPort" }

      if (!sdEnabled)
        sdEnabled = SD_ENABLED.getEnv(false)
      logger.info { "sdEnabled: $sdEnabled" }

      if (sdPath.isEmpty())
        sdPath = SD_PATH.getEnv(service.discovery.path)
      if (sdEnabled)
        require(sdPath.isNotEmpty()) { "sdPath is empty" }
      else
        logger.info { "sdPath: $sdPath" }

      if (sdTargetPrefix.isEmpty())
        sdTargetPrefix = SD_TARGET_PREFIX.getEnv(service.discovery.targetPrefix)
      if (sdEnabled)
        require(sdTargetPrefix.isNotEmpty()) { "sdTargetPrefix is empty" }
      else
        logger.info { "sdTargetPrefix: $sdTargetPrefix" }

      assignAdminEnabled(admin.enabled)
      assignAdminPort(admin.port)
      assignMetricsEnabled(metrics.enabled)
      assignMetricsPort(metrics.port)
      assignTransportFilterDisabled(transportFilterDisabled)
      assignDebugEnabled(admin.debugEnabled)

      with(tls) {
        assignCertChainFilePath(certChainFilePath)
        assignPrivateKeyFilePath(privateKeyFilePath)
        assignTrustCertCollectionFilePath(trustCertCollectionFilePath)
      }

      with(internal) {
        logger.info { "proxy.internal.scrapeRequestTimeoutSecs: $scrapeRequestTimeoutSecs" }
        logger.info { "proxy.internal.staleAgentCheckPauseSecs: $staleAgentCheckPauseSecs" }
        logger.info { "proxy.internal.maxAgentInactivitySecs: $maxAgentInactivitySecs" }
      }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
