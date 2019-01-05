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
import io.prometheus.agent.AgentClientInterceptor
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.PathContext
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.ConfigVals
import io.prometheus.common.GenericService
import io.prometheus.common.GrpcObjects.Companion.newAgentInfo
import io.prometheus.common.GrpcObjects.Companion.newHeartBeatRequest
import io.prometheus.common.GrpcObjects.Companion.newPathMapSizeRequest
import io.prometheus.common.GrpcObjects.Companion.newRegisterAgentRequest
import io.prometheus.common.GrpcObjects.Companion.newRegisterPathRequest
import io.prometheus.common.GrpcObjects.Companion.newScrapeResponse
import io.prometheus.common.GrpcObjects.Companion.newUnregisterPathRequest
import io.prometheus.common.InstrumentedThreadFactory
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.common.getBanner
import io.prometheus.common.getVersionDesc
import io.prometheus.common.localHostName
import io.prometheus.common.newQueueHealthCheck
import io.prometheus.common.sleepForMillis
import io.prometheus.common.toMillis
import io.prometheus.delegate.AtomicDelegates.atomicLong
import io.prometheus.delegate.AtomicDelegates.nonNullableReference
import io.prometheus.dsl.GrpcDsl.channel
import io.prometheus.dsl.GrpcDsl.streamObserver
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.ThreadDsl.threadFactory
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceBlockingStub
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceStub
import io.prometheus.grpc.ProxyServiceGrpc.newBlockingStub
import io.prometheus.grpc.ProxyServiceGrpc.newStub
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import mu.KLogging
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors.newCachedThreadPool
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates.notNull

class Agent(options: AgentOptions,
            private val inProcessServerName: String = "",
            testMode: Boolean = false,
            initBlock: (Agent.() -> Unit)? = null) :
        GenericService(options.configVals,
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
    private var blockingStub: ProxyServiceBlockingStub by nonNullableReference()
    private var asyncStub: ProxyServiceStub by nonNullableReference()
    private val readRequestsExecutorService =
            newCachedThreadPool(if (isMetricsEnabled)
                                    InstrumentedThreadFactory(
                                            threadFactory {
                                                setNameFormat("agent_fetch" + "-%d")
                                                setDaemon(true)
                                            },
                                            "agent_fetch",
                                            "Agent fetch")
                                else
                                    threadFactory {
                                        setNameFormat("agent_fetch-%d")
                                        setDaemon(true)
                                    })!!

    private var tracing: Tracing by notNull()
    private var grpcTracing: GrpcTracing by notNull()

    private val hostName: String
    private val port: Int
    private val reconnectLimiter =
            RateLimiter.create(1.0 / configVals.internal.reconectPauseSecs)!!.apply { acquire() } // Prime the limiter

    private val pathConfigs =
            configVals.pathConfigs
                    .asSequence()
                    .map { mapOf("name" to it.name!!, "path" to it.path!!, "url" to it.url!!) }
                    .onEach { logger.info { "Proxy path /${it["path"]} will be assigned to ${it["url"]}" } }
                    .toList()

    private var lastMsgSent by atomicLong()

    private var isGrpcStarted = AtomicBoolean(false)
    var channel: ManagedChannel by nonNullableReference()
    var agentId: String by nonNullableReference()

    private val proxyHost
        get() = "$hostName:$port"

    val scrapeResponseQueueSize
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
        lastMsgSent = 0

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
                    val timeSinceLastWriteMillis = System.currentTimeMillis() - lastMsgSent
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
                    usePlaintext()
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
        val pathContext = pathContextMap[path]

        if (pathContext == null) {
            logger.warn { "Invalid path in fetchUrl(): $path" }
            updateScrapeCounter("invalid_path")
            return newScrapeResponse(false,
                                     "Invalid path: $path",
                                     scrapeRequest.agentId,
                                     scrapeRequest.scrapeId,
                                     statusCode)
        }

        val requestTimer = if (isMetricsEnabled) metrics.scrapeRequestLatency.labels(agentName).startTimer() else null
        var reason = "None"

        try {
            pathContext.fetchUrl(scrapeRequest).use {
                statusCode = it.code()
                if (it.isSuccessful) {
                    updateScrapeCounter("success")
                    return newScrapeResponse(true,
                                             "",
                                             scrapeRequest.agentId,
                                             scrapeRequest.scrapeId,
                                             statusCode,
                                             it.body()?.string().orEmpty(),
                                             it.header(CONTENT_TYPE) ?: "")
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

        return newScrapeResponse(false,
                                 reason,
                                 scrapeRequest.agentId,
                                 scrapeRequest.scrapeId,
                                 statusCode)
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
                .let {
                    markMsgSent()
                    if (!it.valid)
                        throw RequestFailureException("registerAgent() - ${it.reason}")
                }
        initialConnectionLatch.countDown()
    }

    @Throws(RequestFailureException::class)
    private fun registerPaths() =
            pathConfigs
                    .forEach {
                        registerPath(it["path"]!!, it["url"]!!)
                    }

    @Throws(RequestFailureException::class)
    fun registerPath(pathVal: String, url: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        val pathId = registerPathOnProxy(path)
        if (!isTestMode)
            logger.info { "Registered $url as /$path" }
        pathContextMap[path] = PathContext(okHttpClient, pathId, path, url)
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
        val request = newPathMapSizeRequest(agentId)
        blockingStub.pathMapSize(request)!!
                .let {
                    markMsgSent()
                    return it.pathCount
                }
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request = newRegisterPathRequest(agentId, path)
        blockingStub.registerPath(request)!!
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
        blockingStub.unregisterPath(request)!!
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
        val agentInfo = newAgentInfo(agentId)

        val observer =
                streamObserver<ScrapeRequest> {
                    onNext { readRequestsExecutorService.submit(readRequestAction(it)) }

                    onError {
                        logger.error { "Error in readRequestsFromProxy(): ${Status.fromThrowable(it)}" }
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
                            onNext {
                                // Ignore Empty return value
                            }

                            onError {
                                val s = Status.fromThrowable(it)!!
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
        lastMsgSent = System.currentTimeMillis()
    }

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (agentId.isEmpty())
            return

        try {
            val request = newHeartBeatRequest(agentId)
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