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

package io.prometheus

import com.github.pambrose.common.coroutine.delay
import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.dsl.KtorDsl.http
import com.github.pambrose.common.dsl.KtorDsl.newHttpClient
import com.github.pambrose.common.util.random
import com.google.common.collect.Maps.newConcurrentMap
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.statement.readText
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.RequestFailureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldNotBeNull
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set
import kotlin.time.milliseconds
import kotlin.time.minutes
import kotlin.time.seconds

class ProxyCallTestArgs(val agent: Agent,
                        val httpServerCount: Int,
                        val pathCount: Int,
                        val sequentialQueryCount: Int,
                        val parallelQueryCount: Int,
                        val startPort: Int = 9600,
                        val caller: String)

object ProxyTests : KLogging() {

  fun timeoutTest(pathManager: AgentPathManager,
                  caller: String,
                  agentPort: Int = 9900,
                  agentPath: String = "agent-timeout",
                  proxyPath: String = "proxy-timeout") {
    logger.debug { "Calling timeoutTest() from $caller" }

    val httpServer =
        embeddedServer(CIO, port = agentPort) {
          routing {
            get("/$agentPath") {
              delay(10.seconds)
              call.respondText("This is never reached", Text.Plain)
            }
          }
        }

    runBlocking {
      launch(Dispatchers.Default) {
        logger.info { "Starting httpServer" }
        httpServer.start()
        delay(5.seconds)
      }
    }

    pathManager.registerPath("/$proxyPath", "$agentPort/$agentPath".fixUrl())
    blockingGet("$PROXY_PORT/$proxyPath".fixUrl()) { response ->
      response.status shouldEqual HttpStatusCode.ServiceUnavailable
    }
    pathManager.unregisterPath("/$proxyPath")

    runBlocking {
      launch(Dispatchers.Default) {
        logger.info { "Stopping httpServer" }
        httpServer.stop(5.seconds.toLongMilliseconds(), 5.seconds.toLongMilliseconds())
        delay(5.seconds)
      }
    }
  }


  private class HttpServerWrapper(val port: Int, val server: CIOApplicationEngine)

  private val contentMap = mutableMapOf<Int, String>()

  fun proxyCallTest(args: ProxyCallTestArgs) {
    logger.info { "Calling proxyCallTest() from ${args.caller}" }

    val pathMap: ConcurrentMap<Int, Int> = newConcurrentMap()

    // Take into account pre-existing paths already registered
    val originalSize = args.agent.grpcService.pathMapSize()

    // Create the endpoints
    logger.info { "Creating ${args.httpServerCount} httpServers" }
    val httpServers =
        List(args.httpServerCount) { i ->
          val port = args.startPort + i

          // Create fake content
          val s = "This is the content for an endpoint for server# $i on $port\n"
          val builder = StringBuilder()
          val len =
              when (i % 3) {
                0 -> 100_000
                1 -> 1000
                else -> 1
              }
          repeat(len) { builder.append(s + "${it}\n") }
          contentMap[i] = builder.toString()

          HttpServerWrapper(port = port,
                            server = embeddedServer(CIO, port = port) {
                              routing {
                                get("/agent-$i") {
                                  call.respondText(contentMap[i]!!, Text.Plain)
                                }
                              }
                            })
        }

    logger.debug { "Starting ${args.httpServerCount} httpServers" }

    runBlocking {
      httpServers.forEach { httpServer ->
        launch(Dispatchers.Default) {
          logger.info { "Starting httpServer listening on ${httpServer.port}" }
          httpServer.server.start()
          delay(2.seconds)
        }
      }
    }

    logger.debug { "Finished starting ${args.httpServerCount} httpServers" }

    // Create the paths
    logger.debug { "Registering paths" }
    repeat(args.pathCount) { i ->
      val index = httpServers.size.random()
      args.agent.pathManager.registerPath("proxy-$i", "${args.startPort + index}/agent-$index".fixUrl())
      pathMap[i] = index
    }

    args.agent.grpcService.pathMapSize() shouldEqual originalSize + args.pathCount

    // Call the proxy sequentially
    logger.info { "Calling proxy sequentially ${args.sequentialQueryCount} times" }
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        .use { dispatcher ->
          runBlocking {
            withTimeoutOrNull(1.minutes.toLongMilliseconds()) {
              newHttpClient()
                  .use { httpClient ->
                    val counter = AtomicInteger(0)
                    repeat(args.sequentialQueryCount) { cnt ->
                      val job = launch(dispatcher + coroutineExceptionHandler(logger)) {
                        callProxy(httpClient, pathMap, "Sequential $cnt")
                        counter.incrementAndGet()
                      }

                      job.join()
                      job.getCancellationException().cause.shouldBeNull()

                    }

                    counter.get() shouldEqual args.sequentialQueryCount
                  }
            }
          }
        }

    // Call the proxy in parallel
    logger.info { "Calling proxy in parallel ${args.parallelQueryCount} times" }
    Executors.newFixedThreadPool(10).asCoroutineDispatcher()
        .use { dispatcher ->
          runBlocking {
            withTimeoutOrNull(1.minutes.toLongMilliseconds()) {
              newHttpClient()
                  .use { httpClient ->
                    val counter = AtomicInteger(0)
                    val jobs =
                        List(args.parallelQueryCount) { cnt ->
                          launch(dispatcher + coroutineExceptionHandler(logger)) {
                            delay((200..400).random().milliseconds)
                            callProxy(httpClient, pathMap, "Parallel $cnt")
                            counter.incrementAndGet()
                          }
                        }

                    jobs.forEach { job ->
                      job.join()
                      job.getCancellationException().cause.shouldBeNull()
                    }

                    counter.get() shouldEqual args.parallelQueryCount
                  }
            }
          }
        }

    logger.debug { "Unregistering paths" }
    val counter = AtomicInteger(0)
    val errorCnt = AtomicInteger(0)
    pathMap.forEach { path ->
      try {
        args.agent.pathManager.unregisterPath("proxy-${path.key}")
        counter.incrementAndGet()
      } catch (e: RequestFailureException) {
        errorCnt.incrementAndGet()
      }
    }

    counter.get() shouldEqual pathMap.size
    errorCnt.get() shouldEqual 0
    args.agent.grpcService.pathMapSize() shouldEqual originalSize

    logger.info { "Shutting down ${httpServers.size} httpServers" }
    runBlocking {
      httpServers.forEach { httpServer ->
        launch(Dispatchers.Default) {
          logger.info { "Shutting down httpServer listening on ${httpServer.port}" }
          httpServer.server.stop(5.seconds.toLongMilliseconds(), 5.seconds.toLongMilliseconds())
          delay(5.seconds)
        }
      }
    }
    logger.info { "Finished shutting down ${httpServers.size} httpServers" }
  }

  private suspend fun callProxy(httpClient: HttpClient, pathMap: Map<Int, Int>, msg: String) {
    logger.debug { "Launched $msg" }

    // Randomly choose one of the pathMap values
    val index = pathMap.size.random()
    val httpIndex = pathMap[index]
    httpIndex.shouldNotBeNull()

    http(httpClient) {
      get("$PROXY_PORT/proxy-$index".fixUrl()) { response ->
        val body = response.readText()
        body shouldEqual contentMap[httpIndex]
        response.status shouldEqual HttpStatusCode.OK
      }
    }
  }
}