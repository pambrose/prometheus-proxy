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

import com.google.common.collect.Maps.newConcurrentMap
import io.ktor.application.call
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.Millis
import io.prometheus.common.Secs
import io.prometheus.dsl.KtorDsl.blockingGet
import io.prometheus.dsl.KtorDsl.get
import io.prometheus.dsl.KtorDsl.http
import kotlinx.coroutines.*
import mu.KLogging
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldNotBeNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@KtorExperimentalAPI
object ProxyTests : KLogging() {

    fun timeoutTest(
        agent: Agent,
        caller: String,
        agentPort: Int = 9900,
        agentPath: String = "agent-timeout",
        proxyPath: String = "proxy-timeout"
    ) {
        logger.info { "Calling timeoutTest() from $caller" }

        val httpServer =
            embeddedServer(CIO, port = agentPort) {
                routing {
                    get("/$agentPath") {
                        delay(Secs(10).toMillis().value)
                        call.respondText("I got back a value", ContentType.Text.Plain)
                    }
                }
            }

        runBlocking {
            launch(Dispatchers.Default) {
                logger.info { "Starting httpServer" }
                httpServer.start()
                delay(Secs(5).toMillis().value)
            }
        }

        agent.registerPath("/$proxyPath", "$agentPort/$agentPath".fixUrl())
        blockingGet("$PROXY_PORT/$proxyPath".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.ServiceUnavailable
        }
        agent.unregisterPath("/$proxyPath")

        runBlocking {
            launch(Dispatchers.Default) {
                logger.info { "Stopping httpServer" }
                httpServer.stop(5, 5, TimeUnit.SECONDS)
                delay(Secs(5).toMillis().value)
            }
        }
    }

    class ProxyCallTestArgs(
        val agent: Agent,
        val httpServerCount: Int,
        val pathCount: Int,
        val sequentialQueryCount: Int,
        val sequentialPauseMillis: Millis,
        val parallelQueryCount: Int,
        val startingPort: Int = 9600,
        val caller: String
    )

    @InternalCoroutinesApi
    fun proxyCallTest(args: ProxyCallTestArgs) {
        logger.info { "Calling proxyCallTest() from ${args.caller}" }

        val httpServers = mutableListOf<CIOApplicationEngine>()
        val pathMap = newConcurrentMap<Int, Int>()

        // Take into account pre-existing paths already registered
        val originalSize = args.agent.pathMapSize()

        // Create the endpoints
        logger.info { "Creating ${args.httpServerCount} httpServers" }
        repeat(args.httpServerCount) { i ->
            httpServers +=
                embeddedServer(CIO, port = args.startingPort + i) {
                    routing {
                        get("/agent-$i") {
                            call.respondText("value: $i", ContentType.Text.Plain)
                        }
                    }
                }
        }

        logger.info { "Starting ${args.httpServerCount} httpServers" }

        runBlocking {
            for (httpServer in httpServers) {
                launch(Dispatchers.Default) {
                    logger.info { "Starting httpServer" }
                    httpServer.start()
                    delay(Secs(2).toMillis().value)
                }
            }
        }

        logger.info { "Finished starting ${args.httpServerCount} httpServers" }

        // Create the paths
        repeat(args.pathCount) {
            val index = Random.nextInt(httpServers.size)
            args.agent.registerPath("proxy-$it", "${args.startingPort + index}/agent-$index".fixUrl())
            pathMap[it] = index
        }

        args.agent.pathMapSize() shouldEqual originalSize + args.pathCount

        // Call the proxy sequentially
        repeat(args.sequentialQueryCount) {
            runBlocking {
                withTimeoutOrNull(Secs(10).toMillis().value) {
                    val job =
                        GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler) {
                            callProxy(pathMap, "Sequential $it")
                        }
                    job.join()
                    job.getCancellationException().cause.shouldBeNull()
                }.shouldNotBeNull()
                delay(args.sequentialPauseMillis.value)
            }
        }

        // Call the proxy in parallel
        runBlocking {
            withTimeoutOrNull(Secs(60).toMillis().value) {
                val jobs = mutableListOf<Job>()
                repeat(args.parallelQueryCount) {
                    jobs += GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler) {
                        delay(Random.nextLong(100))
                        callProxy(pathMap, "Parallel $it")
                    }
                }

                jobs.forEach { job ->
                    job.join()
                    job.getCancellationException().cause.shouldBeNull()
                }
            }.shouldNotBeNull()
        }

        val errorCnt = AtomicInteger()
        for (path in pathMap) {
            try {
                args.agent.unregisterPath("proxy-${path.key}")
            } catch (e: RequestFailureException) {
                errorCnt.incrementAndGet()
            }
        }

        errorCnt.get() shouldEqual 0
        args.agent.pathMapSize() shouldEqual originalSize

        logger.info { "Shutting down ${httpServers.size} httpServers" }
        runBlocking {
            for (httpServer in httpServers) {
                launch(Dispatchers.Default) {
                    logger.info { "Shutting down httpServer" }
                    httpServer.stop(5, 5, TimeUnit.SECONDS)
                    delay(Secs(5).toMillis().value)
                }
            }
        }
        logger.info { "Finished shutting down ${httpServers.size} httpServers" }
    }

    private suspend fun callProxy(pathMap: Map<Int, Int>, msg: String) {
        // Randomly choose one of the pathMap values
        val index = Random.nextInt(pathMap.size)
        val httpVal = pathMap[index]
        httpVal.shouldNotBeNull()

        http {
            get("$PROXY_PORT/proxy-$index".fixUrl()) { resp ->
                resp.status shouldEqual HttpStatusCode.OK

                val body = resp.readText()
                body shouldEqual "value: $httpVal"
            }
        }
    }
}


