/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.common

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

/**
 * A [ClientInterceptor] that builds the interceptor it passes calls to on the first call, not when it is installed.
 *
 * The agent installs its Zipkin tracing interceptor as it builds its gRPC channel, in its constructor, but the
 * `zipkinReporterService` that interceptor needs is assigned only later, by `initServletService`. Reading it while the
 * channel was built threw `UninitializedPropertyAccessException`, so an agent with Zipkin on could not start (November
 * #7). The first call comes after startup, by which time the reporter exists.
 *
 * @param supplier builds the delegate; called again on the next call if it throws
 */
internal class DeferredClientInterceptor(
  supplier: () -> ClientInterceptor,
) : ClientInterceptor {
  private val delegate by lazy(supplier)

  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel,
  ): ClientCall<ReqT, RespT> = delegate.interceptCall(method, callOptions, next)
}

/**
 * A [ServerInterceptor] that builds the interceptor it passes calls to on the first call, not when it is installed.
 *
 * The proxy's counterpart of [DeferredClientInterceptor]: the proxy builds its gRPC server, with the Zipkin tracing
 * interceptor, before `initServletService` assigns the `zipkinReporterService` that interceptor needs (November #7).
 *
 * @param supplier builds the delegate; called again on the next call if it throws
 */
internal class DeferredServerInterceptor(
  supplier: () -> ServerInterceptor,
) : ServerInterceptor {
  private val delegate by lazy(supplier)

  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>,
  ): ServerCall.Listener<ReqT> = delegate.interceptCall(call, headers, next)
}
