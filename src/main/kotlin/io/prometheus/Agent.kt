/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.util.MetricsUtils.newBacklogHealthCheck
import com.github.pambrose.common.util.getBanner
import com.github.pambrose.common.util.hostInfo
import com.github.pambrose.common.util.simpleClassName
import com.google.common.util.concurrent.RateLimiter
import io.grpc.StatusRuntimeException
import io.prometheus.agent.AgentGrpcService
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.RequestFailureException
import io.prometheus.client.Summary
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.common.delay
import io.prometheus.common.getVersionDesc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates.notNull
import kotlin.time.ClockMark
import kotlin.time.Duration
import kotlin.time.MonoClock
import kotlin.time.milliseconds
import kotlin.time.seconds

class Agent(options: AgentOptions,
            inProcessServerName: String = "",
            testMode: Boolean = false,
            initBlock: (Agent.() -> Unit)? = null) :
  GenericService<ConfigVals>(options.configVals,
                             newAdminConfig(options.adminEnabled,
                                            options.adminPort,
                                            options.configVals.agent.admin),
                             newMetricsConfig(options.metricsEnabled,
                                              options.metricsPort,
                                              options.configVals.agent.metrics),
                             newZipkinConfig(options.configVals.agent.internal.zipkin),
                             { getVersionDesc(true) },
                             testMode) {
  private val configVals = genericConfigVals.agent.internal
  internal val agentName = if (options.agentName.isBlank()) "Unnamed-${hostInfo.hostName}" else options.agentName
  // Prime the limiter
  private val reconnectLimiter = RateLimiter.create(1.0 / configVals.reconectPauseSecs).apply { acquire() }

  private val clock = MonoClock
  private val initialConnectionLatch = CountDownLatch(1)
  private var lastMsgSentMark: ClockMark by nonNullableReference(clock.markNow())

  internal var metrics: AgentMetrics by notNull()
  internal var agentId: String by nonNullableReference("")
  internal val scrapeRequestBacklogSize = AtomicInteger(0)
  internal val pathManager = AgentPathManager(this)
  internal val grpcService = AgentGrpcService(this, options, inProcessServerName)
  private val agentUtils = AgentHttpService(this)

  init {
    logger.info { "Assigning proxy reconnect pause time to ${configVals.reconectPauseSecs} secs" }

    if (isMetricsEnabled)
      metrics = AgentMetrics(this)

    initService()
    initBlock?.invoke(this)
  }

  override fun run() {
    while (isRunning) {
      try {
        connectToProxy()
      } catch (e: RequestFailureException) {
        logger.info { "Disconnected from proxy at $proxyHost after invalid response ${e.message}" }
      } catch (e: StatusRuntimeException) {
        logger.info { "Disconnected from proxy at $proxyHost" }
      } catch (e: Exception) {
        // Catch anything else to avoid exiting retry loop
      } finally {
        val secsWaiting = reconnectLimiter.acquire()
        logger.info { "Waited $secsWaiting secs to reconnect" }
      }
    }
  }

  internal val proxyHost get() = "${grpcService.hostName}:${grpcService.port}"

  internal fun startTimer(): Summary.Timer? = metrics.scrapeRequestLatency.labels(agentName).startTimer()

  override fun serviceName() = "$simpleClassName $agentName"

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry.register("scrape_request_backlog_check",
                                 newBacklogHealthCheck(scrapeRequestBacklogSize.get(),
                                                       configVals.scrapeRequestBacklogUnhealthySize))
  }

  private fun connectToProxy() {
    val disconnected = AtomicBoolean(false)

    // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agentId != ""
    if (agentId.isNotEmpty()) {
      grpcService.resetGrpcStubs()
      agentId = ""
    }

    // Reset values for each connection attempt
    pathManager.clear()
    scrapeRequestBacklogSize.set(0)
    lastMsgSentMark = clock.markNow()

    if (grpcService.connectAgent()) {
      grpcService.registerAgent(initialConnectionLatch)
      pathManager.registerPaths()

      val scrapeRequestChannel = Channel<ScrapeRequestAction>(configVals.scrapeRequestChannelSize)

      grpcService.readRequestsFromProxy(agentUtils, scrapeRequestChannel, disconnected)

      runBlocking {
        launch(Dispatchers.Default) { startHeartBeat(disconnected) }
        launch(Dispatchers.Default) {
          grpcService.writeResponsesToProxyUntilDisconnected(scrapeRequestChannel, disconnected)
        }
      }
    }
  }

  private suspend fun startHeartBeat(disconnected: AtomicBoolean) =
    if (configVals.heartbeatEnabled) {
      val heartbeatPauseTime = configVals.heartbeatCheckPauseMillis.milliseconds
      val maxInactivityTime = configVals.heartbeatMaxInactivitySecs.seconds
      logger.info { "Heartbeat scheduled to fire after $maxInactivityTime of inactivity" }

      while (isRunning && !disconnected.get()) {
        val timeSinceLastWrite = lastMsgSentMark.elapsedNow()
        if (timeSinceLastWrite > maxInactivityTime)
          grpcService.sendHeartBeat(disconnected)
        delay(heartbeatPauseTime)
      }
      logger.info { "Heartbeat completed" }
    } else {
      logger.info { "Heartbeat disabled" }
    }

  fun updateScrapeCounter(type: String) {
    if (isMetricsEnabled && type.isNotEmpty())
      metrics.scrapeRequests.labels(type).inc()
  }

  fun markMsgSent() {
    lastMsgSentMark = clock.markNow()
  }

  fun awaitInitialConnection(timeout: Duration) =
    initialConnectionLatch.await(timeout.toLongMilliseconds(), MILLISECONDS)

  override fun shutDown() {
    grpcService.shutDown()
    super.shutDown()
  }

  override fun toString() =
    toStringElements {
      add("agentId", agentId)
      add("agentName", agentName)
      add("proxyHost", proxyHost)
      add("adminService", if (isAdminEnabled) adminService else "Disabled")
      add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
    }

  companion object : KLogging() {
    @JvmStatic
    fun main(argv: Array<String>) {

      logger.apply {
        info { getBanner("banners/agent.txt", this) }
        info { getVersionDesc(false) }
      }

      Agent(options = AgentOptions(argv, true)) { startSync() }
    }
  }
}