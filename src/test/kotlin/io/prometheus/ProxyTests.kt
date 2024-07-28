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

package io.prometheus

import com.github.pambrose.common.coroutine.delay
import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.dsl.KtorDsl.httpClient
import com.github.pambrose.common.dsl.KtorDsl.withHttpClient
import com.github.pambrose.common.util.random
import com.google.common.collect.Maps.newConcurrentMap
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.CommonTests.Companion.HTTP_SERVER_COUNT
import io.prometheus.CommonTests.Companion.MAX_DELAY_MILLIS
import io.prometheus.CommonTests.Companion.MIN_DELAY_MILLIS
import io.prometheus.CommonTests.Companion.PARALLEL_QUERY_COUNT
import io.prometheus.CommonTests.Companion.PATH_COUNT
import io.prometheus.CommonTests.Companion.SEQUENTIAL_QUERY_COUNT
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.RequestFailureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProxyCallTestArgs(
  val agent: Agent,
  val httpServerCount: Int = HTTP_SERVER_COUNT,
  val pathCount: Int = PATH_COUNT,
  val sequentialQueryCount: Int = SEQUENTIAL_QUERY_COUNT,
  val parallelQueryCount: Int = PARALLEL_QUERY_COUNT,
  val startPort: Int = 9600,
  val caller: String,
)

internal object ProxyTests {
  private val logger = KotlinLogging.logger {}

  suspend fun timeoutTest(
    pathManager: AgentPathManager,
    caller: String,
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
            call.respondText("This is never reached", Text.Plain)
            error("This should not be reached")
          }
        }
      }

    coroutineScope {
      launch(Dispatchers.Default + exceptionHandler(logger)) {
        logger.info { "Starting httpServer" }
        httpServer.start()
        delay(5.seconds)
      }
    }

    delay(2.seconds) // Give http server a chance to start
    pathManager.registerPath("/$proxyPath", "$agentPort/$agentPath".withPrefix())

    blockingGet("$PROXY_PORT/$proxyPath".withPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.RequestTimeout
    }

    pathManager.unregisterPath("/$proxyPath")

    coroutineScope {
      launch(Dispatchers.Default + exceptionHandler(logger)) {
        logger.info { "Stopping httpServer" }
        httpServer.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
        delay(5.seconds)
      }
    }
  }

  private class HttpServerWrapper(
    val port: Int,
    val server: CIOApplicationEngine,
  )

  private val contentMap = mutableMapOf<Int, String>()

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
          server = embeddedServer(CIO, port = port) {
            routing {
              get("/agent-$i") {
                call.respondText(content, Text.Plain)
              }
            }
          },
        )
      }

    logger.debug { "Starting ${args.httpServerCount} httpServers" }

    coroutineScope {
      httpServers.forEach { wrapper ->
        launch(Dispatchers.Default + exceptionHandler(logger)) {
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

    args.agent.grpcService.pathMapSize() shouldBeEqualTo originalSize + args.pathCount

    // Call the proxy sequentially
    logger.info { "Calling proxy sequentially ${args.sequentialQueryCount} times" }
    newSingleThreadContext("test-single")
      .use { dispatcher ->
        withTimeoutOrNull(1.minutes.inWholeMilliseconds) {
          httpClient { client ->
            val counter = AtomicInteger(0)
            repeat(args.sequentialQueryCount) { cnt ->
              val job =
                launch(dispatcher + exceptionHandler(logger)) {
                  callProxy(client, pathMap, "Sequential $cnt")
                  counter.incrementAndGet()
                }

              job.join()
              job.getCancellationException().cause.shouldBeNull()
            }

            counter.get() shouldBeEqualTo args.sequentialQueryCount
          }
        }
      }

    // Call the proxy in parallel
    logger.info { "Calling proxy in parallel ${args.parallelQueryCount} times" }
    newFixedThreadPoolContext(5, "test-multi")
      .use { dispatcher ->
        withTimeoutOrNull(1.minutes.inWholeMilliseconds) {
          httpClient { client ->
            val counter = AtomicInteger(0)
            val jobs =
              List(args.parallelQueryCount) { cnt ->
                launch(dispatcher + exceptionHandler(logger)) {
                  delay((MIN_DELAY_MILLIS..MAX_DELAY_MILLIS).random().milliseconds)
                  callProxy(client, pathMap, "Parallel $cnt")
                  counter.incrementAndGet()
                }
              }

            jobs.forEach { job ->
              job.join()
              job.getCancellationException().cause.shouldBeNull()
            }

            counter.get() shouldBeEqualTo args.parallelQueryCount
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

    counter.get() shouldBeEqualTo pathMap.size
    errorCnt.get() shouldBeEqualTo 0
    args.agent.grpcService.pathMapSize() shouldBeEqualTo originalSize

    logger.info { "Shutting down ${httpServers.size} httpServers" }
    coroutineScope {
      httpServers.forEach { httpServer ->
        launch(Dispatchers.Default + exceptionHandler(logger)) {
          logger.info { "Shutting down httpServer listening on ${httpServer.port}" }
          httpServer.server.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
          delay(5.seconds)
        }
      }
    }
    logger.info { "Finished shutting down ${httpServers.size} httpServers" }
  }

  private suspend fun callProxy(
    httpClient: HttpClient,
    pathMap: Map<Int, Int>,
    msg: String,
  ) {
    logger.debug { "Launched $msg" }

    // Randomly choose one of the pathMap values
    val index = pathMap.size.random()
    val httpIndex = pathMap[index]
    httpIndex.shouldNotBeNull()

    withHttpClient(httpClient) {
      get("$PROXY_PORT/proxy-$index".withPrefix()) { response ->
        val body = response.bodyAsText()
        body shouldBeEqualTo contentMap[httpIndex]
        response.status shouldBeEqualTo HttpStatusCode.OK
      }
    }
  }
}
