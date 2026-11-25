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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The lifecycle's events raised by hand on an app's monitor, with the agent's start replaced: whether the agent starts,
// what a failure does, and that it stops once, whichever events come.
class AgentLifecycleTest : StringSpec() {
  init {
    "the app's start starts the agent, which the app's handle then gives" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = "localhost:50051")

      app.started()

      launches.single().toString() shouldContain "endpoints=[localhost:50051]"
      app.application.prometheusProxyAgent shouldBeSameInstanceAs app.agent
    }

    "another app's start on the same monitor, as a reload gives, starts nothing" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = "localhost:50051")

      app.events.raise(ApplicationStarted, mockk<Application>())

      launches.shouldBeEmpty()
    }

    "a disabled agent stays off" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = "localhost:50051") { enabled = false }

      app.started()

      launches.shouldBeEmpty()
      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "without a proxy the agent stays off" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = null)

      app.started()

      launches.shouldBeEmpty()
      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "without a proxy a required agent fails the app's start" {
      val app = TestApp()
      app.lifecycle(proxy = null) { required = true }

      shouldThrow<IllegalStateException> { app.started() }.message shouldBe
        "No proxy is set (prometheus-proxy.agent.proxy or PROXY_HOSTNAME), and prometheus-proxy.agent.required is true"
    }

    // The agent's own rule, EmbeddedAgentConfig.namesProxy(), read once the file and the blocks have made the settings:
    // the plugin read only the builder's proxy, so a proxy from a settings key, or the agent's PROXY_HOSTNAME, left the
    // agent off, and failed a required one's app (November 23 review, #11)
    "a proxy the file's settings name starts the agent, a required one too, without a proxy key" {
      val app = TestApp()
      val launches =
        app.lifecycle(proxy = null, file = mapOf("settings.proxy.hostname" to "proxy.example.com")) { required = true }

      app.started()

      launches.single().toString() shouldContain "hostname=proxy.example.com"
      app.application.prometheusProxyAgent shouldBeSameInstanceAs app.agent
    }

    "a proxy an agent block sets with setting() starts the agent" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = null) { agent { setting("proxy.endpoints", listOf("proxy.example.com")) } }

      app.started()

      launches.single().toString() shouldContain "endpoints=[proxy.example.com]"
    }

    "a path without a registry still starts the agent" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = "localhost:50051") { path = "unused_metrics" }

      app.started()

      launches.single().toString() shouldNotContain "unused_metrics"
    }

    "an agent that can't start stays off" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = ConfigLoadException("bad certificate"))

      app.started()

      app.application.prometheusProxyAgent.shouldBeNull()
    }

    // As startIfConfigured, which starts an agent that isn't required, returns null for a start that fails, having
    // logged why
    "an agent whose start leaves it off stays off" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", started = false)

      app.started()

      app.application.prometheusProxyAgent.shouldBeNull()
    }

    "a required agent that can't start fails the app's start with why" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = ConfigLoadException("bad certificate")) { required = true }

      shouldThrow<ConfigLoadException> { app.started() }.message shouldBe "bad certificate"
    }

    // Kotlin can throw a checked exception undeclared, which Ktor would pass on as it is
    "a checked exception from a required agent's start fails the app's start too" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", failure = IOException("unreachable")) { required = true }

      val thrown = shouldThrow<IllegalStateException> { app.started() }

      thrown.message shouldBe "The Prometheus agent could not start"
      thrown.cause?.message shouldBe "unreachable"
    }

    // The blocks add the handlers, after the file's settings, so a value in the file the plugin can't read fails before
    // they're added: the blocks still run, and their handlers are told, as of a start that fails
    "a value in the file the plugin can't read leaves the agent off, and tells the blocks' onFailure handlers" {
      val app = TestApp()
      val heard = mutableListOf<String>()
      val launches =
        app.lifecycle(proxy = "localhost:50051", file = mapOf("metrics-port" to "ninety")) {
          agent { onFailure { reason, _ -> heard += reason } }
        }

      app.started()

      launches.shouldBeEmpty()
      app.application.prometheusProxyAgent.shouldBeNull()
      heard shouldBe listOf("prometheus-proxy.agent.metrics-port must be a number: ninety")
    }

    "an agent block that throws leaves the agent off, and tells the handlers of the blocks before and after it" {
      val app = TestApp()
      val heard = mutableListOf<String>()
      val launches =
        app.lifecycle(proxy = "localhost:50051") {
          agent { onFailure { reason, _ -> heard += "before: $reason" } }
          agent { error("the secret store is down") }
          agent { onFailure { reason, _ -> heard += "after: $reason" } }
        }

      app.started()

      launches.shouldBeEmpty()
      heard shouldBe listOf("before: the secret store is down", "after: the secret store is down")
    }

    // As start() tells no handler: the app's start fails with why instead
    "a required agent whose settings fail fails the app's start, and tells no handler" {
      val app = TestApp()
      val heard = mutableListOf<String>()
      app.lifecycle(proxy = "localhost:50051", file = mapOf("metrics-port" to "ninety")) {
        required = true
        agent { onFailure { reason, _ -> heard += reason } }
      }

      shouldThrow<ConfigLoadException> { app.started() }.message shouldBe
        "prometheus-proxy.agent.metrics-port must be a number: ninety"
      heard.shouldBeEmpty()
    }

    "the engine's stop begins the agent's without waiting for it, and the app's disposal doesn't stop it again" {
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

    // AGENTS.md's split: the engine's stop begins the agent's, and the app's disposal waits for it, so the engine
    // doesn't finish disposing while the agent still stops. The agent's stop ends only once the disposal is waiting in
    // its join, so without the join, the disposal returns first, every time (November 23 review, #54).
    "the app's disposal waits for the agent's stop the engine began" {
      val app = TestApp()
      val release = CountDownLatch(1)
      val stopped = AtomicBoolean(false)
      every { app.agent.shutdown() } answers {
        release.await()
        stopped.set(true)
      }
      app.lifecycle(proxy = "localhost:50051")
      app.started()
      app.events.raise(ApplicationStopPreparing, mockk<ApplicationEnvironment>())
      releaseOnceJoining(waiter = Thread.currentThread(), release = release)

      app.events.raise(ApplicationStopping, app.application)

      // Read as soon as the disposal returns
      stopped.get() shouldBe true
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
      app.lifecycle(proxy = "localhost:50051", stopTimeout = 50.milliseconds)
      app.started()

      app.events.raise(ApplicationStopping, app.application)

      // Returned while the agent's stop still blocks
      release.count shouldBe 1
      release.countDown()
    }

    // Duration.INFINITE waits for as long as the stop takes, as the handle's shutdown does: the second the disposal
    // adds to it mustn't overflow its wait
    "an infinite stop timeout doesn't fail the app's disposal" {
      val app = TestApp()
      app.lifecycle(proxy = "localhost:50051", stopTimeout = Duration.INFINITE)
      app.started()

      app.events.raise(ApplicationStopping, app.application)

      verify(exactly = 1) { app.agent.shutdown() }
    }

    "stopping an agent that never started does nothing" {
      val app = TestApp()
      app.lifecycle(proxy = null)
      app.started()

      app.events.raise(ApplicationStopPreparing, mockk<ApplicationEnvironment>())
      app.events.raise(ApplicationStopping, app.application)

      verify(exactly = 0) { app.agent.shutdown() }
    }

    "once the app stops, its handlers are gone, so its start event raised again starts nothing more" {
      val app = TestApp()
      val launches = app.lifecycle(proxy = "localhost:50051")
      app.started()
      app.events.raise(ApplicationStopping, app.application)
      app.events.raise(ApplicationStopped, app.application)

      app.started()

      launches.size shouldBe 1
    }
  }

  // Opens [release] once [waiter] waits in Thread.join, the wait under test, or after 5 seconds without it, so the
  // agent's stop ends either way.
  private fun releaseOnceJoining(
    waiter: Thread,
    release: CountDownLatch,
  ) = thread(isDaemon = true) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

    fun joining() = waiter.stackTrace.any { it.className == "java.lang.Thread" && it.methodName == "join" }

    while (!joining() && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    release.countDown()
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
      started: Boolean = true,
      // The agent block's, shorter than the plugin's default, so a spec whose agent never stops doesn't wait 30 seconds
      stopTimeout: Duration = 5.seconds,
      // The file's prometheus-proxy.agent section, by its keys
      file: Map<String, String> = emptyMap(),
      configure: PrometheusProxyAgentConfig.() -> Unit = {},
    ): List<EmbeddedAgentConfig> {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig(*file.toList().toTypedArray())).apply {
        proxy?.also { agent { this.proxy = it } }
      }
      config.agent { this.stopTimeout = stopTimeout }
      config.configure()
      // The settings of each launch, a failed one's included
      val launches = mutableListOf<EmbeddedAgentConfig>()
      AgentLifecycle(application = application, config = config) { settings ->
        launches += settings
        failure?.also { throw it }
        agent.takeIf { started }
      }.subscribe()
      return launches
    }
  }
}
