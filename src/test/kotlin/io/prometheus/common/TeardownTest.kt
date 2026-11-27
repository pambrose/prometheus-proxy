/*
 * Copyright © 2026 Paul Ambrose
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

package io.prometheus.common

import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.Service
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The steps the proxy and the agent release what they hold with, as they stop and when their startup fails: every step
 * runs, even after one that throws, and a sub-service that FAILED isn't stopped (November 26 review, #5).
 */
class TeardownTest : StringSpec() {
  // A service whose startUp() or shutDown() throws when told to, with no thread of its own to leave behind.
  private class TestService(
    private val failStart: Boolean = false,
    private val failStop: Boolean = false,
  ) : AbstractIdleService() {
    var stopped = false

    override fun startUp() {
      check(!failStart) { "start failed" }
    }

    override fun shutDown() {
      stopped = true
      check(!failStop) { "stop failed" }
    }
  }

  init {
    "runEachOrThrow should run every step and throw the first failure, with the rest suppressed" {
      val ran = mutableListOf<Int>()

      // Captured as a value first, so each check reports on its own inside assertSoftly.
      val failure =
        runCatching {
          listOf<() -> Unit>(
            { ran += 1 },
            {
              ran += 2
              error("second")
            },
            { ran += 3 },
            {
              ran += 4
              error("fourth")
            },
          ).runEachOrThrow()
        }.exceptionOrNull()

      assertSoftly {
        ran shouldBe [1, 2, 3, 4]
        failure?.message shouldBe "second"
        failure?.suppressed.orEmpty().map { it.message } shouldBe ["fourth"]
      }
    }

    "runEachOrThrow should return when no step throws" {
      val ran = mutableListOf<Int>()
      listOf<() -> Unit>({ ran += 1 }, { ran += 2 }).runEachOrThrow()
      ran shouldBe [1, 2]
    }

    "startOrRelease should run no release step when the start succeeds" {
      val released = mutableListOf<Int>()
      startOrRelease(listOf({ released += 1 })) { }
      released shouldBe emptyList()
    }

    // The start's failure is the one thrown: a release step that throws too only adds to it.
    "startOrRelease should run every release step when the start fails, and throw the start's failure" {
      val released = mutableListOf<Int>()

      val failure =
        runCatching {
          startOrRelease(
            listOf(
              {
                released += 1
                error("release failed")
              },
              { released += 2 },
            ),
          ) { error("start failed") }
        }.exceptionOrNull()

      assertSoftly {
        released shouldBe [1, 2]
        failure?.message shouldBe "start failed"
        failure?.suppressed.orEmpty().map { it.message } shouldBe ["release failed"]
      }
    }

    "stopUnlessFailed should stop a running service" {
      val service = TestService().apply { startAsync().awaitRunning() }
      service.stopUnlessFailed()
      assertSoftly {
        service.state() shouldBe Service.State.TERMINATED
        service.stopped shouldBe true
      }
    }

    // A rollback stops every sub-service, so one the failure came before never started.
    "stopUnlessFailed should terminate a service that never started, without stopping it" {
      val service = TestService()
      service.stopUnlessFailed()
      assertSoftly {
        service.state() shouldBe Service.State.TERMINATED
        service.stopped shouldBe false
      }
    }

    // A FAILED service's wait to terminate throws, which once kept every later step from running.
    "stopUnlessFailed should leave a service that failed alone" {
      val service = TestService(failStart = true)
      runCatching { service.startAsync().awaitRunning() }
      service.stopUnlessFailed()
      service.state() shouldBe Service.State.FAILED
    }

    "stopUnlessFailed should throw when the service fails to stop" {
      val service = TestService(failStop = true).apply { startAsync().awaitRunning() }
      val failure = runCatching { service.stopUnlessFailed() }.exceptionOrNull()
      assertSoftly {
        failure?.cause?.message shouldBe "stop failed"
        service.state() shouldBe Service.State.FAILED
      }
    }
  }
}
