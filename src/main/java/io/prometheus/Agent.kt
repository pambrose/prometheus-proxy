/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus

import brave.Tracing
import brave.grpc.GrpcTracing
import com.google.common.base.Preconditions.checkNotNull
import com.google.common.collect.Maps.newConcurrentMap
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.google.common.util.concurrent.RateLimiter
import com.google.protobuf.Empty
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors.intercept
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.agent.*
import io.prometheus.common.*
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.delegate.AtomicDelegates.notNullReference
import io.prometheus.dsl.GrpcDsl.channel
import io.prometheus.dsl.GrpcDsl.streamObserver
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.ThreadDsl.threadFactory
import io.prometheus.grpc.*
import io.prometheus.grpc.ProxyServiceGrpc.*
import mu.KLogging
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors.newCachedThreadPool
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates.notNull

class Agent(options: AgentOptions,
            private val inProcessServerName: String = "",
            testMode: Boolean = false,
            initBlock: (Agent.() -> Unit)? = null) : GenericService(options.configVals,
                                                                    newAdminConfig(options.adminEnabled,
                                                                                   options.adminPort,
                                                                                   options.configVals.agent.admin),
                                                                    newMetricsConfig(options.metricsEnabled,
                                                                                     options.metricsPort,
                                                                                     options.configVals.agent.metrics),
                                                                    newZipkinConfig(options.configVals.agent.internal.zipkin),
                                                                    testMode) {
    private val pathContextMap = newConcurrentMap<String, PathContext>()  // Map path to PathContext
    private val heartbeatService = newFixedThreadPool(1)
    private val initialConnectionLatch = CountDownLatch(1)
    private val okHttpClient = OkHttpClient()
    private val scrapeResponseQueue = ArrayBlockingQueue<ScrapeResponse>(configVals.internal.scrapeResponseQueueSize)
    private val agentName: String = if (options.agentName.isBlank()) "Unnamed-$localHostName" else options.agentName
    private var metrics: AgentMetrics by notNull()
    private var blockingStub: ProxyServiceBlockingStub by notNullReference()
    private var asyncStub: ProxyServiceStub by notNullReference()
    private val readRequestsExecutorService: ExecutorService =
            newCachedThreadPool(if (isMetricsEnabled)
                                    InstrumentedThreadFactory(
                                            threadFactory {
                                                setNameFormat("agent_fetch" + "-%d")
                                                setDaemon(true)
                                            }, "agent_fetch", "Agent fetch")
                                else
                                    threadFactory {
                                        setNameFormat("agent_fetch-%d")
                                        setDaemon(true)
                                    })

    private var tracing: Tracing by notNull()
    private var grpcTracing: GrpcTracing by notNull()

    private val hostName: String
    private val port: Int
    private val reconnectLimiter =
            RateLimiter.create(1.0 / configVals.internal.reconectPauseSecs).apply { acquire() } // Prime the limiter

    private val pathConfigs =
            configVals.pathConfigs
                    .map { mapOf("name" to it.name, "path" to it.path, "url" to it.url) }
                    .onEach { logger.info { "Proxy path /${it["path"]} will be assigned to ${it["url"]}" } }
                    .toList()

    private var lastMsgSent = AtomicLong()

    private var isGrpcStarted = AtomicBoolean(false)
    var channel: ManagedChannel by notNullReference()
    var agentId: String by notNullReference()

    private val proxyHost: String
        get() = "$hostName:$port"

    val scrapeResponseQueueSize: Int
        get() = scrapeResponseQueue.size

    val configVals: ConfigVals.Agent
        get() = genericConfigVals.agent

    init {
        logger.info { "Assigning proxy reconnect pause time to ${configVals.internal.reconectPauseSecs} secs" }

        agentId = ""

        if (options.proxyHostname.contains(":")) {
            val vals = options.proxyHostname.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            hostName = vals[0]
            port = Integer.valueOf(vals[1])
        }
        else {
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
                .register("scrape_response_queue_check",
                          newQueueHealthCheck(scrapeResponseQueue, configVals.internal.scrapeResponseQueueUnhealthySize))
    }

    override fun serviceName() = "${javaClass.simpleName} $agentName"

    @Throws(RequestFailureException::class)
    private fun connectToProxy() {
        val disconnected = AtomicBoolean(false)

        // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agentId != ""
        if (agentId.isNotEmpty()) {
            resetGrpcStubs()
            agentId = ""
        }

        // Reset values for each connection attempt
        pathContextMap.clear()
        scrapeResponseQueue.clear()
        lastMsgSent.set(0)

        if (connectAgent()) {
            registerAgent()
            registerPaths()
            startHeartBeat(disconnected)
            readRequestsFromProxy(disconnected)
            writeResponsesToProxyUntilDisconnected(disconnected)
        }
    }

    private fun startHeartBeat(disconnected: AtomicBoolean) {
        if (configVals.internal.heartbeatEnabled) {
            val threadPauseMillis = configVals.internal.heartbeatCheckPauseMillis.toLong()
            val maxInactivitySecs = configVals.internal.heartbeatMaxInactivitySecs
            logger.info { "Heartbeat scheduled to fire after $maxInactivitySecs secs of inactivity" }
            heartbeatService.submit {
                while (isRunning && !disconnected.get()) {
                    val timeSinceLastWriteMillis = System.currentTimeMillis() - lastMsgSent.get()
                    if (timeSinceLastWriteMillis > maxInactivitySecs.toLong().toMillis())
                        sendHeartBeat(disconnected)
                    sleepForMillis(threadPauseMillis)
                }
                logger.info { "Heartbeat completed" }
            }
        }
        else {
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
                    usePlaintext(true)
                }

        val interceptors = listOf<ClientInterceptor>(AgentClientInterceptor(this))

        blockingStub = newBlockingStub(intercept(channel, interceptors))
        asyncStub = newStub(intercept(channel, interceptors))
    }

    private fun updateScrapeCounter(type: String) {
        if (isMetricsEnabled)
            metrics.scrapeRequests.labels(type).inc()
    }

    private fun fetchUrl(scrapeRequest: ScrapeRequest): ScrapeResponse {
        var statusCode = 404
        val path = scrapeRequest.path
        val scrapeResponse =
                ScrapeResponse.newBuilder()
                        .apply {
                            agentId = scrapeRequest.agentId
                            scrapeId = scrapeRequest.scrapeId
                        }
        val pathContext = pathContextMap[path]

        if (pathContext == null) {
            logger.warn { "Invalid path in fetchUrl(): $path" }
            updateScrapeCounter("invalid_path")
            return scrapeResponse
                    .run {
                        valid = false
                        reason = "Invalid path: $path"
                        this.statusCode = statusCode
                        text = ""
                        contentType = ""
                        build()
                    }
        }

        val requestTimer = if (isMetricsEnabled) metrics.scrapeRequestLatency.labels(agentName).startTimer() else null
        var reason = "None"

        try {
            pathContext.fetchUrl(scrapeRequest).use {
                statusCode = it.code()
                if (it.isSuccessful) {
                    updateScrapeCounter("success")
                    return scrapeResponse
                            .run {
                                valid = true
                                reason = ""
                                this.statusCode = statusCode
                                text = it.body()?.string() ?: ""
                                contentType = it.header(CONTENT_TYPE)
                                build()
                            }
                }
                else {
                    reason = "Unsucessful response code $statusCode"
                }
            }
        } catch (e: IOException) {
            reason = "${e.javaClass.simpleName} - ${e.message}"
        } catch (e: Exception) {
            logger.warn(e) { "fetchUrl()" }
            reason = "${e.javaClass.simpleName} - ${e.message}"
        } finally {
            requestTimer?.observeDuration()
        }

        updateScrapeCounter("unsuccessful")

        return scrapeResponse
                .run {
                    valid = false
                    this.reason = reason
                    this.statusCode = statusCode
                    text = ""
                    contentType = ""
                    build()
                }
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
        val request =
                RegisterAgentRequest.newBuilder()
                        .run {
                            agentId = this@Agent.agentId
                            agentName = this@Agent.agentName
                            hostName = this@Agent.hostName
                            build()
                        }
        blockingStub.registerAgent(request)
                .let {
                    markMsgSent()
                    if (!it.valid)
                        throw RequestFailureException("registerAgent() - ${it.reason}")
                }

        initialConnectionLatch.countDown()
    }

    @Throws(RequestFailureException::class)
    private fun registerPaths() {
        pathConfigs.forEach {
            registerPath(it["path"]!!, it["url"]!!)
        }
    }

    @Throws(RequestFailureException::class)
    fun registerPath(pathVal: String, url: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        val pathId = registerPathOnProxy(path)
        if (!isTestMode)
            logger.info { "Registered $url as /$path" }
        pathContextMap.put(path, PathContext(okHttpClient, pathId, path, url))
    }

    @Throws(RequestFailureException::class)
    fun unregisterPath(pathVal: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        unregisterPathOnProxy(path)
        val pathContext = pathContextMap.remove(path)
        when {
            pathContext == null -> logger.info { "No path value /$path found in pathContextMap" }
            !isTestMode         -> logger.info { "Unregistered /$path for ${pathContext.url}" }
        }
    }

    fun pathMapSize(): Int {
        val request =
                PathMapSizeRequest.newBuilder()
                        .run {
                            agentId = this@Agent.agentId
                            build()
                        }
        blockingStub.pathMapSize(request)
                .let {
                    markMsgSent()
                    return it.pathCount
                }
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request =
                RegisterPathRequest.newBuilder()
                        .run {
                            agentId = this@Agent.agentId
                            this.path = path
                            build()
                        }
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
        val request =
                UnregisterPathRequest.newBuilder()
                        .run {
                            agentId = this@Agent.agentId
                            this.path = path
                            build()
                        }
        blockingStub.unregisterPath(request)
                .let {
                    markMsgSent()
                    if (!it.valid)
                        throw RequestFailureException("unregisterPath() - ${it.reason}")
                }
    }

    private fun readRequestAction(request: ScrapeRequest) =
            Runnable {
                val response = fetchUrl(request)
                try {
                    scrapeResponseQueue.put(response)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }

    private fun readRequestsFromProxy(disconnected: AtomicBoolean) {
        val agentInfo =
                AgentInfo.newBuilder()
                        .run {
                            agentId = this@Agent.agentId
                            build()
                        }

        val observer =
                streamObserver<ScrapeRequest> {
                    onNext { request ->
                        readRequestsExecutorService.submit(readRequestAction(request))
                    }

                    onError { t ->
                        val status = Status.fromThrowable(t)
                        logger.error { "Error in readRequestsFromProxy(): $status" }
                        disconnected.set(true)
                    }

                    onCompleted {
                        disconnected.set(true)
                    }
                }

        asyncStub.readRequestsFromProxy(agentInfo, observer)
    }

    private fun writeResponsesToProxyUntilDisconnected(disconnected: AtomicBoolean) {
        val checkMillis = configVals.internal.scrapeResponseQueueCheckMillis.toLong()
        val observer =
                asyncStub.writeResponsesToProxy(
                        streamObserver<Empty> {
                            onNext { _ ->
                                // Ignore Empty return value
                            }

                            onError { t ->
                                val s = Status.fromThrowable(t)
                                logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                                disconnected.set(true)
                            }

                            onCompleted { disconnected.set(true) }
                        })

        while (!disconnected.get()) {
            try {
                // Set a short timeout to check if client has disconnected
                scrapeResponseQueue.poll(checkMillis, TimeUnit.MILLISECONDS)
                        ?.let {
                            observer.onNext(it)
                            markMsgSent()
                        }
            } catch (e: InterruptedException) {
                // Ignore
            }
        }

        logger.info { "Disconnected from proxy at $proxyHost" }
        observer.onCompleted()
    }

    private fun markMsgSent() {
        lastMsgSent.set(System.currentTimeMillis())
    }

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (agentId.isEmpty())
            return

        try {
            val request =
                    HeartBeatRequest.newBuilder()
                            .run {
                                agentId = this@Agent.agentId
                                build()
                            }
            blockingStub.sendHeartBeat(request)
                    .let {
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

            logger.info { getBanner("banners/agent.txt", logger) }
            logger.info { getVersionDesc(false) }

            Agent(options = options) { startSync() }
        }
    }
}