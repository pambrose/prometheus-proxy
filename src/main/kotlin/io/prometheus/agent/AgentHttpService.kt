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

import com.github.pambrose.common.dsl.KtorDsl
import com.github.pambrose.common.dsl.KtorDsl.get
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

class AgentHttpService(val agent: Agent) {

  internal suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResponse {
    val responseArg = GrpcObjects.ScrapeResponseArg(agentId = request.agentId, scrapeId = request.scrapeId)
    var scrapeCounterMsg = ""
    val path = request.path
    val pathContext = agent.pathManager[path]

    if (pathContext == null) {
      logger.warn { "Invalid path in fetchScrapeUrl(): $path" }
      scrapeCounterMsg = "invalid_path"
      responseArg.failureReason = "Invalid path: $path"
    } else {
      val requestTimer = if (agent.isMetricsEnabled) agent.startTimer() else null
      try {
        val setup: HttpRequestBuilder.() -> Unit = {
          val accept: String? = request.accept
          if (!accept.isNullOrEmpty())
            header(HttpHeaders.ACCEPT, accept)
        }

        val block: suspend (HttpResponse) -> Unit = { resp ->
          responseArg.statusCode = resp.status

          if (resp.status.isSuccess()) {
            responseArg.apply {
              contentText = resp.readText()
              contentType = resp.headers[HttpHeaders.CONTENT_TYPE].orEmpty()
              validResponse = true
            }
            scrapeCounterMsg = "success"
          } else {
            responseArg.failureReason = "Unsucessful response code ${responseArg.statusCode}"
            scrapeCounterMsg = "unsuccessful"
          }
        }

        KtorDsl.http {
          logger.debug { "Fetching $pathContext" }
          get(pathContext.url, setup, block)
        }

      } catch (e: IOException) {
        logger.info { "Failed HTTP request: ${pathContext.url} [${e.simpleClassName}: ${e.message}]" }
        responseArg.failureReason = "${e.simpleClassName} - ${e.message}"
      } catch (e: Exception) {
        logger.warn(e) { "fetchScrapeUrl() $e - ${pathContext.url}" }
        responseArg.failureReason = "${e.simpleClassName} - ${e.message}"
      } finally {
        requestTimer?.observeDuration()
      }
    }

    agent.updateScrapeCounter(scrapeCounterMsg)
    return GrpcObjects.newScrapeResponse(responseArg)
  }

  companion object : KLogging()
}