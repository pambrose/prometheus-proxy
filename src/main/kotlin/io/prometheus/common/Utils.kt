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
import io.github.oshai.kotlinlogging.KLogger
import io.grpc.Status
import io.prometheus.Proxy
import kotlinx.serialization.json.Json
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

internal object Utils {
  private const val REDACTED = "***"

  internal fun getVersionDesc(asJson: Boolean = false): String = Proxy::class.versionDesc(asJson)

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

  /**
   * Appends an already-URL-encoded query string (as produced by Ktor's `formUrlEncode()`, i.e. raw
   * `=`/`&` delimiters with percent-encoded keys/values) to [baseUrl].
   *
   * The encoded string is appended verbatim rather than URL-decoded first: decoding the whole blob
   * would turn an encoded delimiter inside a value (e.g. `%26`, `%23`) into a real `&`/`#`, splitting
   * one parameter into several or starting a fragment.
   */
  fun appendQueryParams(
    baseUrl: String,
    encodedQueryParams: String,
  ): String {
    if (encodedQueryParams.isBlank()) return baseUrl
    val separator =
      when {
        '?' !in baseUrl -> "?"
        baseUrl.endsWith("?") || baseUrl.endsWith("&") -> ""
        else -> "&"
      }
    return "$baseUrl$separator$encodedQueryParams"
  }

  internal fun String.defaultEmptyJsonObject() = ifEmpty { "{}" }

  fun String.toJsonElement() = Json.parseToJsonElement(this)

  fun setLogLevel(
    context: String,
    logLevel: String,
  ) {
    val level =
      when (logLevel.lowercase()) {
        // "all" is intentionally unsupported: ch.qos.logback.classic.Level.ALL is deprecated. Use "trace".
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

  // The throwable's cause chain (itself first), for detecting a wrapped cause without a hand-rolled
  // while/cause loop. Shared so the different cause-walks no longer drift (finding 34).
  fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

  // Logs a connection/stream-task failure at ERROR with the gRPC status details. Shared by the agent's
  // connection-task and stream-task failure handlers, which were byte-for-byte identical (finding 23) and
  // used apply {} only to smuggle the Status receiver for exceptionDetails() (finding 36).
  fun KLogger.logStreamFailure(
    name: String,
    e: Throwable,
  ) {
    val status = Status.fromThrowable(e)
    error(e) { "$name(): ${status.exceptionDetails(e)}" }
  }

  data class HostPort(
    val host: String,
    val port: Int,
  ) {
    /**
     * Rendering that round-trips back through [parseHostPort].
     *
     * [parseHostPort] strips the brackets off an IPv6 literal, so a naive `"$host:$port"` produces a
     * string that re-parses as a *bare* IPv6 address with no port — silently dialing the wrong
     * authority on the default port. Re-bracketing any host containing a colon makes the render the
     * exact inverse of the parse. Identical to `"$host:$port"` for every IPv4/DNS host.
     */
    val spec: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
  }

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

  // Case-insensitive counterpart to String.removePrefix, matching BaseOptions.isUrlPrefix's
  // case-insensitivity. No-ops when the prefix is absent, so chaining both schemes is safe.
  private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

  internal fun stripScheme(hostPort: String): String =
    hostPort
      .removePrefixIgnoreCase(BaseOptions.HTTPS_PREFIX)
      .removePrefixIgnoreCase(BaseOptions.HTTP_PREFIX)

  /**
   * Parses a comma-separated `host[:port]` spec into an ordered list of endpoints.
   *
   * The ordering is meaningful -- it is the failover priority -- so entries are neither sorted nor
   * de-duplicated; the list stays a faithful record of what was configured. Blank entries (a trailing
   * or doubled comma) are dropped, but a malformed entry throws rather than being skipped: silently
   * discarding it would leave rotation quietly cycling a shorter list than the operator configured.
   *
   * A single entry yields a one-element list, which is what keeps every pre-failover configuration
   * behaving exactly as it did before.
   *
   * @param spec one or more `host[:port]` entries separated by commas, each optionally scheme-prefixed
   * @param defaultPort port applied to any entry that does not carry one
   * @throws IllegalArgumentException if [spec] yields no entries, or if any entry is malformed
   */
  fun parseEndpointList(
    spec: String,
    defaultPort: Int,
  ): List<HostPort> {
    val entries = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require(entries.isNotEmpty()) { "No proxy endpoints found in '$spec'" }
    return entries.map { entry ->
      val stripped = stripScheme(entry)
      // A scheme-only entry ("http://") survives the isNotEmpty filter above but strips to nothing.
      // parseHostPort would reject it with a message naming neither the entry nor the list, so name it.
      require(stripped.isNotEmpty()) { "Proxy endpoint '$entry' in '$spec' has no host" }
      parseHostPort(stripped, defaultPort)
    }
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
