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
import io.ktor.client.HttpClient
import io.ktor.client.call.receive
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.Millis
import io.prometheus.common.Secs
import io.prometheus.common.sleep
import io.prometheus.dsl.KtorDsl.blockingGet
import io.prometheus.dsl.KtorDsl.get
import io.prometheus.dsl.KtorDsl.newHttpClient
import io.prometheus.dsl.SparkDsl.httpServer
import io.prometheus.proxy.ProxyHttpService.Companion.sparkExceptionHandler
import kotlinx.coroutines.*
import mu.KLogging
import org.assertj.core.api.Assertions.assertThat
import spark.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object CommonTests : KLogging() {

    @KtorExperimentalAPI
    fun missingPathTest(caller: String) {
        logger.info { "Calling missingPathTest() from $caller" }
        blockingGet("$PROXY_PORT/") { assertThat(it.status.value).isEqualTo(404) }
    }

    @KtorExperimentalAPI
    fun invalidPathTest(caller: String) {
        logger.info { "Calling invalidPathTest() from $caller" }
        blockingGet("$PROXY_PORT/invalid_path") { assertThat(it.status.value).isEqualTo(404) }
    }

    fun addRemovePathsTest(agent: Agent, caller: String) {
        logger.info { "Calling addRemovePathsTest() from $caller" }

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        var cnt = 0
        repeat(TestConstants.REPS) {
            val path = "test-$it"
            agent.registerPath(path, "http://localhost:$PROXY_PORT/$path")
            cnt++
            assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt)
            agent.unregisterPath(path)
            cnt--
            assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt)
        }
    }

    fun threadedAddRemovePathsTest(agent: Agent, caller: String) {
        logger.info { "Calling threadedAddRemovePathsTest() from $caller" }
        val paths = mutableListOf<String>()
        val cnt = AtomicInteger(0)
        val latch1 = CountDownLatch(TestConstants.REPS)
        val latch2 = CountDownLatch(TestConstants.REPS)

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        repeat(TestConstants.REPS) {
            TestConstants
                .EXECUTOR_SERVICE
                .submit {
                    val path = "test-${cnt.getAndIncrement()}"

                    synchronized(paths) {
                        paths += path
                    }

                    try {
                        val url = "http://localhost:$PROXY_PORT/$path"
                        agent.registerPath(path, url)
                        latch1.countDown()
                    } catch (e: RequestFailureException) {
                        e.printStackTrace()
                    }
                }
        }

        assertThat(latch1.await(1, MINUTES)).isTrue()
        assertThat(paths.size).isEqualTo(TestConstants.REPS)
        assertThat(agent.pathMapSize()).isEqualTo(originalSize + TestConstants.REPS)

        paths.forEach {
            TestConstants
                .EXECUTOR_SERVICE
                .submit {
                    try {
                        agent.unregisterPath(it)
                        latch2.countDown()
                    } catch (e: RequestFailureException) {
                        e.printStackTrace()
                    }
                }
        }

        // Wait for all unregistrations to complete
        assertThat(latch2.await(1, MINUTES)).isTrue()
        assertThat(agent.pathMapSize()).isEqualTo(originalSize)
    }

    @KtorExperimentalAPI
    fun invalidAgentUrlTest(agent: Agent, caller: String, badPath: String = "badPath") {
        logger.info { "Calling invalidAgentUrlTest() from $caller" }

        agent.registerPath(badPath, "http://localhost:33/metrics")
        blockingGet("$PROXY_PORT/$badPath") { assertThat(it.status.value).isEqualTo(404) }
        agent.unregisterPath(badPath)
    }

    @KtorExperimentalAPI
    fun timeoutTest(
        agent: Agent,
        caller: String,
        agentPort: Int = 9700,
        agentPath: String = "agent-timeout",
        proxyPath: String = "proxy-timeout"
    ) {
        logger.info { "Calling timeoutTest() from $caller" }

        val httpServer =
            httpServer {
                initExceptionHandler { sparkExceptionHandler(it, agentPort) }
                port(agentPort)
                get("/$agentPath") { _, res ->
                    res.type("text/plain")
                    sleep(Secs(10))
                    "I timed out"
                }
                awaitInitialization()
            }

        agent.registerPath("/$proxyPath", "http://localhost:$agentPort/$agentPath")
        blockingGet("$PROXY_PORT/$proxyPath") { assertThat(it.status.value).isEqualTo(404) }
        agent.unregisterPath("/$proxyPath")

        httpServer.stop()
        sleep(Secs(5))
    }

    @InternalCoroutinesApi
    @KtorExperimentalAPI
    fun proxyCallTest(
        agent: Agent,
        httpServerCount: Int,
        pathCount: Int,
        sequentialQueryCount: Int,
        sequentialPauseMillis: Millis,
        parallelQueryCount: Int,
        startingPort: Int = 9600,
        caller: String
    ) {
        logger.info { "Calling proxyCallTest() from $caller" }
        val httpServers = mutableListOf<Service>()
        val pathMap = newConcurrentMap<Int, Int>()

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        // Create the endpoints
        repeat(httpServerCount) {
            val port = startingPort + it
            httpServers +=
                httpServer {
                    initExceptionHandler { arg -> sparkExceptionHandler(arg, port) }
                    port(port)
                    threadPool(30, 10, 1000)
                    get("/agent-$it") { _, res ->
                        res.type("text/plain")
                        "value: $it"
                    }
                    awaitInitialization()
                }
        }

        // Create the paths
        repeat(pathCount) {
            val index = Random.nextInt(httpServers.size)
            agent.registerPath("proxy-$it", "http://localhost:${startingPort + index}/agent-$index")
            pathMap[it] = index
        }

        assertThat(agent.pathMapSize()).isEqualTo(originalSize + pathCount)

        val handler =
            CoroutineExceptionHandler { context, e ->
                e.printStackTrace()
                println("Handler caught $e")
            }

        // Call the proxy sequentially
        repeat(sequentialQueryCount) {
            newHttpClient()
                .use { httpClient ->
                    runBlocking {
                        val result =
                            withTimeoutOrNull(1000) {
                                val job =
                                    GlobalScope.launch(handler) {
                                        callProxy(httpClient, pathMap, "Sequential $it")
                                    }
                                job.join()
                                assertThat(job.getCancellationException().cause).isNull()
                            }
                        assertThat(result != null).isTrue()
                    }
                }
            sleep(sequentialPauseMillis)
        }

        // Call the proxy in parallel
        val dispatcher = TestConstants.EXECUTOR_SERVICE.asCoroutineDispatcher()
        newHttpClient()
            .use { httpClient ->
                runBlocking {
                    val jobs = mutableListOf<Job>()
                    val result =
                        withTimeoutOrNull(5000) {
                            repeat(parallelQueryCount) {
                                jobs += GlobalScope.launch(dispatcher + handler) {
                                    callProxy(httpClient, pathMap, "Parallel $it")
                                }
                            }
                        }

                    jobs.forEach {
                        it.join()
                        assertThat(it.getCancellationException().cause).isNull()
                    }

                    // Check if call timed out
                    assertThat(result != null).isTrue()
                }
            }

        val errorCnt = AtomicInteger()
        pathMap.forEach {
            try {
                agent.unregisterPath("proxy-${it.key}")
            } catch (e: RequestFailureException) {
                errorCnt.incrementAndGet()
            }
        }

        assertThat(errorCnt.get()).isEqualTo(0)
        assertThat(agent.pathMapSize()).isEqualTo(originalSize)

        httpServers.forEach { it.stop() }
        sleep(Secs(5))
    }

    @KtorExperimentalAPI
    private suspend fun callProxy(httpClient: HttpClient, pathMap: Map<Int, Int>, msg: String) {
        //logger.info {"Calling proxy for ${msg}")
        // Choose one of the pathMap values
        val index = Random.nextInt(pathMap.size)
        val httpVal = pathMap[index]
        httpClient.get("$PROXY_PORT/proxy-$index") {
            if (it.status.value != 200)
                logger.error { "Proxy failed on $msg" }
            assertThat(it.status.value).isEqualTo(200)
            val body = it.receive<String>()
            assertThat(body).isEqualTo("value: $httpVal")
        }
    }
}


