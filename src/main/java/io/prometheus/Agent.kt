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

import brave.Tracing
import brave.grpc.GrpcTracing
import com.google.common.base.Preconditions.checkNotNull
import com.google.common.collect.Maps.newConcurrentMap
import com.google.common.net.HttpHeaders
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.google.common.util.concurrent.RateLimiter
import com.google.protobuf.Empty
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors.intercept
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.agent.AgentClientInterceptor
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.*
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.GrpcObjects.Companion.ScrapeResponseArg
import io.prometheus.common.GrpcObjects.Companion.newAgentInfo
import io.prometheus.common.GrpcObjects.Companion.newHeartBeatRequest
import io.prometheus.common.GrpcObjects.Companion.newPathMapSizeRequest
import io.prometheus.common.GrpcObjects.Companion.newRegisterAgentRequest
import io.prometheus.common.GrpcObjects.Companion.newRegisterPathRequest
import io.prometheus.common.GrpcObjects.Companion.newScrapeResponse
import io.prometheus.common.GrpcObjects.Companion.newUnregisterPathRequest
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.delegate.AtomicDelegates.atomicMillis
import io.prometheus.delegate.AtomicDelegates.nonNullableReference
import io.prometheus.dsl.GrpcDsl.channel
import io.prometheus.dsl.GrpcDsl.streamObserver
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.KtorDsl.get
import io.prometheus.dsl.KtorDsl.http
import io.prometheus.grpc.ProxyServiceGrpc.*
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates.notNull

typealias FetchUrlAction = suspend () -> ScrapeResponse

