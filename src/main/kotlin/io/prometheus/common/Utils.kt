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

package io.prometheus.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.pambrose.common.util.Version.Companion.versionDesc
import com.pambrose.common.util.simpleClassName
import io.grpc.Status
import io.prometheus.Proxy
import kotlinx.serialization.json.Json
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import kotlin.text.Charsets.UTF_8

internal object Utils {
  private const val REDACTED = "***"

  internal fun getVersionDesc(asJson: Boolean = false): String = Proxy::class.versionDesc(asJson)

  fun decodeParams(encodedQueryParams: String): String =
    if (encodedQueryParams.isNotBlank()) "?${URLDecoder.decode(encodedQueryParams, UTF_8)}" else ""

  /**
   * Masks secrets in a URL before it is logged or echoed back to Prometheus.
   *
   * Redacts both the userinfo component (`user:pass@host` becomes `***@host`) and every
   * query-parameter value (`?token=secret&job=x` becomes `?token=***&job=***`). Query values are
   * redacted unconditionally rather than via a key allowlist, since custom secret parameter names
   * (api_key, access_token, sig, …) cannot be enumerated in advance. The fragment is preserved.
   */
  fun sanitizeUrl(url: String): String {
    val userRedacted = url.replace(Regex("(://)[^@/?#]+@"), "$1***@")
    val queryStart = userRedacted.indexOf('?')
    if (queryStart < 0) return userRedacted
    val prefix = userRedacted.substring(0, queryStart + 1)
    val afterQuery = userRedacted.substring(queryStart + 1)
    val fragmentStart = afterQuery.indexOf('#')
    val query = if (fragmentStart < 0) afterQuery else afterQuery.substring(0, fragmentStart)
    val fragment = if (fragmentStart < 0) "" else afterQuery.substring(fragmentStart)
    return prefix + redactQueryValues(query) + fragment
  }

  /**
   * Masks the values of a bare URL-encoded query string (without a leading `?`), preserving the
   * keys. Used when logging Prometheus-supplied query params that may carry credentials.
   */
  fun sanitizeQueryParams(encodedQueryParams: String): String =
    if (encodedQueryParams.isBlank()) encodedQueryParams else redactQueryValues(encodedQueryParams)

  private fun redactQueryValues(query: String): String =
    query
      .split("&")
      .joinToString("&") { param ->
        val eq = param.indexOf('=')
        if (eq < 0) param else param.substring(0, eq + 1) + REDACTED
      }

  fun appendQueryParams(
    baseUrl: String,
    encodedQueryParams: String,
  ): String {
    if (encodedQueryParams.isBlank()) return baseUrl
    val decodedParams = URLDecoder.decode(encodedQueryParams, UTF_8)
    val separator =
      when {
        '?' !in baseUrl -> "?"
        baseUrl.endsWith("?") || baseUrl.endsWith("&") -> ""
        else -> "&"
      }
    return "$baseUrl$separator$decodedParams"
  }

  internal fun String.defaultEmptyJsonObject() = ifEmpty { "{}" }

  fun String.toJsonElement() = Json.parseToJsonElement(this)

  fun setLogLevel(
    context: String,
    logLevel: String,
  ) {
    val level =
      when (logLevel.lowercase()) {
        "all" -> Level.ALL
        "trace" -> Level.TRACE
        "debug" -> Level.DEBUG
        "info" -> Level.INFO
        "warn" -> Level.WARN
        "error" -> Level.ERROR
        "off" -> Level.OFF
        else -> throw IllegalArgumentException("Invalid $context log level: $logLevel")
      }
    val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as? Logger
      ?: run {
        LoggerFactory.getLogger(ROOT_LOGGER_NAME)
          .warn("Cannot set log level: SLF4J binding is not Logback")
        return
      }
    rootLogger.level = level
  }

  fun Status.exceptionDetails(e: Throwable) = "$code $description ${e.simpleClassName} - ${e.message}"

  data class HostPort(
    val host: String,
    val port: Int,
  )

  private fun parsePort(
    portStr: String,
    hostPort: String,
  ): Int {
    val port =
      portStr.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid port in '$hostPort': '$portStr' is not a valid integer")
    require(port in 0..65535) { "Port out of range in '$hostPort': $port (must be 0-65535)" }
    return port
  }

  fun parseHostPort(
    hostPort: String,
    defaultPort: Int,
  ): HostPort {
    require(hostPort.isNotBlank()) { "Host/port string must not be blank" }
    return when {
      // Bracketed IPv6: [::1]:50051 or [::1]
      hostPort.startsWith("[") -> {
        val closeBracket = hostPort.indexOf(']')
        when {
          closeBracket == -1 -> {
            throw IllegalArgumentException("Malformed IPv6 address in '$hostPort': missing closing bracket")
          }

          closeBracket + 1 < hostPort.length && hostPort[closeBracket + 1] == ':' -> {
            HostPort(
              hostPort.substring(1, closeBracket),
              parsePort(hostPort.substring(closeBracket + 2), hostPort),
            )
          }

          else -> {
            HostPort(hostPort.substring(1, closeBracket), defaultPort)
          }
        }
      }

      // No colon: plain hostname
      ':' !in hostPort -> {
        HostPort(hostPort, defaultPort)
      }

      // Multiple colons without brackets: unbracketed IPv6 (no port)
      hostPort.indexOf(':') != hostPort.lastIndexOf(':') -> {
        HostPort(hostPort, defaultPort)
      }

      // Single colon: host:port
      else -> {
        val colonIndex = hostPort.indexOf(':')
        HostPort(hostPort.substring(0, colonIndex), parsePort(hostPort.substring(colonIndex + 1), hostPort))
      }
    }
  }
}
