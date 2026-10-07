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

package io.prometheus.agent

import com.beust.jcommander.Parameter
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.common.BaseOptions
import io.prometheus.common.ConfigVals
import io.prometheus.common.EnvVars.AGENT_CONFIG
import io.prometheus.common.EnvVars.AGENT_LOG_LEVEL
import io.prometheus.common.EnvVars.AGENT_NAME
import io.prometheus.common.EnvVars.AGENT_TOKEN
import io.prometheus.common.EnvVars.CHUNK_CONTENT_SIZE_KBS
import io.prometheus.common.EnvVars.CLIENT_CACHE_CLEANUP_INTERVAL_MINS
import io.prometheus.common.EnvVars.CLIENT_TIMEOUT_SECS
import io.prometheus.common.EnvVars.CONSOLIDATED
import io.prometheus.common.EnvVars.HTTPS_TRUST_STORE_PASSWORD
import io.prometheus.common.EnvVars.HTTPS_TRUST_STORE_PATH
import io.prometheus.common.EnvVars.KEEPALIVE_WITHOUT_CALLS
import io.prometheus.common.EnvVars.MAX_CLIENT_CACHE_AGE_MINS
import io.prometheus.common.EnvVars.MAX_CLIENT_CACHE_IDLE_MINS
import io.prometheus.common.EnvVars.MAX_CLIENT_CACHE_SIZE
import io.prometheus.common.EnvVars.MAX_CONCURRENT_CLIENTS
import io.prometheus.common.EnvVars.MIN_GZIP_SIZE_BYTES
import io.prometheus.common.EnvVars.OVERRIDE_AUTHORITY
import io.prometheus.common.EnvVars.PROXY_HOSTNAME
import io.prometheus.common.EnvVars.SCRAPE_MAX_RETRIES
import io.prometheus.common.EnvVars.SCRAPE_TIMEOUT_SECS
import io.prometheus.common.EnvVars.TRUST_ALL_X509_CERTIFICATES
import io.prometheus.common.EnvVars.UNARY_DEADLINE_SECS
import io.prometheus.common.Utils.parseEndpointList
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.common.Utils.stripScheme
import io.prometheus.common.requirePositive
import kotlin.time.Duration.Companion.seconds

