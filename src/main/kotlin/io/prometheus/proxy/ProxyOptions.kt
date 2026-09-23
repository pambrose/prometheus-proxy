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
import com.google.common.net.InetAddresses
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Proxy
import io.prometheus.common.BaseOptions
import io.prometheus.common.ConfigVals
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
import io.prometheus.common.EnvVars.DASHBOARD_ENABLED
import io.prometheus.common.EnvVars.DASHBOARD_HOST
import io.prometheus.common.EnvVars.DASHBOARD_PATH
import io.prometheus.common.EnvVars.DASHBOARD_PORT
import io.prometheus.common.requireGrpcTimeout
import io.prometheus.common.requirePositive

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
   * Enables the Proxy's read-only operational dashboard, served from its **own** port ([dashboardPort]) rather
   * than the admin or scrape port. Off by default, matching the admin and metrics posture.
   * Resolved from CLI → [DASHBOARD_ENABLED] env var → `proxy.dashboard.enabled` config.
   */
  @Parameter(names = ["--dashboard"], description = "Operational dashboard enabled")
  var dashboardEnabled = false
    private set

  /**
   * TCP port the operational dashboard listens on. Deliberately separate from the admin port so the dashboard can
   * be firewalled independently of the k8s probe endpoints (`/ping`, `/healthcheck`).
   * `-1` means "fall back to [DASHBOARD_PORT] env var, then `proxy.dashboard.port` config (default `8094`)".
   */
  @Parameter(names = ["--dashboard_port"], description = "Operational dashboard port")
  var dashboardPort: Int = -1
    private set

  /**
   * Base HTTP path the operational dashboard is served from. Validated non-empty when [dashboardEnabled] is `true`.
   */
  @Parameter(names = ["--dashboard_path"], description = "Operational dashboard base path")
  var dashboardPath = ""
    private set

  /**
   * Address the operational dashboard listens on. Empty means "fall back to [DASHBOARD_HOST] env var, then
   * `proxy.dashboard.host` config (default `0.0.0.0`)". The dashboard has no authentication, so enabling it on a
   * wildcard address logs a startup warning; `127.0.0.1` keeps it reachable only from the proxy host.
   */
  @Parameter(names = ["--dashboard_host"], description = "Operational dashboard listen address")
  var dashboardHost = ""
    private set

  /**
   * Disables the gRPC server reflection service on the Proxy.
   *
   * Reflection is disabled by default (`proxy.reflectionDisabled = true`), and when enabled it
   * requires the same agent authentication as `ProxyService`. Enable it with
   * `proxy.reflectionDisabled = false` or `REFLECTION_DISABLED=false`; this flag can only disable it.
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
        validateHttpHostAndInFlightLimit(proxyConfigVals)

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

        assignDashboardOptions(proxyConfigVals.dashboard)

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
        logger.requireGrpcTimeout("grpc.handshakeTimeoutSecs", handshakeTimeoutSecs, "120")

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
        logger.requireGrpcTimeout("grpc.permitKeepAliveTimeSecs", permitKeepAliveTimeSecs, "300")

        if (maxConnectionIdleSecs == -1L)
          maxConnectionIdleSecs = MAX_CONNECTION_IDLE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionIdleSecs)
        logger.requireGrpcTimeout("grpc.maxConnectionIdleSecs", maxConnectionIdleSecs, "INT_MAX")

        if (maxConnectionAgeSecs == -1L)
          maxConnectionAgeSecs = MAX_CONNECTION_AGE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeSecs)
        logger.requireGrpcTimeout("grpc.maxConnectionAgeSecs", maxConnectionAgeSecs, "INT_MAX")

        if (maxConnectionAgeGraceSecs == -1L)
          maxConnectionAgeGraceSecs =
            MAX_CONNECTION_AGE_GRACE_SECS.getEnv(proxyConfigVals.grpc.maxConnectionAgeGraceSecs)
        logger.requireGrpcTimeout("grpc.maxConnectionAgeGraceSecs", maxConnectionAgeGraceSecs, "INT_MAX")

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

          logger.requirePositive("internal.scrapeRequestTimeoutSecs", internal.scrapeRequestTimeoutSecs)
          logger.requirePositive("internal.staleAgentCheckPauseSecs", internal.staleAgentCheckPauseSecs)
          logger.requirePositive("internal.maxAgentInactivitySecs", internal.maxAgentInactivitySecs)
          // Each agent's queue is capped at twice this, so 0 would answer every scrape with 503 agent_backlog_full.
          logger.requirePositive(
            "internal.scrapeRequestBacklogUnhealthySize",
            internal.scrapeRequestBacklogUnhealthySize,
          )

          // 0 is a valid (degenerate) "reject all content" limit, so only negatives are invalid here.
          require(internal.maxUnzippedContentSizeMBytes >= 0) {
            "internal.maxUnzippedContentSizeMBytes must be >= 0: ${internal.maxUnzippedContentSizeMBytes}"
          }
          logger.info { "internal.maxUnzippedContentSizeMBytes: ${internal.maxUnzippedContentSizeMBytes}" }

          // maxZippedContentSizeMBytes is used in chunked-transfer size math; mirror its sibling above
          // (0 is a valid reject-all limit, negatives are invalid) so a bad value fails fast at startup
          // instead of surfacing as a confusing ChunkValidationException per transfer (finding 11).
          require(internal.maxZippedContentSizeMBytes >= 0) {
            "internal.maxZippedContentSizeMBytes must be >= 0: ${internal.maxZippedContentSizeMBytes}"
          }
          logger.info { "internal.maxZippedContentSizeMBytes: ${internal.maxZippedContentSizeMBytes}" }
        }

        // Resolved after assignCommonOptions so trustCertCollectionFilePath reflects the CLI/env/config value.
        if (agentToken.isEmpty())
          agentToken = AGENT_TOKEN.getEnv(proxyConfigVals.agentToken)
        // Never log the token value -- only whether one is configured.
        logger.info { "agentToken: ${if (agentToken.isEmpty()) "(none)" else "***"}" }
        // Warn only when the agent port is genuinely open to any reachable peer.
        if (
          isAgentPortUnauthenticated(
            agentToken = agentToken,
            authIdentityCount = proxyConfigVals.auth.size,
            isTlsEnabled = isTlsEnabled,
            trustCertCollectionFilePath = trustCertCollectionFilePath,
          )
        ) {
          logger.warn {
            "Agent gRPC port is unauthenticated -- no agent token, per-agent identity (proxy.auth), or mutual TLS " +
              "is configured. Any reachable peer can register as an agent. Do not expose this port in production."
          }
        }
        if (areAgentTokensSentInCleartext(agentToken, proxyConfigVals.auth.size, isTlsEnabled)) {
          logger.warn {
            "Agent tokens are configured but TLS is not enabled on the agent gRPC port -- tokens are sent in " +
              "cleartext and can be captured by anyone who can observe the traffic. Set certChainFilePath and " +
              "privateKeyFilePath to enable TLS."
          }
        }

        assignLogLevel("proxy", PROXY_LOG_LEVEL, proxyConfigVals.logLevel)
      }
  }

  // Resolves and validates the dashboard options, kept out of assignConfigVals so that function stays within
  // detekt's length limit.
  private fun assignDashboardOptions(dashboard: ConfigVals.Proxy2.Dashboard) {
    dashboardEnabled = resolveBooleanOption(dashboardEnabled, DASHBOARD_ENABLED, dashboard.enabled, "--dashboard")
    logger.info { "dashboardEnabled: $dashboardEnabled" }

    if (dashboardPort == -1)
      dashboardPort = DASHBOARD_PORT.getEnv(dashboard.port)
    require(dashboardPort in 1..65535) { "dashboardPort must be in 1..65535: $dashboardPort" }

    if (dashboardPath.isEmpty())
      dashboardPath = DASHBOARD_PATH.getEnv(dashboard.path)
    if (dashboardHost.isEmpty())
      dashboardHost = DASHBOARD_HOST.getEnv(dashboard.host)

    if (dashboardEnabled) {
      require(dashboardPath.isNotEmpty()) { "dashboardPath is empty" }
      require(dashboardHost.isNotBlank()) { "dashboardHost is blank" }
      logger.requirePositive("dashboard.maxSessions", dashboard.maxSessions)
      // The push loop waits this long between pushes; a wait of 0 returns at once, spinning a thread.
      logger.requirePositive("dashboard.refreshIntervalSecs", dashboard.refreshIntervalSecs)
      logger.info { "dashboardHost: $dashboardHost, dashboardPort: $dashboardPort, dashboardPath: $dashboardPath" }
      if (isWildcardAddress(dashboardHost)) {
        val rebindingHint =
          if (dashboard.allowedHosts.isEmpty())
            " Set proxy.dashboard.allowedHosts to refuse unknown host names."
          else
            ""
        logger.warn {
          "The dashboard has no authentication and listens on all interfaces ($dashboardHost:$dashboardPort). " +
            "Set proxy.dashboard.host (--dashboard_host) to 127.0.0.1, or firewall the port.$rebindingHint"
        }
      }
    }
  }

  // Checks for the scrape-port bind address and the in-flight limit, kept out of assignConfigVals so that function
  // stays within detekt's length limit.
  private fun validateHttpHostAndInFlightLimit(proxyConfigVals: ConfigVals.Proxy2) {
    val http = proxyConfigVals.http
    // A blank bind address would otherwise fail only when the scrape server starts, with an opaque Ktor error.
    require(http.host.isNotBlank()) { "proxy.http.host must not be blank" }
    logger.info { "http.host: ${http.host}" }

    // A non-positive limit would refuse every scrape.
    logger.requirePositive("internal.maxInFlightScrapeRequests", proxyConfigVals.internal.maxInFlightScrapeRequests)

    // Path-registration limits: 0 turns one off, and only a negative value is meaningless.
    with(proxyConfigVals.internal) {
      listOf(
        "maxPathsPerAgent" to maxPathsPerAgent,
        "maxPathLength" to maxPathLength,
        "maxLabelsSizeBytes" to maxLabelsSizeBytes,
      ).forEach { (name, value) ->
        require(value >= 0) { "internal.$name must be >= 0 (0 = unlimited): $value" }
        logger.info { "internal.$name: ${if (value == 0) "unlimited" else value}" }
      }
    }
  }

  internal companion object {
    private val logger = logger {}

    /**
     * True when the agent gRPC port accepts any reachable peer: no per-agent identities, no legacy
     * shared token, and no mutual TLS.
     *
     * Mutual TLS counts only when TLS is actually enabled (both a certificate and a key) *and* a trust
     * store is set. The gRPC server ignores a trust store when TLS is off, so a trust store alone leaves the
     * port in plaintext with no client-certificate check and must not silence the warning.
     *
     * Extracted from the startup warning so the condition is directly testable — asserting on a log
     * line would otherwise need an appender harness. Takes the identity *count* rather than the
     * config object so the caller does not need a ConfigVals instance to evaluate it.
     */
    internal fun isAgentPortUnauthenticated(
      agentToken: String,
      authIdentityCount: Int,
      isTlsEnabled: Boolean,
      trustCertCollectionFilePath: String,
    ): Boolean {
      val isMutualTls = isTlsEnabled && trustCertCollectionFilePath.isNotEmpty()
      return agentToken.isEmpty() && authIdentityCount == 0 && !isMutualTls
    }

    /**
     * True when agents authenticate with tokens (the legacy shared token or per-agent identities) but TLS is
     * not enabled on the agent port, so every token crosses the network in plaintext.
     */
    internal fun areAgentTokensSentInCleartext(
      agentToken: String,
      authIdentityCount: Int,
      isTlsEnabled: Boolean,
    ): Boolean = (agentToken.isNotEmpty() || authIdentityCount > 0) && !isTlsEnabled

    /**
     * True when [host] is a literal wildcard address (`0.0.0.0`, `::`), which listens on every interface.
     *
     * Only a literal counts: a hostname is never resolved at startup just to decide whether to log a warning.
     */
    internal fun isWildcardAddress(host: String): Boolean {
      val literal = host.removeSurrounding("[", "]")
      return InetAddresses.isInetAddress(literal) && InetAddresses.forString(literal).isAnyLocalAddress
    }
  }
}
