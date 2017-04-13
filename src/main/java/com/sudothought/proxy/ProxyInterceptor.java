package com.sudothought.proxy;

import io.grpc.Attributes;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.LoggerFactory;

public class ProxyInterceptor
    implements ServerInterceptor {

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ProxyInterceptor.class);

  private static final Metadata.Key<String> META_AGENT_ID = Metadata.Key.of(Proxy.AGENT_ID, Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
                                                               final Metadata requestHeaders,
                                                               final ServerCallHandler<ReqT, RespT> handler) {
    final Attributes attributes = call.getAttributes();
    final MethodDescriptor<ReqT, RespT> methodDescriptor = call.getMethodDescriptor();
    final String methodName = methodDescriptor.getFullMethodName();

    return handler.startCall(
        new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendMessage(RespT message) {
            super.sendMessage(message);
          }

          @Override
          public void request(int numMessages) {
            super.request(numMessages);
          }

          @Override
          public void sendHeaders(Metadata headers) {
            // agent_id was assigned in ServerTransportFilter
            final String agent_id = attributes.get(Proxy.ATTRIB_AGENT_ID);
            if (agent_id != null)
              headers.put(META_AGENT_ID, agent_id);
            super.sendHeaders(headers);
          }

          @Override
          public boolean isReady() {
            return super.isReady();
          }

          @Override
          public void close(Status status, Metadata trailers) {
            super.close(status, trailers);
          }

          @Override
          public boolean isCancelled() {
            return super.isCancelled();
          }
        },
        requestHeaders);
  }
}
