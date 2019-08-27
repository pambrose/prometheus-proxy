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

import com.google.common.net.HttpHeaders
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.google.common.util.concurrent.RateLimiter
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.agent.*
import io.prometheus.common.*
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.GrpcObjects.Companion.ScrapeResponseArg
import io.prometheus.common.GrpcObjects.Companion.newAgentInfo
import io.prometheus.common.GrpcObjects.Companion.newHeartBeatRequest
import io.prometheus.common.GrpcObjects.Companion.newRegisterAgentRequest
import io.prometheus.common.GrpcObjects.Companion.newScrapeResponse
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.delegate.AtomicDelegates.nonNullableReference
import io.prometheus.dsl.GrpcDsl.streamObserver
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.KtorDsl.get
import io.prometheus.dsl.KtorDsl.http
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KLogging
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates.notNull
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@UseExperimental(ExperimentalTime::class)
class Agent(
    options: AgentOptions,
    inProcessServerName: String = "",
    testMode: Boolean = false,
    initBlock: (Agent.() -> Unit)? = null
) :
    GenericService(
        options.configVals,
        newAdminConfig(
            options.adminEnabled,
            options.adminPort,
            options.configVals.agent.admin
        ),
        newMetricsConfig(
            options.metricsEnabled,
            options.metricsPort,
            options.configVals.agent.metrics
        ),
        newZipkinConfig(options.configVals.agent.internal.zipkin),
        testMode
    ) {

    private val configVals = genericConfigVals.agent.internal
    private val initialConnectionLatch = CountDownLatch(1)
    private val agentName = options.agentName.isBlank().thenElse("Unnamed-$localHostName", options.agentName)
    private val reconnectLimiter =
        RateLimiter.create(1.0 / configVals.reconectPauseSecs).apply { acquire() } // Prime the limiter

    private var lastMsgSent by nonNullableReference<Duration>()
    private var metrics by notNull<AgentMetrics>()

    var agentId by nonNullableReference("")

    val scrapeRequestBacklogSize = AtomicInteger(0)
    val pathManager = AgentPathManager(this)
    val grpcService: AgentGrpcService = AgentGrpcService(this, options, inProcessServerName)

    init {
        logger.info { "Assigning proxy reconnect pause time to ${configVals.reconectPauseSecs} secs" }

        if (isMetricsEnabled)
            metrics = AgentMetrics(this)

        initService()
        initBlock?.invoke(this)
    }

    override fun shutDown() {
        grpcService.shutDown()
        super.shutDown()
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

    private val proxyHost
        get() = "${grpcService.hostName}:${grpcService.port}"

    override fun serviceName() = "$simpleClassName $agentName"

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry.register(
            "scrape_request_backlog_check",
            newBacklogHealthCheck(scrapeRequestBacklogSize.get(), configVals.scrapeRequestBacklogUnhealthySize)
        )
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
        lastMsgSent = 0.milliseconds

        if (connectAgent()) {
            registerAgent()
            pathManager.registerPaths()

            val scrapeRequestChannel = Channel<ScrapeRequestAction>(configVals.scrapeRequestChannelSize)

            readFromProxy(scrapeRequestChannel, disconnected)

            runBlocking {
                launch(Dispatchers.Default) { startHeartBeat(disconnected) }
                launch(Dispatchers.Default) { writeToProxyUntilDisconnected(scrapeRequestChannel, disconnected) }
            }
        }
    }

    private suspend fun startHeartBeat(disconnected: AtomicBoolean) =
        if (configVals.heartbeatEnabled) {
            val heartbeatPauseMillis = configVals.heartbeatCheckPauseMillis.milliseconds
            val maxInactivitySecs = configVals.heartbeatMaxInactivitySecs.seconds
            logger.info { "Heartbeat scheduled to fire after ${maxInactivitySecs.inSeconds.toInt()} secs of inactivity" }

            while (isRunning && !disconnected.get()) {
                val timeSinceLastWriteMillis = now() - lastMsgSent
                if (timeSinceLastWriteMillis > maxInactivitySecs)
                    sendHeartBeat(disconnected)
                delay(heartbeatPauseMillis.toLongMilliseconds())
            }
            logger.info { "Heartbeat completed" }

        } else {
            logger.info { "Heartbeat disabled" }
        }

    private fun updateScrapeCounter(type: String) {
        if (isMetricsEnabled && type.isNotEmpty())
            metrics.scrapeRequests.labels(type).inc()
    }

    private suspend fun fetchScrapeUrl(request: ScrapeRequest): ScrapeResponse {
        val responseArg = ScrapeResponseArg(agentId = request.agentId, scrapeId = request.scrapeId)
        var scrapeCounterMsg = ""
        val path = request.path
        val pathContext = pathManager[path]

        if (pathContext == null) {
            logger.warn { "Invalid path in fetchScrapeUrl(): $path" }
            scrapeCounterMsg = "invalid_path"
            responseArg.failureReason = "Invalid path: $path"
        } else {
            val requestTimer =
                if (isMetricsEnabled) metrics.scrapeRequestLatency.labels(agentName).startTimer() else null

            try {
                val setup: HttpRequestBuilder.() -> Unit = {
                    val accept = request.accept
                    if (!accept.isNullOrEmpty())
                        header(HttpHeaders.ACCEPT, accept)
                }

                val block: suspend (HttpResponse) -> Unit = { resp ->
                    //logger.info { "Fetching ${pathContext}" }
                    responseArg.statusCode = resp.status

                    when {
                        resp.status.isSuccessful -> {
                            responseArg.apply {
                                contentText = resp.readText()
                                contentType = resp.headers[CONTENT_TYPE].orEmpty()
                                validResponse = true
                            }
                            scrapeCounterMsg = "success"
                        }
                        else -> {
                            responseArg.failureReason = "Unsucessful response code ${responseArg.statusCode}"
                            scrapeCounterMsg = "unsuccessful"
                        }
                    }
                }

                http {
                    get(pathContext.url, setup, block)
                }

            } catch (e: IOException) {
                logger.info { "Failed HTTP request: ${pathContext.url} [${e.simpleClassName}: ${e.message}]" }
                responseArg.failureReason = "${e.simpleClassName} - ${e.message}"
            } catch (e: Exception) {
                logger.warn(e) { "fetchScrapeUrl() $e" }
                responseArg.failureReason = "${e.simpleClassName} - ${e.message}"
            } finally {
                requestTimer?.observeDuration()
            }
        }

        updateScrapeCounter(scrapeCounterMsg)
        return newScrapeResponse(responseArg)
    }

    // If successful, this will create an agentContxt on the Proxy and an interceptor will add an agent_id to the headers`
    private fun connectAgent() =
        try {
            logger.info { "Connecting to proxy at $proxyHost..." }
            grpcService.blockingStub.connectAgent(Empty.getDefaultInstance())
            logger.info { "Connected to proxy at $proxyHost" }
            if (isMetricsEnabled)
                metrics.connects.labels("success")?.inc()
            true
        } catch (e: StatusRuntimeException) {
            if (isMetricsEnabled)
                metrics.connects.labels("failure")?.inc()
            logger.info { "Cannot connect to proxy at $proxyHost [${e.message}]" }
            false
        }

    @Throws(RequestFailureException::class)
    private fun registerAgent() {
        val request = newRegisterAgentRequest(agentId, agentName, grpcService.hostName)
        grpcService.blockingStub.registerAgent(request)
            .also { resp ->
                markMsgSent()
                if (!resp.valid)
                    throw RequestFailureException("registerAgent() - ${resp.reason}")
            }
        initialConnectionLatch.countDown()
    }

    private fun readFromProxy(scrapeRequestChannel: Channel<ScrapeRequestAction>, disconnected: AtomicBoolean) {
        val agentInfo = newAgentInfo(agentId)
        val observer =
            streamObserver<ScrapeRequest> {
                onNext { req ->
                    // This will block, but only for the duration of the send. The fetch happens at the other end of the channel
                    runBlocking {
                        scrapeRequestChannel.send { fetchScrapeUrl(req) }
                        scrapeRequestBacklogSize.incrementAndGet()
                    }
                }

                onError { throwable ->
                    logger.error { "Error in readFromProxy(): ${Status.fromThrowable(throwable)}" }
                    disconnected.set(true)
                    scrapeRequestChannel.close()
                }

                onCompleted {
                    disconnected.set(true)
                    scrapeRequestChannel.close()
                }
            }

        grpcService.asyncStub.readRequestsFromProxy(agentInfo, observer)
    }

    private suspend fun writeToProxyUntilDisconnected(
        scrapeRequestChannel: Channel<ScrapeRequestAction>,
        disconnected: AtomicBoolean
    ) {
        val observer =
            grpcService.asyncStub.writeResponsesToProxy(
                streamObserver<Empty> {
                    onNext {
                        // Ignore Empty return value
                    }

                    onError { throwable ->
                        val s = Status.fromThrowable(throwable)
                        logger.error { "Error in writeToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                        disconnected.set(true)
                    }

                    onCompleted { disconnected.set(true) }
                })

        for (scrapeRequestAction in scrapeRequestChannel) {
            val scrapeResponse = scrapeRequestAction.invoke()
            observer.onNext(scrapeResponse)
            markMsgSent()
            scrapeRequestBacklogSize.decrementAndGet()
            if (disconnected.get())
                break
        }

        logger.info { "Disconnected from proxy at $proxyHost" }

        observer.onCompleted()
    }

    fun markMsgSent() {
        lastMsgSent = now()
    }

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (agentId.isEmpty())
            return

        try {
            val request = newHeartBeatRequest(agentId)
            grpcService.blockingStub.sendHeartBeat(request)
                .also { resp ->
                    markMsgSent()
                    if (!resp.valid) {
                        logger.error { "AgentId $agentId not found on proxy" }
                        throw StatusRuntimeException(Status.NOT_FOUND)
                    }
                }
        } catch (e: StatusRuntimeException) {
            logger.error { "Hearbeat failed ${e.status}" }
            disconnected.set(true)
        }
    }

    @Throws(InterruptedException::class)
    fun awaitInitialConnection(timeout: Long, unit: TimeUnit) = initialConnectionLatch.await(timeout, unit)

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

            val options = AgentOptions(argv, true)
            Agent(options = options) { startSync() }
        }
    }
}