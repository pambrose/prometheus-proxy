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

package io.prometheus.harness

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentOptions.Companion.agentOptions
import io.prometheus.client.CollectorRegistry
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.proxy.ProxyOptions.Companion.proxyOptions
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

// Finding 1: nothing cancels the idle server-streaming readRequestsFromProxy collect when stopSync()
// flips isRunning to false. Guava's AbstractExecutionThreadService only runs the shutDown() hook
// (which tears down the channel) AFTER run() returns, but run() is parked in that collect -- a
// circular wait. The fix overrides Agent.triggerShutdown() to shut the gRPC channel down on the
// stopping thread, breaking the collect so run() can return.
//
// The existing harness suite structurally cannot catch this: HarnessSetup.takeDownProxyAndAgent()
// stops the proxy and agent concurrently, so proxy teardown errors the stream and releases the agent.
// This spec keeps the proxy up and stops the agent alone while the connection is healthy but idle.
class InProcessIdleShutdownTest : StringSpec() {
  init {
    "Finding 1: stopping an idle connected agent alone must not deadlock" {
      CollectorRegistry.defaultRegistry.clear()

      val serverName = "idle-shutdown-${System.nanoTime()}"
      val httpPort = 9525

      val args = ["-Dproxy.admin.enabled=false", "-Dproxy.metrics.enabled=false"]
      val proxy =
        Proxy(
          options = proxyOptions(CONFIG_ARG + args),
          proxyPort = httpPort,
          inProcessServerName = serverName,
          testMode = true,
        ) { startSync() }

      try {
        val args = ["-Dagent.admin.enabled=false", "-Dagent.metrics.enabled=false"]
        val agent =
          Agent(
            options = agentOptions(CONFIG_ARG + args, exitOnMissingConfig = false),
            inProcessServerName = serverName,
            testMode = true,
          ) { startSync() }

        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          // Let the readRequestsFromProxy stream settle into its idle, suspended state.
          delay(1.seconds)

          // Under the bug, stopSync() deadlocks on the idle read stream and awaitTerminated() throws
          // TimeoutException. With the triggerShutdown() fix it terminates in well under a second.
          val elapsed = measureTime { agent.stopSync(15.seconds) }

          agent.isRunning.shouldBeFalse()
          elapsed shouldBeLessThan 10.seconds
        } finally {
          if (agent.isRunning) runCatching { agent.stopSync(5.seconds) }
        }
      } finally {
        runCatching { proxy.stopSync(10.seconds) }
      }
    }
  }
}
