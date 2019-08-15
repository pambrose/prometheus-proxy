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

package io.prometheus.agent

import brave.Tracing
import brave.grpc.GrpcTracing
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Agent
import io.prometheus.delegate.AtomicDelegates
import io.prometheus.dsl.GrpcDsl.channel
import io.prometheus.grpc.ProxyServiceGrpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mu.KLogging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class AgentGrpcService(
    private val agent: Agent,
    options: AgentOptions,
    private val inProcessServerName: String
) {

    private var grpcStarted = AtomicBoolean(false)

    var channel: ManagedChannel by AtomicDelegates.nonNullableReference()
    var grpcTracing: GrpcTracing by Delegates.notNull()
    var blockingStub: ProxyServiceGrpc.ProxyServiceBlockingStub by AtomicDelegates.nonNullableReference()
    var asyncStub: ProxyServiceGrpc.ProxyServiceStub by AtomicDelegates.nonNullableReference()
    private var tracing: Tracing by Delegates.notNull()

    val hostName: String
    val port: Int

    init {
        if (options.proxyHostname.contains(":")) {
            val vals = options.proxyHostname.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            hostName = vals[0]
            port = Integer.valueOf(vals[1])
        } else {
            hostName = options.proxyHostname
            port = 50051
        }

        if (agent.isZipkinEnabled) {
            tracing = agent.zipkinReporterService.newTracing("grpc_client")
            grpcTracing = GrpcTracing.create(tracing)
        }

        resetGrpcStubs()
    }

    val isGrpcStarted
        get() = grpcStarted.get()

    fun shutDown() {
        if (agent.isZipkinEnabled)
            tracing.close()
        if (isGrpcStarted)
            channel.shutdownNow()
    }

    fun resetGrpcStubs() {
        logger.info { "Creating gRPC stubs" }

        if (isGrpcStarted)
            shutDown()
        else
            grpcStarted.set(true)

        channel =
            channel(inProcessServerName = inProcessServerName, hostName = hostName, port = port) {
                if (agent.isZipkinEnabled)
                    intercept(grpcTracing.newClientInterceptor())
                usePlaintext()
            }

        val interceptors = listOf<ClientInterceptor>(AgentClientInterceptor(agent))

        blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptors))
        asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(channel, interceptors))
    }

    companion object : KLogging()
}