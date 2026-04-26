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

@file:Suppress("UndocumentedPublicFunction")

package io.prometheus.common

import java.lang.System.getenv

/**
 * Environment variables recognized by the Proxy and Agent processes.
 *
 * Values are resolved with the precedence: CLI argument → environment variable → config file → built-in default.
 * Use [getEnv] to read a variable with a typed default; missing values fall through to the supplied default,
 * and malformed numeric or boolean values raise [IllegalArgumentException].
 */
enum class EnvVars {
  // ---- Proxy ----

  /** Path or URL to the Proxy HOCON config file. Equivalent to the `--config`/`-c` CLI flag. */
  PROXY_CONFIG,

  /** TCP port the Proxy serves proxied scrape requests on (the port Prometheus connects to). Default `8080`. */
  PROXY_PORT,

  /** TCP port the Proxy listens on for incoming gRPC connections from agents. Default `50051`. */
  AGENT_PORT,

  /**
   * Enable Prometheus HTTP service-discovery on the Proxy. When `true` the Proxy serves a discovery document
   * listing currently registered agent paths. Default `false`.
   */
  SD_ENABLED,

  /** HTTP path the Proxy exposes the service-discovery document on (relative to the Proxy HTTP port). */
  SD_PATH,

  /** Base URL used to build per-target entries in the service-discovery document (e.g. `http://proxy:8080/`). */
  SD_TARGET_PREFIX,

  /** Disable the gRPC server reflection service on the Proxy. Default `false` (reflection enabled). */
  REFLECTION_DISABLED,

  /** gRPC handshake timeout for the Proxy server, in seconds. `-1` uses the gRPC default. */
  HANDSHAKE_TIMEOUT_SECS,

  /** If `true`, allow gRPC keepalive pings from agents even when no RPCs are in-flight. Default `false`. */
  PERMIT_KEEPALIVE_WITHOUT_CALLS,

  /** Minimum interval the Proxy will accept gRPC keepalive pings from agents, in seconds. */
  PERMIT_KEEPALIVE_TIME_SECS,

  /** gRPC server `MAX_CONNECTION_IDLE` in seconds — idle connections are closed after this period. `-1` disables. */
  MAX_CONNECTION_IDLE_SECS,

  /** gRPC server `MAX_CONNECTION_AGE` in seconds — connections older than this are gracefully closed. `-1` disables. */
  MAX_CONNECTION_AGE_SECS,

  /** Grace period after [MAX_CONNECTION_AGE_SECS] before forced close, in seconds. `-1` disables. */
  MAX_CONNECTION_AGE_GRACE_SECS,

  /**
   * Proxy log level: one of `all`, `trace`, `debug`, `info`, `warn`, `error`, `off`.
   * Empty leaves the configured level unchanged.
   */
  PROXY_LOG_LEVEL,

  // ---- Agent ----

  /** Path or URL to the Agent HOCON config file. Equivalent to the `--config`/`-c` CLI flag. */
  AGENT_CONFIG,

  /** Hostname (or `host:port`) of the Proxy the Agent should connect to. */
  PROXY_HOSTNAME,

  /** Friendly name for this Agent reported in metrics and logs. */
  AGENT_NAME,

  /**
   * Run the Agent in *consolidated* mode, allowing multiple agents to register the same path for redundancy.
   * Default `false`.
   */
  CONSOLIDATED,

  /** Per-scrape timeout when the Agent fetches the underlying metrics endpoint, in seconds. */
  SCRAPE_TIMEOUT_SECS,

  /** Maximum number of retries the Agent attempts on a failed scrape before giving up. `0` disables retries. */
  SCRAPE_MAX_RETRIES,

  /**
   * Threshold above which a scrape response is split into chunked gRPC messages, in kilobytes.
   * Also doubles as the chunk buffer size. Default `32`.
   */
  CHUNK_CONTENT_SIZE_KBS,

  /** Scrape responses larger than this size are gzipped before being streamed to the Proxy, in bytes. */
  MIN_GZIP_SIZE_BYTES,

  /**
   * Disable X.509 certificate verification for HTTPS scrape targets. Insecure — only enable for self-signed
   * internal endpoints. Default `false`.
   */
  TRUST_ALL_X509_CERTIFICATES,

