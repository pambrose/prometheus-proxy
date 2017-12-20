/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus

import com.google.common.collect.Lists
import com.google.common.collect.Maps
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.Utils
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import spark.Service
import java.lang.Math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

object Tests {

    @Throws(Exception::class)
    fun missingPathTest() {
        val url = "http://localhost:${TestConstants.PROXY_PORT}/"
        val request = Request.Builder().url(url)
        TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute().use({ response -> assertThat(response.code()).isEqualTo(404) })
    }

    @Throws(Exception::class)
    fun invalidPathTest() {
        val url = "http://localhost:${TestConstants.PROXY_PORT}/invalid_path"
        val request = Request.Builder().url(url)
        TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute().use({ response -> assertThat(response.code()).isEqualTo(404) })
    }

    @Throws(Exception::class)
    fun addRemovePathsTest(agent: Agent) {

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        var cnt = 0
        for (i in 0 until TestConstants.REPS) {
            val path = "test-$i"
            agent.registerPath(path, "http://localhost:${TestConstants.PROXY_PORT}/$path")
            cnt++
            assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt)
            agent.unregisterPath(path)
            cnt--
            assertThat(agent.pathMapSize()).isEqualTo(originalSize + cnt)
        }
    }

    @Throws(Exception::class)
    fun threadedAddRemovePathsTest(agent: Agent) {
        val paths = Lists.newArrayList<String>()
        val cnt = AtomicInteger(0)
        val latch1 = CountDownLatch(TestConstants.REPS)
        val latch2 = CountDownLatch(TestConstants.REPS)

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        IntStream.range(0, TestConstants.REPS)
                .forEach { `val` ->
                    TestConstants.EXECUTOR_SERVICE.submit(
                            {
                                val path = "test-${cnt.getAndIncrement()}"
                                synchronized(paths) {
                                    paths.add(path)
                                }
                                try {
                                    val url = "http://localhost:${TestConstants.PROXY_PORT}/$path"
                                    agent.registerPath(path, url)
                                    latch1.countDown()
                                } catch (e: RequestFailureException) {
                                    e.printStackTrace()
                                }
                            })
                }

        assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(paths.size).isEqualTo(TestConstants.REPS)
        assertThat(agent.pathMapSize()).isEqualTo(originalSize + TestConstants.REPS)

        paths.forEach { path ->
            TestConstants.EXECUTOR_SERVICE.submit(
                    {
                        try {
                            agent.unregisterPath(path)
                            latch2.countDown()
                        } catch (e: RequestFailureException) {
                            e.printStackTrace()
                        }
                    })
        }

        // Wait for all unregistrations to complete
        assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(agent.pathMapSize()).isEqualTo(originalSize)
    }

    @Throws(Exception::class)
    fun invalidAgentUrlTest(agent: Agent) {
        val badPath = "badPath"

        agent.registerPath(badPath, "http://localhost:33/metrics")

        val url = "http://localhost:${TestConstants.PROXY_PORT}/$badPath"
        val request = Request.Builder().url(url)
        TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute().use({ response -> assertThat(response.code()).isEqualTo(404) })

        agent.unregisterPath(badPath)
    }

    @Throws(Exception::class)
    fun timeoutTest(agent: Agent) {
        val agentPort = 9700
        val proxyPath = "proxy-timeout"
        val agentPath = "agent-timeout"

        val http = Service.ignite()
        http.port(agentPort)
                .get("/$agentPath") { req, res ->
                    res.type("text/plain")
                    Utils.sleepForSecs(10)
                    "I timed out"
                }
        val agentUrl = "http://localhost:$agentPort/$agentPath"
        agent.registerPath("/$proxyPath", agentUrl)

        val proxyUrl = "http://localhost:${TestConstants.PROXY_PORT}/$proxyPath"
        val request = Request.Builder().url(proxyUrl)
        TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute().use({ response -> assertThat(response.code()).isEqualTo(404) })

        agent.unregisterPath("/$proxyPath")
        http.stop()
    }

    @Throws(Exception::class)
    fun proxyCallTest(agent: Agent,
                      httpServerCount: Int,
                      pathCount: Int,
                      queryCount: Int,
                      pauseMillis: Long) {

        val startingPort = 9600
        val httpServers = Lists.newArrayList<Service>()
        val pathMap = Maps.newConcurrentMap<Int, Int>()

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        // Create the endpoints
        IntStream.range(0, httpServerCount)
                .forEach { i ->
                    val http = Service.ignite()
                    http.port(startingPort + i)
                            .threadPool(30, 10, 1000)
                            .get("/agent-$i") { req, res ->
                                res.type("text/plain")
                                "value: $i"
                            }
                    httpServers.add(http)
                }

        // Create the paths
        for (i in 0 until pathCount) {
            val index = abs(TestConstants.RANDOM.nextInt()) % httpServers.size
            val url = "http://localhost:${startingPort + index}/agent-$index"
            agent.registerPath("proxy-$i", url)
            pathMap.put(i, index)
        }

        assertThat(agent.pathMapSize()).isEqualTo(originalSize + pathCount)

        // Call the proxy sequentially
        for (i in 0 until queryCount) {
            callProxy(pathMap)
            Utils.sleepForMillis(pauseMillis)
        }

        // Call the proxy in parallel
        val threadedQueryCount = 100
        val latch = CountDownLatch(threadedQueryCount)
        IntStream.range(0, threadedQueryCount)
                .forEach { i ->
                    TestConstants.EXECUTOR_SERVICE.submit(
                            {
                                try {
                                    callProxy(pathMap)
                                    latch.countDown()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            })
                }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()

        val errorCnt = AtomicInteger()
        pathMap.forEach { k, v ->
            try {
                agent.unregisterPath("proxy-$k")
            } catch (e: RequestFailureException) {
                errorCnt.incrementAndGet()
            }
        }

        assertThat(errorCnt.get()).isEqualTo(0)
        assertThat(agent.pathMapSize()).isEqualTo(originalSize)

        httpServers.forEach(Service::stop)
    }

    @Throws(Exception::class)
    private fun callProxy(pathMap: Map<Int, Int>) {
        // Choose one of the pathMap values
        val index = abs(TestConstants.RANDOM.nextInt() % pathMap.size)
        val httpVal = pathMap[index]
        val url = "http://localhost:${TestConstants.PROXY_PORT}/proxy-$index"
        val request = Request.Builder().url(url)
        TestConstants.OK_HTTP_CLIENT.newCall(request.build()).execute().use({ response ->
                                                                                assertThat(response.code()).isEqualTo(200)
                                                                                val body = response.body()!!.string()
                                                                                assertThat(body).isEqualTo("value: $httpVal")
                                                                            })
    }
}
