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
import io.ktor.client.HttpClient
import io.ktor.client.call.receive
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
import io.prometheus.common.sleep
import io.prometheus.dsl.KtorDsl.blockingGet
import io.prometheus.dsl.KtorDsl.get
import io.prometheus.dsl.KtorDsl.newHttpClient
import kotlinx.coroutines.*
import mu.KLogging
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldNotBeNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object CommonTests : KLogging() {

    @KtorExperimentalAPI
    fun missingPathTest(caller: String) {
        logger.info { "Calling missingPathTest() from $caller" }
        blockingGet("$PROXY_PORT/") { resp -> resp.status shouldEqual HttpStatusCode.NotFound }
    }

    @KtorExperimentalAPI
    fun invalidPathTest(caller: String) {
        logger.info { "Calling invalidPathTest() from $caller" }
        blockingGet("$PROXY_PORT/invalid_path") { resp -> resp.status shouldEqual HttpStatusCode.NotFound }
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
            agent.pathMapSize() shouldEqual originalSize + cnt
            agent.unregisterPath(path)
            cnt--
            agent.pathMapSize() shouldEqual originalSize + cnt
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

        latch1.await(1, MINUTES).shouldBeTrue()
        paths.size shouldEqual TestConstants.REPS
        agent.pathMapSize() shouldEqual originalSize + TestConstants.REPS

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
        latch2.await(1, MINUTES).shouldBeTrue()
        agent.pathMapSize() shouldEqual originalSize
    }

    @KtorExperimentalAPI
    fun invalidAgentUrlTest(agent: Agent, caller: String, badPath: String = "badPath") {
        logger.info { "Calling invalidAgentUrlTest() from $caller" }

        agent.registerPath(badPath, "http://localhost:33/metrics")
        blockingGet("$PROXY_PORT/$badPath") { resp -> resp.status shouldEqual HttpStatusCode.NotFound }
        agent.unregisterPath(badPath)
    }

    @KtorExperimentalAPI
    fun timeoutTest(
        agent: Agent,
        caller: String,
        agentPort: Int = 9900,
        agentPath: String = "agent-timeout",
        proxyPath: String = "proxy-timeout"
    ) {
        logger.info { "Calling timeoutTest() from $caller" }

        /*
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
        */

        val httpServer =
            embeddedServer(CIO, port = agentPort) {
                routing {
                    get("/$agentPath") {
                        sleep(Secs(10))
                        call.respondText("I timed out", ContentType.Text.Plain)
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


        agent.registerPath("/$proxyPath", "http://localhost:$agentPort/$agentPath")
        blockingGet("$PROXY_PORT/$proxyPath") { resp -> resp.status shouldEqual HttpStatusCode.ServiceUnavailable }
        agent.unregisterPath("/$proxyPath")

        runBlocking {
            launch(Dispatchers.Default) {
                logger.info { "Stopping httpServer" }
                //httpServer.environment.stop()
                httpServer.stop(5, 5, TimeUnit.SECONDS)
                delay(Secs(5).toMillis().value)
            }
        }
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

        val httpServers = mutableListOf<CIOApplicationEngine>()
        val pathMap = newConcurrentMap<Int, Int>()

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        // Create the endpoints
        logger.info { "Creating $httpServerCount httpServers" }
        repeat(httpServerCount) { i ->
            val port = startingPort + i
            /*
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
             */
            httpServers +=
                embeddedServer(CIO, port = port) {
                    routing {
                        get("/agent-$i") {
                            call.respondText("value: $i", ContentType.Text.Plain)
                        }
                    }
                }
        }

        logger.info { "Starting $httpServerCount httpServers" }

        runBlocking {
            for (httpServer in httpServers) {
                launch(Dispatchers.Default) {
                    logger.info { "Starting httpServer" }
                    httpServer.start()
                    delay(Secs(2).toMillis().value)
                }
            }
        }

        logger.info { "Finished starting $httpServerCount httpServers" }

        // Create the paths
        repeat(pathCount) {
            val index = Random.nextInt(httpServers.size)
            agent.registerPath("proxy-$it", "http://localhost:${startingPort + index}/agent-$index")
            pathMap[it] = index
        }

        agent.pathMapSize() shouldEqual originalSize + pathCount

        val coroutineExceptionHandler =
            CoroutineExceptionHandler { context, e ->
                println("CoroutineExceptionHandler caught: $e")
                e.printStackTrace()
            }

        // Call the proxy sequentially
        repeat(sequentialQueryCount) {
            newHttpClient()
                .use { httpClient ->
                    runBlocking {
                        val result =
                            withTimeoutOrNull(Secs(10).toMillis().value) {
                                val job =
                                    GlobalScope.launch(coroutineExceptionHandler) {
                                        callProxy(httpClient, pathMap, "Sequential $it")
                                    }
                                job.join()
                                job.getCancellationException().cause.shouldBeNull()
                            }
                        result.shouldNotBeNull()
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
                    val results = withTimeoutOrNull(Secs(30).toMillis().value) {
                        repeat(parallelQueryCount) {
                            jobs += GlobalScope.launch(dispatcher + coroutineExceptionHandler) {
                                delay(Random.nextLong(500))
                                callProxy(httpClient, pathMap, "Parallel $it")

                            }
                        }

                        for (job in jobs) {
                            job.join()
                            job.getCancellationException().cause.shouldBeNull()
                        }
                    }

                    // Check if timed out
                    results.shouldNotBeNull()
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

        errorCnt.get() shouldEqual 0
        agent.pathMapSize() shouldEqual originalSize

        logger.info { "Shutting down ${httpServers.size} httpServers" }
        runBlocking {
            for (httpServer in httpServers) {
                launch(Dispatchers.Default) {
                    logger.info { "Shutting down httpServer" }
                    //httpServer.environment.stop()
                    httpServer.stop(5, 5, TimeUnit.SECONDS)
                    delay(Secs(5).toMillis().value)
                }
            }
        }
        logger.info { "Finished shutting down ${httpServers.size} httpServers" }
    }

    @KtorExperimentalAPI
    private suspend fun callProxy(httpClient: HttpClient, pathMap: Map<Int, Int>, msg: String) {
        // Randomly choose one of the pathMap values
        val index = Random.nextInt(pathMap.size)
        val httpVal = pathMap[index]
        httpVal.shouldNotBeNull()

        httpClient
            .get("$PROXY_PORT/proxy-$index") { resp ->
                if (resp.status != HttpStatusCode.OK)
                    logger.error { "Proxy failed on $msg" }
                resp.status shouldEqual HttpStatusCode.OK

                val body = resp.receive<String>()
                body shouldEqual "value: $httpVal"
            }
    }
}


