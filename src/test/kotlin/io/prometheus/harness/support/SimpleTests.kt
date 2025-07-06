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

package io.prometheus.harness.support

import com.github.pambrose.common.dsl.KtorDsl
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.prometheus.agent.AgentPathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal object SimpleTests {
  private val logger = KotlinLogging.logger {}

  fun missingPathTest(caller: String) {
    logger.debug { "Calling missingPathTest() from $caller" }
    KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/".withPrefix()) { response ->
      response.status shouldBe HttpStatusCode.Companion.NotFound
    }
  }

  fun invalidPathTest(caller: String) {
    logger.debug { "Calling invalidPathTest() from $caller" }
    KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/invalid_path".withPrefix()) { response ->
      response.status shouldBe HttpStatusCode.Companion.NotFound
    }
  }

  suspend fun addRemovePathsTest(
    pathManager: AgentPathManager,
    caller: String,
  ) {
    logger.debug { "Calling addRemovePathsTest() from $caller" }

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    var cnt = 0
    repeat(TestConstants.REPS) { i ->
      val path = "test-$i"
      pathManager.let { manager ->
        manager.registerPath(path, "${TestConstants.PROXY_PORT}/$path".withPrefix())
        cnt++
        manager.pathMapSize() shouldBe originalSize + cnt
        manager.unregisterPath(path)
        cnt--
        manager.pathMapSize() shouldBe originalSize + cnt
      }
    }
  }

  suspend fun invalidAgentUrlTest(
    pathManager: AgentPathManager,
    caller: String,
    badPath: String = "badPath",
  ) {
    logger.debug { "Calling invalidAgentUrlTest() from $caller" }

    pathManager.registerPath(badPath, "33/metrics".withPrefix())
    KtorDsl.blockingGet("${TestConstants.PROXY_PORT}/$badPath".withPrefix()) { response ->
      response.status shouldBe HttpStatusCode.Companion.NotFound
    }
    pathManager.unregisterPath(badPath)
  }

  suspend fun threadedAddRemovePathsTest(
    pathManager: AgentPathManager,
    caller: String,
  ) {
    logger.debug { "Calling threadedAddRemovePathsTest() from $caller" }
    val paths: MutableList<String> = mutableListOf()

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    withTimeoutOrNull(30.seconds.inWholeMilliseconds) {
      val mutex = Mutex()
      val jobs =
        List(TestConstants.REPS) { i ->
          launch(Dispatchers.Default + exceptionHandler(logger)) {
            val path = "test-$i}"
            val url = "${TestConstants.PROXY_PORT}/$path".withPrefix()
            mutex.withLock { paths += path }
            pathManager.registerPath(path, url)
          }
        }

      jobs.forEach { job ->
        job.join()
        job.getCancellationException().cause.shouldBeNull()
      }
    }.shouldNotBeNull()

    paths.size shouldBe TestConstants.REPS
    pathManager.pathMapSize() shouldBe (originalSize + TestConstants.REPS)

    withTimeoutOrNull(30.seconds.inWholeMilliseconds) {
      val jobs =
        List(paths.size) {
          launch(Dispatchers.Default + exceptionHandler(logger)) {
            pathManager.unregisterPath(paths[it])
          }
        }

      jobs.forEach { job ->
        job.join()
        job.getCancellationException().cause.shouldBeNull()
      }
    }.shouldNotBeNull()

    pathManager.pathMapSize() shouldBe originalSize
  }
}
