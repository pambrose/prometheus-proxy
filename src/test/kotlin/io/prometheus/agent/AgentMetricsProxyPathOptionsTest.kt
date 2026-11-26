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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.agentOptions
import io.prometheus.common.agentOptionsFromConfig
import io.prometheus.common.captureLogs
import io.prometheus.common.shouldBeRejected

/**
 * The proxy path the agent serves its own metrics at, `agent.metrics.proxyPath` and `--metrics_proxy_path`, and its
 * labels: where each comes from, and what is refused or warned of at startup.
 */
class AgentMetricsProxyPathOptionsTest : StringSpec() {
  init {
    "metricsProxyPath should default to none, with empty labels" {
      val options = agentOptions(["--name", "test", "--proxy", "host"], false)
      options.metricsProxyPath shouldBe ""
      options.metricsProxyPathLabels shouldBe "{}"
    }

    "metricsProxyPath and its labels should come from the config, without a leading slash" {
      val options =
        agentOptionsFromConfig(
          """
          agent.metrics.proxyPath = " /east_agent_metrics "
          agent.metrics.proxyPathLabels = "{\"job\": \"agent-east\"}"
          """.trimIndent(),
        )
      options.metricsProxyPath shouldBe "east_agent_metrics"
      options.metricsProxyPathLabels shouldBe """{"job": "agent-east"}"""
    }

    "metricsProxyPath should be settable via command line, over the config" {
      val options =
        agentOptionsFromConfig(
          "agent.metrics.proxyPath = config_agent_metrics",
          extraArgs = ["--metrics_proxy_path", "/cli_agent_metrics"],
        )
      options.metricsProxyPath shouldBe "cli_agent_metrics"
    }

    // A slash alone leaves no path once its slash is dropped, which would read as no path at all, the feature off
    "a metricsProxyPath of only a slash should be rejected, not read as none" {
      shouldBeRejected {
        agentOptionsFromConfig("agent.metrics.proxyPath = \" / \"")
      }.message shouldContain "metricsProxyPath must name a path"
      shouldBeRejected {
        agentOptions(["--name", "test", "--proxy", "host", "--metrics_proxy_path", "/"], false)
      }.message shouldContain "metricsProxyPath must name a path"
    }

    // "//" lost one slash here, leaving "/", which the path's check dropped as no path at all, so the agent collected
    // its metrics for a path it never registered (November 26 #11). The proxy serves a path as one segment.
    "a metricsProxyPath of more than one segment should be rejected" {
      for (given in ["//", "//agent_metrics", "agent/metrics"]) {
        withClue(given) {
          shouldBeRejected {
            agentOptions(["--name", "test", "--proxy", "host", "--metrics_proxy_path", given], false)
          }.message shouldContain "one segment"
        }
      }
    }

    "proxyPathLabels without a proxyPath should be warned of, as they do nothing" {
      fun warnings(contents: String) =
        captureLogs<AgentOptions> { agentOptionsFromConfig(contents) }
          .filter { it.level == Level.WARN }
          .map { it.formattedMessage }

      warnings("""agent.metrics.proxyPathLabels = "{\"job\": \"agent\"}"""") shouldBe
        listOf("agent.metrics.proxyPathLabels is set without agent.metrics.proxyPath, so it does nothing")
      warnings("""agent.metrics.proxyPathLabels = "{ }"""") shouldBe emptyList()
      warnings(
        """
        agent.metrics.proxyPath = with_labels
        agent.metrics.proxyPathLabels = "{\"job\": \"agent\"}"
        """.trimIndent(),
      ) shouldBe emptyList()
    }
  }
}
