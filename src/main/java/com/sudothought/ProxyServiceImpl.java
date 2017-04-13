package com.sudothought;

import com.google.protobuf.Empty;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

class ProxyServiceImpl
    extends ProxyServiceGrpc.ProxyServiceImplBase {

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ProxyServiceImpl.class);

  private static final AtomicLong PATH_ID_GENERATOR = new AtomicLong(0);

  private final Proxy proxy;

  public ProxyServiceImpl(Proxy proxy) {
    this.proxy = proxy;
  }

  @Override
  public void connectAgent(final Empty request, final StreamObserver<Empty> responseObserver) {
    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void registerAgent(final RegisterAgentRequest request,
                            final StreamObserver<RegisterAgentResponse> responseObserver) {
    final AgentContext agentContext = this.proxy.getAgentContextMap().get(request.getAgentId());
    agentContext.setHostname(request.getHostname());
    //this.proxy.getAgentContextMap().put(agentContext.getAgentId(), agentContext);
    final RegisterAgentResponse response = RegisterAgentResponse.newBuilder()
                                                                .setAgentId(agentContext.getAgentId())
                                                                .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void registerPath(final RegisterPathRequest request,
                           final StreamObserver<RegisterPathResponse> responseObserver) {
    this.proxy.getPathMap().put(request.getPath(), request.getAgentId());
    final RegisterPathResponse response = RegisterPathResponse.newBuilder()
                                                              .setPathId(PATH_ID_GENERATOR.getAndIncrement())
                                                              .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(final AgentInfo agentInfo, final StreamObserver<ScrapeRequest> responseObserver) {
    final AgentContext agentContext = this.proxy.getAgentContextMap().get(agentInfo.getAgentId());
    while (true) {
      try {
        final ScrapeRequestContext scrapeRequestContext = agentContext.getScrapeRequestQueue().take();
        responseObserver.onNext(scrapeRequestContext.getScrapeRequest());
      }
      catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void writeResponseToProxy(final ScrapeResponse response, final StreamObserver<Empty> responseObserver) {
    final long scrapeId = response.getScrapeId();
    final ScrapeRequestContext scrapeRequestContext = proxy.getScrapeRequestMap().remove(scrapeId);
    scrapeRequestContext.getScrapeResponse().set(response);
    scrapeRequestContext.markComplete();

    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }
}
