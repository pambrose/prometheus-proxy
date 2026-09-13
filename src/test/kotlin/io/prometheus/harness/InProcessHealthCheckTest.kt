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

import com.pambrose.common.dsl.KtorDsl.get
import com.pambrose.common.dsl.KtorDsl.withHttpClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.ScrapeRequestWrapper

// The backlog health checks on both sides exist so Kubernetes can take an overloaded agent or proxy out
// of rotation, and neither unhealthy branch had ever been evaluated: the registries are protected, so the
// admin /healthcheck servlet is the supported way to read them. Dropwizard's servlet answers 200 when every
// check passes and 500 when any fails, with each check's message in the body.
class InProcessHealthCheckTest : StringSpec() {
  companion object : HarnessSetup() {
    private const val SERVER_NAME = "health-check"
    private const val HTTP_PORT = TestPorts.HEALTH_CHECK_HTTP_PORT
    private const val PROXY_ADMIN_PORT = TestPorts.HEALTH_CHECK_PROXY_ADMIN_PORT
    private const val AGENT_ADMIN_PORT = TestPorts.HEALTH_CHECK_AGENT_ADMIN_PORT
    private const val DASHBOARD_PORT = TestPorts.HEALTH_CHECK_DASHBOARD_PORT

    // Low so the proxy-side check can be tripped with a couple of queued requests.
    private const val PROXY_BACKLOG_THRESHOLD = 2
  }

  // Dropwizard's servlet answers 200 when every check passes and 500 when any fails, with each check's
  // message in the body, so a status plus a substring is the whole assertion at every call site.
  private suspend fun healthCheck(
    port: Int,
    expected: HttpStatusCode,
    vararg contains: String,
  ) = withHttpClient {
    get("$port/healthcheck".withPrefix()) { response ->
      response.status shouldBe expected
      val body = response.bodyAsText()
      contains.forEach { body shouldContain it }
    }
  }

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = HTTP_PORT,
        proxySetup = {
          startProxy(
            serverName = SERVER_NAME,
            adminEnabled = true,
            proxyPort = HTTP_PORT,
            args = [
              "-Dproxy.admin.port=$PROXY_ADMIN_PORT",
              "-Dproxy.internal.scrapeRequestBacklogUnhealthySize=$PROXY_BACKLOG_THRESHOLD",
              // The dashboard registers its own check, which is otherwise never evaluated.
              "--dashboard",
              "--dashboard_port",
              "$DASHBOARD_PORT",
            ],
          )
        },
        agentSetup = {
          startAgent(
            serverName = SERVER_NAME,
            adminEnabled = true,
            args = ["-Dagent.admin.port=$AGENT_ADMIN_PORT"],
          )
        },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    "the proxy health check should report every registered check healthy, the dashboard's included" {
      healthCheck(
        PROXY_ADMIN_PORT,
        HttpStatusCode.OK,
        "\"dashboard_service\"",
        "\"agent_scrape_request_backlog\"",
      )
    }

    "the agent backlog check should turn unhealthy at the threshold and recover below it" {
      val threshold = agent.agentConfigVals.internal.scrapeRequestBacklogUnhealthySize
      try {
        agent.scrapeRequestBacklogSize.store(threshold)
        healthCheck(
          AGENT_ADMIN_PORT,
          HttpStatusCode.InternalServerError,
          "Scrape request backlog size $threshold >= threshold $threshold",
        )
      } finally {
        agent.scrapeRequestBacklogSize.store(0)
      }

      healthCheck(AGENT_ADMIN_PORT, HttpStatusCode.OK, "\"scrape_request_backlog_check\"")
    }

    "the proxy backlog check should name an agent whose queue reaches the threshold and recover once it is gone" {
      // A context nothing ever reads from, so its queue only grows.
      val backlogged = AgentContext("backlogged").also { proxy.agentContextManager.addAgentContext(it) }
      try {
        repeat(PROXY_BACKLOG_THRESHOLD) {
          backlogged.writeScrapeRequest(ScrapeRequestWrapper(backlogged, "metrics", "", "", "", false))
        }
        healthCheck(PROXY_ADMIN_PORT, HttpStatusCode.InternalServerError, "Large agent scrape request backlog")
      } finally {
        proxy.removeAgentContext(backlogged.agentId, "test cleanup")
      }

      healthCheck(PROXY_ADMIN_PORT, HttpStatusCode.OK)
    }
  }
}
