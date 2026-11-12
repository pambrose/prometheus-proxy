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

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startProxy
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// The public embedded entry point, end to end. AgentTest pins the failure contract for a config that can't
// be loaded (it surfaces as ConfigLoadException instead of exiting the host JVM). This pins the success
// contract: an agent built from a config file connects to a real proxy over Netty, the returned handle
// reports the configured name, and shutdown() tears the connection down so the proxy forgets the agent.
// The banner is left on (the default) because that is what an embedding host gets unless it opts out. It
// also pins the failure contract for a config that loads but holds a setting the agent can't use.
class EmbeddedAgentApiTest : StringSpec() {
  init {
    "startAsyncAgent should connect the agent from a config file and shutdown() should disconnect it" {
      PrometheusRegistry.defaultRegistry.clear()

      val proxy = startProxy(args = ["--agent_port", "$GRPC_PORT"], proxyPort = HTTP_PORT)
      val configFile =
        createTempFile("embedded-agent", ".conf").toFile().apply {
          writeText(
            """
            agent {
              name = "$AGENT_NAME"
              proxy {
                hostname = "$LOOPBACK_HOST"
                port = $GRPC_PORT
              }
              pathConfigs = []
              internal.reconnectPauseSecs = 1
            }
            """.trimIndent(),
          )
        }

      var info: EmbeddedAgentInfo? = null
      try {
        // exitOnMissingConfig = false is the embedded mode: startup failures throw rather than exit.
        info = Agent.startAsyncAgent(configFile.absolutePath, exitOnMissingConfig = false)
        info.agentName shouldBe AGENT_NAME
        info.launchId shouldHaveLength 15

        // The handle hides the Agent, so the proxy is the only witness that it actually connected.
        eventually(30.seconds) {
          proxy.agentContextManager.agentContextEntries
            .any { it.value.agentName == AGENT_NAME }
            .shouldBeTrue()
        }

        info.shutdown()

        eventually(30.seconds) {
          proxy.agentContextManager.agentContextSize shouldBe 0
        }
      } finally {
        runCatching { info?.shutdown() }
        runCatching { proxy.stopSync(10.seconds) }
        configFile.delete()
      }
    }

    // November #8: the failure contract covers a config the agent loads but can't use, not only one it can't load. Each
    // of these escaped as an IllegalArgumentException or a Typesafe ConfigException, and the Typesafe messages began
    // with the config URL, credentials and all. The first four are the review's inputs. The trust store and the three
    // after it fail as the Agent is built, rather than as its options are resolved.
    listOf(
      InvalidConfig("an out-of-range value", "scrapeTimeoutSecs = 0", "scrapeTimeoutSecs"),
      InvalidConfig("a wrong-typed value", "scrapeTimeoutSecs = \"abc\"", "scrapeTimeoutSecs"),
      InvalidConfig("an unresolved substitution", $$"name = ${missing.value}", "missing.value"),
      InvalidConfig("a trust store that can't be loaded", "http.trustStorePath = \"$MISSING_FILE\"", "trustStorePath"),
      InvalidConfig(
        "a TLS trust file that doesn't exist",
        "tls.trustCertCollectionFilePath = \"$MISSING_FILE\"",
        MISSING_FILE,
      ),
      InvalidConfig(
        "a duplicate pathConfigs entry",
        """pathConfigs = [{ name = a, path = m, url = "$TARGET_URL" }, { name = b, path = m, url = "$TARGET_URL" }]""",
        "pathConfigs",
      ),
      InvalidConfig(
        "a filter regex that doesn't compile",
        """filters = [{ path = m, metricNameAllow = ["("], metricNameDeny = [] }]""",
        "regex",
      ),
    ).forEach { (description, setting, named) ->
      "startAsyncAgent should throw ConfigLoadException for $description, without the config URL's credentials" {
        val failure = startFromCredentialedUrl("agent {\n  $setting\n}")

        val exception = failure.exception.shouldBeInstanceOf<ConfigLoadException>()
        exception.message.orEmpty() shouldContain named
        // An embedded host that logs the exception prints its whole cause chain.
        generateSequence<Throwable>(exception) { it.cause }.forEach { it.message.orEmpty() shouldNotContain SECRET }
        exception.stackTraceToString() shouldNotContain SECRET
        // The reason is logged too, so the log check below can't pass for want of lines.
        failure.logs.any { it.startsWith("Invalid configuration: ") && named in it }.shouldBeTrue()
        failure.logs.forEach { it shouldNotContain SECRET }
      }
    }
  }

  // A setting the agent rejects, and a word the exception's message should contain to name it.
  private data class InvalidConfig(
    val description: String,
    val setting: String,
    val named: String,
  )

  private class StartFailure(
    val exception: Throwable?,
    val logs: List<String>,
  )

  // Serves [config] at a URL that carries credentials, starts an embedded agent from it, and returns what startup threw
  // and every io.prometheus log line it wrote. An agent that starts anyway is shut down, and the test then fails on the
  // missing exception.
  private suspend fun startFromCredentialedUrl(config: String): StartFailure {
    val server =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing { get("/agent.conf") { call.respondText(config) } }
      }
    try {
      val port = server.startAndAwaitReady()
      var exception: Throwable? = null
      val events =
        captureLogs("io.prometheus") {
          runCatching { Agent.startAsyncAgent(configUrl(port), exitOnMissingConfig = false, logBanner = false) }
            .onSuccess { it.shutdown() }
            .onFailure { exception = it }
        }
      val logs =
        events.flatMap { event ->
          listOf(event.formattedMessage) +
            generateSequence(event.throwableProxy) { it.cause }.map { it.message.orEmpty() }
        }
      return StartFailure(exception, logs)
    } finally {
      server.stop(0, 0)
    }
  }

  companion object {
    private const val SECRET = "secret"
    private const val MISSING_FILE = "/no/such/dir/missing.pem"
    private const val TARGET_URL = "http://$LOOPBACK_HOST:1/metrics"

    private fun configUrl(port: Int) = "http://user:$SECRET@$LOOPBACK_HOST:$port/agent.conf"

    private const val AGENT_NAME = "embedded-api-agent"

    // Dedicated ports, following the one-off convention the other standalone harness specs use.
    private const val HTTP_PORT = TestPorts.EMBEDDED_API_HTTP_PORT
    private const val GRPC_PORT = TestPorts.EMBEDDED_API_GRPC_PORT
  }
}