// The primary constructor is internal so parseOnly stays out of the public API, and the public constructor below keeps
// the (args, exitOnMissingConfig) signature that code compiled against earlier releases calls.
class AgentOptions internal constructor(
  args: Array<String>,
  exitOnMissingConfig: Boolean,
  // Parse the command line and handle -u/-v only, without loading the config (Agent.startSyncAgent).
  parseOnly: Boolean,
) : BaseOptions(
  progName = Agent::class.java.name,
  args = args,
  envConfig = AGENT_CONFIG.name,
  exitOnMissingConfig = exitOnMissingConfig,
  // For the Agent, exitOnMissingConfig doubles as the standalone/embedded switch: main() and
  // startSyncAgent() pass true, while embedded hosts pass false so startup failures stay catchable.
  embedded = !exitOnMissingConfig,
) {
  constructor(args: Array<String>, exitOnMissingConfig: Boolean) :
    this(args, exitOnMissingConfig, parseOnly = false)

  constructor(args: List<String>, exitOnMissingConfig: Boolean) :
    this(args.toTypedArray(), exitOnMissingConfig)

  constructor(configFilename: String, exitOnMissingConfig: Boolean) :
    this(listOf("--config", configFilename), exitOnMissingConfig)

  /**
   * Proxy address the Agent connects to. Accepts either `hostname` (port defaults to `agent.proxy.port` from
   * config, normally `50051`) or `hostname:port`.
   *
   * Accepts a **comma-separated list** for high availability — `proxy1:50051,proxy2:50051`. The agent tries
   * them in order, first successful connection wins, and rotates on a failed connect. A single value behaves
   * exactly as it always has.
   *
   * Empty means "fall back to [PROXY_HOSTNAME] env var, then `agent.proxy.endpoints` from config, then
   * `agent.proxy.hostname[:port]`".
   */
  @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname, or a comma-separated failover list")
  var proxyHostname = ""
    private set

  /**
   * Friendly name for this Agent. Surfaces in metrics labels and log lines on both Proxy and Agent.
   * Empty means "fall back to [AGENT_NAME] env var, then `agent.name` from config".
   */
  @Parameter(names = ["-n", "--name"], description = "Agent name")
  var agentName = ""
    private set

  /**
   * Run the Agent in *consolidated* mode, allowing multiple agents to register the same path on the Proxy
   * for redundancy/load-spreading. Resolved from CLI → [CONSOLIDATED] env var → `agent.consolidated` config.
   */
  @Parameter(names = ["-o", "--consolidated"], description = "Consolidated Agent")
  var consolidated = false
    private set

  /**
   * Pre-shared token presented to the Proxy on every gRPC call. Must match the Proxy's `proxy.agentToken`; the
   * Proxy rejects calls with a missing or mismatched token (`UNAUTHENTICATED`). Empty (default) sends no token.
   * Resolved from CLI → [AGENT_TOKEN] env var → `agent.agentToken` config. Never logged.
   */
  @Parameter(names = ["--agent_token"], description = "Pre-shared token presented to the Proxy")
  var agentToken = ""
    private set

  /**
   * TLS authority override for the Agent's outbound gRPC channel — useful when the Proxy hostname does not
   * match its certificate SAN (e.g. connecting through a reverse proxy or load balancer using a private DNS name).
   * Empty disables the override and gRPC validates against the Proxy hostname.
   */
  @Parameter(names = ["--over", "--override"], description = "Override Authority")
  var overrideAuthority = ""
    private set

  /**
   * Per-scrape timeout when the Agent fetches the underlying metrics endpoint, in seconds.
   * `-1` means "fall back to [SCRAPE_TIMEOUT_SECS] env var, then `agent.scrapeTimeoutSecs` config (default `15`)".
   */
  @Parameter(names = ["--timeout"], description = "Scrape timeout time (seconds)")
  var scrapeTimeoutSecs = -1
    private set

  /**
   * Maximum number of retries on a failed scrape before reporting failure to the Proxy.
   * `0` disables retries entirely. `-1` means "fall back to [SCRAPE_MAX_RETRIES] env var, then config (default `0`)".
   */
  @Parameter(names = ["--max_retries"], description = "Scrape max retries")
  var scrapeMaxRetries = -1
    private set

  /**
   * Threshold (in **kilobytes**) above which a scrape response is split into chunked gRPC messages; also the
   * chunk buffer size. This is the raw `--chunk` input value and keeps the unit of the config key/env var.
   *
   * `-1` means "fall back to [CHUNK_CONTENT_SIZE_KBS] env var, then `agent.chunkContentSizeKbs` (default `32` KB)".
   * Validated `> 0`. The byte value used at runtime is the derived [chunkContentSizeBytes].
   */
  @Parameter(names = ["--chunk"], description = "Threshold for chunking content to Proxy and buffer size (KBs)")
  var chunkContentSizeKbs = -1
    private set

  /**
   * Chunking threshold and buffer size in **bytes**, derived once from [chunkContentSizeKbs] during option
   * resolution (`chunkContentSizeKbs * 1024`). This is the value read at runtime by the gRPC client; it is
   * `-1` until [chunkContentSizeKbs] has been resolved. Validated to fit in [Int].
   */
  var chunkContentSizeBytes = -1
    private set

  /**
   * Scrape responses larger than this size are gzipped before being streamed to the Proxy, in bytes.
   * `-1` means "fall back to [MIN_GZIP_SIZE_BYTES] env var, then `agent.minGzipSizeBytes` (default `512`)".
   */
  @Parameter(names = ["--gzip"], description = "Minimum size for content to be gzipped (bytes)")
  var minGzipSizeBytes = -1
    private set

  /**
   * **Insecure.** When `true`, the Agent's HTTP client trusts every X.509 certificate presented by HTTPS
   * scrape targets — no hostname or chain validation. Intended only for self-signed internal endpoints during
   * development; logs a warning at startup. Resolved from CLI → [TRUST_ALL_X509_CERTIFICATES] env var →
   * `agent.http.enableTrustAllX509Certificates` config.
   *
   * Scope is **process-global and all-or-nothing**: enabling it to reach one self-signed endpoint disables
   * validation for *every* HTTPS target this agent scrapes. There is no per-target trust override.
   */
  @Parameter(names = ["--trust_all_x509"], description = "Disable SSL verification for https agent endpoints")
  var trustAllX509Certificates = false
    private set

  /**
   * Path to a JKS/PKCS12 trust store used to verify HTTPS scrape targets signed by a custom or private CA
   * (e.g. an internal corporate CA), without disabling validation entirely. Empty (default) uses the JDK
   * default trust store. Ignored when [trustAllX509Certificates] is enabled. Resolved from CLI →
   * [HTTPS_TRUST_STORE_PATH] env var → `agent.http.trustStorePath` config.
   */
  @Parameter(names = ["--https_truststore"], description = "Trust store (JKS/PKCS12) for HTTPS scrape targets")
  var httpsTrustStorePath = ""
    private set

  /**
   * Password for the trust store at [httpsTrustStorePath]. Empty if the store has no password. Resolved from
   * CLI → [HTTPS_TRUST_STORE_PASSWORD] env var → `agent.http.trustStorePassword` config. Never logged.
   */
  @Parameter(names = ["--https_truststore_password"], description = "Password for --https_truststore")
  var httpsTrustStorePassword = ""
    private set

  /**
   * Maximum number of concurrent HTTP scrape requests the Agent will issue across all targets.
   * `-1` means "fall back to [MAX_CONCURRENT_CLIENTS] env var, then `agent.http.maxConcurrentClients` (default `1`)".
   * Validated `> 0`.
   */
  @Parameter(names = ["--max_concurrent_clients"], description = "Maximum number of concurrent HTTP clients")
  var maxConcurrentHttpClients = -1
    private set

  /**
   * HTTP client request timeout used by the Agent when scraping endpoints, in seconds.
   * `-1` means "fall back to [CLIENT_TIMEOUT_SECS] env var, then `agent.http.clientTimeoutSecs` (default `90`)".
   * Validated `> 0`.
   */
  @Parameter(names = ["--client_timeout_secs"], description = "HTTP client timeout (seconds)")
  var httpClientTimeoutSecs = -1
    private set

  /**
   * Maximum allowed size of a scrape response, in megabytes. Larger payloads are rejected by the Agent.
   * `-1` falls back to `agent.http.maxContentLengthMBytes` from config (default `10`). Validated `> 0`.
   */
  @Parameter(
    names = ["--max_content_length_mbytes"],
    description = "Maximum allowed size of scrape response (megabytes)",
  )
  var maxContentLengthMBytes = -1
    private set

  /**
   * Maximum number of pooled HTTP clients the Agent caches (keyed by target/auth credentials).
   * `-1` means "fall back to [MAX_CLIENT_CACHE_SIZE] env var, then `agent.http.clientCache.maxSize`
   * (default `100`)". Validated `> 0`.
   */
  @Parameter(names = ["--max_cache_size"], description = "Maximum number of HTTP clients to cache")
  var maxCacheSize = -1
    private set

  /**
   * Maximum age before a cached HTTP client is evicted, in minutes (regardless of activity).
   * `-1` means "fall back to [MAX_CLIENT_CACHE_AGE_MINS] env var, then `agent.http.clientCache.maxAgeMins`
   * (default `30`)". Validated `> 0`.
   */
  @Parameter(names = ["--max_cache_age_mins"], description = "Maximum age of cached HTTP clients (minutes)")
  var maxCacheAgeMins = -1
    private set

  /**
   * Maximum idle time before a cached HTTP client is evicted, in minutes.
   * `-1` means "fall back to [MAX_CLIENT_CACHE_IDLE_MINS] env var, then `agent.http.clientCache.maxIdleMins`
   * (default `10`)". Validated `> 0`.
   */
  @Parameter(
    names = ["--max_cache_idle_mins"],
    description = "Maximum idle time before HTTP client is evicted (minutes)",
  )
  var maxCacheIdleMins = -1
    private set

  /**
   * Interval between HTTP-client-cache cleanup sweeps, in minutes.
   * `-1` means "fall back to [CLIENT_CACHE_CLEANUP_INTERVAL_MINS] env var, then
   * `agent.http.clientCache.cleanupIntervalMins` (default `5`)". Validated `> 0`.
   */
  @Parameter(
    names = ["--cache_cleanup_interval_mins"],
    description = "Interval between HTTP client cache cleanup runs (minutes)",
  )
  var cacheCleanupIntervalMins = -1
    private set

  /**
   * If `true`, the Agent sends gRPC keepalive pings to the Proxy even when no RPCs are in-flight — useful when
   * the connection traverses a load balancer or NAT that idles out silent TCP sessions. The Proxy must permit
   * this via `--permit_keepalive_without_calls` or it will reject the pings as a protocol violation.
   */
  @Parameter(names = ["--keepalive_without_calls"], description = "gRPC KeepAlive without calls")
  var keepAliveWithoutCalls = false
    private set

  /**
   * Per-call deadline applied to unary gRPC calls (e.g. `registerAgent`, `registerPath`) from Agent to Proxy,
   * in seconds. Streaming RPCs are not affected.
   * `-1` means "fall back to [UNARY_DEADLINE_SECS] env var, then `agent.grpc.unaryDeadlineSecs` (default `30`)".
   */
  @Parameter(names = ["--unary_deadline_secs"], description = "gRPC Unary deadline (seconds)")
  var unaryDeadlineSecs = -1
    private set

  init {
    parseOptions(parseOnly)
  }

  // Resolves each option from the CLI, then its env var, then the config file, and validates and logs it.
  override fun assignConfigVals() {
    val agentConfigVals = configVals.agent

    assignProxyHostname(agentConfigVals.proxy)

    agentName = resolveStringOption(agentName, AGENT_NAME, agentConfigVals.name)
    logger.info { "agentName: $agentName" }

    consolidated =
      resolveBooleanOption(consolidated, CONSOLIDATED, agentConfigVals.consolidated, "-o", "--consolidated")
    logger.info { "consolidated: $consolidated" }

    agentToken = resolveStringOption(agentToken, AGENT_TOKEN, agentConfigVals.agentToken)
    // Never log the token value -- only whether one is configured.
    logger.info { "agentToken: ${if (agentToken.isEmpty()) "(none)" else "***"}" }

    assignScrapeConfigVals(agentConfigVals)

    overrideAuthority =
      resolveStringOption(overrideAuthority, OVERRIDE_AUTHORITY, agentConfigVals.tls.overrideAuthority)
    logger.info { "overrideAuthority: $overrideAuthority" }

    assignHttpClientConfigVals(agentConfigVals.http)
    assignGrpcConfigVals(agentConfigVals.grpc)

    with(agentConfigVals) {
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
    }

    // Checked here, after assignCommonOptions resolves the TLS paths.
    if (isAgentTokenSentInCleartext(agentToken, isTlsEnabled, trustCertCollectionFilePath)) {
      logger.warn {
        "agentToken is configured but TLS is not -- the token is sent to the proxy in cleartext and can be " +
          "captured by anyone who can observe the traffic. Set trustCertCollectionFilePath to use TLS."
      }
    }

    validateInternalConfigVals(agentConfigVals.internal)
    validateDiscoveryConfigVals(agentConfigVals.discovery)

    assignLogLevel("agent", AGENT_LOG_LEVEL, agentConfigVals.logLevel)
  }

  private fun assignProxyHostname(proxy: ConfigVals.Agent.Proxy) {
    // agent.proxy.endpoints wins over the legacy hostname/port pair when set. They are deliberately NOT
    // merged: silently prepending hostname would strand anyone who set it and expected endpoints alone
    // to apply. The single hostname is promoted to a one-element list so both shapes normalize through
    // one expression -- agent.proxy.port becomes the per-entry default, and once this string reaches
    // AgentGrpcService the only default left is 50051. Built only when needed, so a malformed config entry
    // doesn't fail an agent given --proxy.
    proxyHostname =
      proxyHostname.ifEmpty {
        val entries = proxy.endpoints.ifEmpty { [proxy.hostname] }
        PROXY_HOSTNAME.getEnv(entries.joinToString(",") { parseHostPort(stripScheme(it.trim()), proxy.port).spec })
      }
    // Parse eagerly so a malformed endpoint fails at startup with a clear message rather than surfacing
    // later as a connect failure that rotation would paper over by moving to the next endpoint. Written back with
    // agent.proxy.port as the default, so a host-only entry from --proxy or PROXY_HOSTNAME gets the configured port
    // rather than the 50051 AgentGrpcService would otherwise apply.
    val endpoints = parseEndpointList(proxyHostname, proxy.port)
    proxyHostname = endpoints.joinToString(",") { it.spec }
    val failoverSuffix =
      if (endpoints.size == 1) "" else " (${endpoints.size} failover endpoints, tried in order)"
    logger.info { "proxyHostname: $proxyHostname$failoverSuffix" }
  }

  private fun assignScrapeConfigVals(agentConfigVals: ConfigVals.Agent) {
    scrapeTimeoutSecs = resolveIntOption(scrapeTimeoutSecs, SCRAPE_TIMEOUT_SECS, agentConfigVals.scrapeTimeoutSecs)
    require(scrapeTimeoutSecs > 0) { "scrapeTimeoutSecs must be > 0: $scrapeTimeoutSecs" }
    logger.info { "scrapeTimeoutSecs: ${scrapeTimeoutSecs.seconds}" }

    scrapeMaxRetries = resolveIntOption(scrapeMaxRetries, SCRAPE_MAX_RETRIES, agentConfigVals.scrapeMaxRetries)
    logger.info { "scrapeMaxRetries: $scrapeMaxRetries" }

    chunkContentSizeKbs =
      resolveIntOption(chunkContentSizeKbs, CHUNK_CONTENT_SIZE_KBS, agentConfigVals.chunkContentSizeKbs)
    logger.requirePositive("chunkContentSizeKbs", chunkContentSizeKbs)
    // Derive the byte value once into the runtime field. A chunk travels as one gRPC message, so it must fit the
    // proxy's inbound limit; computed as a Long so a huge KB value cannot overflow past the check.
    val chunkSizeAsBytes = chunkContentSizeKbs.toLong() * 1024
    require(chunkSizeAsBytes <= MAX_GRPC_PAYLOAD_BYTES) {
      "chunkContentSizeKbs value $chunkContentSizeKbs is too large (max: ${MAX_GRPC_PAYLOAD_BYTES / 1024})"
    }
    chunkContentSizeBytes = chunkSizeAsBytes.toInt()
    logger.info { "chunkContentSizeBytes: $chunkContentSizeBytes" }

    minGzipSizeBytes = resolveIntOption(minGzipSizeBytes, MIN_GZIP_SIZE_BYTES, agentConfigVals.minGzipSizeBytes)
    // 0 is valid (gzip every non-empty payload); a negative threshold would gzip everything (finding 11).
    require(minGzipSizeBytes >= 0) { "minGzipSizeBytes must be >= 0: $minGzipSizeBytes" }
    // A scrape at or below this size is sent unzipped as one message, so it has the same bound as a chunk.
    require(minGzipSizeBytes <= MAX_GRPC_PAYLOAD_BYTES) {
      "minGzipSizeBytes value $minGzipSizeBytes is too large (max: $MAX_GRPC_PAYLOAD_BYTES)"
    }
    logger.info { "minGzipSizeBytes: $minGzipSizeBytes" }
  }

  private fun assignHttpClientConfigVals(http: ConfigVals.Agent.Http) {
    assignHttpsTrustConfigVals(http)

    maxConcurrentHttpClients =
      resolveIntOption(maxConcurrentHttpClients, MAX_CONCURRENT_CLIENTS, http.maxConcurrentClients)
    logger.requirePositive("http.maxConcurrentClients", maxConcurrentHttpClients)

    httpClientTimeoutSecs = resolveIntOption(httpClientTimeoutSecs, CLIENT_TIMEOUT_SECS, http.clientTimeoutSecs)
    logger.requirePositive("http.clientTimeoutSecs", httpClientTimeoutSecs)

    // No env var for this one: --max_content_length_mbytes, else the config value.
    if (maxContentLengthMBytes == -1)
      maxContentLengthMBytes = http.maxContentLengthMBytes
    logger.requirePositive("http.maxContentLengthMBytes", maxContentLengthMBytes)

    maxCacheSize = resolveIntOption(maxCacheSize, MAX_CLIENT_CACHE_SIZE, http.clientCache.maxSize)
    logger.requirePositive("http.clientCache.maxSize", maxCacheSize)

    maxCacheAgeMins = resolveIntOption(maxCacheAgeMins, MAX_CLIENT_CACHE_AGE_MINS, http.clientCache.maxAgeMins)
    logger.requirePositive("http.clientCache.maxCacheAgeMins", maxCacheAgeMins)

    maxCacheIdleMins = resolveIntOption(maxCacheIdleMins, MAX_CLIENT_CACHE_IDLE_MINS, http.clientCache.maxIdleMins)
    logger.requirePositive("http.clientCache.maxCacheIdleMins", maxCacheIdleMins)

    cacheCleanupIntervalMins =
      resolveIntOption(
        cacheCleanupIntervalMins,
        CLIENT_CACHE_CLEANUP_INTERVAL_MINS,
        http.clientCache.cleanupIntervalMins,
      )
    logger.requirePositive("http.clientCache.cleanupIntervalMins", cacheCleanupIntervalMins)
  }

  private fun assignHttpsTrustConfigVals(http: ConfigVals.Agent.Http) {
    trustAllX509Certificates =
      resolveBooleanOption(
        trustAllX509Certificates,
        TRUST_ALL_X509_CERTIFICATES,
        http.enableTrustAllX509Certificates,
        "--trust_all_x509",
      )
    logger.info { "http.trustAllX509Certificates: $trustAllX509Certificates" }
    if (trustAllX509Certificates) {
      logger.warn {
        "X.509 certificate verification is disabled -- ALL certificates will be trusted. " +
          "Do not use this in production."
      }
    }

    httpsTrustStorePath = resolveStringOption(httpsTrustStorePath, HTTPS_TRUST_STORE_PATH, http.trustStorePath)
    httpsTrustStorePassword =
      resolveStringOption(httpsTrustStorePassword, HTTPS_TRUST_STORE_PASSWORD, http.trustStorePassword)
    // The path is safe to log; the password must never be logged.
    logger.info { "http.trustStorePath: ${httpsTrustStorePath.ifEmpty { "(JDK default)" }}" }
    // The trust-all-shadows-trust-store precedence is enforced in
    // AgentHttpService.resolveHttpsTrustManager; this only warns that the store won't take effect.
    if (trustAllX509Certificates && httpsTrustStorePath.isNotEmpty())
      logger.warn { "http.trustStorePath is ignored because trustAllX509Certificates is enabled" }
  }

  private fun assignGrpcConfigVals(grpc: ConfigVals.Agent.Grpc) {
    keepAliveWithoutCalls =
      resolveBooleanOption(
        keepAliveWithoutCalls,
        KEEPALIVE_WITHOUT_CALLS,
        grpc.keepAliveWithoutCalls,
        "--keepalive_without_calls",
      )
    logger.info { "grpc.keepAliveWithoutCalls: $keepAliveWithoutCalls" }

    unaryDeadlineSecs = resolveIntOption(unaryDeadlineSecs, UNARY_DEADLINE_SECS, grpc.unaryDeadlineSecs)
    logger.info { "grpc.unaryDeadlineSecs: $unaryDeadlineSecs" }
  }

  // Config-only values (no CLI flag or env var), checked to fail fast at startup.
  private fun validateInternalConfigVals(internal: ConfigVals.Agent.Internal) {
    logger.info { "agent.internal.cioTimeoutSecs: ${internal.cioTimeoutSecs.seconds}" }

    // heartbeatCheckPauseMillis is the poll interval of the keepalive loops in Agent.sendHeartBeats and
    // Agent.awaitDisconnectWithoutHeartBeat (heartbeat enabled and disabled). delay() returns immediately for
    // a non-positive duration, and neither loop condition suspends, so 0 spins without ever reaching
    // a cancellation check and pins an IO thread for the connection's lifetime.
    logger.requirePositive("agent.internal.heartbeatCheckPauseMillis", internal.heartbeatCheckPauseMillis)

    val inactivityVal = internal.heartbeatMaxInactivitySecs
    logger.info { "agent.internal.heartbeatMaxInactivitySecs: $inactivityVal" }

    // reconnectPauseSecs feeds RateLimiter.create(1.0 / reconnectPauseSecs) in Agent: 0 yields an
    // infinite rate (hot reconnect loop) and a negative value an opaque Guava IAE at startup (finding 11).
    logger.requirePositive("agent.internal.reconnectPauseSecs", internal.reconnectPauseSecs)

    // rejectedPathRetrySecs paces Agent.retryRejectedStaticPathsForConnection's retry loop, and is the
    // base of AgentPathManager's retry backoff; a non-positive value would spin that loop for the connection's
    // lifetime.
    logger.requirePositive("agent.internal.rejectedPathRetrySecs", internal.rejectedPathRetrySecs)

    // rejectedPathRetryMaxSecs caps that backoff (see AgentPathManager); a cap of zero or less has no meaning.
    logger.requirePositive("agent.internal.rejectedPathRetryMaxSecs", internal.rejectedPathRetryMaxSecs)

    // scrapeRequestBacklogUnhealthySize * 2 is the AgentConnectionContext channel capacity: 0 makes it
    // a rendezvous channel (every send blocks). A negative value is worse than it looks -- -1 yields
    // Channel(-2), which kotlinx maps to BUFFERED, i.e. a silent 64-slot channel rather than an error,
    // and the health check then compares the backlog against a negative threshold and reports
    // unhealthy from startup. Only values <= -2 actually throw at connect time (finding 11).
    logger.requirePositive(
      "agent.internal.scrapeRequestBacklogUnhealthySize",
      internal.scrapeRequestBacklogUnhealthySize,
    )
  }

  // Discovery misconfig fails fast like the other config-only values: an empty file path would
  // silently discover nothing, and a non-positive interval would turn the reconcile loop into a hot spin.
  private fun validateDiscoveryConfigVals(discovery: ConfigVals.Agent.Discovery) {
    if (!discovery.enabled)
      return
    require(discovery.file.path.isNotEmpty()) {
      "agent.discovery.file.path must be set when agent.discovery.enabled is true"
    }
    logger.info { "agent.discovery.file.path: ${discovery.file.path}" }
    logger.requirePositive("agent.discovery.reconcileIntervalSecs", discovery.reconcileIntervalSecs)
  }

  internal companion object {
    private val logger = logger {}

    // Default port for a proxy endpoint that does not carry one. Lives here rather than in
    // AgentGrpcService because defaults are an options concern, and AgentGrpcService already depends on
    // AgentOptions -- the reverse import would have the public options type reaching into the internals
    // of the gRPC client it configures.
    internal const val DEFAULT_GRPC_PORT = 50051

    // gRPC's default inbound message limit (4 MiB), which the proxy does not raise, less 64 KiB for the fields around
    // the payload. A chunk, or an unzipped scrape at or below minGzipSizeBytes, travels as a single message; one past
    // the limit ends the agent's write stream and drops every in-flight result on that connection.
    internal const val MAX_GRPC_PAYLOAD_BYTES = 4 * 1024 * 1024 - 64 * 1024

    /**
     * True when the agent has a token but its gRPC channel is plaintext, so the token is sent in the clear.
     *
     * Mirrors the channel choice in AgentGrpcService: the agent uses TLS when a certificate and key are set,
     * or when a trust store alone is set (server-authenticated TLS).
     */
    internal fun isAgentTokenSentInCleartext(
      agentToken: String,
      isTlsEnabled: Boolean,
      trustCertCollectionFilePath: String,
    ): Boolean = agentToken.isNotEmpty() && !isTlsEnabled && trustCertCollectionFilePath.isEmpty()
  }
}