  /** Maximum number of concurrent HTTP scrape requests the Agent will issue. */
  MAX_CONCURRENT_CLIENTS,

  /** HTTP client request timeout used by the Agent when scraping endpoints, in seconds. */
  CLIENT_TIMEOUT_SECS,

  /** Maximum number of pooled HTTP clients the Agent caches (keyed by auth credentials / target). */
  MAX_CLIENT_CACHE_SIZE,

  /** Maximum age before a cached HTTP client is evicted, in minutes. */
  MAX_CLIENT_CACHE_AGE_MINS,

  /** Maximum idle time before a cached HTTP client is evicted, in minutes. */
  MAX_CLIENT_CACHE_IDLE_MINS,

  /** Interval between HTTP-client-cache cleanup sweeps, in minutes. */
  CLIENT_CACHE_CLEANUP_INTERVAL_MINS,

  /** If `true`, the Agent sends gRPC keepalive pings even when no RPCs are in-flight. Default `false`. */
  KEEPALIVE_WITHOUT_CALLS,

  /** Per-call deadline applied to unary gRPC calls from the Agent to the Proxy, in seconds. */
  UNARY_DEADLINE_SECS,

  /**
   * Agent log level: one of `all`, `trace`, `debug`, `info`, `warn`, `error`, `off`.
   * Empty leaves the configured level unchanged.
   */
  AGENT_LOG_LEVEL,

  // ---- Common (apply to both Proxy and Agent) ----

  /** Enable the `/debug` admin servlet on whichever process this variable is read by. Default `false`. */
  DEBUG_ENABLED,

  /** Enable the Prometheus metrics endpoint on this process. Default `false`. */
  METRICS_ENABLED,

  /** Listen port for this process's Prometheus metrics endpoint. */
  METRICS_PORT,

  /** Enable the admin servlets (ping, version, healthcheck, threaddump) on this process. Default `false`. */
  ADMIN_ENABLED,

  /** Listen port for this process's admin servlets. */
  ADMIN_PORT,

  /**
   * Disable the gRPC transport filter that records remote-peer information per call. Set `true` when running
   * behind an L7 reverse proxy (e.g. nginx) that strips this information. Default `false`.
   */
  TRANSPORT_FILTER_DISABLED,

  /** Filesystem path to the TLS certificate chain file (PEM). Empty disables TLS on this side. */
  CERT_CHAIN_FILE_PATH,

  /** Filesystem path to the TLS private key file (PEM). Empty disables TLS on this side. */
  PRIVATE_KEY_FILE_PATH,

  /** Filesystem path to the trust-store certificate collection (PEM) used to validate the peer. */
  TRUST_CERT_COLLECTION_FILE_PATH,

  /**
   * TLS authority override for the Agent's gRPC channel.
   * Useful when the Proxy hostname does not match its certificate SAN.
   */
  OVERRIDE_AUTHORITY,

  /** gRPC keepalive ping interval, in seconds. `-1` uses the gRPC default. */
  KEEPALIVE_TIME_SECS,

  /** gRPC keepalive ping timeout, in seconds. `-1` uses the gRPC default. */
  KEEPALIVE_TIMEOUT_SECS,
  ;

  fun getEnv(defaultVal: String) = getenv(name) ?: defaultVal

  fun getEnv(defaultVal: Boolean): Boolean {
    val value = getenv(name) ?: return defaultVal
    return parseBooleanStrict(name, value)
  }

  fun getEnv(defaultVal: Int): Int {
    val value = getenv(name) ?: return defaultVal
    return value.toIntOrNull()
      ?: throw IllegalArgumentException("Environment variable $name has invalid integer value: '$value'")
  }

  fun getEnv(defaultVal: Long): Long {
    val value = getenv(name) ?: return defaultVal
    return value.toLongOrNull()
      ?: throw IllegalArgumentException("Environment variable $name has invalid long value: '$value'")
  }

  internal companion object {
    internal fun parseBooleanStrict(
      envName: String,
      value: String,
    ): Boolean =
      when (value.lowercase()) {
        "true" -> true

        "false" -> false

        else -> throw IllegalArgumentException(
          "Environment variable $envName has invalid boolean value: '$value' (expected 'true' or 'false')",
        )
      }
  }
}
