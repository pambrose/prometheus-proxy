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

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.ktor.http.HttpStatusCode
import io.prometheus.agent.AgentPathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import kotlin.time.seconds

object SimpleTests : KLogging() {

  fun missingPathTest(caller: String) {
    logger.debug { "Calling missingPathTest() from $caller" }
    blockingGet("${TestConstants.PROXY_PORT}/".addPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
  }

  fun invalidPathTest(caller: String) {
    logger.debug { "Calling invalidPathTest() from $caller" }
    blockingGet("${TestConstants.PROXY_PORT}/invalid_path".addPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
  }

  fun addRemovePathsTest(pathManager: AgentPathManager, caller: String) {
    logger.debug { "Calling addRemovePathsTest() from $caller" }

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    var cnt = 0
    repeat(TestConstants.REPS) { i ->
      val path = "test-$i"
      pathManager.let { manager ->
        manager.registerPath(path, "${TestConstants.PROXY_PORT}/$path".addPrefix())
        cnt++
        manager.pathMapSize() shouldBeEqualTo originalSize + cnt
        manager.unregisterPath(path)
        cnt--
        manager.pathMapSize() shouldBeEqualTo originalSize + cnt
      }
    }
  }

  fun invalidAgentUrlTest(pathManager: AgentPathManager, caller: String, badPath: String = "badPath") {
    logger.debug { "Calling invalidAgentUrlTest() from $caller" }

    pathManager.registerPath(badPath, "33/metrics".addPrefix())
    blockingGet("${TestConstants.PROXY_PORT}/$badPath".addPrefix()) { response ->
      response.status shouldBeEqualTo HttpStatusCode.NotFound
    }
    pathManager.unregisterPath(badPath)
  }

  fun threadedAddRemovePathsTest(pathManager: AgentPathManager, caller: String) {
    logger.debug { "Calling threadedAddRemovePathsTest() from $caller" }
    val paths: MutableList<String> = mutableListOf()

    // Take into account pre-existing paths already registered
    val originalSize = pathManager.pathMapSize()

    runBlocking {
      withTimeoutOrNull(30.seconds.toLongMilliseconds()) {
        val mutex = Mutex()
        val jobs =
          List(TestConstants.REPS) { i ->
            GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler(logger)) {
              val path = "test-$i}"
              val url = "${TestConstants.PROXY_PORT}/$path".addPrefix()
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

    paths.size shouldBeEqualTo TestConstants.REPS
    pathManager.pathMapSize() shouldBeEqualTo (originalSize + TestConstants.REPS)

    runBlocking {
      withTimeoutOrNull(30.seconds.toLongMilliseconds()) {
        val jobs =
            List(paths.size) {
              GlobalScope.launch(Dispatchers.Default + coroutineExceptionHandler(logger)) {
                pathManager.unregisterPath(paths[it])
              }
            }
        jobs.forEach { job ->
          job.join()
          job.getCancellationException().cause.shouldBeNull()
        }.shouldNotBeNull()
      }
    }

    pathManager.pathMapSize() shouldBeEqualTo originalSize
  }
}
