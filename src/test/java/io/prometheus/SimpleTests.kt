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
import io.prometheus.agent.AgentPathManager
import io.prometheus.common.Secs
import io.prometheus.dsl.KtorDsl.blockingGet
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogging
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldNotBeNull
import java.util.concurrent.atomic.AtomicInteger


@KtorExperimentalAPI
@InternalCoroutinesApi
@ExperimentalCoroutinesApi
object SimpleTests : KLogging() {

    fun missingPathTest(caller: String) {
        logger.info { "Calling missingPathTest() from $caller" }
        blockingGet("${TestConstants.PROXY_PORT}/".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
    }

    fun invalidPathTest(caller: String) {
        logger.info { "Calling invalidPathTest() from $caller" }
        blockingGet("${TestConstants.PROXY_PORT}/invalid_path".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
    }

    fun addRemovePathsTest(pathManager: AgentPathManager, caller: String) {
        logger.info { "Calling addRemovePathsTest() from $caller" }

        // Take into account pre-existing paths already registered
        val originalSize = pathManager.pathMapSize()

        var cnt = 0
        repeat(TestConstants.REPS) {
            val path = "test-$it"
            pathManager.let { manager ->
                manager.registerPath(path, "${TestConstants.PROXY_PORT}/$path".fixUrl())
                cnt++
                manager.pathMapSize() shouldEqual originalSize + cnt
                manager.unregisterPath(path)
                cnt--
                manager.pathMapSize() shouldEqual originalSize + cnt
            }
        }
    }

    fun invalidAgentUrlTest(pathManager: AgentPathManager, caller: String, badPath: String = "badPath") {
        logger.info { "Calling invalidAgentUrlTest() from $caller" }

        pathManager.registerPath(badPath, "33/metrics".fixUrl())
        blockingGet("${TestConstants.PROXY_PORT}/$badPath".fixUrl()) { resp ->
            resp.status shouldEqual HttpStatusCode.NotFound
        }
        pathManager.unregisterPath(badPath)
    }

    fun threadedAddRemovePathsTest(pathManager: AgentPathManager, caller: String) {
        logger.info { "Calling threadedAddRemovePathsTest() from $caller" }
        val paths = mutableListOf<String>()
        val cnt = AtomicInteger(0)

        // Take into account pre-existing paths already registered
        val originalSize = pathManager.pathMapSize()

        runBlocking {
            withTimeoutOrNull(Secs(30).toMillis().value) {
                val mutex = Mutex()
                val jobs = mutableListOf<Job>()
                repeat(TestConstants.REPS) {
                    jobs += GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler) {
                        val path = "test-${cnt.getAndIncrement()}"
                        val url = "${TestConstants.PROXY_PORT}/$path".fixUrl()
                        mutex.withLock { paths += path }
                        pathManager.registerPath(path, url)
                    }
                }
                jobs.forEach { job ->
                    job.join()
                    job.getCancellationException().cause.shouldBeNull()
                }
            }.shouldNotBeNull()
        }

        paths.size shouldEqual TestConstants.REPS
        pathManager.pathMapSize() shouldEqual (originalSize + TestConstants.REPS)

        runBlocking {
            withTimeoutOrNull(Secs(30).toMillis().value) {
                val jobs = mutableListOf<Job>()
                for (path in paths)
                    jobs += GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler) {
                        pathManager.unregisterPath(path)
                    }
                jobs.forEach { job ->
                    job.join()
                    job.getCancellationException().cause.shouldBeNull()
                }.shouldNotBeNull()
            }
        }

        pathManager.pathMapSize() shouldEqual originalSize
    }
}
