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
import com.pambrose.common.util.simpleClassName
import com.pambrose.common.util.sleep
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.prometheus.common.TestPorts
import io.prometheus.common.TestPorts.HARNESS_AGENT_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_AGENT_METRICS_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_METRICS_PORT
import io.prometheus.harness.HarnessConstants.DEFAULT_CHUNK_SIZE_BYTES
import io.prometheus.harness.HarnessConstants.DEFAULT_SCRAPE_TIMEOUT_SECS
import io.prometheus.harness.HarnessConstants.HARNESS_CONFIG
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.AbstractHarnessTests
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.ProxyCallTestArgs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix
import kotlin.time.Duration.Companion.seconds

class NettyTestWithAdminMetricsTest :
  AbstractHarnessTests(
    argsProvider = {
      ProxyCallTestArgs(
        agent = agent,
        proxyPort = PROXY_PORT,
        startPort = TestPorts.NETTY_WITH_ADMIN_START_PORT,
        caller = simpleClassName,
      )
    },
  ) {
  companion object : HarnessSetup()

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = {
          startProxy(
            adminEnabled = true,
            debugEnabled = true,
            metricsEnabled = true,
          )
        },
        agentSetup = {
          startAgent(
            adminEnabled = true,
            debugEnabled = true,
            metricsEnabled = true,
            scrapeTimeoutSecs = DEFAULT_SCRAPE_TIMEOUT_SECS,
            chunkContentSizeBytes = DEFAULT_CHUNK_SIZE_BYTES,
            maxConcurrentClients = HARNESS_CONFIG.concurrentClients,
          )
        },
        actions = {
          // Wait long enough to trigger heartbeat for code coverage
          sleep(15.seconds)
        },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    "should return debug info from admin endpoints" {
      withHttpClient {
        get("$HARNESS_AGENT_ADMIN_PORT/debug".withPrefix()) { response ->
          val body = response.bodyAsText()
          body.length shouldBeGreaterThan 100
          response.status shouldBe HttpStatusCode.OK
        }
      }

      withHttpClient {
        get("$HARNESS_PROXY_ADMIN_PORT/debug".withPrefix()) { response ->
          val body = response.bodyAsText()
          body.length shouldBeGreaterThan 100
          response.status shouldBe HttpStatusCode.OK
        }
      }
    }

    // The proxy and agent run in this JVM and share Prometheus' default registry, so each metrics endpoint serves
    // both components' families. Under the 1.x client a name collision between them fails every scrape with a 500
    // rather than serving duplicates, so both endpoints are scraped.
    "metrics endpoints should serve both components' series in the text format" {
      listOf(HARNESS_PROXY_METRICS_PORT, HARNESS_AGENT_METRICS_PORT).forEach { port ->
        withHttpClient {
          get("$port/metrics".withPrefix()) { response ->
            val body = response.bodyAsText()
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType].orEmpty() shouldStartWith "text/plain; version=0.0.4"
            body shouldContain "\nproxy_connect_count_total "
            body shouldContain "\nproxy_agent_map_size "
            body shouldContain "\nproxy_start_time_seconds{launch_id=\""
            body shouldContain "\nagent_start_time_seconds{launch_id=\""
            body shouldContain "\nagent_scrape_backlog_size{launch_id=\""
            // The 1.x client no longer exposes the counters' and histograms' _created series by default.
            body shouldNotContain "_created"
          }
        }
      }
    }

    // Prometheus 3 asks for OpenMetrics first.
    "metrics endpoint should serve OpenMetrics when the Accept header asks for it" {
      withHttpClient {
        get(
          "$HARNESS_PROXY_METRICS_PORT/metrics".withPrefix(),
          setUp = { header(HttpHeaders.Accept, "application/openmetrics-text; version=1.0.0") },
        ) { response ->
          val body = response.bodyAsText()
          response.status shouldBe HttpStatusCode.OK
          response.headers[HttpHeaders.ContentType].orEmpty() shouldStartWith "application/openmetrics-text"
          body shouldContain "# TYPE proxy_connect_count counter"
          body.trimEnd() shouldEndWith "# EOF"
        }
      }
    }

    // Netty puts the peer address in the transport attributes before transportReady(); the in-process tests can't
    // show that the filter reads the key Netty actually sets.
    "the proxy should record the connected agent's remote address" {
      val addresses = proxy.agentContextManager.agentContextEntries.map { it.value.remoteAddr }

      addresses.shouldNotBeEmpty()
      addresses.forEach { it shouldMatch Regex("""(127\.0\.0\.1|\[0:0:0:0:0:0:0:1]):\d+""") }
    }
  }
}
