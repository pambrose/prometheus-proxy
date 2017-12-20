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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Agent(options: AgentOptions,
            private val inProcessServerName: String?,
            testMode: Boolean) : GenericService(options.configVals!!,
                                                AdminConfig.create(options.adminEnabled,
                                                                   options.adminPort!!,
                                                                   options.configVals!!.agent.admin),
                                                MetricsConfig.create(options.metricsEnabled,
                                                                     options.metricsPort!!,
                                                                     options.configVals!!.agent.metrics),
                                                ZipkinConfig.create(options.configVals!!.agent.internal.zipkin),
                                                testMode) {

    private val pathContextMap = Maps.newConcurrentMap<String, PathContext>()  // Map path to PathContext
    private val heartbeatService = Executors.newFixedThreadPool(1)
    private val initialConnectionLatch = CountDownLatch(1)
    private val okHttpClient = OkHttpClient()
    private val lastMsgSentRef = AtomicLong()
    private val agentIdRef = AtomicReference<String>()
    private val channelRef = AtomicReference<ManagedChannel>()
    private val blockingStubRef = AtomicReference<ProxyServiceBlockingStub>()
    private val asyncStubRef = AtomicReference<ProxyServiceStub>()
    private val scrapeResponseQueue = ArrayBlockingQueue<ScrapeResponse>(this.configVals.internal.scrapeResponseQueueSize)
    private val agentName: String? = if (options.agentName.isNullOrBlank()) "Unnamed-${Utils.hostName}" else options.agentName
    private val metrics: AgentMetrics? = if (this.metricsEnabled) AgentMetrics(this) else null
    private val readRequestsExecutorService: ExecutorService = newCachedThreadPool(if (this.metricsEnabled)
                                                                                       InstrumentedThreadFactory.newInstrumentedThreadFactory("agent_fetch",
                                                                                                                                              "Agent fetch",
                                                                                                                                              true)
                                                                                   else
                                                                                       ThreadFactoryBuilder().setNameFormat("agent_fetch-%d")
                                                                                               .setDaemon(true)
                                                                                               .build())

    private val hostname: String?
    private val port: Int
    private val reconnectLimiter: RateLimiter
    private val pathConfigs: List<Map<String, String>>

    private val proxyHost: String
        get() = "$hostname:$port"

    val scrapeResponseQueueSize: Int
        get() = this.scrapeResponseQueue.size

    var channel: ManagedChannel?
        get() = this.channelRef.get()
        set(v) = this.channelRef.set(v)

    var agentId: String?
        get() = this.agentIdRef.get()
        set(v) = this.agentIdRef.set(v)

    val configVals: ConfigVals.Agent
        get() = this.genericConfigVals.agent

    init {

        logger.info("Assigning proxy reconnect pause time to ${this.configVals.internal.reconectPauseSecs} secs")
        this.reconnectLimiter = RateLimiter.create(1.0 / this.configVals.internal.reconectPauseSecs)
        this.reconnectLimiter.acquire()  // Prime the limiter

        this.pathConfigs =
                this.configVals.pathConfigs
                        .map { mapOf("name" to it.name, "path" to it.path, "url" to it.url) }
                        .onEach { logger.info("Proxy path /{} will be assigned to {}", it["path"], it["url"]) }
                        .toList()


        if (options.proxyHostname!!.contains(":")) {
            val vals = options.proxyHostname!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            this.hostname = vals[0]
            this.port = Integer.valueOf(vals[1])
        }
        else {
            this.hostname = options.proxyHostname
            this.port = 50051
        }

        this.resetGrpcStubs()
        this.init()
    }

    @Throws(Exception::class)
    override fun shutDown() {
        this.channel?.shutdownNow()
        this.heartbeatService.shutdownNow()
        super.shutDown()
    }

    override fun run() {
        while (this.isRunning) {
            try {
                this.connectToProxy()
            } catch (e: RequestFailureException) {
                logger.info("Disconnected from proxy at ${this.proxyHost} after invalid response ${e.message}")
            } catch (e: StatusRuntimeException) {
                logger.info("Disconnected from proxy at ${this.proxyHost}")
            } catch (e: Exception) {
                // Catch anything else to avoid exiting retry loop
            } finally {
                val secsWaiting = this.reconnectLimiter.acquire()
                logger.info("Waited $secsWaiting secs to reconnect")
            }
        }
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        this.healthCheckRegistry
                .register("scrape_response_queue_check",
                          Utils.queueHealthCheck(scrapeResponseQueue,
                                                 this.configVals.internal.scrapeResponseQueueUnhealthySize))
    }

    override fun serviceName() = "${this.javaClass.simpleName} ${this.agentName}"

    @Throws(RequestFailureException::class)
    private fun connectToProxy() {
        val disconnected = AtomicBoolean(false)

        // Reset gRPC stubs if previous iteration had a successful connection, i.e., the agent id != null
        if (this.agentId != null) {
            this.resetGrpcStubs()
            this.agentId = null
        }

        // Reset values for each connection attempt
        this.pathContextMap.clear()
        this.scrapeResponseQueue.clear()
        this.lastMsgSentRef.set(0)

        if (this.connectAgent()) {
            this.registerAgent()
            this.registerPaths()
            this.startHeartBeat(disconnected)
            this.readRequestsFromProxy(disconnected)
            this.writeResponsesToProxyUntilDisconnected(disconnected)
        }
    }

    private fun startHeartBeat(disconnected: AtomicBoolean) {
        if (this.configVals.internal.heartbeatEnabled) {
            val threadPauseMillis = this.configVals.internal.heartbeatCheckPauseMillis.toLong()
            val maxInactivitySecs = this.configVals.internal.heartbeatMaxInactivitySecs
            logger.info("Heartbeat scheduled to fire after $maxInactivitySecs secs of inactivity")
            this.heartbeatService.submit {
                while (isRunning && !disconnected.get()) {
                    val timeSinceLastWriteMillis = System.currentTimeMillis() - this.lastMsgSentRef.get()
                    if (timeSinceLastWriteMillis > maxInactivitySecs.toLong().toMillis())
                        this.sendHeartBeat(disconnected)
                    Utils.sleepForMillis(threadPauseMillis)
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

        this.channel?.shutdownNow()

        this.channel =
                if (this.inProcessServerName.isNullOrBlank())
                    NettyChannelBuilder.forAddress(this.hostname, this.port)
                            .usePlaintext(true)
                            .build()
                else
                    InProcessChannelBuilder.forName(this.inProcessServerName)
                            .usePlaintext(true)
                            .build()
        val interceptors = listOf<ClientInterceptor>(AgentClientInterceptor(this))

        /*
    if (this.getConfigVals().metrics.grpc.metricsEnabled)
      interceptors.add(MonitoringClientInterceptor.create(this.getConfigVals().grpc.allMetricsReported
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (this.zipkinReporter != null && this.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcClientInterceptor.create(this.zipkinReporter.getBrave()));
    */

        this.blockingStubRef.set(newBlockingStub(intercept(this.channel, interceptors)))
        this.asyncStubRef.set(newStub(intercept(this.channel, interceptors)))
    }

    private fun updateScrapeCounter(type: String) = this.metrics?.scrapeRequests?.labels(type)?.inc()

    private fun fetchUrl(scrapeRequest: ScrapeRequest): ScrapeResponse {
        var statusCode = 404
        val path = scrapeRequest.path
        val scrapeResponse =
                ScrapeResponse.newBuilder()
                        .setAgentId(scrapeRequest.agentId)
                        .setScrapeId(scrapeRequest.scrapeId)
        val pathContext = this.pathContextMap[path]
        if (pathContext == null) {
            logger.warn("Invalid path in fetchUrl(): $path")
            this.updateScrapeCounter("invalid_path")
            return scrapeResponse
                    .setValid(false)
                    .setReason("Invalid path: $path")
                    .setStatusCode(statusCode)
                    .setText("")
                    .setContentType("")
                    .build()
        }

        val requestTimer = this.metrics?.scrapeRequestLatency?.labels(this.agentName!!)?.startTimer()
        var reason = "None"
        try {
            pathContext.fetchUrl(scrapeRequest).use {
                statusCode = it.code()
                if (it.isSuccessful) {
                    this.updateScrapeCounter("success")
                    return scrapeResponse
                            .setValid(true)
                            .setReason("")
                            .setStatusCode(statusCode)
                            .setText(it.body()!!.string())
                            .setContentType(it.header(CONTENT_TYPE))
                            .build()
                }
                else {
                    reason = "Unsucessful response code $statusCode"
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

        this.updateScrapeCounter("unsuccessful")

        return scrapeResponse
                .setValid(false)
                .setReason(reason)
                .setStatusCode(statusCode)
                .setText("")
                .setContentType("")
                .build()
    }

    // If successful, this will create an agentContxt on the Proxy and an interceptor will
    // add an agent_id to the headers`
    private fun connectAgent(): Boolean {
        return try {
            logger.info("Connecting to proxy at ${this.proxyHost}...")
            this.blockingStubRef.get().connectAgent(Empty.getDefaultInstance())
            logger.info("Connected to proxy at ${this.proxyHost}")
            this.metrics?.connects?.labels("success")?.inc()
            true
        } catch (e: StatusRuntimeException) {
            this.metrics?.connects?.labels("failure")?.inc()
            logger.info("Cannot connect to proxy at ${this.proxyHost} [${e.message}]")
            false
        }
    }

    @Throws(RequestFailureException::class)
    private fun registerAgent() {
        val request =
                RegisterAgentRequest.newBuilder()
                        .setAgentId(this.agentId)
                        .setAgentName(this.agentName)
                        .setHostname(Utils.hostName)
                        .build()
        val response = this.blockingStubRef.get().registerAgent(request)
        this.markMsgSent()
        if (!response.valid)
            throw RequestFailureException("registerAgent() - ${response.reason}")

        this.initialConnectionLatch.countDown()
    }

    @Throws(RequestFailureException::class)
    private fun registerPaths() {
        this.pathConfigs.forEach {
            this.registerPath(it["path"]!!, it["url"]!!)
        }
    }

    @Throws(RequestFailureException::class)
    fun registerPath(pathVal: String, url: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        val pathId = this.registerPathOnProxy(path)
        if (!this.isTestMode)
            logger.info("Registered $url as /$path")
        this.pathContextMap.put(path, PathContext(this.okHttpClient, pathId, path, url))
    }

    @Throws(RequestFailureException::class)
    fun unregisterPath(pathVal: String) {
        val path = if (checkNotNull(pathVal).startsWith("/")) pathVal.substring(1) else pathVal
        this.unregisterPathOnProxy(path)
        val pathContext = this.pathContextMap.remove(path)
        when {
            pathContext == null -> logger.info("No path value /$path found in pathContextMap")
            !this.isTestMode    -> logger.info("Unregistered /$path for ${pathContext.url}")
        }
    }

    fun pathMapSize(): Int {
        val request = PathMapSizeRequest.newBuilder().setAgentId(this.agentId).build()
        val response = this.blockingStubRef.get().pathMapSize(request)
        this.markMsgSent()
        return response.pathCount
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request =
                RegisterPathRequest.newBuilder()
                        .setAgentId(this.agentId)
                        .setPath(path)
                        .build()
        val response = this.blockingStubRef.get().registerPath(request)
        this.markMsgSent()
        if (!response.valid)
            throw RequestFailureException("registerPath() - ${response.reason}")
        return response.pathId
    }

    @Throws(RequestFailureException::class)
    private fun unregisterPathOnProxy(path: String) {
        val request =
                UnregisterPathRequest.newBuilder()
                        .setAgentId(this.agentId)
                        .setPath(path)
                        .build()
        val response = this.blockingStubRef.get().unregisterPath(request)
        this.markMsgSent()
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
        val agentInfo = AgentInfo.newBuilder().setAgentId(this.agentId).build()
        this.asyncStubRef.get().readRequestsFromProxy(agentInfo, observer)
    }

    private fun writeResponsesToProxyUntilDisconnected(disconnected: AtomicBoolean) {
        val checkMillis = this.configVals.internal.scrapeResponseQueueCheckMillis.toLong()
        val observer = this.asyncStubRef.get().writeResponsesToProxy(
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
                val response = this.scrapeResponseQueue.poll(checkMillis, TimeUnit.MILLISECONDS)
                if (response != null) {
                    observer.onNext(response)
                    this.markMsgSent()
                }
            } catch (e: InterruptedException) {
                // Ignore
            }

        }

        logger.info("Disconnected from proxy at ${this.proxyHost}")
        observer.onCompleted()
    }

    private fun markMsgSent() = this.lastMsgSentRef.set(System.currentTimeMillis())

    private fun sendHeartBeat(disconnected: AtomicBoolean) {
        if (this.agentId == null)
            return

        try {
            val request = HeartBeatRequest.newBuilder().setAgentId(this.agentId).build()
            val response = this.blockingStubRef.get().sendHeartBeat(request)
            this.markMsgSent()
            if (!response.valid) {
                logger.info("AgentId ${this.agentId} not found on proxy")
                throw StatusRuntimeException(Status.NOT_FOUND)
            }
        } catch (e: StatusRuntimeException) {
            logger.info("Hearbeat failed ${e.status}")
            disconnected.set(true)
        }
    }

    @Throws(InterruptedException::class)
    fun awaitInitialConnection(timeout: Long, unit: TimeUnit) = this.initialConnectionLatch.await(timeout, unit)

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("agentId", this.agentId)
                .add("agentName", this.agentName)
                .add("proxyHost", this.proxyHost)
                .add("adminService", this.adminService ?: "Disabled")
                .add("metricsService", this.metricsService ?: "Disabled")
                .toString()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Agent::class.java)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = AgentOptions(argv, true)

            logger.info(Utils.getBanner("banners/agent.txt"))
            logger.info(Utils.getVersionDesc(false))

            val agent = Agent(options, null, false)
            agent.startAsync()
        }
    }
}
