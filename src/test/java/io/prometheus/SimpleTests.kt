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

import io.ktor.http.HttpStatusCode
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.fixUrl
import io.prometheus.dsl.KtorDsl
import mu.KLogging
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldEqual
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


@KtorExperimentalAPI
object SimpleTests : KLogging() {

    fun missingPathTest(caller: String) {
        ProxyTests.logger.info { "Calling missingPathTest() from $caller" }
        KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
    }

    fun invalidPathTest(caller: String) {
        ProxyTests.logger.info { "Calling invalidPathTest() from $caller" }
        KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/invalid_path".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
    }

    fun addRemovePathsTest(agent: Agent, caller: String) {
        ProxyTests.logger.info { "Calling addRemovePathsTest() from $caller" }

        // Take into account pre-existing paths already registered
        val originalSize = agent.pathMapSize()

        var cnt = 0
        repeat(TestConstants.REPS) {
            val path = "test-$it"
            agent.registerPath(path, "http://localhost:${TestConstants.PROXY_PORT}/$path")
            cnt++
            agent.pathMapSize() shouldEqual originalSize + cnt
            agent.unregisterPath(path)
            cnt--
            agent.pathMapSize() shouldEqual originalSize + cnt
        }
    }

    fun invalidAgentUrlTest(agent: Agent, caller: String, badPath: String = "badPath") {
        ProxyTests.logger.info { "Calling invalidAgentUrlTest() from $caller" }

        agent.registerPath(badPath, "http://localhost:33/metrics")
        KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/$badPath".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
        agent.unregisterPath(badPath)
    }

    fun threadedAddRemovePathsTest(agent: Agent, caller: String) {
        ProxyTests.logger.info { "Calling threadedAddRemovePathsTest() from $caller" }
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
                        val url = "http://localhost:${TestConstants.PROXY_PORT}/$path"
                        agent.registerPath(path, url)
                        latch1.countDown()
                    } catch (e: RequestFailureException) {
                        e.printStackTrace()
                    }
                }
        }

        latch1.await(1, TimeUnit.MINUTES).shouldBeTrue()
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
        latch2.await(1, TimeUnit.MINUTES).shouldBeTrue()
        agent.pathMapSize() shouldEqual originalSize
    }
}
