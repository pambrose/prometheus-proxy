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

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyConstants.CACHE_CONTROL_VALUE
import io.prometheus.proxy.ProxyConstants.MISSING_PATH_MSG
import mu.two.KLogger

object ProxyUtils {
  fun invalidAgentContextResponse(
    path: String,
    proxy: Proxy,
    logger: KLogger,
    responseResults: ResponseResults,
  ) {
    updateResponse(
      message = "Invalid AgentContext for /$path",
      proxy = proxy,
      logger = logger,
      logLevel = KLogger::error,
      responseResults = responseResults,
      updateMsg = "invalid_agent_context",
      statusCode = HttpStatusCode.NotFound,
    )
  }

  fun invalidPathResponse(
    path: String,
    proxy: Proxy,
    logger: KLogger,
    responseResults: ResponseResults,
  ) {
    updateResponse(
      message = "Invalid path request /$path",
      proxy = proxy,
      logger = logger,
      logLevel = KLogger::info,
      responseResults = responseResults,
      updateMsg = "invalid_path",
      statusCode = HttpStatusCode.NotFound,
    )
  }

  fun emptyPathResponse(
    proxy: Proxy,
    logger: KLogger,
    responseResults: ResponseResults,
  ) {
    updateResponse(
      message = MISSING_PATH_MSG,
      proxy = proxy,
      logger = logger,
      logLevel = KLogger::info,
      responseResults = responseResults,
      updateMsg = "missing_path",
      statusCode = HttpStatusCode.NotFound,
    )
  }

  fun proxyNotRunningResponse(
    logger: KLogger,
    responseResults: ResponseResults,
  ) {
    updateResponse(
      message = "Proxy stopped",
      proxy = null,
      logger = logger,
      logLevel = KLogger::error,
      responseResults = responseResults,
      updateMsg = "proxy_stopped",
      statusCode = HttpStatusCode.ServiceUnavailable,
    )
  }

  private fun updateResponse(
    message: String,
    proxy: Proxy?,
    logger: KLogger,
    logLevel: (KLogger, String) -> Unit,
    responseResults: ResponseResults,
    updateMsg: String,
    statusCode: HttpStatusCode,
  ) {
    proxy?.logActivity(message)
    logLevel(logger, message)
    responseResults.apply {
      this.updateMsg = updateMsg
      this.statusCode = statusCode
    }
  }

  fun incrementScrapeRequestCount(
    proxy: Proxy,
    type: String,
  ) {
    if (type.isNotEmpty()) proxy.metrics { scrapeRequestCount.labels(type).inc() }
  }

  suspend fun ApplicationCall.respondWith(
    text: String,
    contentType: ContentType = ContentType.Text.Plain,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)
    response.status(status)
    respondText(text, contentType, status)
  }
}
