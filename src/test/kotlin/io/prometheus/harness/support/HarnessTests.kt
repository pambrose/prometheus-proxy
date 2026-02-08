/*
 * Copyright © 2025 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.harness.support

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.dsl.KtorDsl.httpClient
import com.github.pambrose.common.dsl.KtorDsl.withHttpClient
import com.github.pambrose.common.util.random
import com.google.common.collect.Maps.newConcurrentMap
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Agent
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.RequestFailureException
import io.prometheus.harness.support.HarnessConstants.HTTP_SERVER_COUNT
import io.prometheus.harness.support.HarnessConstants.MAX_DELAY_MILLIS
import io.prometheus.harness.support.HarnessConstants.MIN_DELAY_MILLIS
import io.prometheus.harness.support.HarnessConstants.PARALLEL_QUERY_COUNT
import io.prometheus.harness.support.HarnessConstants.PATH_COUNT
import io.prometheus.harness.support.HarnessConstants.SEQUENTIAL_QUERY_COUNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.plusAssign
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProxyCallTestArgs(
  val agent: Agent,
  val proxyPort: Int,
  val httpServerCount: Int = HTTP_SERVER_COUNT,
  val pathCount: Int = PATH_COUNT,
  val sequentialQueryCount: Int = SEQUENTIAL_QUERY_COUNT,
  val parallelQueryCount: Int = PARALLEL_QUERY_COUNT,
  val startPort: Int = 9600,
  val caller: String,
)

internal object HarnessTests {
  private val logger = logger {}
  private val contentMap = mutableMapOf<Int, String>()

  suspend fun timeoutTest(
    pathManager: AgentPathManager,
    caller: String,
    proxyPort: Int,
    agentPort: Int = 9900,
    agentPath: String = "agent-timeout",
    proxyPath: String = "proxy-timeout",
  ) {
    logger.debug { "Calling timeoutTest() from $caller" }

    val httpServer =
      embeddedServer(CIO, port = agentPort) {
        routing {
          get("/$agentPath") {
            delay(60.seconds)
            call.respondText("This is never reached", Text.Plain.withCharset(Charsets.UTF_8))
            error("This should not be reached")
          }
        }
      }

    coroutineScope {
      launch(Dispatchers.IO + exceptionHandler(logger)) {
        logger.info { "Starting httpServer" }
        httpServer.start()
      }
    }

    // Give http server a chance to start
    delay(2.seconds)

    try {
      pathManager.registerPath("/$proxyPath", "$agentPort/$agentPath".withPrefix())

      blockingGet("$proxyPort/$proxyPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.RequestTimeout
      }

      pathManager.unregisterPath("/$proxyPath")
    } finally {
      coroutineScope {
        launch(Dispatchers.IO + exceptionHandler(logger)) {
          delay(5.seconds)
          logger.info { "Stopping httpServer" }
          httpServer.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
          // delay(5.seconds)
        }
      }
    }
  }

  private class HttpServerWrapper(
    val port: Int,
    val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
  )

  suspend fun proxyCallTest(args: ProxyCallTestArgs) {
    logger.info { "Calling proxyCallTest() from ${args.caller}" }

    val pathMap = newConcurrentMap<Int, Int>()

    // Take into account pre-existing paths already registered
    val originalSize = args.agent.grpcService.pathMapSize()

    // Create the endpoints
    logger.info { "Creating ${args.httpServerCount} httpServers" }
    val httpServers =
      List(args.httpServerCount) { i ->
        val port = args.startPort + i

        // Create fake content
        val s = "This is the content for an endpoint for server# $i on $port\n"
        val len =
          when (i % 3) {
            0 -> 100_000
            1 -> 1_000
            else -> 1
          }
        val content = buildString { repeat(len) { append("$s$it\n") } }.also { contentMap[i] = it }

        HttpServerWrapper(
          port = port,
          server = embeddedServer(factory = CIO, port = port) {
            routing {
              get("/agent-$i") {
                call.respondText(content, Text.Plain.withCharset(Charsets.UTF_8))
              }
            }
          },
        )
      }

    logger.debug { "Starting ${args.httpServerCount} httpServers" }

    coroutineScope {
      httpServers.forEach { wrapper ->
        launch(Dispatchers.IO + exceptionHandler(logger)) {
          logger.info { "Starting httpServer listening on ${wrapper.port}" }
          wrapper.server.start()
          delay(5.seconds)
        }
      }
    }

    logger.debug { "Finished starting ${args.httpServerCount} httpServers" }

    // Create the paths
    logger.debug { "Registering paths" }
    repeat(args.pathCount) { i ->
      val index = httpServers.size.random()
      args.agent.pathManager.registerPath("proxy-$i", "${args.startPort + index}/agent-$index".withPrefix())
      pathMap[i] = index
    }

    args.agent.grpcService.pathMapSize() shouldBe originalSize + args.pathCount

    // Call the proxy sequentially
    logger.info { "Calling proxy sequentially ${args.sequentialQueryCount} times" }
    newSingleThreadContext("test-single")
      .use { dispatcher ->
        withTimeoutOrNull(1.minutes.inWholeMilliseconds) {
          httpClient { client ->
            val counter = AtomicInt(0)
            repeat(args.sequentialQueryCount) { cnt ->
              val job =
                launch(dispatcher + exceptionHandler(logger)) {
                  callRandomProxyPath(client, args.proxyPort, pathMap, "Sequential $cnt")
                  counter += 1
                }

              job.join()
              job.getCancellationException().cause.shouldBeNull()
            }

            counter.load() shouldBe args.sequentialQueryCount
          }
        }
      }

    // Call the proxy in parallel
    logger.info { "Calling proxy in parallel ${args.parallelQueryCount} times" }
    newFixedThreadPoolContext(5, "test-multi")
      .use { dispatcher ->
        withTimeoutOrNull(1.minutes.inWholeMilliseconds) {
          httpClient { client ->
            val counter = AtomicInt(0)
            val jobs =
              List(args.parallelQueryCount) { cnt ->
                launch(dispatcher + exceptionHandler(logger)) {
                  delay((MIN_DELAY_MILLIS..MAX_DELAY_MILLIS).random().milliseconds)
                  callRandomProxyPath(client, args.proxyPort, pathMap, "Parallel $cnt")
                  counter += 1
                }
              }

            jobs.forEach { job ->
              job.join()
              job.getCancellationException().cause.shouldBeNull()
            }

            counter.load() shouldBe args.parallelQueryCount
          }
        }
      }

    logger.debug { "Unregistering paths" }
    val counter = AtomicInt(0)
    val errorCnt = AtomicInt(0)
    pathMap.forEach { path ->
      try {
        args.agent.pathManager.unregisterPath("proxy-${path.key}")
        counter += 1
      } catch (e: RequestFailureException) {
        errorCnt += 1
      }
    }

    counter.load() shouldBe pathMap.size
    errorCnt.load() shouldBe 0
    args.agent.grpcService.pathMapSize() shouldBe originalSize

    logger.info { "Shutting down ${httpServers.size} httpServers" }
    coroutineScope {
      httpServers.forEach { httpServer ->
        launch(Dispatchers.IO + exceptionHandler(logger)) {
          delay(5.seconds)
          logger.info { "Shutting down httpServer listening on ${httpServer.port}" }
          httpServer.server.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
          delay(5.seconds)
        }
      }
    }
    logger.info { "Finished shutting down ${httpServers.size} httpServers" }
  }

  private suspend fun callRandomProxyPath(
    httpClient: HttpClient,
    proxyPort: Int,
    pathMap: Map<Int, Int>,
    msg: String,
  ) {
    logger.debug { "Launched $msg" }

    // Randomly choose one of the pathMap values
    val index = pathMap.size.random()
    val httpIndex = pathMap[index]
    httpIndex.shouldNotBeNull()

    withHttpClient(httpClient) {
      get("$proxyPort/proxy-$index".withPrefix()) { response ->
        val body = response.bodyAsText()
        body shouldBe contentMap[httpIndex]
        response.status shouldBe HttpStatusCode.OK
      }
    }
  }
}
