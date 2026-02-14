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

package io.prometheus.common

import java.lang.System.getenv

enum class EnvVars {
  // Proxy
  PROXY_CONFIG,
  PROXY_PORT,
  AGENT_PORT,
  SD_ENABLED,
  SD_PATH,
  SD_TARGET_PREFIX,
  REFLECTION_DISABLED,

  HANDSHAKE_TIMEOUT_SECS,
  PERMIT_KEEPALIVE_WITHOUT_CALLS,
  PERMIT_KEEPALIVE_TIME_SECS,
  MAX_CONNECTION_IDLE_SECS,
  MAX_CONNECTION_AGE_SECS,
  MAX_CONNECTION_AGE_GRACE_SECS,
  PROXY_LOG_LEVEL,

  // Agent
  AGENT_CONFIG,
  PROXY_HOSTNAME,
  AGENT_NAME,
  CONSOLIDATED,
  SCRAPE_TIMEOUT_SECS,
  SCRAPE_MAX_RETRIES,
  CHUNK_CONTENT_SIZE_KBS,
  MIN_GZIP_SIZE_BYTES,

  TRUST_ALL_X509_CERTIFICATES,
  MAX_CONCURRENT_CLIENTS,
  CLIENT_TIMEOUT_SECS,
  MAX_CLIENT_CACHE_SIZE,
  MAX_CLIENT_CACHE_AGE_MINS,
  MAX_CLIENT_CACHE_IDLE_MINS,
  CLIENT_CACHE_CLEANUP_INTERVAL_MINS,

  KEEPALIVE_WITHOUT_CALLS,
  UNARY_DEADLINE_SECS,

  AGENT_LOG_LEVEL,

  // Common
  DEBUG_ENABLED,
  METRICS_ENABLED,
  METRICS_PORT,
  ADMIN_ENABLED,
  ADMIN_PORT,
  TRANSPORT_FILTER_DISABLED,

  CERT_CHAIN_FILE_PATH,
  PRIVATE_KEY_FILE_PATH,
  TRUST_CERT_COLLECTION_FILE_PATH,
  OVERRIDE_AUTHORITY,

  KEEPALIVE_TIME_SECS,
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

  companion object {
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
