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
import io.prometheus.common.EnvVars.PROXY_PORT
import io.prometheus.common.EnvVars.REFLECTION_DISABLED
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

  @Parameter(names = ["--ref-disabled"], description = "gRPC Reflection disabled")
  var reflectionDisabled = false
    private set

  @Parameter(names = ["--handshake_timeout_secs"], description = "gRPC Handshake timeout (secs)")
  var handshakeTimeoutSecs = -1L
    private set

  @Parameter(names = ["--permit_keepalive_without_calls"], description = "gRPC Permit KeepAlive without calls")
  var permitKeepAliveWithoutCalls = false
    private set

  @Parameter(names = ["--permit_keepalive_time_secs"], description = "gRPC Permit KeepAlive time (secs)")
  var permitKeepAliveTimeSecs = -1L
    private set

  @Parameter(names = ["--max_connection_idle_secs"], description = "gRPC Max connection idle (secs)")
  var maxConnectionIdleSecs = -1L
    private set

  @Parameter(names = ["--max_connection_age_secs"], description = "gRPC Max connection age (secs)")
  var maxConnectionAgeSecs = -1L
    private set

  @Parameter(names = ["--max_connection_age_grace_secs"], description = "gRPC Max connection age grace (secs)")
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
        logger.info { "grpcHandshakeTimeoutSecs: $handshakeTimeoutSecs" }

        assignKeepAliveTimeSecs(proxyConfigVals.grpc.keepAliveTimeSecs)
        assignKeepAliveTimeoutSecs(proxyConfigVals.grpc.keepAliveTimeoutSecs)

        if (!permitKeepAliveWithoutCalls)
          permitKeepAliveWithoutCalls =
            PERMIT_KEEPALIVE_WITHOUT_CALLS.getEnv(proxyConfigVals.grpc.permitKeepAliveWithoutCalls)
        logger.info { "grpcPermitKeepAliveWithoutCalls: $permitKeepAliveWithoutCalls" }

        if (permitKeepAliveTimeSecs == -1L)
          permitKeepAliveTimeSecs = PERMIT_KEEPALIVE_TIME_SECS.getEnv(proxyConfigVals.grpc.permitKeepAliveTimeSecs)
        logger.info { "grpcPermitKeepAliveTimeSecs: $permitKeepAliveTimeSecs" }

        if (maxConnectionIdleSecs == -1L)
          maxConnectionIdleSecs = MAX_CONNECTION_IDLE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionIdleSecs)
        logger.info { "grpcMaxConnectionIdleSecs: ${if (maxConnectionIdleSecs == -1L) "INT_MAX" else maxConnectionIdleSecs}" }

        if (maxConnectionAgeSecs == -1L)
          maxConnectionAgeSecs = MAX_CONNECTION_AGE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeSecs)
        logger.info { "grpcMaxConnectionAgeSecs: ${if (maxConnectionAgeSecs == -1L) "INT_MAX" else maxConnectionAgeSecs}" }

        if (maxConnectionAgeGraceSecs == -1L)
          maxConnectionAgeGraceSecs =
            MAX_CONNECTION_AGE_GRACE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeGraceSecs)
        logger.info { "grpcMaxConnectionAgeGraceSecs: ${if (maxConnectionAgeGraceSecs == -1L) "INT_MAX" else maxConnectionAgeGraceSecs}" }


        assignAdminEnabled(proxyConfigVals.admin.enabled)
        assignAdminPort(proxyConfigVals.admin.port)
        assignMetricsEnabled(proxyConfigVals.metrics.enabled)
        assignMetricsPort(proxyConfigVals.metrics.port)
        assignTransportFilterDisabled(proxyConfigVals.transportFilterDisabled)
        assignDebugEnabled(proxyConfigVals.admin.debugEnabled)

        with(proxyConfigVals.tls) {
          assignCertChainFilePath(certChainFilePath)
          assignPrivateKeyFilePath(privateKeyFilePath)
          assignTrustCertCollectionFilePath(trustCertCollectionFilePath)
        }

        with(proxyConfigVals.internal) {
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
