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

package io.prometheus.agent

import ch.qos.logback.classic.Level
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.TestPorts.AGENT_ADMIN_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_PORT
import io.prometheus.common.TestPorts.PROXY_DASHBOARD_PORT
import io.prometheus.common.agentOptions
import io.prometheus.common.agentOptionsFromConfig
import io.prometheus.common.captureLogs

/**
 * The agent dashboard's options, `agent.dashboard` and the `--dashboard*` flags, which the agent resolves as the proxy
 * resolves its own (`ProxyDashboardOptionsTest`): their defaults, what they refuse while the dashboard is on, and what
 * is logged at startup, naming the agent's settings.
 */
class AgentDashboardOptionsTest : StringSpec() {
  private fun options(args: List<String> = emptyList()) = agentOptions(args, exitOnMissingConfig = false)

  init {
    // Off unless asked for, as the proxy's is: an agent embedded in another app must not open a port of its own.
    "the agent's dashboard should be off by default" {
      options().dashboardEnabled.shouldBeFalse()
    }

    "the agent's dashboard should default to port $AGENT_DASHBOARD_PORT, apart from the admin port and the proxy's" {
      options().apply {
        dashboardPort shouldBe AGENT_DASHBOARD_PORT
        dashboardPort.shouldNotBeIn(AGENT_ADMIN_PORT, PROXY_DASHBOARD_PORT)
        dashboardPath shouldBe "dashboard"
        dashboardHost shouldBe "0.0.0.0"
      }
    }

    "the agent's dashboard should be settable from the command line, as the proxy's is" {
      options(["--dashboard", "--dashboard_port", "9123", "--dashboard_path", "agent", "--dashboard_host", "127.0.0.1"])
        .apply {
          dashboardEnabled.shouldBeTrue()
          dashboardPort shouldBe 9123
          dashboardPath shouldBe "agent"
          dashboardHost shouldBe "127.0.0.1"
        }
    }

    "the agent's dashboard settings should be read from the agent's config, not the proxy's" {
      agentOptionsFromConfig(
        """
        proxy.dashboard { enabled = false, port = 9001 }
        agent.dashboard { enabled = true, port = 9002, path = "agent-dash", host = "127.0.0.1" }
        """,
      ).apply {
        dashboardEnabled.shouldBeTrue()
        dashboardPort shouldBe 9002
        dashboardPath shouldBe "agent-dash"
        dashboardHost shouldBe "127.0.0.1"
      }
    }

    // Refused as every agent setting is, with a ConfigLoadException an embedded host can catch.
    "an out-of-range port, or an empty path or a blank host while it is on, should be refused" {
      shouldBeRejected { options(["--dashboard_port", "70000"]) }.message.shouldNotBeNull() shouldContain
        "dashboardPort"
      shouldBeRejected {
        agentOptionsFromConfig("""agent.dashboard { enabled = true, path = "" }""")
      }.message.shouldNotBeNull() shouldContain "dashboardPath is empty"
      shouldBeRejected {
        agentOptionsFromConfig("""agent.dashboard { enabled = true, host = " " }""")
      }.message.shouldNotBeNull() shouldContain "dashboardHost is blank"
    }

    "a non-positive maxSessions or refreshIntervalSecs should be refused while the dashboard is on, naming it" {
      listOf("maxSessions", "refreshIntervalSecs").forEach { name ->
        shouldBeRejected {
          agentOptionsFromConfig("""agent.dashboard { enabled = true, $name = 0 }""")
        }.message.shouldNotBeNull() shouldContain "dashboard.$name"
        // Off, it isn't used, so it isn't checked.
        agentOptionsFromConfig("""agent.dashboard { $name = 0 }""").dashboardEnabled.shouldBeFalse()
      }
    }

    "the agent's login should be off, with the username admin, until a password is set" {
      options(["--dashboard"]).apply {
        dashboardUsername shouldBe "admin"
        dashboardPassword shouldBe ""
      }
    }

    "a password should turn the agent's login on, logged with its username and the password masked" {
      var options: AgentOptions? = null
      val logs =
        captureLogs<AgentOptions>(Level.INFO) {
          options = agentOptionsFromConfig("""agent.dashboard { enabled = true, password = "$LOGIN_PASSWORD" }""")
        }
      options.shouldNotBeNull().dashboardPassword shouldBe LOGIN_PASSWORD
      val messages = logs.map { it.formattedMessage }
      messages shouldContainElement "dashboard login: on, dashboardUsername: admin, dashboardPassword: ***"
      messages.none { LOGIN_PASSWORD in it }.shouldBeTrue()
      logs.none { it.level == Level.WARN && "no authentication" in it.formattedMessage }.shouldBeTrue()
    }

    "without a login, an agent dashboard on all interfaces should warn, naming the agent's settings" {
      captureLogs<AgentOptions>(Level.INFO) { options(["--dashboard"]) }
        .single { it.level == Level.WARN && "no authentication" in it.formattedMessage }
        .formattedMessage
        .apply {
          this shouldContain "agent.dashboard.password (DASHBOARD_PASSWORD)"
          this shouldContain "agent.dashboard.host"
          this shouldContain "agent.dashboard.allowedHosts"
          this shouldNotContain "proxy.dashboard"
        }
    }

    "an agent login the dashboard can't check should fail startup, naming the agent's setting and no value" {
      shouldBeRejected {
        agentOptionsFromConfig(
          """agent.dashboard { enabled = true, username = "ops:team", password = "$LOGIN_PASSWORD" }""",
        )
      }.message.shouldNotBeNull().apply {
        this shouldContain "agent.dashboard.username can't contain a colon"
        this shouldNotContain LOGIN_PASSWORD
      }
    }
  }

  private fun Int.shouldNotBeIn(vararg others: Int) = others.none { it == this }.shouldBeTrue()

  private inline fun shouldBeRejected(block: () -> Any?): ConfigLoadException =
    shouldThrow<ConfigLoadException>(block).also { it.cause.shouldBeInstanceOf<IllegalArgumentException>() }

  private companion object {
    const val LOGIN_PASSWORD = "s3cret-Passw0rd"
  }
}
