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
import io.prometheus.common.EnvVars.HANDSHAKE_TIMEOUT_SECS
import io.prometheus.common.EnvVars.MAX_CONNECTION_AGE_GRACE_SECS
import io.prometheus.common.EnvVars.MAX_CONNECTION_AGE_SECS
import io.prometheus.common.EnvVars.MAX_CONNECTION_IDLE_SECS
import io.prometheus.common.EnvVars.PERMIT_KEEPALIVE_TIME_SECS
import io.prometheus.common.EnvVars.PERMIT_KEEPALIVE_WITHOUT_CALLS
import io.prometheus.common.EnvVars.PROXY_CONFIG
import io.prometheus.common.EnvVars.PROXY_LOG_LEVEL
import io.prometheus.common.EnvVars.PROXY_PORT
import io.prometheus.common.EnvVars.REFLECTION_DISABLED
import io.prometheus.common.EnvVars.SD_ENABLED
import io.prometheus.common.EnvVars.SD_PATH
import io.prometheus.common.EnvVars.SD_TARGET_PREFIX
import io.prometheus.common.Utils.setLogLevel

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

  @Parameter(names = ["--ref-disabled"], description = "gRPC Reflection disabled")
  var reflectionDisabled = false
    private set

  @Parameter(names = ["--handshake_timeout_secs"], description = "gRPC Handshake timeout (secs)")
  var handshakeTimeoutSecs = -1L
    private set

  @Parameter(names = ["--permit_keepalive_without_calls"], description = "Permit gRPC KeepAlive without calls")
  var permitKeepAliveWithoutCalls = false
    private set

  @Parameter(names = ["--permit_keepalive_time_secs"], description = "Permit gRPC KeepAlive time (secs)")
  var permitKeepAliveTimeSecs = -1L
    private set

  @Parameter(names = ["--max_connection_idle_secs"], description = "Max gRPC connection idle (secs)")
  var maxConnectionIdleSecs = -1L
    private set

  @Parameter(names = ["--max_connection_age_secs"], description = "Max gRPC connection age (secs)")
  var maxConnectionAgeSecs = -1L
    private set

  @Parameter(names = ["--max_connection_age_grace_secs"], description = "Max gRPC connection age grace (secs)")
  var maxConnectionAgeGraceSecs = -1L
    private set

  init {
    parseOptions()
  }

  override fun assignConfigVals() {
    configVals.proxy
      .also { proxyConfigVals ->
        if (proxyHttpPort == -1)
          proxyHttpPort = PROXY_PORT.getEnv(proxyConfigVals.http.port)
        logger.info { "proxyHttpPort: $proxyHttpPort" }

        if (proxyAgentPort == -1)
          proxyAgentPort = AGENT_PORT.getEnv(proxyConfigVals.agent.port)
        logger.info { "proxyAgentPort: $proxyAgentPort" }

        if (!sdEnabled)
          sdEnabled = SD_ENABLED.getEnv(proxyConfigVals.service.discovery.enabled)
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

        if (!reflectionDisabled)
          reflectionDisabled = REFLECTION_DISABLED.getEnv(proxyConfigVals.reflectionDisabled)
        logger.info { "reflectionDisabled: $reflectionDisabled" }

        if (handshakeTimeoutSecs == -1L)
          handshakeTimeoutSecs = HANDSHAKE_TIMEOUT_SECS.getEnv(proxyConfigVals.grpc.handshakeTimeoutSecs)
        val hsTimeout = if (handshakeTimeoutSecs == -1L) "default (120)" else handshakeTimeoutSecs
        logger.info { "grpc.handshakeTimeoutSecs: $hsTimeout" }

        if (!permitKeepAliveWithoutCalls)
          permitKeepAliveWithoutCalls =
            PERMIT_KEEPALIVE_WITHOUT_CALLS.getEnv(proxyConfigVals.grpc.permitKeepAliveWithoutCalls)
        logger.info { "grpc.permitKeepAliveWithoutCalls: $permitKeepAliveWithoutCalls" }

        if (permitKeepAliveTimeSecs == -1L)
          permitKeepAliveTimeSecs = PERMIT_KEEPALIVE_TIME_SECS.getEnv(proxyConfigVals.grpc.permitKeepAliveTimeSecs)
        val kaTime = if (permitKeepAliveTimeSecs == -1L) "default (300)" else permitKeepAliveTimeSecs
        logger.info { "grpc.permitKeepAliveTimeSecs: $kaTime" }

        if (maxConnectionIdleSecs == -1L)
          maxConnectionIdleSecs = MAX_CONNECTION_IDLE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionIdleSecs)
        val idleVal = if (maxConnectionIdleSecs == -1L) "default (INT_MAX)" else maxConnectionIdleSecs
        logger.info { "grpc.maxConnectionIdleSecs: $idleVal" }

        if (maxConnectionAgeSecs == -1L)
          maxConnectionAgeSecs = MAX_CONNECTION_AGE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeSecs)
        val ageVal = if (maxConnectionAgeSecs == -1L) "default (INT_MAX)" else maxConnectionAgeSecs
        logger.info { "grpc.maxConnectionAgeSecs: $ageVal" }

        if (maxConnectionAgeGraceSecs == -1L)
          maxConnectionAgeGraceSecs =
            MAX_CONNECTION_AGE_GRACE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeGraceSecs)
        val graceVal = if (maxConnectionAgeGraceSecs == -1L) "default (INT_MAX)" else maxConnectionAgeGraceSecs
        logger.info { "grpc.maxConnectionAgeGraceSecs: $graceVal" }

        with(proxyConfigVals) {
          assignKeepAliveTimeSecs(grpc.keepAliveTimeSecs)
          assignKeepAliveTimeoutSecs(grpc.keepAliveTimeoutSecs)
          assignAdminEnabled(admin.enabled)
          assignAdminPort(admin.port)
          assignMetricsEnabled(metrics.enabled)
          assignMetricsPort(metrics.port)
          assignTransportFilterDisabled(transportFilterDisabled)
          assignDebugEnabled(admin.debugEnabled)

          assignCertChainFilePath(tls.certChainFilePath)
          assignPrivateKeyFilePath(tls.privateKeyFilePath)
          assignTrustCertCollectionFilePath(tls.trustCertCollectionFilePath)

          logger.info { "internal.scrapeRequestTimeoutSecs: ${internal.scrapeRequestTimeoutSecs}" }
          logger.info { "internal.staleAgentCheckPauseSecs: ${internal.staleAgentCheckPauseSecs}" }
          logger.info { "internal.maxAgentInactivitySecs: ${internal.maxAgentInactivitySecs}" }
        }

        if (logLevel.isEmpty())
          logLevel = PROXY_LOG_LEVEL.getEnv(proxyConfigVals.logLevel)
        if (logLevel.isNotEmpty()) {
          logger.info { "proxy.logLevel: $logLevel" }
          setLogLevel("proxy", logLevel)
        } else {
          logger.info { "proxy.logLevel: info" }
        }
      }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
