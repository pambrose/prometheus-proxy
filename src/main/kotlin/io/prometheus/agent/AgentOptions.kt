/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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
import io.prometheus.common.EnvVars.CHUNK_CONTENT_SIZE_KBS
import io.prometheus.common.EnvVars.CLIENT_CACHE_CLEANUP_INTERVAL_MINS
import io.prometheus.common.EnvVars.CLIENT_TIMEOUT_SECS
import io.prometheus.common.EnvVars.CONSOLIDATED
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
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.common.Utils.setLogLevel
import kotlin.time.Duration.Companion.seconds

class AgentOptions(
  args: Array<String>,
  exitOnMissingConfig: Boolean,
) : BaseOptions(Agent::class.java.name, args, AGENT_CONFIG.name, exitOnMissingConfig) {
  constructor(args: List<String>, exitOnMissingConfig: Boolean) :
    this(args.toTypedArray(), exitOnMissingConfig)

  constructor(configFilename: String, exitOnMissingConfig: Boolean) :
    this(listOf("--config", configFilename), exitOnMissingConfig)

  /**
   * Proxy address the Agent connects to. Accepts either `hostname` (port defaults to `agent.proxy.port` from
   * config, normally `50051`) or `hostname:port`.
   * Empty means "fall back to [PROXY_HOSTNAME] env var, then `agent.proxy.hostname[:port]` from config".
   */
  @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname")
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
   * Threshold above which a scrape response is split into chunked gRPC messages, expressed in **kilobytes**
   * on input. After resolution, the field is converted in-place to **bytes** for runtime use; the
   * `chunkContentSizeBytes` name reflects that final unit.
   *
   * `-1` means "fall back to [CHUNK_CONTENT_SIZE_KBS] env var, then `agent.chunkContentSizeKbs` (default `32` KB)".
   * Validated `> 0` and that the resulting byte value fits in [Int].
   */
  @Parameter(names = ["--chunk"], description = "Threshold for chunking content to Proxy and buffer size (KBs)")
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
   */
  @Parameter(names = ["--trust_all_x509"], description = "Disable SSL verification for https agent endpoints")
  var trustAllX509Certificates = false
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
    parseOptions()
  }

  override fun assignConfigVals() {
    val agentConfigVals = configVals.agent

    if (proxyHostname.isEmpty()) {
      val configHostname = agentConfigVals.proxy.hostname
      val defaultPort = agentConfigVals.proxy.port
      val parsed = parseHostPort(configHostname, defaultPort)
      val str = "${parsed.host}:${parsed.port}"
      proxyHostname = PROXY_HOSTNAME.getEnv(str)
    }
    logger.info { "proxyHostname: $proxyHostname" }

    if (agentName.isEmpty())
      agentName = AGENT_NAME.getEnv(agentConfigVals.name)
    logger.info { "agentName: $agentName" }

    consolidated =
      resolveBooleanOption(consolidated, CONSOLIDATED, agentConfigVals.consolidated, "-o", "--consolidated")
    logger.info { "consolidated: $consolidated" }

    if (scrapeTimeoutSecs == -1)
      scrapeTimeoutSecs = SCRAPE_TIMEOUT_SECS.getEnv(agentConfigVals.scrapeTimeoutSecs)
    logger.info { "scrapeTimeoutSecs: ${scrapeTimeoutSecs.seconds}" }

    if (scrapeMaxRetries == -1)
      scrapeMaxRetries = SCRAPE_MAX_RETRIES.getEnv(agentConfigVals.scrapeMaxRetries)
    logger.info { "scrapeMaxRetries: $scrapeMaxRetries" }

    if (chunkContentSizeBytes == -1)
      chunkContentSizeBytes = CHUNK_CONTENT_SIZE_KBS.getEnv(agentConfigVals.chunkContentSizeKbs)
    require(chunkContentSizeBytes > 0) { "chunkContentSizeKbs must be > 0: ($chunkContentSizeBytes)" }
    // Convert KB value to bytes with overflow protection
    val chunkSizeAsBytes = chunkContentSizeBytes.toLong() * 1024
    require(chunkSizeAsBytes <= Int.MAX_VALUE) {
      "chunkContentSizeKbs value $chunkContentSizeBytes is too large (max: ${Int.MAX_VALUE / 1024})"
    }
    chunkContentSizeBytes = chunkSizeAsBytes.toInt()
    logger.info { "chunkContentSizeBytes: $chunkContentSizeBytes" }

    if (minGzipSizeBytes == -1)
      minGzipSizeBytes = MIN_GZIP_SIZE_BYTES.getEnv(agentConfigVals.minGzipSizeBytes)
    logger.info { "minGzipSizeBytes: $minGzipSizeBytes" }

    if (overrideAuthority.isEmpty())
      overrideAuthority = OVERRIDE_AUTHORITY.getEnv(agentConfigVals.tls.overrideAuthority)
    logger.info { "overrideAuthority: $overrideAuthority" }

    assignHttpClientConfigVals(agentConfigVals)

    keepAliveWithoutCalls =
      resolveBooleanOption(
        keepAliveWithoutCalls,
        KEEPALIVE_WITHOUT_CALLS,
        agentConfigVals.grpc.keepAliveWithoutCalls,
        "--keepalive_without_calls",
      )
    logger.info { "grpc.keepAliveWithoutCalls: $keepAliveWithoutCalls" }

    if (unaryDeadlineSecs == -1)
      unaryDeadlineSecs = UNARY_DEADLINE_SECS.getEnv(agentConfigVals.grpc.unaryDeadlineSecs)
    logger.info { "grpc.unaryDeadlineSecs: $unaryDeadlineSecs" }

    agentConfigVals.apply {
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
      validateTlsConfig()

      logger.info { "scrapeTimeoutSecs: ${scrapeTimeoutSecs.seconds}" }
      logger.info { "agent.internal.cioTimeoutSecs: ${internal.cioTimeoutSecs.seconds}" }

      val pauseVal = internal.heartbeatCheckPauseMillis
      logger.info { "agent.internal.heartbeatCheckPauseMillis: $pauseVal" }

      val inactivityVal = internal.heartbeatMaxInactivitySecs
      logger.info { "agent.internal.heartbeatMaxInactivitySecs: $inactivityVal" }
    }

    if (logLevel.isEmpty())
      logLevel = AGENT_LOG_LEVEL.getEnv(agentConfigVals.logLevel)
    if (logLevel.isNotEmpty()) {
      logger.info { "agent.logLevel: $logLevel" }
      setLogLevel("agent", logLevel)
    } else {
      logger.info { "agent.logLevel: info" }
    }
  }

  private fun assignHttpClientConfigVals(agentConfigVals: ConfigVals.Agent) {
    agentConfigVals.http.apply {
      trustAllX509Certificates =
        resolveBooleanOption(
          trustAllX509Certificates,
          TRUST_ALL_X509_CERTIFICATES,
          enableTrustAllX509Certificates,
          "--trust_all_x509",
        )
      logger.info { "http.trustAllX509Certificates: $trustAllX509Certificates" }
      if (trustAllX509Certificates) {
        logger.warn {
          "X.509 certificate verification is disabled -- ALL certificates will be trusted. " +
            "Do not use this in production."
        }
      }

      if (maxConcurrentHttpClients == -1)
        maxConcurrentHttpClients = MAX_CONCURRENT_CLIENTS.getEnv(maxConcurrentClients)
      require(maxConcurrentHttpClients > 0) { "http.maxConcurrentClients must be > 0" }
      logger.info { "http.maxConcurrentClients: $maxConcurrentHttpClients" }

      if (httpClientTimeoutSecs == -1)
        httpClientTimeoutSecs = CLIENT_TIMEOUT_SECS.getEnv(clientTimeoutSecs)
      require(httpClientTimeoutSecs > 0) { "http.clientTimeoutSecs must be > 0" }
      logger.info { "http.clientTimeoutSecs: $httpClientTimeoutSecs" }

      if (this@AgentOptions.maxContentLengthMBytes == -1)
        this@AgentOptions.maxContentLengthMBytes = agentConfigVals.http.maxContentLengthMBytes
      require(this@AgentOptions.maxContentLengthMBytes > 0) { "http.maxContentLengthMBytes must be > 0" }
      logger.info { "http.maxContentLengthMBytes: ${this@AgentOptions.maxContentLengthMBytes}" }

      if (maxCacheSize == -1)
        maxCacheSize = MAX_CLIENT_CACHE_SIZE.getEnv(clientCache.maxSize)
      require(maxCacheSize > 0) { "http.clientCache.maxSize must be > 0: ($maxCacheSize)" }
      logger.info { "http.clientCache.maxSize: $maxCacheSize" }

      if (maxCacheAgeMins == -1)
        maxCacheAgeMins = MAX_CLIENT_CACHE_AGE_MINS.getEnv(clientCache.maxAgeMins)
      require(maxCacheAgeMins > 0) { "http.clientCache.maxCacheAgeMins must be > 0: ($maxCacheAgeMins)" }
      logger.info { "http.clientCache.maxCacheAgeMins: $maxCacheAgeMins" }

      if (maxCacheIdleMins == -1)
        maxCacheIdleMins = MAX_CLIENT_CACHE_IDLE_MINS.getEnv(clientCache.maxIdleMins)
      require(maxCacheIdleMins > 0) { "http.clientCache.maxCacheIdleMins must be > 0: ($maxCacheIdleMins)" }
      logger.info { "http.clientCache.maxCacheIdleMins: $maxCacheIdleMins" }

      if (cacheCleanupIntervalMins == -1)
        cacheCleanupIntervalMins = CLIENT_CACHE_CLEANUP_INTERVAL_MINS.getEnv(clientCache.cleanupIntervalMins)
      require(cacheCleanupIntervalMins > 0) {
        "http.clientCache.cleanupIntervalMins must be > 0: ($cacheCleanupIntervalMins)"
      }
      logger.info { "http.clientCache.cleanupIntervalMins: $cacheCleanupIntervalMins" }
    }
  }

  internal companion object {
    private val logger = logger {}
  }
}
