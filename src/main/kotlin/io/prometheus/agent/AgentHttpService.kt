/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import com.github.pambrose.common.dsl.KtorDsl.http
import com.github.pambrose.common.util.simpleClassName
import com.google.common.net.HttpHeaders
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.isSuccess
import io.prometheus.Agent
import io.prometheus.common.GrpcObjects
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import mu.KLogging
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class AgentHttpService(val agent: Agent) {

  suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResponse {
    val responseArg = GrpcObjects.ScrapeResponseArg(agentId = request.agentId, scrapeId = request.scrapeId)
    val scrapeCounterMsg = AtomicReference("")
    val path = request.path
    val pathContext = agent.pathManager[path]

    if (pathContext == null) {
      logger.warn { "Invalid path in fetchScrapeUrl(): $path" }
      scrapeCounterMsg.set("invalid_path")
      if (request.debugEnabled)
        responseArg.setDebugInfo("None", "Invalid path: $path")
    } else {
      val requestTimer = if (agent.isMetricsEnabled) agent.startTimer() else null
      val url = pathContext.url
      logger.debug { "Fetching $pathContext" }

      try {
        http {
          get(url, getSetUp(request), getBlock(url, responseArg, scrapeCounterMsg, request.debugEnabled))
        }
      } catch (e: IOException) {
        logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
        if (request.debugEnabled)
          responseArg.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
      } catch (e: Throwable) {
        logger.warn(e) { "fetchScrapeUrl() $e - $url" }
        if (request.debugEnabled)
          responseArg.setDebugInfo(url, "${e.simpleClassName} - ${e.message}")
      } finally {
        requestTimer?.observeDuration()
      }
    }

    agent.updateScrapeCounter(scrapeCounterMsg.get())
    return GrpcObjects.newScrapeResponse(responseArg)
  }

  private fun getSetUp(request: ScrapeRequest): HttpRequestBuilder.() -> Unit = {
    val accept: String? = request.accept
    if (accept?.isNotEmpty() == true)
      header(HttpHeaders.ACCEPT, accept)
  }

  private fun getBlock(url: String,
                       responseArg: GrpcObjects.ScrapeResponseArg,
                       scrapeCounterMsg: AtomicReference<String>,
                       debugEnabled: Boolean): suspend (HttpResponse) -> Unit =
    { resp ->
      responseArg.statusCode = resp.status

      if (resp.status.isSuccess()) {
        responseArg.apply {
          contentText = resp.readText()
          contentType = resp.headers[HttpHeaders.CONTENT_TYPE].orEmpty()
          validResponse = true
        }
        if (debugEnabled)
          responseArg.setDebugInfo(url)
        scrapeCounterMsg.set("success")
      } else {
        if (debugEnabled)
          responseArg.setDebugInfo(url, "Unsucessful response code ${responseArg.statusCode}")
        scrapeCounterMsg.set("unsuccessful")
      }
    }

  companion object : KLogging()
}