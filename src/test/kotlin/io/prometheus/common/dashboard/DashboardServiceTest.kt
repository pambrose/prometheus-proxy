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

package io.prometheus.common.dashboard

import com.google.common.util.concurrent.Service
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.BindException

/**
 * A dashboard's service lifecycle (`DashboardService`), over a mocked `DashboardServer`: a server that fails to start
 * is stopped, since Guava calls `shutDown()` only after a start that succeeds. The real server on a taken port is
 * covered through the agent, in `AgentStartupTest`.
 */
class DashboardServiceTest : StringSpec() {
  private class TestDashboardService(
    override val server: DashboardServer<Any, Any>,
  ) : DashboardService<Any, Any>()

  // A server whose start fails as Ktor's does on a port that's taken.
  private fun serverFailingToStart(): DashboardServer<Any, Any> =
    mockk<DashboardServer<Any, Any>>(relaxed = true).also { server ->
      every { server.start() } throws BindException("Address already in use")
    }

  init {
    // Ktor's start() registers a JVM shutdown hook and builds the application before its engine binds the port, and
    // leaves both when the bind fails, so the hook held the server, and the proxy or agent it renders, until the JVM
    // ended (November 23 review, #5).
    "a server that fails to start should be stopped, and the start's failure thrown" {
      val server = serverFailingToStart()
      val service = TestDashboardService(server)

      val failure = shouldThrow<IllegalStateException> { service.startSync() }

      // Captured as a value first, so each check below reports on its own inside assertSoftly.
      val suppressed = failure.cause?.suppressed.orEmpty().toList()
      assertSoftly {
        failure.cause.shouldBeInstanceOf<BindException>()
        suppressed.shouldBeEmpty()
        service.state() shouldBe Service.State.FAILED
        verify(exactly = 1) { server.stop() }
      }
    }

    "a stop that fails after a failed start should be attached to the start's failure" {
      val server = serverFailingToStart()
      every { server.stop() } throws IllegalStateException("stop failed")
      val service = TestDashboardService(server)

      val failure = shouldThrow<IllegalStateException> { service.startSync() }

      // Captured as a value first, so each check below reports on its own inside assertSoftly.
      val suppressed = failure.cause?.suppressed.orEmpty().map { it.message }
      assertSoftly {
        failure.cause.shouldBeInstanceOf<BindException>()
        suppressed shouldBe listOf("stop failed")
      }
    }

    "a server that starts should be stopped only when the service stops" {
      val server = mockk<DashboardServer<Any, Any>>(relaxed = true)
      val service = TestDashboardService(server)

      service.startSync()
      verify(exactly = 0) { server.stop() }
      service.stopSync()

      verify(exactly = 1) { server.stop() }
    }
  }
}
