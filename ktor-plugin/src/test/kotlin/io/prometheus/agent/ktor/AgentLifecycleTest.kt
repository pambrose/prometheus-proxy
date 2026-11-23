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

package io.prometheus.agent.ktor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.MapApplicationConfig
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.common.ConfigLoadException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// The lifecycle's events raised by hand on an app's monitor, with the agent's start replaced: whether the agent starts,
// what a failure does, and that it stops once, whichever events come.
class AgentLifecycleTest : StringSpec() {
  init {
    "the app's start starts the agent, which the app's handle then gives" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = "localhost:50051")

      app.started()

      lifecycle.launched?.toString() shouldContain "endpoints=[localhost:50051]"
      app.application.prometheusProxyAgent shouldBeSameInstanceAs app.agent
    }

    "another app's start on the same monitor, as a reload gives, starts nothing" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = "localhost:50051")

      app.events.raise(ApplicationStarted, mockk<Application>())

      lifecycle.launched.shouldBeNull()
    }

    "a disabled agent stays off" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = "localhost:50051") { enabled = false }

      app.started()

      lifecycle.launched.shouldBeNull()
      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "without a proxy the agent stays off" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = null)

      app.started()

      lifecycle.launched.shouldBeNull()
      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "without a proxy a required agent fails the app's start" {
      val app = TestApp()
      app.lifecycle(proxy = null) { required = true }

      shouldThrow<IllegalStateException> { app.started() }.message shouldBe
        "prometheus-proxy.agent.proxy isn't set, and prometheus-proxy.agent.required is true"
    }

    "a path without a registry still starts the agent" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = "localhost:50051") { path = "unused_metrics" }

      app.started()

      lifecycle.launched?.toString()?.contains("unused_metrics") shouldBe false
    }

    "an agent that can't start stays off" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = ConfigLoadException("bad certificate"))

      app.started()

      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "a required agent that can't start fails the app's start with why" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = ConfigLoadException("bad certificate")) { required = true }

      shouldThrow<ConfigLoadException> { app.started() }.message shouldBe "bad certificate"
    }

    "a checked exception from a required agent's start fails the app's start too" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = TimeoutException("too slow")) { required = true }

      val thrown = shouldThrow<IllegalStateException> { app.started() }

      thrown.message shouldBe "The Prometheus agent could not start"
      thrown.cause?.message shouldBe "too slow"
    }

    "the engine's stop begins the agent's, and the app's disposal waits for it" {
      val app = TestApp()
      val stopping = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { app.agent.shutdown() } answers {
        stopping.countDown()
        release.await()
      }
      app.lifecycle(proxy = "localhost:50051")
      app.started()

      // Returns while the agent is still stopping, so the engine's grace period is the app's
      app.events.raise(ApplicationStopPreparing, mockk<ApplicationEnvironment>())

      stopping.await(5, TimeUnit.SECONDS) shouldBe true
      app.application.prometheusProxyAgent.shouldBeNull()
      release.countDown()
      app.events.raise(ApplicationStopping, app.application)
      verify(exactly = 1) { app.agent.shutdown() }
    }

    "an app disposed without the engine stopping still stops the agent" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051")
      app.started()

      app.events.raise(ApplicationStopping, app.application)

      verify(exactly = 1) { app.agent.shutdown() }
      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "an agent that fails to stop doesn't fail the app's" {
      val app = TestApp()
      every { app.agent.shutdown() } throws IllegalStateException("stuck")
      app.lifecycle(proxy = "localhost:50051")
      app.started()

      app.events.raise(ApplicationStopPreparing, mockk<ApplicationEnvironment>())
      app.events.raise(ApplicationStopping, app.application)

      verify(exactly = 1) { app.agent.shutdown() }
    }

    "an agent still stopping when the wait runs out doesn't hold up the app's disposal" {
      val app = TestApp()
      val release = CountDownLatch(1)
      every { app.agent.shutdown() } answers { release.await() }
      app.lifecycle(proxy = "localhost:50051", stopTimeoutMillis = 50)
      app.started()

      app.events.raise(ApplicationStopping, app.application)

      // Returned while the agent's stop still blocks
      release.count shouldBe 1
      release.countDown()
    }

    "stopping an agent that never started does nothing" {
      val app = TestApp()
      app.lifecycle(proxy = null)
      app.started()

      app.events.raise(ApplicationStopPreparing, mockk<ApplicationEnvironment>())
      app.events.raise(ApplicationStopping, app.application)

      verify(exactly = 0) { app.agent.shutdown() }
    }

    "once the app stops, its handlers are gone, so the next app's start starts nothing more" {
      val app = TestApp()
      val lifecycle = app.lifecycle(proxy = "localhost:50051")
      app.started()
      app.events.raise(ApplicationStopping, app.application)
      app.events.raise(ApplicationStopped, app.application)

      app.started()

      lifecycle.launches shouldBe 1
    }
  }

  // An app's monitor and attributes, its agent's handle, and the lifecycle under test
  private class TestApp {
    val events = Events()
    val application =
      mockk<Application> {
        every { monitor } returns events
        every { attributes } returns Attributes()
      }
    val agent =
      mockk<EmbeddedAgentInfo>(relaxed = true) {
        every { agentName } returns "test-agent"
      }

    fun started() = events.raise(ApplicationStarted, application)

    fun lifecycle(
      proxy: String?,
      failure: Exception? = null,
      stopTimeoutMillis: Long = 5_000,
      configure: PrometheusProxyAgentConfig.() -> Unit = {},
    ): Launches {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig()).apply {
        proxy?.also { agent { this.proxy = it } }
      }
      config.configure()
      val launches = Launches()
      AgentLifecycle(application = application, config = config, stopTimeoutMillis = stopTimeoutMillis) { settings ->
        launches.launches++
        failure?.also { throw it }
        launches.launched = settings
        agent
      }.subscribe()
      return launches
    }
  }

  private class Launches {
    var launched: EmbeddedAgentConfig? = null
    var launches = 0
  }
}
