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

import com.github.pambrose.common.dsl.GrpcDsl.streamObserver
import com.github.pambrose.common.dsl.KtorDsl
import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.util.simpleClassName
import com.google.common.net.HttpHeaders
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.isSuccess
import io.prometheus.Agent
import io.prometheus.common.GrpcObjects
import io.prometheus.common.GrpcObjects.newAgentInfo
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AgentUtils(val agent: Agent) {

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

  internal fun sendHeartBeat(grpcService: AgentGrpcService, disconnected: AtomicBoolean) {
    if (agent.agentId.isEmpty())
      return

    try {
      val request = GrpcObjects.newHeartBeatRequest(agent.agentId)
      grpcService.blockingStub
        .sendHeartBeat(request)
        .also { resp ->
          agent.markMsgSent()
          if (!resp.valid) {
            logger.error { "AgentId ${agent.agentId} not found on proxy" }
            throw StatusRuntimeException(Status.NOT_FOUND)
          }
        }
    } catch (e: StatusRuntimeException) {
      logger.error { "Hearbeat failed ${e.status}" }
      disconnected.set(true)
    }
  }

  internal fun readRequestsFromProxy(grpcService: AgentGrpcService,
                                     scrapeRequestChannel: Channel<ScrapeRequestAction>,
                                     disconnected: AtomicBoolean) {
    grpcService.asyncStub
      .readRequestsFromProxy(newAgentInfo(agent.agentId),
                             streamObserver {
                               onNext { req ->
                                 // This will block, but only for the duration of the send.
                                 // The actual fetch happens at the other end of the channel
                                 runBlocking {
                                   logger.debug { "readRequestsFromProxy(): \n$req" }
                                   scrapeRequestChannel.send { fetchScrapeUrl(req) }
                                   agent.scrapeRequestBacklogSize.incrementAndGet()
                                 }
                               }

                               onError { throwable ->
                                 logger.error {
                                   "Error in readRequestsFromProxy(): ${Status.fromThrowable(
                                     throwable)}"
                                 }
                                 disconnected.set(true)
                                 scrapeRequestChannel.cancel()
                               }

                               onCompleted {
                                 disconnected.set(true)
                                 scrapeRequestChannel.close()
                               }
                             })
  }

  internal suspend fun writeResponsesToProxyUntilDisconnected(grpcService: AgentGrpcService,
                                                              scrapeRequestChannel: Channel<ScrapeRequestAction>,
                                                              disconnected: AtomicBoolean) {
    val observer =
      grpcService.asyncStub
        .writeResponsesToProxy(
          streamObserver<Empty> {
            onNext {
              // Ignore Empty return value
            }

            onError { throwable ->
              val s = Status.fromThrowable(throwable)
              logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
              disconnected.set(true)
            }

            onCompleted { disconnected.set(true) }
          })

    for (scrapeRequestAction in scrapeRequestChannel) {
      // The fetch actually occurs here
      val scrapeResponse = scrapeRequestAction.invoke()
      logger.debug { "writeResponsesToProxyUntilDisconnected(): \n$scrapeResponse" }
      observer.onNext(scrapeResponse)
      agent.markMsgSent()
      agent.scrapeRequestBacklogSize.decrementAndGet()
      if (disconnected.get())
        break
    }

    logger.info { "Disconnected from proxy at ${agent.proxyHost}" }

    observer.onCompleted()
  }

  companion object : KLogging()
}