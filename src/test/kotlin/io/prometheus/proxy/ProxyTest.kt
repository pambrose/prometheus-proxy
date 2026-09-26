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

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.captureLogs
import ch.qos.logback.classic.Level
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.prometheus.Proxy
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.grpc.registerAgentRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import com.google.common.util.concurrent.Service
import io.kotest.assertions.assertSoftly
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.net.ServerSocket

class ProxyTest : StringSpec() {
  private fun createTestProxy(vararg extraArgs: String) =
    Proxy(
      options = ProxyOptions(extraArgs.asList()),
      inProcessServerName = "proxy-test-${System.nanoTime()}",
      testMode = true,
    )

  private fun createAgentContext(
    name: String = "test-agent",
    host: String = "test-host",
    consolidated: Boolean = false,
  ): AgentContext =
    AgentContext("test-remote").apply {
      assignProperties(
        registerAgentRequest {
          agentName = name
          hostName = host
          launchId = "launch-${System.nanoTime()}"
          this.consolidated = consolidated
        },
      )
    }

  init {
    // ==================== buildServiceDiscoveryJson Tests ====================

    "buildServiceDiscoveryJson should return empty array when no paths registered" {
      val proxy = createTestProxy()

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 0
    }

    "buildServiceDiscoveryJson should return correct structure for single path" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy.example.com:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext(name = "agent-01", host = "internal.host.com")
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("app1_metrics", "", agentContext)

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 1
      val entry = json[0].jsonObject
      val targets = entry["targets"]!!.jsonArray
      targets.size shouldBe 1
      targets[0].jsonPrimitive.content shouldBe "proxy.example.com:$PROXY_HTTP_PORT"

      val labels = entry["labels"]!!.jsonObject
      labels["__metrics_path__"]!!.jsonPrimitive.content shouldBe "/app1_metrics"
      labels["agentName"]!!.jsonPrimitive.content shouldBe "agent-01"
      labels["hostName"]!!.jsonPrimitive.content shouldBe "internal.host.com"
    }

