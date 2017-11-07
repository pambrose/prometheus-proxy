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

package io.prometheus.proxy;

import io.grpc.Attributes;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.prometheus.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyInterceptor
    implements ServerInterceptor {

  private static final Logger               logger        = LoggerFactory.getLogger(ProxyInterceptor.class);
  private static final Metadata.Key<String> META_AGENT_ID = Metadata.Key.of(Proxy.AGENT_ID, Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
                                                               final Metadata requestHeaders,
                                                               final ServerCallHandler<ReqT, RespT> handler) {
    final Attributes attributes = call.getAttributes();
    final MethodDescriptor<ReqT, RespT> methodDescriptor = call.getMethodDescriptor();
    // final String methodName = methodDescriptor.getFullMethodName();
    // logger.info("Intercepting {}", methodName);

    return handler.startCall(
        new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendHeaders(Metadata headers) {
            // agent_id was assigned in ServerTransportFilter
            final String agentId = attributes.get(Proxy.ATTRIB_AGENT_ID);
            if (agentId != null)
              headers.put(META_AGENT_ID, agentId);
            super.sendHeaders(headers);
          }
        },
        requestHeaders);
  }
}
