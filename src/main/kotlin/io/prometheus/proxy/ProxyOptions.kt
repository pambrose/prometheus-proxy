/*
 * Copyright © 2026 Paul Ambrose
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
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars.AGENT_PORT
import io.prometheus.common.EnvVars.AGENT_TOKEN
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

class ProxyOptions(
  args: Array<String>,
) : BaseOptions(Proxy::class.java.simpleName, args, PROXY_CONFIG.name) {
  constructor(args: List<String>) : this(args.toTypedArray())

  /**
   * TCP port the Proxy serves proxied scrape requests on (the port Prometheus connects to).
   * `-1` means "fall back to [PROXY_PORT] env var, then `proxy.http.port` from config (default `8080`)".
   */
  @Parameter(names = ["-p", "--port"], description = "Proxy listen port")
  var proxyPort = -1
    private set

  /**
   * TCP port the Proxy listens on for incoming gRPC connections from agents.
   * `-1` means "fall back to [AGENT_PORT] env var, then `proxy.agent.port` from config (default `50051`)".
   */
  @Parameter(names = ["-a", "--agent_port"], description = "gRPC listen port for Agents")
  var proxyAgentPort = -1
    private set

  /**
   * Pre-shared token agents must present on every gRPC call to authenticate to the Proxy. When set, a server
   * interceptor rejects any agent RPC with a missing or mismatched token (`UNAUTHENTICATED`).
   * Empty (default) disables token authentication and leaves the agent port open, subject only to TLS/network
   * controls. Resolved from CLI → [AGENT_TOKEN] env var → `proxy.agentToken` config. Never logged.
   */
  @Parameter(names = ["--agent_token"], description = "Pre-shared agent authentication token")
  var agentToken = ""
    private set

  /**
   * Enables the Prometheus HTTP service-discovery endpoint on the Proxy.
   * When `true`, the Proxy serves a discovery document listing currently registered agent paths
   * at [sdPath]. Resolved from CLI → [SD_ENABLED] env var → `proxy.service.discovery.enabled` config.
   */
  @Parameter(names = ["--sd_enabled"], description = "Service discovery endpoint enabled")
  var sdEnabled = false
    private set

  /**
   * HTTP path (relative to the Proxy HTTP port) at which the service-discovery document is served.
   * Required and validated to be non-empty when [sdEnabled] is `true`.
   */
  @Parameter(names = ["--sd_path"], description = "Service discovery endpoint path")
  var sdPath = ""
    private set

  /**
   * Base URL used to build per-target entries in the service-discovery document
   * (e.g. `http://proxy.example.com:8080/`).
   * Required and validated to be non-empty when [sdEnabled] is `true`.
   */
  @Parameter(names = ["--sd_target_prefix"], description = "Service discovery target prefix")
  var sdTargetPrefix = ""
    private set

  /**
   * Disables the gRPC server reflection service on the Proxy.
   *
   * Both `--ref-disabled` (current) and `--ref_disabled` (legacy typo) are accepted to preserve
   * backwards compatibility for existing deployments.
   */
  @Parameter(names = ["--ref-disabled", "--ref_disabled"], description = "gRPC Reflection disabled")
  var reflectionDisabled = false
    private set

  /**
   * gRPC handshake timeout for the Proxy server, in seconds.
   * `-1L` means "use the gRPC default (120s)".
   */
  @Parameter(names = ["--handshake_timeout_secs"], description = "gRPC Handshake timeout (secs)")
  var handshakeTimeoutSecs = -1L
    private set

  /**
   * If `true`, the Proxy permits gRPC keepalive pings from agents even when no RPCs are in-flight.
   * Pair with [permitKeepAliveTimeSecs] to control the minimum allowed ping interval.
   */
  @Parameter(names = ["--permit_keepalive_without_calls"], description = "Permit gRPC KeepAlive without calls")
  var permitKeepAliveWithoutCalls = false
    private set

  /**
   * Minimum interval, in seconds, that the Proxy will accept gRPC keepalive pings from agents.
   * Pings arriving more frequently than this are treated as a protocol violation by gRPC.
   * `-1L` means "use the gRPC default (300s)".
   */
  @Parameter(names = ["--permit_keepalive_time_secs"], description = "Permit gRPC KeepAlive time (secs)")
  var permitKeepAliveTimeSecs = -1L
    private set

  /**
   * gRPC server `MAX_CONNECTION_IDLE` in seconds — connections idle longer than this are closed.
   * `-1L` means "use the gRPC default (`INT_MAX`, effectively no idle timeout)".
   */
  @Parameter(names = ["--max_connection_idle_secs"], description = "Max gRPC connection idle (secs)")
  var maxConnectionIdleSecs = -1L
    private set

  /**
   * gRPC server `MAX_CONNECTION_AGE` in seconds — connections older than this are gracefully closed,
   * forcing reconnect (useful for load-balancer rebalancing).
   * `-1L` means "use the gRPC default (`INT_MAX`, effectively no age limit)".
   */
  @Parameter(names = ["--max_connection_age_secs"], description = "Max gRPC connection age (secs)")
  var maxConnectionAgeSecs = -1L
    private set

  /**
   * Grace period, in seconds, after [maxConnectionAgeSecs] is reached before the Proxy forcibly
   * closes the connection. Allows in-flight RPCs to complete cleanly.
   * `-1L` means "use the gRPC default (`INT_MAX`)".
   */
  @Parameter(names = ["--max_connection_age_grace_secs"], description = "Max gRPC connection age grace (secs)")
  var maxConnectionAgeGraceSecs = -1L
    private set

  init {
    parseOptions()
  }

  override fun assignConfigVals() {
    configVals.proxy
      .also { proxyConfigVals ->
        if (proxyPort == -1)
          proxyPort = PROXY_PORT.getEnv(proxyConfigVals.http.port)
        require(proxyPort in 1..65535) { "proxyPort must be in 1..65535: $proxyPort" }
        logger.info { "proxyPort: $proxyPort" }

        if (proxyAgentPort == -1)
          proxyAgentPort = AGENT_PORT.getEnv(proxyConfigVals.agent.port)
        require(proxyAgentPort in 1..65535) { "proxyAgentPort must be in 1..65535: $proxyAgentPort" }
        logger.info { "proxyAgentPort: $proxyAgentPort" }

        sdEnabled =
          resolveBooleanOption(sdEnabled, SD_ENABLED, proxyConfigVals.service.discovery.enabled, "--sd_enabled")
        logger.info { "sdEnabled: $sdEnabled" }

        if (sdPath.isEmpty())
          sdPath = SD_PATH.getEnv(proxyConfigVals.service.discovery.path)
        if (sdEnabled)
          require(sdPath.isNotEmpty()) { "sdPath is empty" }
        logger.info { "sdPath: $sdPath" }

        if (sdTargetPrefix.isEmpty())
          sdTargetPrefix = SD_TARGET_PREFIX.getEnv(proxyConfigVals.service.discovery.targetPrefix)
        if (sdEnabled)
          require(sdTargetPrefix.isNotEmpty()) { "sdTargetPrefix is empty" }
        logger.info { "sdTargetPrefix: $sdTargetPrefix" }

        reflectionDisabled =
          resolveBooleanOption(
            reflectionDisabled,
            REFLECTION_DISABLED,
            proxyConfigVals.reflectionDisabled,
            "--ref-disabled",
            "--ref_disabled",
          )
        logger.info { "reflectionDisabled: $reflectionDisabled" }

        if (handshakeTimeoutSecs == -1L)
          handshakeTimeoutSecs = HANDSHAKE_TIMEOUT_SECS.getEnv(proxyConfigVals.grpc.handshakeTimeoutSecs)
        requireGrpcTimeout(handshakeTimeoutSecs, "grpc.handshakeTimeoutSecs")
        logger.info { "grpc.handshakeTimeoutSecs: ${handshakeTimeoutSecs.grpcDefaultLabel("120")}" }

        permitKeepAliveWithoutCalls =
          resolveBooleanOption(
            permitKeepAliveWithoutCalls,
            PERMIT_KEEPALIVE_WITHOUT_CALLS,
            proxyConfigVals.grpc.permitKeepAliveWithoutCalls,
            "--permit_keepalive_without_calls",
          )
        logger.info { "grpc.permitKeepAliveWithoutCalls: $permitKeepAliveWithoutCalls" }

        if (permitKeepAliveTimeSecs == -1L)
          permitKeepAliveTimeSecs = PERMIT_KEEPALIVE_TIME_SECS.getEnv(proxyConfigVals.grpc.permitKeepAliveTimeSecs)
        requireGrpcTimeout(permitKeepAliveTimeSecs, "grpc.permitKeepAliveTimeSecs")
        logger.info { "grpc.permitKeepAliveTimeSecs: ${permitKeepAliveTimeSecs.grpcDefaultLabel("300")}" }

        if (maxConnectionIdleSecs == -1L)
          maxConnectionIdleSecs = MAX_CONNECTION_IDLE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionIdleSecs)
        requireGrpcTimeout(maxConnectionIdleSecs, "grpc.maxConnectionIdleSecs")
        logger.info { "grpc.maxConnectionIdleSecs: ${maxConnectionIdleSecs.grpcDefaultLabel("INT_MAX")}" }

        if (maxConnectionAgeSecs == -1L)
          maxConnectionAgeSecs = MAX_CONNECTION_AGE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeSecs)
        requireGrpcTimeout(maxConnectionAgeSecs, "grpc.maxConnectionAgeSecs")
        logger.info { "grpc.maxConnectionAgeSecs: ${maxConnectionAgeSecs.grpcDefaultLabel("INT_MAX")}" }

        if (maxConnectionAgeGraceSecs == -1L)
          maxConnectionAgeGraceSecs =
            MAX_CONNECTION_AGE_GRACE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeGraceSecs)
        requireGrpcTimeout(maxConnectionAgeGraceSecs, "grpc.maxConnectionAgeGraceSecs")
        logger.info { "grpc.maxConnectionAgeGraceSecs: ${maxConnectionAgeGraceSecs.grpcDefaultLabel("INT_MAX")}" }

        proxyConfigVals.apply {
          assignCommonOptions(
            keepAliveTimeSecs = grpc.keepAliveTimeSecs,
            keepAliveTimeoutSecs = grpc.keepAliveTimeoutSecs,
            adminEnabled = admin.enabled,
            adminPort = admin.port,
            metricsEnabled = metrics.enabled,
            metricsPort = metrics.port,
            transportFilterDisabled = transportFilterDisabled,
            debugEnabled = admin.debugEnabled,
            certChainFilePath = tls.certChainFilePath,
            privateKeyFilePath = tls.privateKeyFilePath,
            trustCertCollectionFilePath = tls.trustCertCollectionFilePath,
          )

          require(internal.scrapeRequestTimeoutSecs > 0) {
            "internal.scrapeRequestTimeoutSecs must be > 0: ${internal.scrapeRequestTimeoutSecs}"
          }
          logger.info { "internal.scrapeRequestTimeoutSecs: ${internal.scrapeRequestTimeoutSecs}" }

          require(internal.staleAgentCheckPauseSecs > 0) {
            "internal.staleAgentCheckPauseSecs must be > 0: ${internal.staleAgentCheckPauseSecs}"
          }
          logger.info { "internal.staleAgentCheckPauseSecs: ${internal.staleAgentCheckPauseSecs}" }

          require(internal.maxAgentInactivitySecs > 0) {
            "internal.maxAgentInactivitySecs must be > 0: ${internal.maxAgentInactivitySecs}"
          }
          logger.info { "internal.maxAgentInactivitySecs: ${internal.maxAgentInactivitySecs}" }

          // 0 is a valid (degenerate) "reject all content" limit, so only negatives are invalid here.
          require(internal.maxUnzippedContentSizeMBytes >= 0) {
            "internal.maxUnzippedContentSizeMBytes must be >= 0: ${internal.maxUnzippedContentSizeMBytes}"
          }
          logger.info { "internal.maxUnzippedContentSizeMBytes: ${internal.maxUnzippedContentSizeMBytes}" }
        }

        // Resolved after assignCommonOptions so trustCertCollectionFilePath reflects the CLI/env/config value.
        if (agentToken.isEmpty())
          agentToken = AGENT_TOKEN.getEnv(proxyConfigVals.agentToken)
        // Never log the token value -- only whether one is configured.
        logger.info { "agentToken: ${if (agentToken.isEmpty()) "(none)" else "***"}" }
        // Warn only when the agent port is genuinely open: no token AND no mutual-TLS trust store.
        if (agentToken.isEmpty() && trustCertCollectionFilePath.isEmpty()) {
          logger.warn {
            "Agent gRPC port is unauthenticated -- no pre-shared agent token and no mutual-TLS trust store " +
              "are configured. Any reachable peer can register as an agent. Do not expose this port in production."
          }
        }

        assignLogLevel("proxy", PROXY_LOG_LEVEL, proxyConfigVals.logLevel)
      }
  }

  internal companion object {
    private val logger = logger {}

    // gRPC timeout fields use -1L as the "leave the gRPC default in place" sentinel (the
    // `> -1L` guards in ProxyGrpcService rely on it). Any other non-positive value is invalid
    // and would otherwise surface as an opaque gRPC builder exception at startup.
    private fun requireGrpcTimeout(
      value: Long,
      name: String,
    ) = require(value == -1L || value > 0L) { "$name must be -1 (default) or > 0: $value" }
  }
}
