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

package io.prometheus

import com.google.common.collect.Maps.newConcurrentMap
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.sleepForMillis
import io.prometheus.common.sleepForSecs
import io.prometheus.dsl.OkHttpDsl.get
import io.prometheus.dsl.SparkDsl.httpServer
import io.prometheus.proxy.ProxyHttpService.Companion.sparkExceptionHandler
import mu.KLogging
import org.assertj.core.api.Assertions.assertThat
import spark.Service
import java.lang.Math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

object CommonTests : KLogging() {

    fun missingPathTest(caller: String) {
        logger.info { "Calling missingPathTest() from $caller" }
        "http://localhost:$PROXY_PORT/".get { assertThat(it.code()).isEqualTo(404) }
    }

    fun invalidPathTest(caller: String) {
        logger.info { "Calling invalidPathTest() from $caller" }
        "http://localhost:$PROXY_PORT/invalid_path".get { assertThat(it.code()).isEqualTo(404) }
    }

    fun addRemovePathsTest(agent: Agent, caller: String) {
        logger.info { "Calling addRemovePathsTest() from $caller" }

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        var cnt = 0
        IntStream.range(0, TestConstants.REPS)
                .forEach {
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

        IntStream.range(0, TestConstants.REPS)
                .forEach {
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

    fun invalidAgentUrlTest(agent: Agent, badPath: String = "badPath", caller: String) {
        logger.info { "Calling invalidAgentUrlTest() from $caller" }

        agent.registerPath(badPath, "http://localhost:33/metrics")
        "http://localhost:$PROXY_PORT/$badPath".get { assertThat(it.code()).isEqualTo(404) }
        agent.unregisterPath(badPath)
    }

    fun timeoutTest(agent: Agent,
                    agentPort: Int = 9700,
                    proxyPath: String = "proxy-timeout",
                    agentPath: String = "agent-timeout",
                    caller: String) {

        logger.info { "Calling timeoutTest() from $caller" }

        val httpServer =
                httpServer {
                    initExceptionHandler { sparkExceptionHandler(it, agentPort) }
                    port(agentPort)
                    get("/$agentPath") { _, res ->
                        res.type("text/plain")
                        sleepForSecs(10)
                        "I timed out"
                    }
                    awaitInitialization()
                }

        agent.registerPath("/$proxyPath", "http://localhost:$agentPort/$agentPath")
        "http://localhost:$PROXY_PORT/$proxyPath".get { assertThat(it.code()).isEqualTo(404) }
        agent.unregisterPath("/$proxyPath")

        httpServer.stop()
        sleepForSecs(5)
    }

    fun proxyCallTest(agent: Agent,
                      httpServerCount: Int,
                      pathCount: Int,
                      sequentialQueryCount: Int,
                      sequentialPauseMillis: Long,
                      parallelQueryCount: Int,
                      startingPort: Int = 9600,
                      caller: String) {

        logger.info { "Calling proxyCallTest() from $caller" }
        val httpServers = mutableListOf<Service>()
        val pathMap = newConcurrentMap<Int, Int>()

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        // Create the endpoints
        IntStream.range(0, httpServerCount)
                .forEach {
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
        IntStream.range(0, pathCount)
                .forEach {
                    val index = abs(TestConstants.RANDOM.nextInt()) % httpServers.size
                    agent.registerPath("proxy-$it", "http://localhost:${startingPort + index}/agent-$index")
                    pathMap[it] = index
                }

        assertThat(agent.pathMapSize()).isEqualTo(originalSize + pathCount)

        // Call the proxy sequentially
        IntStream.range(0, sequentialQueryCount)
                .forEach {
                    callProxy(pathMap, "Sequential $it")
                    sleepForMillis(sequentialPauseMillis)
                }

        // Call the proxy in parallel
        val latch = CountDownLatch(parallelQueryCount)
        IntStream.range(0, parallelQueryCount)
                .forEach {
                    TestConstants
                            .EXECUTOR_SERVICE
                            .submit {
                                try {
                                    callProxy(pathMap, "Parallel $it")
                                    latch.countDown()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                }

        assertThat(latch.await(1, MINUTES)).isTrue()

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

        httpServers.forEach(Service::stop)
        sleepForSecs(5)
    }

    private fun callProxy(pathMap: Map<Int, Int>, msg: String) {
        //logger.info {"Calling proxy for ${msg}")
        // Choose one of the pathMap values
        val index = abs(TestConstants.RANDOM.nextInt() % pathMap.size)
        val httpVal = pathMap[index]
        "http://localhost:$PROXY_PORT/proxy-$index"
                .get {
                    if (it.code() != 200)
                        logger.error { "Proxy failed on $msg" }
                    assertThat(it.code()).isEqualTo(200)
                    val body = it.body()!!.string()
                    assertThat(body).isEqualTo("value: $httpVal")
                }
    }
}