    "buildServiceDiscoveryJson should produce multiple entries for multiple paths" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics1", "", agentContext)
      proxy.pathManager.addPath("metrics2", "", agentContext)

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 2
      val paths = json.map { it.jsonObject["labels"]!!.jsonObject["__metrics_path__"]!!.jsonPrimitive.content }
      paths.toSet() shouldBe setOf("/metrics1", "/metrics2")
    }

    "buildServiceDiscoveryJson should include custom labels from agent" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", """{"environment":"production","service":"web"}""", agentContext)

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 1
      val labels = json[0].jsonObject["labels"]!!.jsonObject
      labels["environment"]!!.jsonPrimitive.content shouldBe "production"
      labels["service"]!!.jsonPrimitive.content shouldBe "web"
    }

    "buildServiceDiscoveryJson should skip invalid JSON labels gracefully" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext(name = "agent-bad-labels")
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", "not-valid-json{{{", agentContext)

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 1
      val labels = json[0].jsonObject["labels"]!!.jsonObject
      // Standard labels should still be present
      labels["__metrics_path__"]!!.jsonPrimitive.content shouldBe "/metrics"
      labels["agentName"]!!.jsonPrimitive.content shouldBe "agent-bad-labels"
      // Invalid JSON labels should not cause custom keys to appear
      labels.containsKey("environment").shouldBeFalse()
    }

    // Agent-supplied labels must not clobber the proxy-computed reserved keys (__metrics_path__,
    // agentName, hostName). Reserved keys are written last/skipped so an agent cannot redirect the
    // scrape target or spoof identity via a colliding label name.
    "buildServiceDiscoveryJson should not let agent labels override reserved keys" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext(name = "agent-real", host = "real-host")
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath(
        "metrics",
        """{"__metrics_path__":"/evil","hostName":"spoofed","env":"prod"}""",
        agentContext,
      )

      val json = proxy.buildServiceDiscoveryJson()

      val labels = json[0].jsonObject["labels"]!!.jsonObject
      // Proxy-computed reserved values win over the agent-supplied collisions.
      labels["__metrics_path__"]!!.jsonPrimitive.content shouldBe "/metrics"
      labels["hostName"]!!.jsonPrimitive.content shouldBe "real-host"
      // Non-reserved custom labels are still applied.
      labels["env"]!!.jsonPrimitive.content shouldBe "prod"
    }

    // In HTTP service discovery a target's labels override the scrape config's __scheme__, __scrape_interval__,
    // __scrape_timeout__, and __param_*, so an agent could change how Prometheus scrapes it. Every __-prefixed agent
    // label is dropped; ordinary labels, job included by default, still apply.
    "buildServiceDiscoveryJson should drop agent labels that set Prometheus meta labels" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath(
        "metrics",
        """{"__scheme__":"https","__param_target":"x","__scrape_timeout__":"1s","env":"prod","job":"team-a"}""",
        agentContext,
      )

      val labels = proxy.buildServiceDiscoveryJson()[0].jsonObject["labels"]!!.jsonObject

      labels.keys.filter { it.startsWith("__") } shouldBe listOf("__metrics_path__")
      labels["env"]!!.jsonPrimitive.content shouldBe "prod"
      labels["job"]!!.jsonPrimitive.content shouldBe "team-a"
    }

    // With per-agent identities, a job or instance label lets one team's agent pass its targets off as another's.
    // Deployments that separate teams can reserve both.
    "with reserveJobAndInstanceLabels, agent job and instance labels should be dropped" {
      val proxy =
        createTestProxy(
          "-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT",
          "-Dproxy.service.discovery.reserveJobAndInstanceLabels=true",
        )
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", """{"job":"team-b","instance":"other:9100","env":"prod"}""", agentContext)

      val labels = proxy.buildServiceDiscoveryJson()[0].jsonObject["labels"]!!.jsonObject

      labels.containsKey("job") shouldBe false
      labels.containsKey("instance") shouldBe false
      labels["env"]!!.jsonPrimitive.content shouldBe "prod"
    }

    // Prometheus polls service discovery every refresh interval, so a reserved label warned about on every build
    // repeated for as long as the path was registered. It is reported once, when the path registers.
    "a reserved agent label should be warned about once, at registration" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)

      val registrationWarnings =
        captureLogs<ProxyPathManager>(Level.WARN) {
          proxy.pathManager.addPath(
            "metrics",
            """{"__scheme__":"https","hostName":"spoofed","env":"prod"}""",
            agentContext,
          )
        }.map { it.formattedMessage }
      val buildWarnings =
        captureLogs<Proxy>(Level.WARN) {
          repeat(3) { proxy.buildServiceDiscoveryJson() }
        }

      registrationWarnings.single() shouldContain "__scheme__"
      registrationWarnings.single() shouldContain "hostName"
      buildWarnings shouldBe emptyList()
    }

    "a path with no reserved agent labels should register without a warning" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)

      captureLogs<ProxyPathManager>(Level.WARN) {
        proxy.pathManager.addPath("metrics", """{"env":"prod","job":"team-a"}""", agentContext)
      } shouldBe emptyList()
    }

    // Bug #10: __metrics_path__ must include a leading slash per Prometheus SD convention
    "buildServiceDiscoveryJson should include leading slash in __metrics_path__" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("my_metrics", "", agentContext)

      val json = proxy.buildServiceDiscoveryJson()

      json.size shouldBe 1
      val labels = json[0].jsonObject["labels"]!!.jsonObject
      val metricsPath = labels["__metrics_path__"]!!.jsonPrimitive.content
      metricsPath shouldBe "/my_metrics"
      metricsPath[0] shouldBe '/'
    }

    // Multi-segment paths are rejected at registration: the get("/*") scrape route matches only a
    // single segment, so such a path would be advertised yet 404. Confirm a rejected path never
    // reaches the service-discovery document.
    "buildServiceDiscoveryJson should omit a rejected multi-segment path" {
      val proxy = createTestProxy("-Dproxy.service.discovery.targetPrefix=proxy:$PROXY_HTTP_PORT")
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)

      val reason = proxy.pathManager.addPath("app/metrics", "", agentContext)?.reason

      reason.shouldNotBeNull()
      proxy.buildServiceDiscoveryJson().size shouldBe 0
    }

    // ==================== removeAgentContext Tests ====================

    "removeAgentContext should throw on empty agentId" {
      val proxy = createTestProxy()

      shouldThrow<IllegalArgumentException> {
        proxy.removeAgentContext("", "test reason")
      }
    }

    "removeAgentContext should delegate to both managers" {
      val proxy = createTestProxy()
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", "", agentContext)

      val removed = proxy.removeAgentContext(agentContext.agentId, "disconnect")

      removed.shouldNotBeNull()
      removed.agentId shouldBe agentContext.agentId
      // Path should be removed
      proxy.pathManager.pathMapSize shouldBe 0
      // Agent context should be removed
      proxy.agentContextManager.getAgentContext(agentContext.agentId).shouldBeNull()
    }

    "removeAgentContext should return null for unknown agentId" {
      val proxy = createTestProxy()

      val removed = proxy.removeAgentContext("nonexistent-id", "test")

      removed.shouldBeNull()
    }

    // ==================== isBlitzRequest Tests ====================

    "isBlitzRequest should return false when blitz is disabled" {
      val proxy = createTestProxy()

      proxy.isBlitzRequest("any-path").shouldBeFalse()
    }

    "isBlitzRequest should return true when blitz enabled and path matches" {
      val proxy = createTestProxy(
        "-Dproxy.internal.blitz.enabled=true",
        "-Dproxy.internal.blitz.path=mu-test-blitz.txt",
      )

      proxy.isBlitzRequest("mu-test-blitz.txt").shouldBeTrue()
    }

    "isBlitzRequest should return false when blitz enabled but path does not match" {
      val proxy = createTestProxy(
        "-Dproxy.internal.blitz.enabled=true",
        "-Dproxy.internal.blitz.path=mu-test-blitz.txt",
      )

      proxy.isBlitzRequest("other-path").shouldBeFalse()
    }

    // ==================== metrics Tests ====================

    "metrics should not invoke lambda when metrics disabled" {
      val proxy = createTestProxy()

      var invoked = false
      proxy.metrics { invoked = true }

      invoked.shouldBeFalse()
    }

    "metrics should invoke lambda when metrics enabled" {
      val mockMetrics = mockk<ProxyMetrics>(relaxed = true)
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.isMetricsEnabled } returns true
      every { mockProxy.metrics } returns mockMetrics
      every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
        val block = firstArg<ProxyMetrics.() -> Unit>()
        block.invoke(mockMetrics)
      }

      var invoked = false
      mockProxy.metrics { invoked = true }

      invoked.shouldBeTrue()
    }

    // A connection that closed before it authenticated was never shown as connected, so it mustn't be shown leaving.
    "removing a context that was never announced should not emit AgentDisconnected" {
      val proxy = createTestProxy()
      val pending = createAgentContext().also { proxy.agentContextManager.addAgentContext(it, announce = false) }
      val marker = ProxyEvent.PathRegistered("marker", "none")

      val seen =
        coroutineScope {
          val subscribed = CompletableDeferred<Unit>()
          val collected = async { proxy.eventBus.flow.onSubscription { subscribed.complete(Unit) }.take(1).toList() }
          subscribed.await()
          proxy.removeAgentContext(pending.agentId, "Termination")
          proxy.eventBus.emit(marker)
          collected.await()
        }

      seen shouldBe listOf(marker)
    }

    // ==================== Bug #20: shutDown ordering Tests ====================

    // Bug #20: failAllInFlightScrapeRequests must run before invalidateAllAgentContexts
    // so HTTP handlers get "Proxy is shutting down" instead of generic "missing_results".
    // We test the ordering by calling the operations in the correct (fixed) order and
    // verifying the wrapper receives the shutdown failure reason.
    "Bug #20: failing scrape requests before invalidating gives informative error" {
      val proxy = createTestProxy()
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", "", agentContext)

      val wrapper = ScrapeRequestWrapper(
        agentContext = agentContext,
        pathVal = "metrics",
        encodedQueryParamsVal = "",
        authHeaderVal = "",
        acceptVal = "",
        debugEnabledVal = false,
      )
      proxy.scrapeRequestManager.addToScrapeRequestMap(wrapper)

      // Fixed order: fail requests THEN invalidate agents
      proxy.scrapeRequestManager.failAllInFlightScrapeRequests("Proxy is shutting down", ProxyFailure.PROXY_STOPPED)
      proxy.agentContextManager.invalidateAllAgentContexts()

      // The wrapper should have the informative shutdown message, and the shutdown's outcome rather than the
      // agent-disconnected one invalidating the agents would give it
      wrapper.scrapeResults.shouldNotBeNull()
      wrapper.scrapeResults!!.srFailureReason shouldContain "Proxy is shutting down"
      wrapper.proxyFailure shouldBe ProxyFailure.PROXY_STOPPED
    }

    // Verify the old (broken) order would lose the failure reason
    "Bug #20: invalidating before failing loses the informative error" {
      val proxy = createTestProxy()
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", "", agentContext)

      val wrapper = ScrapeRequestWrapper(
        agentContext = agentContext,
        pathVal = "metrics",
        encodedQueryParamsVal = "",
        authHeaderVal = "",
        acceptVal = "",
        debugEnabledVal = false,
      )
      proxy.scrapeRequestManager.addToScrapeRequestMap(wrapper)

      // Old (broken) order: invalidate agents THEN fail requests
      proxy.agentContextManager.invalidateAllAgentContexts()
      // This wrapper is only in the scrapeRequestMap (not an agent queue), so failAllInFlightScrapeRequests
      // completes it: ScrapeRequestWrapper.complete() publishes the result behind its CAS (finding 16).
      proxy.scrapeRequestManager.failAllInFlightScrapeRequests("Proxy is shutting down", ProxyFailure.PROXY_STOPPED)

      // Results are set, but awaitCompleted() would have already returned false
      // (before the failure reason was set) in the old ordering
      wrapper.scrapeResults.shouldNotBeNull()
    }

    // ==================== logActivity Tests ====================

    "logActivity should add timestamped entry without error" {
      val proxy = createTestProxy()

      // logActivity adds to a private EvictingQueue; verify it doesn't throw
      proxy.logActivity("test request to /metrics")
      proxy.logActivity("another request to /health")
    }

    // ==================== toString Tests ====================

    "toString should contain proxyPort and service info" {
      val proxy = createTestProxy()

      val str = proxy.toString()

      str shouldContain "proxyPort"
      str shouldContain "adminService"
      str shouldContain "metricsService"
    }

    // ==================== removeAgentContext Ordering Tests ====================

    "removeAgentContext should invalidate the agent context before sweeping the path map" {
      val proxy = createTestProxy()
      val agentContext = spyk(createAgentContext())

      // Capture how many paths were still registered at the instant invalidate() ran. The sweep in
      // removeFromPathManager must come after invalidation, so the path is still present here.
      var pathMapSizeAtInvalidation = -1
      every { agentContext.invalidate() } answers {
        pathMapSizeAtInvalidation = proxy.pathManager.pathMapSize
        callOriginal()
      }

      proxy.agentContextManager.addAgentContext(agentContext)
      proxy.pathManager.addPath("metrics", "", agentContext)
      proxy.pathManager.pathMapSize shouldBe 1

      proxy.removeAgentContext(agentContext.agentId, "test disconnect")

      // If the sweep ran first this is 0, which leaves an unguarded window: a registerPath blocked on
      // the pathMap monitor during the sweep is released into it and strands a path pointing at a
      // context that is about to be invalidated, and no later sweep removes it (finding 7).
      pathMapSizeAtInvalidation shouldBe 1
      proxy.pathManager.pathMapSize shouldBe 0
    }

    "removeAgentContext should reject a path registered for the removed context" {
      val proxy = createTestProxy()
      val agentContext = createAgentContext()
      proxy.agentContextManager.addAgentContext(agentContext)

      proxy.removeAgentContext(agentContext.agentId, "test disconnect")
      val error = proxy.pathManager.addPath("metrics", "", agentContext)?.reason

      error.shouldNotBeNull()
      error shouldContain "was invalidated during registration"
      proxy.pathManager.pathMapSize shouldBe 0
    }

    // ==================== Service Registration ====================

    // The ServiceManager, its failure listener and the all_services_healthy check see only the services registered
    // before the constructor's initServletService() builds it. The cleanup service used to register when startUp()
    // first touched it, too late, so common-utils logged that it "was added after Proxy was initialized".
    "the stale-agent cleanup service should be registered while the proxy is built" {
      cleanupServiceRegistrations { createTestProxy() } shouldBe 1
    }

    // Registered but never started, it would hold the all_services_healthy check unhealthy.
    "the stale-agent cleanup service should not be registered when stale-agent checks are off" {
      cleanupServiceRegistrations {
        createTestProxy("-Dproxy.internal.staleAgentCheckEnabled=false")
      } shouldBe 0
    }

    "the stale-agent cleanup service should be registered when a disabled transport filter forces it on" {
      cleanupServiceRegistrations {
        createTestProxy("-Dproxy.internal.staleAgentCheckEnabled=false", "--tf_disabled")
      } shouldBe 1
    }

    // ==================== Failed startup ====================

    // Guava calls shutDown() only after startUp() succeeds. When a sub-service failed to start, the admin and metrics
    // servers super.startUp() had started and the sub-services already running were left up: a standalone proxy with
    // a taken port logged the failure but never exited, its admin server's threads keeping the JVM alive.
    "a failed startup should stop what the proxy started" {
      PrometheusRegistry.defaultRegistry.clear()
      val adminPort = ServerSocket(0).use { it.localPort }
      val metricsPort = ServerSocket(0).use { it.localPort }
      ServerSocket(0).use { takenHttpPort ->
        val proxy =
          Proxy(
            options =
              ProxyOptions(
                listOf(
                  "--port",
                  "${takenHttpPort.localPort}",
                  "--admin",
                  "--admin_port",
                  "$adminPort",
                  "--metrics",
                  "--metrics_port",
                  "$metricsPort",
                ),
              ),
            inProcessServerName = "failed-start-${System.nanoTime()}",
            testMode = true,
          )

        shouldThrow<IllegalStateException> { proxy.startSync() }

        // Captured as values first, so each check reports on its own inside assertSoftly.
        val state = proxy.state()
        val adminPortFree = runCatching { ServerSocket(adminPort).close() }.isSuccess
        val metricsRunning = proxy.metricsService.isRunning

        assertSoftly {
          state shouldBe Service.State.FAILED
          adminPortFree.shouldBeTrue()
          metricsRunning.shouldBeFalse()
        }
      }
    }
  }

  // How many times common-utils logged registering the cleanup service with the proxy while [block] ran.
  private fun cleanupServiceRegistrations(block: () -> Unit): Int =
    captureLogs(SERVICE_LOGGER) { block() }
      .map { it.formattedMessage }
      .count { it.startsWith("Adding service") && "AgentContextCleanupService" in it }

  companion object {
    // The logger common-utils' GenericService logs service registrations with.
    private const val SERVICE_LOGGER = "com.pambrose.common.service"
  }
}
