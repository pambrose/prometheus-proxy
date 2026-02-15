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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyConstants.CACHE_CONTROL_VALUE
import io.prometheus.proxy.ProxyConstants.MISSING_PATH_MSG
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

object ProxyUtils {
  private val logger = logger {}

  fun ByteArray.unzip(maxSize: Long): String {
    if (isEmpty()) return ""
    GZIPInputStream(ByteArrayInputStream(this)).use { gzis ->
      ByteArrayOutputStream().use { baos ->
        val buffer = ByteArray(1024)
        var totalBytes = 0L
        while (true) {
          val len = gzis.read(buffer)
          if (len <= 0) break
          totalBytes += len
          if (totalBytes > maxSize) {
            val msg = "Unzipped content size exceeds limit of $maxSize bytes"
            logger.error { msg }
            throw ZipBombException(msg)
          }
          baos.write(buffer, 0, len)
        }
        return baos.toString(Charsets.UTF_8.name())
      }
    }
  }

  class ZipBombException(
    message: String,
  ) : RuntimeException(message)

  fun invalidAgentContextResponse(
    path: String,
    proxy: Proxy,
  ): ResponseResults {
    val message = "Invalid AgentContext for /$path"
    proxy.logActivity(message)
    logger.error { message }
    return ResponseResults(
      statusCode = HttpStatusCode.NotFound,
      updateMsgs = listOf("invalid_agent_context"),
    )
  }

  fun invalidPathResponse(
    path: String,
    proxy: Proxy,
  ): ResponseResults {
    val message = "Invalid path request /$path"
    proxy.logActivity(message)
    logger.error { message }
    return ResponseResults(
      statusCode = HttpStatusCode.NotFound,
      updateMsgs = listOf("invalid_path"),
    )
  }

  fun emptyPathResponse(proxy: Proxy): ResponseResults {
    proxy.logActivity(MISSING_PATH_MSG)
    logger.info { MISSING_PATH_MSG }
    return ResponseResults(
      statusCode = HttpStatusCode.NotFound,
      updateMsgs = listOf("missing_path"),
    )
  }

  fun proxyNotRunningResponse(): ResponseResults {
    logger.error { "Proxy stopped" }
    return ResponseResults(
      statusCode = HttpStatusCode.ServiceUnavailable,
      updateMsgs = listOf("proxy_stopped"),
    )
  }

  fun incrementScrapeRequestCount(
    proxy: Proxy,
    type: String,
  ) {
    if (type.isNotEmpty()) proxy.metrics { scrapeRequestCount.labels(type).inc() }
  }

  suspend fun ApplicationCall.respondWith(
    text: String,
    contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)
    respondText(text, contentType, status)
  }
}
