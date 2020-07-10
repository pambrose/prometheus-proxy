/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.dsl.KtorDsl.withHttpClient
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.zip
import com.google.common.net.HttpHeaders
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readText
import io.ktor.http.isSuccess
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ScrapeRequest
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import mu.KLogging
import java.io.IOException
import java.net.URLDecoder

internal class AgentHttpService(val agent: Agent) {

  suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResults =
    ScrapeResults(agentId = request.agentId, scrapeId = request.scrapeId).also { scrapeResults ->
      val scrapeMsg = atomic("")
      val path = request.path
      val encodedQueryParams = request.encodedQueryParams
      val pathContext = agent.pathManager[path]

      if (pathContext == null) {
        logger.warn { "Invalid path in fetchScrapeUrl(): $path" }
        scrapeMsg.value = "invalid_path"
        if (request.debugEnabled)
          scrapeResults.setDebugInfo("None", "Invalid path: $path")
      }
      else {
        val requestTimer = if (agent.isMetricsEnabled) agent.startTimer() else null
        // Add the incoming query params to the url
        val url = pathContext.url +
                  (if (encodedQueryParams.isNotEmpty())
                    "?${URLDecoder.decode(encodedQueryParams, Charsets.UTF_8.name())}"
                  else "")

        logger.debug { "Fetching $pathContext" }
        if (encodedQueryParams.isNotEmpty())
          logger.debug { "URL: $url" }

        // Content is fetched here
        try {
          withHttpClient {
            get(url, setup(request), getBlock(url, scrapeResults, scrapeMsg, request.debugEnabled))
          }
        }
        catch (e: IOException) {
          logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        }
        catch (e: Throwable) {
          logger.warn(e) { "fetchScrapeUrl() $e - $url" }
          if (request.debugEnabled)
            scrapeResults.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
        }
        finally {
          requestTimer?.observeDuration()
        }
      }

      agent.updateScrapeCounter(scrapeMsg.value)
    }

  private fun setup(request: ScrapeRequest): HttpRequestBuilder.() -> Unit = {
    val accept: String? = request.accept
    if (accept?.isNotEmpty() == true)
      header(HttpHeaders.ACCEPT, accept)
  }

  private fun getBlock(url: String,
                       responseArg: ScrapeResults,
                       scrapeCounterMsg: AtomicRef<String>,
                       debugEnabled: Boolean): suspend (HttpResponse) -> Unit =
    { response ->
      responseArg.statusCode = response.status.value

      if (response.status.isSuccess()) {
        responseArg.apply {
          contentType = response.headers[HttpHeaders.CONTENT_TYPE].orEmpty()
          // Zip the content here
          val content = response.readText()
          zipped = content.length > agent.configVals.agent.minGzipSizeBytes
          if (zipped)
            contentAsZipped = content.zip()
          else
            contentAsText = content
          validResponse = true
        }
        if (debugEnabled)
          responseArg.setDebugInfo(url)
        scrapeCounterMsg.value = "success"
      }
      else {
        if (debugEnabled)
          responseArg.setDebugInfo(url, "Unsuccessful response code ${responseArg.statusCode}")
        scrapeCounterMsg.value = "unsuccessful"
      }
    }

  companion object : KLogging()
}