@KtorExperimentalAPI
class Agent(
    options: AgentOptions,
    private val inProcessServerName: String = "",
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

    class PathContext(val pathId: Long, val path: String, val url: String)

    val channelBacklogSize = AtomicInteger(0)

    private val pathContextMap = newConcurrentMap<String, PathContext>()
    private val heartbeatService = newFixedThreadPool(1)
    private val initialConnectionLatch = CountDownLatch(1)
    private var isGrpcStarted = AtomicBoolean(false)
    private val agentName = if (options.agentName.isBlank()) "Unnamed-$localHostName" else options.agentName
    private val reconnectLimiter =
        RateLimiter.create(1.0 / configVals.internal.reconectPauseSecs).apply { acquire() } // Prime the limiter

    private val pathConfigs =
        configVals.pathConfigs
            .asSequence()
            .map {
                mapOf(
                    "name" to it.name,
                    "path" to it.path,
                    "url" to it.url
                )
            }
            .onEach { logger.info { "Proxy path /${it["path"]} will be assigned to ${it["url"]}" } }
            .toList()

    private var lastMsgSent by atomicMillis()
    private var tracing: Tracing by notNull()
    private var grpcTracing: GrpcTracing by notNull()
    private var metrics: AgentMetrics by notNull()
    private var blockingStub: ProxyServiceBlockingStub by nonNullableReference()
    private var asyncStub: ProxyServiceStub by nonNullableReference()

    var channel: ManagedChannel by nonNullableReference()
    var agentId: String by nonNullableReference()

    private val hostName: String
    private val port: Int

    private val proxyHost
        get() = "$hostName:$port"

    val configVals
        get() = genericConfigVals.agent

    init {
        logger.info { "Assigning proxy reconnect pause time to ${configVals.internal.reconectPauseSecs} secs" }

        agentId = ""

        if (options.proxyHostname.contains(":")) {
            val vals = options.proxyHostname.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            hostName = vals[0]
            port = Integer.valueOf(vals[1])
        } else {
            hostName = options.proxyHostname
            port = 50051
        }

        if (isMetricsEnabled)
            metrics = AgentMetrics(this)

        if (isZipkinEnabled) {
            tracing = zipkinReporterService.newTracing("grpc_client")
            grpcTracing = GrpcTracing.create(tracing)
        }

        resetGrpcStubs()
        initService()
        initBlock?.invoke(this)
    }

    override fun shutDown() {
        if (isZipkinEnabled)
            tracing.close()
        if (isGrpcStarted.get())
            channel.shutdownNow()
        heartbeatService.shutdownNow()
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

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry
            .register(
                "scrape_channel_backlog_check",
                newBacklogHealthCheck(channelBacklogSize.get(), configVals.internal.scrapeChannelBacklogUnhealthySize)
            )
    }

    override fun serviceName() = "$simpleClassName $agentName"

    private fun connectToProxy() {
        val disconnected = AtomicBoolean(false)

        // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agentId != ""
        if (agentId.isNotEmpty()) {
            resetGrpcStubs()
            agentId = ""
        }

        // Reset values for each connection attempt
        pathContextMap.clear()
        channelBacklogSize.set(0)
        lastMsgSent = Millis(0)

        val fetchRequestChannel: Channel<FetchUrlAction> = Channel(Channel.UNLIMITED)

        if (connectAgent()) {
            registerAgent()
            registerPaths()
            startHeartBeat(disconnected)
            readRequestsFromProxy(fetchRequestChannel, disconnected)
            writeResponsesToProxyUntilDisconnected(fetchRequestChannel, disconnected)
        }
    }

    private fun startHeartBeat(disconnected: AtomicBoolean) {
        if (configVals.internal.heartbeatEnabled) {
            val threadPauseMillis = Millis(configVals.internal.heartbeatCheckPauseMillis)
            val maxInactivitySecs = Secs(configVals.internal.heartbeatMaxInactivitySecs)
            logger.info { "Heartbeat scheduled to fire after $maxInactivitySecs secs of inactivity" }
            heartbeatService
                .submit {
                    while (isRunning && !disconnected.get()) {
                        val timeSinceLastWriteMillis = now() - lastMsgSent
                        if (timeSinceLastWriteMillis > maxInactivitySecs.toMillis())
                            sendHeartBeat(disconnected)
                        runBlocking {
                            delay(threadPauseMillis.value)
                        }
                    }
                    logger.info { "Heartbeat completed" }
                }
        } else {
            logger.info { "Heartbeat disabled" }
        }
    }

    private fun resetGrpcStubs() {
        logger.info { "Creating gRPC stubs" }

        if (isGrpcStarted.get())
            channel.shutdownNow()
        else
            isGrpcStarted.set(true)

        channel =
            channel(inProcessServerName = inProcessServerName, hostName = hostName, port = port) {
                if (isZipkinEnabled)
                    intercept(grpcTracing.newClientInterceptor())
                usePlaintext()
            }

        val interceptors = listOf<ClientInterceptor>(AgentClientInterceptor(this))

        blockingStub = newBlockingStub(intercept(channel, interceptors))
        asyncStub = newStub(intercept(channel, interceptors))
    }

    private fun updateScrapeCounter(type: String) {
        if (isMetricsEnabled && type.isNotEmpty())
            metrics.scrapeRequests.labels(type).inc()
    }

    suspend private fun fetchUrl(scrapeRequest: ScrapeRequest): ScrapeResponse {
        val responseArg = ScrapeResponseArg(agentId = scrapeRequest.agentId, scrapeId = scrapeRequest.scrapeId)
        var scrapeCounterMsg = ""
        val path = scrapeRequest.path
        val pathContext = pathContextMap[path]

        if (pathContext == null) {
            logger.warn { "Invalid path in fetchUrl(): $path" }
            scrapeCounterMsg = "invalid_path"
            responseArg.failureReason = "Invalid path: $path"
        } else {
            val requestTimer =
                if (isMetricsEnabled) metrics.scrapeRequestLatency.labels(agentName).startTimer() else null

            try {
                val setup: HttpRequestBuilder.() -> Unit = {
                    val accept = scrapeRequest.accept
                    if (!accept.isNullOrEmpty())
                        header(HttpHeaders.ACCEPT, accept)
                }

                val block: suspend (HttpResponse) -> Unit = { resp ->
                    //logger.info { "Fetching ${pathContext}" }
                    responseArg.statusCode = resp.status

                    when {
                        resp.status.isSuccessful -> {
                            responseArg.contentText = resp.readText()
                            responseArg.contentType = resp.headers[CONTENT_TYPE].orEmpty()
                            responseArg.validResponse = true
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
                logger.warn(e) { "fetchUrl() $e" }
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
            blockingStub.connectAgent(Empty.getDefaultInstance())
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
        val request = newRegisterAgentRequest(agentId, agentName, hostName)
        blockingStub.registerAgent(request)
            .also { resp ->
                markMsgSent()
                if (!resp.valid)
                    throw RequestFailureException("registerAgent() - ${resp.reason}")
            }
        initialConnectionLatch.countDown()
    }

    @Throws(RequestFailureException::class)
    private fun registerPaths() =
        pathConfigs.forEach {
            val path = it["path"]
            val url = it["url"]
            if (path != null && url != null)
                registerPath(path, url)
            else
                logger.error { "Null path/url values: $path/$url" }
        }

    @Throws(RequestFailureException::class)
    fun registerPath(pathVal: String, url: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        val pathId = registerPathOnProxy(path)
        if (!isTestMode)
            logger.info { "Registered $url as /$path" }
        pathContextMap[path] = PathContext(pathId, path, url)
    }

    @Throws(RequestFailureException::class)
    fun unregisterPath(pathVal: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        unregisterPathOnProxy(path)
        val pathContext = pathContextMap.remove(path)
        when {
            pathContext == null -> logger.info { "No path value /$path found in pathContextMap" }
            !isTestMode -> logger.info { "Unregistered /$path for ${pathContext.url}" }
        }
    }

    fun pathMapSize(): Int {
        val request = newPathMapSizeRequest(agentId)
        blockingStub.pathMapSize(request)
            .let {
                markMsgSent()
                return it.pathCount
            }
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request = newRegisterPathRequest(agentId, path)
        blockingStub.registerPath(request)
            .let {
                markMsgSent()
                if (!it.valid)
                    throw RequestFailureException("registerPath() - ${it.reason}")
                return it.pathId
            }
    }

    @Throws(RequestFailureException::class)
    private fun unregisterPathOnProxy(path: String) {
        val request = newUnregisterPathRequest(agentId, path)
        blockingStub.unregisterPath(request)
            .also {
                markMsgSent()
                if (!it.valid)
                    throw RequestFailureException("unregisterPath() - ${it.reason}")
            }
    }

    private fun readRequestsFromProxy(fetchRequestChannel: Channel<FetchUrlAction>, disconnected: AtomicBoolean) {
        val agentInfo = newAgentInfo(agentId)
        val observer =
            streamObserver<ScrapeRequest> {
                onNext { scrapeRequest ->
                    // This will block, but only for the duration of the send. The fetch happens at the other end of the channel
                    runBlocking {
                        fetchRequestChannel.send({ fetchUrl(scrapeRequest) })
                        channelBacklogSize.incrementAndGet()
                    }
                }

                onError {
                    logger.error { "Error in readRequestsFromProxy(): ${Status.fromThrowable(it)}" }
                    disconnected.set(true)
                    fetchRequestChannel.close()
                }

                onCompleted {
                    disconnected.set(true)
                    fetchRequestChannel.close()
                }
            }

        asyncStub.readRequestsFromProxy(agentInfo, observer)
    }

    private fun writeResponsesToProxyUntilDisconnected(
        fetchRequestChannel: Channel<FetchUrlAction>,
        disconnected: AtomicBoolean
    ) {
        val observer =
            asyncStub.writeResponsesToProxy(
                streamObserver<Empty> {
                    onNext {
                        // Ignore Empty return value
                    }

                    onError {
                        val s = Status.fromThrowable(it)
                        logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                        disconnected.set(true)
                    }

                    onCompleted { disconnected.set(true) }
                })

        runBlocking {
            for (fetchUrlAction in fetchRequestChannel) {
                val scrapeResponse = fetchUrlAction.invoke()
                observer.onNext(scrapeResponse)
                markMsgSent()
                channelBacklogSize.decrementAndGet()
                if (disconnected.get())
                    break
            }
        }

        logger.info { "Disconnected from proxy at $proxyHost" }

        observer.onCompleted()
    }

    private fun markMsgSent() {
        lastMsgSent = now()
    }

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (agentId.isEmpty())
            return

        try {
            val request = newHeartBeatRequest(agentId)
            blockingStub.sendHeartBeat(request)
                .also {
                    markMsgSent()
                    if (!it.valid) {
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
            val options = AgentOptions(argv, true)

            logger.apply {
                info { getBanner("banners/agent.txt", this) }
                info { getVersionDesc(false) }
            }

            Agent(options = options) { startSync() }
        }
    }
}