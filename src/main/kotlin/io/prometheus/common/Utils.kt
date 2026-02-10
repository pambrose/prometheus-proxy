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

package io.prometheus.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.JCommander
import com.github.pambrose.common.util.Version.Companion.versionDesc
import com.github.pambrose.common.util.simpleClassName
import io.grpc.Status
import io.prometheus.Proxy
import kotlinx.serialization.json.Json
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8

object Utils {
  internal fun getVersionDesc(asJson: Boolean = false): String = Proxy::class.versionDesc(asJson)

  internal class VersionValidator : IParameterValidator {
    override fun validate(
      name: String,
      value: String,
    ) {
      val console = JCommander().console
      console.println(getVersionDesc(false))
      exitProcess(0)
    }
  }

  fun decodeParams(encodedQueryParams: String): String =
    if (encodedQueryParams.isNotBlank()) "?${URLDecoder.decode(encodedQueryParams, UTF_8.name())}" else ""

  internal fun String.defaultEmptyJsonObject() = ifEmpty { "{}" }

  fun String.toJsonElement() = Json.parseToJsonElement(this)

  fun setLogLevel(
    context: String,
    logLevel: String,
  ) {
    val level =
      when (logLevel.lowercase()) {
        "trace" -> Level.TRACE
        "debug" -> Level.DEBUG
        "info" -> Level.INFO
        "warn" -> Level.WARN
        "error" -> Level.ERROR
        "off" -> Level.OFF
        else -> throw IllegalArgumentException("Invalid $context log level: $logLevel")
      }
    val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
    rootLogger.level = level
  }

  fun Status.exceptionDetails(e: Throwable) = "$code $description ${e.simpleClassName} - ${e.message}"

  data class HostPort(
    val host: String,
    val port: Int,
  )

  fun parseHostPort(
    hostPort: String,
    defaultPort: Int,
  ): HostPort =
    when {
      // Bracketed IPv6: [::1]:50051 or [::1]
      hostPort.startsWith("[") -> {
        val closeBracket = hostPort.indexOf(']')
        when {
          closeBracket == -1 -> {
            HostPort(hostPort, defaultPort)
          }

          closeBracket + 1 < hostPort.length && hostPort[closeBracket + 1] == ':' -> {
            HostPort(
              hostPort.substring(0, closeBracket + 1),
              hostPort.substring(closeBracket + 2).toInt(),
            )
          }

          else -> {
            HostPort(hostPort.substring(0, closeBracket + 1), defaultPort)
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
        HostPort(hostPort.substring(0, colonIndex), hostPort.substring(colonIndex + 1).toInt())
      }
    }
}
