/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus

import brave.Tracing
import brave.grpc.GrpcTracing
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions.checkNotNull
import com.google.common.collect.Maps
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import com.google.common.util.concurrent.RateLimiter
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.protobuf.Empty
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors.intercept
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import io.prometheus.agent.*
import io.prometheus.common.*
import io.prometheus.grpc.*
import io.prometheus.grpc.ProxyServiceGrpc.*
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.Executors.newCachedThreadPool
import java.util.concurrent.atomic.AtomicBoolean

class Agent(options: AgentOptions,
            private val inProcessServerName: String = "",
            testMode: Boolean = false) : GenericService(options.configVals,
                                                        AdminConfig.create(options.adminEnabled,
                                                                           options.adminPort!!,
                                                                           options.configVals.agent.admin),
                                                        MetricsConfig.create(options.metricsEnabled,
                                                                             options.metricsPort!!,
                                                                             options.configVals.agent.metrics),
                                                        ZipkinConfig.create(options.configVals.agent.internal.zipkin),
                                                        testMode) {

    private val pathContextMap = Maps.newConcurrentMap<String, PathContext>()  // Map path to PathContext
    private val heartbeatService = Executors.newFixedThreadPool(1)
    private val initialConnectionLatch = CountDownLatch(1)
    private val okHttpClient = OkHttpClient()
    private val scrapeResponseQueue = ArrayBlockingQueue<ScrapeResponse>(configVals.internal.scrapeResponseQueueSize)
    private val agentName: String = if (options.agentName.isNullOrBlank()) "Unnamed-${io.prometheus.common.hostName}" else options.agentName!!
    private val metrics: AgentMetrics? = if (metricsEnabled) AgentMetrics(this) else null
    private var blockingStub: ProxyServiceBlockingStub by AtomicDelegates.notNullReference()
    private var asyncStub: ProxyServiceStub by AtomicDelegates.notNullReference()
    private val readRequestsExecutorService: ExecutorService =
            newCachedThreadPool(if (metricsEnabled)
                                    InstrumentedThreadFactory.newInstrumentedThreadFactory("agent_fetch",
                                                                                           "Agent fetch",
                                                                                           true)
                                else
                                    ThreadFactoryBuilder()
                                            .setNameFormat("agent_fetch-%d")
                                            .setDaemon(true)
                                            .build())

    private val tracing: Tracing?
    private val grpcTracing: GrpcTracing?
    private val hostName: String
    private val port: Int
    private val reconnectLimiter: RateLimiter
    private val pathConfigs: List<Map<String, String>>

    private var lastMsgSent: Long by AtomicDelegates.long()

    private val proxyHost: String
        get() = "$hostName:$port"

    var channel: ManagedChannel? by AtomicDelegates.nullableReference()
    var agentId: String by AtomicDelegates.notNullReference()

    val scrapeResponseQueueSize: Int
        get() = scrapeResponseQueue.size

    val configVals: ConfigVals.Agent
        get() = genericConfigVals.agent

    init {
        logger.info("Assigning proxy reconnect pause time to ${configVals.internal.reconectPauseSecs} secs")

        agentId = ""
        reconnectLimiter = RateLimiter.create(1.0 / configVals.internal.reconectPauseSecs)
        reconnectLimiter.acquire()  // Prime the limiter

        pathConfigs =
                configVals.pathConfigs
                        .map { mapOf("name" to it.name, "path" to it.path, "url" to it.url) }
                        .onEach { logger.info("Proxy path /{} will be assigned to {}", it["path"], it["url"]) }
                        .toList()

        if (options.proxyHostname!!.contains(":")) {
            val vals = options.proxyHostname!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            hostName = vals[0]
            port = Integer.valueOf(vals[1])
        }
        else {
            hostName = options.proxyHostname!!
            port = 50051
        }

        if (zipkinEnabled) {
            tracing = zipkinReporterService!!.newTracing("grpc_client")
            grpcTracing = GrpcTracing.create(tracing)
        }
        else {
            tracing = null
            grpcTracing = null
        }

        resetGrpcStubs()
        initService()
    }

    override fun shutDown() {
        tracing?.close()
        channel?.shutdownNow()
        heartbeatService.shutdownNow()
        super.shutDown()
    }

    override fun run() {
        while (isRunning) {
            try {
                connectToProxy()
            } catch (e: RequestFailureException) {
                logger.info("Disconnected from proxy at $proxyHost after invalid response ${e.message}")
            } catch (e: StatusRuntimeException) {
                logger.info("Disconnected from proxy at $proxyHost")
            } catch (e: Exception) {
                // Catch anything else to avoid exiting retry loop
            } finally {
                val secsWaiting = reconnectLimiter.acquire()
                logger.info("Waited $secsWaiting secs to reconnect")
            }
        }
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry
                .register("scrape_response_queue_check",
                          queueHealthCheck(scrapeResponseQueue, configVals.internal.scrapeResponseQueueUnhealthySize))
    }

    override fun serviceName() = "${javaClass.simpleName} $agentName"

    @Throws(RequestFailureException::class)
    private fun connectToProxy() {
        val disconnected = AtomicBoolean(false)

        // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agentId != null
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
            logger.info("Heartbeat scheduled to fire after $maxInactivitySecs secs of inactivity")
            heartbeatService.submit {
                while (isRunning && !disconnected.get()) {
                    val timeSinceLastWriteMillis = System.currentTimeMillis() - lastMsgSent
                    if (timeSinceLastWriteMillis > maxInactivitySecs.toLong().toMillis())
                        sendHeartBeat(disconnected)
                    sleepForMillis(threadPauseMillis)
                }
                logger.info("Heartbeat completed")
            }
        }
        else {
            logger.info("Heartbeat disabled")
        }
    }

    private fun resetGrpcStubs() {
        logger.info("Creating gRPC stubs")

        channel?.shutdownNow()

        val channelBuilder =
                if (inProcessServerName.isEmpty())
                    NettyChannelBuilder.forAddress(hostName, port)
                else
                    InProcessChannelBuilder.forName(inProcessServerName)

        if (zipkinEnabled)
            channelBuilder.intercept(grpcTracing!!.newClientInterceptor())

        channel = channelBuilder.usePlaintext(true).build()
        val interceptors = listOf<ClientInterceptor>(AgentClientInterceptor(this))

        blockingStub = newBlockingStub(intercept(channel, interceptors))
        asyncStub = newStub(intercept(channel, interceptors))
    }

    private fun updateScrapeCounter(type: String) = metrics?.scrapeRequests?.labels(type)?.inc()

    private fun fetchUrl(scrapeRequest: ScrapeRequest): ScrapeResponse {
        var statusCodeVal = 404
        val path = scrapeRequest.path
        val scrapeResponse =
                ScrapeResponse.newBuilder().apply {
                    agentId = scrapeRequest.agentId
                    scrapeId = scrapeRequest.scrapeId
                }
        val pathContext = pathContextMap[path]
        if (pathContext == null) {
            logger.warn("Invalid path in fetchUrl(): $path")
            updateScrapeCounter("invalid_path")
            return with(scrapeResponse) {
                valid = false
                reason = "Invalid path: $path"
                statusCode = statusCodeVal
                text = ""
                contentType = ""
                build()
            }
        }

        val requestTimer = metrics?.scrapeRequestLatency?.labels(agentName)?.startTimer()
        var reason = "None"
        try {
            pathContext.fetchUrl(scrapeRequest).use {
                statusCodeVal = it.code()
                if (it.isSuccessful) {
                    updateScrapeCounter("success")
                    return scrapeResponse
                            .setValid(true)
                            .setReason("")
                            .setStatusCode(statusCodeVal)
                            .setText(it.body()!!.string())
                            .setContentType(it.header(CONTENT_TYPE))
                            .build()
                }
                else {
                    reason = "Unsucessful response code $statusCodeVal"
                }
            }
        } catch (e: IOException) {
            reason = "${e.javaClass.simpleName} - ${e.message}"
        } catch (e: Exception) {
            logger.warn("fetchUrl()", e)
            reason = "${e.javaClass.simpleName} - ${e.message}"
        } finally {
            requestTimer?.observeDuration()
        }

        updateScrapeCounter("unsuccessful")

        return scrapeResponse
                .setValid(false)
                .setReason(reason)
                .setStatusCode(statusCodeVal)
                .setText("")
                .setContentType("")
                .build()
    }

    // If successful, this will create an agentContxt on the Proxy and an interceptor will
    // add an agent_id to the headers`
    private fun connectAgent(): Boolean {
        return try {
            logger.info("Connecting to proxy at $proxyHost...")
            blockingStub.connectAgent(Empty.getDefaultInstance())
            logger.info("Connected to proxy at $proxyHost")
            metrics?.connects?.labels("success")?.inc()
            true
        } catch (e: StatusRuntimeException) {
            metrics?.connects?.labels("failure")?.inc()
            logger.info("Cannot connect to proxy at $proxyHost [${e.message}]")
            false
        }
    }

    @Throws(RequestFailureException::class)
    private fun registerAgent() {
        val request =
                with(RegisterAgentRequest.newBuilder()) {
                    agentId = this@Agent.agentId
                    agentName = this@Agent.agentName
                    hostName = this@Agent.hostName
                    build()
                }
        val response = blockingStub.registerAgent(request)
        markMsgSent()
        if (!response.valid)
            throw RequestFailureException("registerAgent() - ${response.reason}")

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
            logger.info("Registered $url as /$path")
        pathContextMap.put(path, PathContext(okHttpClient, pathId, path, url))
    }

    @Throws(RequestFailureException::class)
    fun unregisterPath(pathVal: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        unregisterPathOnProxy(path)
        val pathContext = pathContextMap.remove(path)
        when {
            pathContext == null -> logger.info("No path value /$path found in pathContextMap")
            !isTestMode         -> logger.info("Unregistered /$path for ${pathContext.url}")
        }
    }

    fun pathMapSize(): Int {
        val request =
                with(PathMapSizeRequest.newBuilder()) {
                    agentId = this@Agent.agentId
                    build()
                }
        val response = blockingStub.pathMapSize(request)
        markMsgSent()
        return response.pathCount
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request =
                with(RegisterPathRequest.newBuilder()) {
                    agentId = this@Agent.agentId
                    this.path = path
                    build()
                }
        val response = blockingStub.registerPath(request)
        markMsgSent()
        if (!response.valid)
            throw RequestFailureException("registerPath() - ${response.reason}")
        return response.pathId
    }

    @Throws(RequestFailureException::class)
    private fun unregisterPathOnProxy(path: String) {
        val request =
                with(UnregisterPathRequest.newBuilder()) {
                    agentId = this@Agent.agentId
                    this.path = path
                    build()
                }
        val response = blockingStub.unregisterPath(request)
        markMsgSent()
        if (!response.valid)
            throw RequestFailureException("unregisterPath() - ${response.reason}")
    }

    private fun readRequestAction(request: ScrapeRequest): Runnable {
        return Runnable {
            val response = fetchUrl(request)
            try {
                scrapeResponseQueue.put(response)
            } catch (e: InterruptedException) {
                // Ignore
            }
        }
    }

    private fun readRequestsFromProxy(disconnected: AtomicBoolean) {
        val observer = object : StreamObserver<ScrapeRequest> {
            override fun onNext(request: ScrapeRequest) {
                readRequestsExecutorService.submit(readRequestAction(request))
            }

            override fun onError(t: Throwable) {
                val status = Status.fromThrowable(t)
                logger.info("Error in readRequestsFromProxy(): $status")
                disconnected.set(true)
            }

            override fun onCompleted() {
                disconnected.set(true)
            }
        }
        val agentInfo =
                with(AgentInfo.newBuilder()) {
                    agentId = this@Agent.agentId
                    build()
                }
        asyncStub.readRequestsFromProxy(agentInfo, observer)
    }

    private fun writeResponsesToProxyUntilDisconnected(disconnected: AtomicBoolean) {
        val checkMillis = configVals.internal.scrapeResponseQueueCheckMillis.toLong()
        val observer =
                asyncStub.writeResponsesToProxy(
                        object : StreamObserver<Empty> {
                            override fun onNext(empty: Empty) {
                                // Ignore Empty return value
                            }

                            override fun onError(t: Throwable) {
                                val s = Status.fromThrowable(t)
                                logger.info("Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}")
                                disconnected.set(true)
                            }

                            override fun onCompleted() = disconnected.set(true)
                        })

        while (!disconnected.get()) {
            try {
                // Set a short timeout to check if client has disconnected
                val response = scrapeResponseQueue.poll(checkMillis, TimeUnit.MILLISECONDS)
                if (response != null) {
                    observer.onNext(response)
                    markMsgSent()
                }
            } catch (e: InterruptedException) {
                // Ignore
            }

        }

        logger.info("Disconnected from proxy at $proxyHost")
        observer.onCompleted()
    }

    private fun markMsgSent() {
        lastMsgSent = System.currentTimeMillis()
    }

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (agentId.isEmpty())
            return

        try {
            val request =
                    with(HeartBeatRequest.newBuilder()) {
                        agentId = this@Agent.agentId
                        build()
                    }
            val response = blockingStub.sendHeartBeat(request)
            markMsgSent()
            if (!response.valid) {
                logger.info("AgentId $agentId not found on proxy")
                throw StatusRuntimeException(Status.NOT_FOUND)
            }
        } catch (e: StatusRuntimeException) {
            logger.info("Hearbeat failed ${e.status}")
            disconnected.set(true)
        }
    }

    @Throws(InterruptedException::class)
    fun awaitInitialConnection(timeout: Long, unit: TimeUnit) = initialConnectionLatch.await(timeout, unit)

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("agentId", agentId)
                    .add("agentName", agentName)
                    .add("proxyHost", proxyHost)
                    .add("adminService", adminService ?: "Disabled")
                    .add("metricsService", metricsService ?: "Disabled")
                    .toString()

    companion object {
        private val logger = LoggerFactory.getLogger(Agent::class.java)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = AgentOptions(argv, true)

            logger.info(getBanner("banners/agent.txt"))
            logger.info(getVersionDesc(false))

            val agent = Agent(options = options)
            agent.startAsync()
        }
    }
}
