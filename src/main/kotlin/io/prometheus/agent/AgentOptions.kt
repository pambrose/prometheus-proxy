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

  @Parameter(names = ["-p", "--proxy"], description = "Proxy hostname")
  var proxyHostname = ""
    private set

  @Parameter(names = ["-n", "--name"], description = "Agent name")
  var agentName = ""
    private set

  @Parameter(names = ["-o", "--consolidated"], description = "Consolidated Agent")
  var consolidated = false
    private set

  @Parameter(names = ["--over", "--override"], description = "Override Authority")
  var overrideAuthority = ""
    private set

  @Parameter(names = ["--timeout"], description = "Scrape timeout time (seconds)")
  var scrapeTimeoutSecs = -1
    private set

  @Parameter(names = ["--max_retries"], description = "Scrape max retries")
  var scrapeMaxRetries = -1
    private set

  @Parameter(names = ["--chunk"], description = "Threshold for chunking content to Proxy and buffer size (KBs)")
  var chunkContentSizeBytes = -1
    private set

  @Parameter(names = ["--gzip"], description = "Minimum size for content to be gzipped (bytes)")
  var minGzipSizeBytes = -1
    private set

  @Parameter(names = ["--trust_all_x509"], description = "Disable SSL verification for https agent endpoints")
  var trustAllX509Certificates = false
    private set

  @Parameter(names = ["--max_concurrent_clients"], description = "Maximum number of concurrent HTTP clients")
  var maxConcurrentHttpClients = -1
    private set

  @Parameter(names = ["--client_timeout_secs"], description = "HTTP client timeout (seconds)")
  var httpClientTimeoutSecs = -1
    private set

  @Parameter(names = ["--max_cache_size"], description = "Maximum number of HTTP clients to cache")
  var maxCacheSize = -1
    private set

  @Parameter(names = ["--max_cache_age_mins"], description = "Maximum age of cached HTTP clients (minutes)")
  var maxCacheAgeMins = -1
    private set

  @Parameter(
    names = ["--max_cache_idle_mins"],
    description = "Maximum idle time before HTTP client is evicted (minutes)",
  )
  var maxCacheIdleMins = -1
    private set

  @Parameter(
    names = ["--cache_cleanup_interval_mins"],
    description = "Interval between HTTP client cache cleanup runs (minutes)",
  )
  var cacheCleanupIntervalMins = -1
    private set

  @Parameter(names = ["--keepalive_without_calls"], description = "gRPC KeepAlive without calls")
  var keepAliveWithoutCalls = false
    private set

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

    if (!consolidated)
      consolidated = CONSOLIDATED.getEnv(agentConfigVals.consolidated)
    logger.info { "consolidated: $consolidated" }

    if (scrapeTimeoutSecs == -1)
      scrapeTimeoutSecs = SCRAPE_TIMEOUT_SECS.getEnv(agentConfigVals.scrapeTimeoutSecs)
    logger.info { "scrapeTimeoutSecs: ${scrapeTimeoutSecs.seconds}" }

    if (scrapeMaxRetries == -1)
      scrapeMaxRetries = SCRAPE_MAX_RETRIES.getEnv(agentConfigVals.scrapeMaxRetries)
    logger.info { "scrapeMaxRetries: $scrapeMaxRetries" }

    if (chunkContentSizeBytes == -1)
      chunkContentSizeBytes = CHUNK_CONTENT_SIZE_KBS.getEnv(agentConfigVals.chunkContentSizeKbs)
    // Multiply the value time KB
    chunkContentSizeBytes *= 1024
    logger.info { "chunkContentSizeBytes: $chunkContentSizeBytes" }

    if (minGzipSizeBytes == -1)
      minGzipSizeBytes = MIN_GZIP_SIZE_BYTES.getEnv(agentConfigVals.minGzipSizeBytes)
    logger.info { "minGzipSizeBytes: $minGzipSizeBytes" }

    if (overrideAuthority.isEmpty())
      overrideAuthority = OVERRIDE_AUTHORITY.getEnv(agentConfigVals.tls.overrideAuthority)
    logger.info { "overrideAuthority: $overrideAuthority" }

    assignHttpClientConfigVals(agentConfigVals)

    if (!keepAliveWithoutCalls)
      keepAliveWithoutCalls = KEEPALIVE_WITHOUT_CALLS.getEnv(agentConfigVals.grpc.keepAliveWithoutCalls)
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
      if (!trustAllX509Certificates)
        trustAllX509Certificates = TRUST_ALL_X509_CERTIFICATES.getEnv(enableTrustAllX509Certificates)
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

  companion object {
    private val logger = logger {}
  }
}
