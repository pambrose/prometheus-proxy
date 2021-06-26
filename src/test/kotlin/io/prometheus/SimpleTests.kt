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

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.ktor.http.*
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.AgentPathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import kotlin.time.Duration.Companion.seconds

internal object SimpleTests : KLogging() {

  fun missingPathTest(caller: String) {
    logger.debug { "Calling missingPathTest() from $caller" }
    blockingGet("$PROXY_PORT/".withPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
  }

  fun invalidPathTest(caller: String) {
    logger.debug { "Calling invalidPathTest() from $caller" }
    blockingGet("$PROXY_PORT/invalid_path".withPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
  }

  suspend fun addRemovePathsTest(pathManager: AgentPathManager, caller: String) {
    logger.debug { "Calling addRemovePathsTest() from $caller" }

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    var cnt = 0
    repeat(TestConstants.REPS) { i ->
      val path = "test-$i"
      pathManager.let { manager ->
        manager.registerPath(path, "$PROXY_PORT/$path".withPrefix())
        cnt++
        manager.pathMapSize() shouldBeEqualTo originalSize + cnt
        manager.unregisterPath(path)
        cnt--
        manager.pathMapSize() shouldBeEqualTo originalSize + cnt
      }
    }
  }

  suspend fun invalidAgentUrlTest(pathManager: AgentPathManager, caller: String, badPath: String = "badPath") {
    logger.debug { "Calling invalidAgentUrlTest() from $caller" }

    pathManager.registerPath(badPath, "33/metrics".withPrefix())
    blockingGet("$PROXY_PORT/$badPath".withPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
    pathManager.unregisterPath(badPath)
  }

  suspend fun threadedAddRemovePathsTest(pathManager: AgentPathManager, caller: String) {
    logger.debug { "Calling threadedAddRemovePathsTest() from $caller" }
    val paths: MutableList<String> = mutableListOf()

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    withTimeoutOrNull(seconds(30).inWholeMilliseconds) {
      val mutex = Mutex()
      val jobs =
        List(TestConstants.REPS) { i ->
          launch(Dispatchers.Default + exceptionHandler(logger)) {
            val path = "test-$i}"
            val url = "$PROXY_PORT/$path".withPrefix()
            mutex.withLock { paths += path }
            pathManager.registerPath(path, url)
          }
        }

      jobs.forEach { job ->
        job.join()
        job.getCancellationException().cause.shouldBeNull()
      }
    }.shouldNotBeNull()

    paths.size shouldBeEqualTo TestConstants.REPS
    pathManager.pathMapSize() shouldBeEqualTo (originalSize + TestConstants.REPS)

    withTimeoutOrNull(seconds(30).inWholeMilliseconds) {
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

    pathManager.pathMapSize() shouldBeEqualTo originalSize
  }
}