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
import io.prometheus.common.EnvVars.DASHBOARD_ENABLED
import io.prometheus.common.EnvVars.DASHBOARD_HOST
import io.prometheus.common.EnvVars.DASHBOARD_PASSWORD
import io.prometheus.common.EnvVars.DASHBOARD_PATH
import io.prometheus.common.EnvVars.DASHBOARD_PORT
import io.prometheus.common.EnvVars.DASHBOARD_USERNAME
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
import io.prometheus.common.requireDefaultOrPositive
import io.prometheus.common.requirePositive
import io.prometheus.proxy.dashboard.PathLink

// The primary constructor is internal so parseOnly stays out of the public API, and the public constructor below keeps
// the (args) signature that code compiled against earlier releases calls.
class ProxyOptions internal constructor(
  args: Array<String>,
  // Parse the command line and handle -u/-v only, without loading the config (Proxy.main).
  parseOnly: Boolean,
) : BaseOptions(
  progName = Proxy::class.java.simpleName,
  args = args,
  envConfig = PROXY_CONFIG.name,
) {
  constructor(args: Array<String>) : this(args, parseOnly = false)

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
   * Service-discovery target: where Prometheus scrapes the advertised paths. `host:port`, default `localhost:8080`, or
   * an `http` or `https` URL, whose host and port are the target, and whose scheme and path the proxy gives each target
   * as its `__scheme__` label and a prefix of its `__metrics_path__`. It can't have user info, a query, or a fragment.
   * When [sdEnabled] is `true`, a value that doesn't parse fails startup.
   */
  @Parameter(
    names = ["--sd_target_prefix"],
    description = "Service discovery target: host:port, or an http(s) URL split into target, scheme, and path",
  )
  var sdTargetPrefix = ""
    private set

  // sdTargetPrefix parsed, or null when service discovery is off and the value doesn't parse.
  internal var sdTarget: SdTarget? = null
    private set

  // The scrape and chunk map health checks' thresholds, with 0 resolved to 80% of maxInFlightScrapeRequests.
  internal var scrapeRequestMapUnhealthySize = 0
    private set
  internal var chunkContextMapUnhealthySize = 0
    private set

  // proxy.internal.blitz.path in the form the scrape route compares, a registered path's key: without its leading
  // slashes. Config-only. The route drops every leading slash from a request's path (#416), so a path configured as
  // "/mu-....txt" was never answered, and registration reserved a spelling no agent's key has. Declared above the init
  // block that assigns it, as dashboardLinks is.
  internal var blitzPath = ""
    private set

  /**
   * Enables the Proxy's operational dashboard, served from its **own** port ([dashboardPort]) rather
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
   * `proxy.dashboard.host` config (default `0.0.0.0`)". Without a login ([dashboardPassword]) the dashboard has no
   * authentication, so enabling it on a wildcard address logs a startup warning; `127.0.0.1` keeps it reachable only
   * from the proxy host.
   */
  @Parameter(names = ["--dashboard_host"], description = "Operational dashboard listen address")
  var dashboardHost = ""
    private set

  // The Paths table's links into other tools, proxy.dashboard.links, parsed here so a template the dashboard can't use
  // fails startup. A list, so it can only be set in a config file. Declared above the init block that assigns it, or
  // its initializer would run after and empty it again.
  internal var dashboardLinks: List<PathLink> = emptyList()
    private set

  // The login the dashboard requires, from its login page or as HTTP Basic Auth: proxy.dashboard.username (default
  // "admin") and password. A password turns it on; empty, the default, leaves the dashboard open. Each resolves from
  // its env var, then its setting; there are no command-line flags, since a command line shows in `ps`. The password is
  // never logged. Declared above the init block that assigns them, as dashboardLinks is.
  internal var dashboardUsername = ""
    private set

  internal var dashboardPassword = ""
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
    parseOptions(parseOnly)
  }

  // Resolves each option from the CLI, then its env var, then the config file, and validates and logs it.
  override fun assignConfigVals() {
    val proxyConfigVals = configVals.proxy

    proxyPort = resolveIntOption(proxyPort, PROXY_PORT, proxyConfigVals.http.port)
    require(proxyPort in 1..65535) { "proxyPort must be in 1..65535: $proxyPort" }
    logger.info { "proxyPort: $proxyPort" }
    validateHttpAndInFlightLimit(proxyConfigVals)

    proxyAgentPort = resolveIntOption(proxyAgentPort, AGENT_PORT, proxyConfigVals.agent.port)
    require(proxyAgentPort in 1..65535) { "proxyAgentPort must be in 1..65535: $proxyAgentPort" }
    logger.info { "proxyAgentPort: $proxyAgentPort" }

    assignServiceDiscoveryOptions(proxyConfigVals.service.discovery)
    assignDashboardOptions(proxyConfigVals.dashboard)
    assignGrpcConfigVals(proxyConfigVals)

    with(proxyConfigVals) {
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
        certChainPem = tls.certChainPem,
        privateKeyPem = tls.privateKeyPem,
        trustCertCollectionPem = tls.trustCertCollectionPem,
      )
    }

    validateInternalConfigVals(proxyConfigVals.internal)
    assignBlitzPath(proxyConfigVals.internal.blitz)

    // Resolved after assignCommonOptions so the trust collection, as a file or PEM, reflects the CLI/env/config value.
    agentToken = resolveStringOption(agentToken, AGENT_TOKEN, proxyConfigVals.agentToken)
    // Never log the token value -- only whether one is configured.
    logger.info { "agentToken: ${if (agentToken.isEmpty()) "(none)" else "***"}" }
    // Warn only when the agent port is genuinely open to any reachable peer.
    if (isAgentPortUnauthenticated(agentToken, proxyConfigVals.auth.size, isTlsEnabled, hasTrustCertCollection)) {
      logger.warn {
        "Agent gRPC port is unauthenticated -- no agent token, per-agent identity (proxy.auth), or mutual TLS " +
          "is configured. Any reachable peer can register as an agent. Do not expose this port in production."
      }
    }
    if (areAgentTokensSentInCleartext(agentToken, proxyConfigVals.auth.size, isTlsEnabled)) {
      logger.warn {
        "Agent tokens are configured but TLS is not enabled on the agent gRPC port -- tokens are sent in " +
          "cleartext and can be captured by anyone who can observe the traffic. Set certChainFilePath and " +
          "privateKeyFilePath, or certChainPem and privateKeyPem, to enable TLS."
      }
    }

    assignLogLevel("proxy", PROXY_LOG_LEVEL, proxyConfigVals.logLevel)
  }

  private fun assignServiceDiscoveryOptions(discovery: ConfigVals.Proxy2.Service.Discovery2) {
    sdEnabled = resolveBooleanOption(sdEnabled, SD_ENABLED, discovery.enabled, "--sd_enabled")
    logger.info { "sdEnabled: $sdEnabled" }

    sdPath = resolveStringOption(sdPath, SD_PATH, discovery.path)
    if (sdEnabled)
      require(sdPath.isNotEmpty()) { "sdPath is empty" }
    logger.info { "sdPath: $sdPath" }

    sdTargetPrefix = resolveStringOption(sdTargetPrefix, SD_TARGET_PREFIX, discovery.targetPrefix)
    // Parsed here, so a value Prometheus can't use fails startup, not every refresh of every target (October #1).
    sdTarget =
      if (sdEnabled)
        SdTarget.parse(sdTargetPrefix)
      else
        runCatching { SdTarget.parse(sdTargetPrefix) }.getOrNull()
    logger.info { "sdTargetPrefix: $sdTargetPrefix" }
  }

  private fun assignDashboardOptions(dashboard: ConfigVals.Proxy2.Dashboard) {
    dashboardEnabled = resolveBooleanOption(dashboardEnabled, DASHBOARD_ENABLED, dashboard.enabled, "--dashboard")
    logger.info { "dashboardEnabled: $dashboardEnabled" }

    dashboardPort = resolveIntOption(dashboardPort, DASHBOARD_PORT, dashboard.port)
    require(dashboardPort in 1..65535) { "dashboardPort must be in 1..65535: $dashboardPort" }

    dashboardPath = resolveStringOption(dashboardPath, DASHBOARD_PATH, dashboard.path)
    dashboardHost = resolveStringOption(dashboardHost, DASHBOARD_HOST, dashboard.host)
    dashboardUsername = DASHBOARD_USERNAME.getEnv(dashboard.username)
    dashboardPassword = DASHBOARD_PASSWORD.getEnv(dashboard.password)

    if (dashboardEnabled) {
      require(dashboardPath.isNotEmpty()) { "dashboardPath is empty" }
      require(dashboardHost.isNotBlank()) { "dashboardHost is blank" }
      logger.requirePositive("dashboard.maxSessions", dashboard.maxSessions)
      // The push loop waits this long between pushes; a wait of 0 returns at once, spinning a thread.
      logger.requirePositive("dashboard.refreshIntervalSecs", dashboard.refreshIntervalSecs)
      // At 0 no path would keep a scrape, so the path table would lose its history, its last scrapes, and every path
      // whose agent has gone; a retention of 0 would drop a gone path at the next refresh.
      logger.requirePositive("dashboard.pathHistorySize", dashboard.pathHistorySize)
      logger.requirePositive("dashboard.departedPathRetentionSecs", dashboard.departedPathRetentionSecs)
      // At 0 the activity feed would keep nothing, so a refused path or a bad token would again reach only the log.
      logger.requirePositive("dashboard.activityLogSize", dashboard.activityLogSize)
      dashboardLinks = PathLink.parseAll(dashboard.links.map { PathLink.Entry(it.name, it.url, it.icon) })
      logger.info { "dashboard.links: ${dashboardLinks.joinToString { it.logged }.ifEmpty { "(none)" }}" }
      logger.info { "dashboardHost: $dashboardHost, dashboardPort: $dashboardPort, dashboardPath: $dashboardPath" }
      validateDashboardLogin(dashboardUsername, dashboardPassword)
      if (dashboardPassword.isNotEmpty()) {
        // The username was just checked for control characters, so it can't forge a log line; never the password.
        logger.info { "dashboard login: on, dashboardUsername: $dashboardUsername, dashboardPassword: ***" }
        logger.info {
          "The dashboard requires a login, over plain HTTP, so its password crosses the network readable; put TLS in " +
            "front of it, such as a reverse proxy, unless the network is trusted"
        }
      } else if (isWildcardAddress(dashboardHost)) {
        val rebindingHint =
          if (dashboard.allowedHosts.isEmpty())
            " Set proxy.dashboard.allowedHosts to refuse unknown host names."
          else
            ""
        logger.warn {
          "The dashboard has no authentication and listens on all interfaces ($dashboardHost:$dashboardPort). " +
            "Set proxy.dashboard.host (--dashboard_host) to 127.0.0.1, firewall the port, or set " +
            "proxy.dashboard.password (DASHBOARD_PASSWORD) to require a login.$rebindingHint"
        }
      }
    }
  }

  private fun assignGrpcConfigVals(proxyConfigVals: ConfigVals.Proxy2) {
    val grpc = proxyConfigVals.grpc
    reflectionDisabled =
      resolveBooleanOption(
        reflectionDisabled,
        REFLECTION_DISABLED,
        proxyConfigVals.reflectionDisabled,
        "--ref-disabled",
        "--ref_disabled",
      )
    logger.info { "reflectionDisabled: $reflectionDisabled" }

    handshakeTimeoutSecs = resolveLongOption(handshakeTimeoutSecs, HANDSHAKE_TIMEOUT_SECS, grpc.handshakeTimeoutSecs)
    logger.requireDefaultOrPositive("grpc.handshakeTimeoutSecs", handshakeTimeoutSecs, "120")

    permitKeepAliveWithoutCalls =
      resolveBooleanOption(
        permitKeepAliveWithoutCalls,
        PERMIT_KEEPALIVE_WITHOUT_CALLS,
        grpc.permitKeepAliveWithoutCalls,
        "--permit_keepalive_without_calls",
      )
    logger.info { "grpc.permitKeepAliveWithoutCalls: $permitKeepAliveWithoutCalls" }

    permitKeepAliveTimeSecs =
      resolveLongOption(permitKeepAliveTimeSecs, PERMIT_KEEPALIVE_TIME_SECS, grpc.permitKeepAliveTimeSecs)
    logger.requireDefaultOrPositive("grpc.permitKeepAliveTimeSecs", permitKeepAliveTimeSecs, "300")

    maxConnectionIdleSecs =
      resolveLongOption(maxConnectionIdleSecs, MAX_CONNECTION_IDLE_SECS, grpc.maxConnectionIdleSecs)
    logger.requireDefaultOrPositive("grpc.maxConnectionIdleSecs", maxConnectionIdleSecs, "INT_MAX")

    maxConnectionAgeSecs = resolveLongOption(maxConnectionAgeSecs, MAX_CONNECTION_AGE_SECS, grpc.maxConnectionAgeSecs)
    logger.requireDefaultOrPositive("grpc.maxConnectionAgeSecs", maxConnectionAgeSecs, "INT_MAX")

    maxConnectionAgeGraceSecs =
      resolveLongOption(maxConnectionAgeGraceSecs, MAX_CONNECTION_AGE_GRACE_SECS, grpc.maxConnectionAgeGraceSecs)
    logger.requireDefaultOrPositive("grpc.maxConnectionAgeGraceSecs", maxConnectionAgeGraceSecs, "INT_MAX")
  }

  // Config-only values (no CLI flag or env var), checked to fail fast at startup.
  private fun validateInternalConfigVals(internal: ConfigVals.Proxy2.Internal2) {
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

  // Normalized once here, so the scrape route's answer and registration's reservation compare the same key. With blitz
  // on, a path of slashes alone, or a blank one, names nothing the route can reach, and one with a slash inside it,
  // such as "a/b", can't be reached either, since the route serves one path segment; so each fails startup, as an
  // empty sdPath does with service discovery on, rather than leave blitz silently unanswered.
  private fun assignBlitzPath(blitz: ConfigVals.Proxy2.Internal2.Blitz) {
    blitzPath = blitz.path.trimStart('/')
    if (blitz.enabled) {
      require(blitzPath.isNotBlank()) { "internal.blitz.path names no path: '${blitz.path}'" }
      require('/' !in blitzPath) { "internal.blitz.path must be one path segment: '${blitz.path}'" }
      logger.info { "internal.blitz.path: $blitzPath" }
    }
  }

  // Checks for the scrape port's bind address and idle timeout, the in-flight limit and the map health checks'
  // thresholds under it, and the path-, agent-registration, and agents-per-identity limits.
  private fun validateHttpAndInFlightLimit(proxyConfigVals: ConfigVals.Proxy2) {
    val http = proxyConfigVals.http
    // A blank bind address would otherwise fail only when the scrape server starts, with an opaque Ktor error.
    require(http.host.isNotBlank()) { "proxy.http.host must not be blank" }
    logger.info { "http.host: ${http.host}" }

    // Config-only. CIO waits this long for each response, the first as soon as a connection opens, so 0 or a negative
    // value other than the -1 sentinel closed every scrape connection unanswered, while the proxy looked healthy
    // (November #1).
    logger.requireDefaultOrPositive(
      "http.idleTimeoutSecs",
      http.idleTimeoutSecs.toLong(),
      ProxyHttpService.DEFAULT_IDLE_TIMEOUT_SECS.toString(),
    )

    val internal = proxyConfigVals.internal
    // A non-positive limit would refuse every scrape.
    logger.requirePositive("internal.maxInFlightScrapeRequests", internal.maxInFlightScrapeRequests)

    // A fixed 25 turned /healthcheck unhealthy at a fraction of the load the proxy admits, so a readiness probe on it
    // flapped (October #2). The scrape map never holds more than maxInFlightScrapeRequests, so a threshold above that
    // could never trip.
    scrapeRequestMapUnhealthySize =
      resolveMapThreshold("scrapeRequestMapUnhealthySize", internal.scrapeRequestMapUnhealthySize, internal)
    chunkContextMapUnhealthySize =
      resolveMapThreshold("chunkContextMapUnhealthySize", internal.chunkContextMapUnhealthySize, internal)

    // Path- and agent-registration limits, and the per-identity agent limit (November #17): 0 turns one off, and only a
    // negative value is meaningless.
    with(internal) {
      listOf(
        "maxPathsPerAgent" to maxPathsPerAgent,
        "maxPathLength" to maxPathLength,
        "maxLabelsSizeBytes" to maxLabelsSizeBytes,
        "maxAgentFieldLength" to maxAgentFieldLength,
        "maxAgentProxyEndpoints" to maxAgentProxyEndpoints,
        "maxTargetUrlLength" to maxTargetUrlLength,
        "maxAgentsPerIdentity" to maxAgentsPerIdentity,
      ).forEach { (name, value) ->
        require(value >= 0) { "internal.$name must be >= 0 (0 = unlimited): $value" }
        logger.info { "internal.$name: ${if (value == 0) "unlimited" else value}" }
      }
    }
  }

  // A map health check's threshold: as configured, or 80% of maxInFlightScrapeRequests when 0.
  private fun resolveMapThreshold(
    name: String,
    value: Int,
    internal: ConfigVals.Proxy2.Internal2,
  ): Int {
    val maxInFlight = internal.maxInFlightScrapeRequests
    require(value in 0..maxInFlight) {
      "internal.$name must be in 0..maxInFlightScrapeRequests ($maxInFlight), 0 for 80% of it: $value"
    }
    return (if (value == 0) maxInFlight - maxInFlight / 5 else value)
      .also { logger.info { "internal.$name: $it" } }
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
      hasTrustCertCollection: Boolean,
    ): Boolean {
      val isMutualTls = isTlsEnabled && hasTrustCertCollection
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

    /**
     * Fails startup on a dashboard login the dashboard can't check, once a [password] turns it on: an empty [username],
     * a colon in it, where HTTP Basic Auth splits the username from the password, or a control character in either,
     * such as the trailing newline of a secret read from a file, which no browser would send. No message includes a
     * value. Without a password the username isn't used, so it isn't checked.
     */
    internal fun validateDashboardLogin(
      username: String,
      password: String,
    ) {
      if (password.isEmpty())
        return
      require(username.isNotEmpty()) {
        "proxy.dashboard.username (DASHBOARD_USERNAME) is empty, with proxy.dashboard.password set"
      }
      require(':' !in username) {
        "proxy.dashboard.username can't contain a colon, where HTTP Basic Auth splits it from the password"
      }
      require(username.none { it.isISOControl() }) { "proxy.dashboard.username contains a control character" }
      require(password.none { it.isISOControl() }) {
        "proxy.dashboard.password contains a control character, such as a trailing newline"
      }
    }
  }
}
