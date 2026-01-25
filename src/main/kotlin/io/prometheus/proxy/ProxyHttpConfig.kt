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

package io.prometheus.proxy

import com.github.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.logging.toLogString
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.CallLoggingConfig
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.prometheus.Proxy
import org.slf4j.event.Level

internal object ProxyHttpConfig {
  private val logger = logger {}

  fun Application.configureKtorServer(
    proxy: Proxy,
    isTestMode: Boolean,
  ) {
    install(DefaultHeaders) {
      header("X-Engine", "Ktor")
    }

    if (!isTestMode && proxy.options.configVals.proxy.http.requestLoggingEnabled)
      install(CallLogging) {
        configureCallLogging()
      }

    install(Compression) {
      configureCompression()
    }

    install(StatusPages) {
      configureStatusPages()
    }
  }

  private fun CallLoggingConfig.configureCallLogging() {
    level = Level.INFO
    filter { call -> call.request.path().startsWith("/") }
    format { call -> getFormattedLog(call) }
  }

  private fun getFormattedLog(call: ApplicationCall) =
    call.run {
      when (val status = response.status()) {
        HttpStatusCode.Found -> {
          val logMsg = request.toLogString()
          "$status: $logMsg -> ${response.headers[HttpHeaders.Location]} - ${request.origin.remoteHost}"
        }

        else -> {
          "$status: ${request.toLogString()} - ${request.origin.remoteHost}"
        }
      }
    }

  private fun CompressionConfig.configureCompression() {
    gzip {
      priority = 1.0
    }
    deflate {
      priority = 10.0
      minimumSize(1024) // condition
    }
  }

  private fun StatusPagesConfig.configureStatusPages() {
    // Catch all
    exception<Throwable> { call, cause ->
      logger.warn(cause) { "Throwable caught: ${cause.simpleClassName}" }
      call.respond(HttpStatusCode.InternalServerError)
    }

    status(NotFound) { call, cause ->
      call.respond(TextContent("${cause.value} ${cause.description}", Text.Plain.withCharset(Charsets.UTF_8), cause))
    }
  }
}
