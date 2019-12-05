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

package io.prometheus.agent

import brave.Tracing
import brave.grpc.GrpcTracing
import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GrpcDsl
import com.github.pambrose.common.dsl.GrpcDsl.channel
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildClientTlsContext
import com.google.protobuf.Empty
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.GrpcObjects
import io.prometheus.common.GrpcObjects.newRegisterAgentRequest
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceBlockingStub
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceStub
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class AgentGrpcService(private val agent: Agent,
                       options: AgentOptions,
                       private val inProcessServerName: String) {
  private var grpcStarted = AtomicBoolean(false)
  private var blockingStub: ProxyServiceBlockingStub by nonNullableReference()
  private var asyncStub: ProxyServiceStub by nonNullableReference()

  private lateinit var tracing: Tracing
  private lateinit var grpcTracing: GrpcTracing

  var channel: ManagedChannel by nonNullableReference()

  val hostName: String
  val port: Int
  private val tlsContext: TlsContext

  init {
    val schemeStripped =
        options.proxyHostname
            .run {
              when {
                startsWith("http://") -> removePrefix("http://")
                startsWith("https://") -> removePrefix("https://")
                else -> this
              }
            }

    if (":" in schemeStripped) {
      val vals = schemeStripped.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
      hostName = vals[0]
      port = Integer.valueOf(vals[1])
    }
    else {
      hostName = schemeStripped
      port = 50051
    }

    if (agent.isZipkinEnabled) {
      tracing = agent.zipkinReporterService.newTracing("grpc_client")
      grpcTracing = GrpcTracing.create(tracing)
    }

    val options = agent.options
    tlsContext =
        if (options.certChainFilePath.isNotEmpty()
            || options.privateKeyFilePath.isNotEmpty()
            || options.trustCertCollectionFilePath.isNotEmpty())
          buildClientTlsContext(certChainFilePath = options.certChainFilePath,
                                privateKeyFilePath = options.privateKeyFilePath,
                                trustCertCollectionFilePath = options.trustCertCollectionFilePath)
        else
          PLAINTEXT_CONTEXT

    resetGrpcStubs()
  }

  fun shutDown() {
    if (agent.isZipkinEnabled)
      tracing.close()
    if (grpcStarted.get())
      channel.shutdownNow()
  }

  fun resetGrpcStubs() {
    logger.info { "Creating gRPC stubs" }

    if (grpcStarted.get())
      shutDown()
    else
      grpcStarted.set(true)


    channel =
        channel(hostName = hostName,
                port = port,
                tlsContext = tlsContext,
                overrideAuthority = agent.options.overrideAuthority,
                inProcessServerName = inProcessServerName) {
          if (agent.isZipkinEnabled)
            intercept(grpcTracing.newClientInterceptor())
        }

    val interceptors = listOf(AgentClientInterceptor(agent))

    blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptors))
    asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(channel, interceptors))
  }

  // If successful, this will create an agentContext on the Proxy and an interceptor will add an agent_id to the headers`
  fun connectAgent() =
      try {
        logger.info { "Connecting to proxy at ${agent.proxyHost} using ${tlsContext.desc()}..." }
        blockingStub.connectAgent(Empty.getDefaultInstance())
        logger.info { "Connected to proxy at ${agent.proxyHost} using ${tlsContext.desc()}" }
        if (agent.isMetricsEnabled)
          agent.metrics.connects.labels("success")?.inc()
        true
      } catch (e: StatusRuntimeException) {
        if (agent.isMetricsEnabled)
          agent.metrics.connects.labels("failure")?.inc()
        logger.info { "Cannot connect to proxy at ${agent.proxyHost} using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}" }
        false
      }

  fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request = newRegisterAgentRequest(agent.agentId, agent.agentName, hostName)
    blockingStub.registerAgent(request)
        .also { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("registerAgent() - ${resp.reason}")
        }
    initialConnectionLatch.countDown()
  }

  fun pathMapSize(): Int {
    val request = GrpcObjects.newPathMapSizeRequest(agent.agentId)
    return blockingStub.pathMapSize(request)
        .let { resp ->
          agent.markMsgSent()
          resp.pathCount
        }
  }

  fun registerPathOnProxy(path: String): Long {
    val request = GrpcObjects.newRegisterPathRequest(agent.agentId, path)
    return blockingStub.registerPath(request)
        .let { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("registerPath() - ${resp.reason}")
          resp.pathId
        }
  }

  fun unregisterPathOnProxy(path: String) {
    val request = GrpcObjects.newUnregisterPathRequest(agent.agentId, path)
    blockingStub.unregisterPath(request)
        .also { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("unregisterPath() - ${resp.reason}")
        }
  }

  fun sendHeartBeat(connectionContext: AgentConnectionContext) {
    if (agent.agentId.isEmpty())
      return

    try {
      val request = GrpcObjects.newHeartBeatRequest(agent.agentId)
      blockingStub
          .sendHeartBeat(request)
          .also { resp ->
            agent.markMsgSent()
            if (!resp.valid) {
              logger.error { "AgentId ${agent.agentId} not found on proxy" }
              throw StatusRuntimeException(Status.NOT_FOUND)
            }
          }
    } catch (e: StatusRuntimeException) {
      logger.error { "Hearbeat failed ${e.status}" }
      connectionContext.disconnect()
    }
  }

  fun readRequestsFromProxy(agentHttpService: AgentHttpService, connectionContext: AgentConnectionContext) {
    asyncStub
        .readRequestsFromProxy(GrpcObjects.newAgentInfo(agent.agentId),
                               GrpcDsl.streamObserver {
                                 onNext { req ->
                                   // This will block, but only for the duration of the send.
                                   // The actual fetch happens at the other end of the channel
                                   runBlocking {
                                     logger.debug { "readRequestsFromProxy(): \n$req" }
                                     connectionContext.scrapeRequestChannel.send { agentHttpService.fetchScrapeUrl(req) }
                                     agent.scrapeRequestBacklogSize.incrementAndGet()
                                   }
                                 }

                                 onError { throwable ->
                                   val s = Status.fromThrowable(throwable)
                                   logger.error { "Error in readRequestsFromProxy(): ${s.code} ${s.description}" }
                                   connectionContext.disconnect()
                                 }

                                 onCompleted {
                                   connectionContext.disconnect()
                                 }
                               })
  }

  suspend fun writeResponsesToProxyUntilDisconnected(connectionContext: AgentConnectionContext) {
    val observer =
        asyncStub
            .writeResponsesToProxy(
                GrpcDsl.streamObserver<Empty> {
                  onNext {
                    // Ignore Empty return value
                  }

                  onError { throwable ->
                    val s = Status.fromThrowable(throwable)
                    logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                    connectionContext.disconnect()
                  }

                  onCompleted {
                    connectionContext.disconnect()
                  }
                })

    for (scrapeResponse in connectionContext.scrapeResultChannel) {
      logger.debug { "writeResponsesToProxyUntilDisconnected(): \n$scrapeResponse" }
      observer.onNext(scrapeResponse)
      agent.markMsgSent()
      agent.scrapeRequestBacklogSize.decrementAndGet()
    }

    logger.info { "Disconnected from proxy at ${agent.proxyHost}" }

    observer.onCompleted()
  }


  companion object : KLogging()
}