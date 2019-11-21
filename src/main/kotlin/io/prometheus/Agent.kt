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

import com.github.pambrose.common.coroutine.delay
import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.servlet.LambdaServlet
import com.github.pambrose.common.time.format
import com.github.pambrose.common.util.MetricsUtils.newBacklogHealthCheck
import com.github.pambrose.common.util.getBanner
import com.github.pambrose.common.util.hostInfo
import com.github.pambrose.common.util.simpleClassName
import com.google.common.util.concurrent.RateLimiter
import io.grpc.StatusRuntimeException
import io.prometheus.agent.AgentConnectionContext
import io.prometheus.agent.AgentGrpcService
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.RequestFailureException
import io.prometheus.client.Summary
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.getVersionDesc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
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
                             isTestMode = testMode) {

  private val agentConfigVals = configVals.agent.internal
  private val clock = MonoClock
  private val agentHttpService = AgentHttpService(this)
  private val initialConnectionLatch = CountDownLatch(1)
  // Prime the limiter
  private val reconnectLimiter = RateLimiter.create(1.0 / agentConfigVals.reconnectPauseSecs).apply { acquire() }
  private var lastMsgSentMark: ClockMark by nonNullableReference(clock.markNow())

  val agentName = if (options.agentName.isBlank()) "Unnamed-${hostInfo.hostName}" else options.agentName
  val scrapeRequestBacklogSize = AtomicInteger(0)
  val pathManager = AgentPathManager(this)
  val grpcService = AgentGrpcService(this, options, inProcessServerName)
  var agentId: String by nonNullableReference("")

  lateinit var metrics: AgentMetrics

  init {
    logger.info { "Assigning proxy reconnect pause time to ${agentConfigVals.reconnectPauseSecs.seconds}" }

    if (isMetricsEnabled)
      metrics = AgentMetrics(this)

    initService {
      if (options.debugEnabled)
        addServlet(DEBUG,
                   LambdaServlet {
                     listOf(toPlainText(), pathManager.toPlainText()).joinToString("\n")
                   })
    }

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
        logger.info { "Waited ${reconnectLimiter.acquire().roundToInt().seconds} to reconnect" }
      }
    }
  }

  val proxyHost get() = "${grpcService.hostName}:${grpcService.port}"

  fun startTimer(): Summary.Timer? = metrics.scrapeRequestLatency.labels(agentName).startTimer()

  override fun serviceName() = "$simpleClassName $agentName"

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry.register("scrape_request_backlog_check",
                                 newBacklogHealthCheck(scrapeRequestBacklogSize.get(),
                                                       agentConfigVals.scrapeRequestBacklogUnhealthySize))
  }

  private fun connectToProxy() {
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

      val connectionContext = AgentConnectionContext()
      grpcService.readRequestsFromProxy(agentHttpService, connectionContext)

      runBlocking {
        launch(Dispatchers.Default) { startHeartBeat(connectionContext) }

        launch(Dispatchers.Default) { grpcService.writeResponsesToProxyUntilDisconnected(connectionContext) }

        for (scrapeRequestAction in connectionContext.scrapeRequestChannel) {
          launch(Dispatchers.Default) {
            // The fetch actually occurs here
            val scrapeResponse = scrapeRequestAction.invoke()
            connectionContext.scrapeResultChannel.send(scrapeResponse)
          }
        }
      }
    }
  }

  private suspend fun startHeartBeat(connectionContext: AgentConnectionContext) =
    if (agentConfigVals.heartbeatEnabled) {
      val heartbeatPauseTime = agentConfigVals.heartbeatCheckPauseMillis.milliseconds
      val maxInactivityTime = agentConfigVals.heartbeatMaxInactivitySecs.seconds
      logger.info { "Heartbeat scheduled to fire after $maxInactivityTime of inactivity" }

      while (isRunning && connectionContext.connected) {
        val timeSinceLastWrite = lastMsgSentMark.elapsedNow()
        if (timeSinceLastWrite > maxInactivityTime)
          grpcService.sendHeartBeat(connectionContext)
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

  fun toPlainText() =
    """
      Prometheus Agent Info [${getVersionDesc(false)}]
      
      Uptime:    ${upTime.format(true)}
      AgentId:   $agentId 
      AgentName: $agentName
      ProxyHost: $proxyHost
      
      AdminService:
      ${if (isAdminEnabled) adminService.toString() else "Disabled"}
      
      MetricsService:
      ${if (isMetricsEnabled) metricsService.toString() else "Disabled"}
      
    """.trimIndent()

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