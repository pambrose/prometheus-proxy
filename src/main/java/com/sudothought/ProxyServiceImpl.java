package com.sudothought;

import com.cinch.grpc.AgentInfo;
import com.cinch.grpc.ProxyServiceGrpc;
import com.cinch.grpc.RegisterAgentRequest;
import com.cinch.grpc.RegisterAgentResponse;
import com.cinch.grpc.RegisterPathRequest;
import com.cinch.grpc.RegisterPathResponse;
import com.cinch.grpc.ScrapeRequest;
import com.cinch.grpc.ScrapeResponse;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

class ProxyServiceImpl
    extends ProxyServiceGrpc.ProxyServiceImplBase {

  private static final Logger     logger            = Logger.getLogger(ProxyServiceImpl.class.getName());
  private static final Empty      EMPTY             = Empty.newBuilder().build();
  private static final AtomicLong PATH_ID_GENERATOR = new AtomicLong(0);

  private final Proxy proxy;

  public ProxyServiceImpl(Proxy proxy) {
    this.proxy = proxy;
  }

  @Override
  public void registerAgent(RegisterAgentRequest request, StreamObserver<RegisterAgentResponse> responseObserver) {
    final AgentContext agentContext = new AgentContext(request.getHostname());
    this.proxy.getAgentContextMap().put(agentContext.getAgentId(), agentContext);
    final RegisterAgentResponse response = RegisterAgentResponse.newBuilder()
                                                                .setAgentId(agentContext.getAgentId())
                                                                .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void registerPath(RegisterPathRequest request, StreamObserver<RegisterPathResponse> responseObserver) {
    this.proxy.getPathMap().put(request.getPath(), request.getAgentId());
    final RegisterPathResponse response = RegisterPathResponse.newBuilder()
                                                              .setPathId(PATH_ID_GENERATOR.getAndIncrement())
                                                              .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(AgentInfo agentInfo, StreamObserver<ScrapeRequest> requests) {
    final AgentContext agentContext = this.proxy.getAgentContextMap().get(agentInfo.getAgentId());
    while (true) {
      try {
        final ScrapeRequestContext scrapeRequestContext = agentContext.getScrapeRequestQueue().take();
        requests.onNext(scrapeRequestContext.getScrapeRequest());
      }
      catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void writeResponseToProxy(ScrapeResponse response, StreamObserver<Empty> responseObserver) {
    final long scrapeId = response.getScrapeId();
    final ScrapeRequestContext scrapeRequestContext = proxy.getScrapeRequestMap().remove(scrapeId);
    scrapeRequestContext.getScrapeResponse().set(response);
    scrapeRequestContext.markComplete();

    // Return Empty value
    responseObserver.onNext(EMPTY);
    responseObserver.onCompleted();
  }
}
