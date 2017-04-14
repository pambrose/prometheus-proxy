package com.sudothought.proxy;

import com.google.protobuf.Empty;
import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class ProxyServiceImpl
    extends ProxyServiceGrpc.ProxyServiceImplBase {

  private static final Logger     logger            = LoggerFactory.getLogger(ProxyServiceImpl.class);
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
    final String agentId = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    final boolean valid;
    if (agentContext == null) {
      logger.info("Missing AgentContext agent_id: {}", agentId);
      valid = false;
    }
    else {
      agentContext.setHostname(request.getHostname());
      valid = true;
    }
    final RegisterAgentResponse response = RegisterAgentResponse.newBuilder()
                                                                .setValid(valid)
                                                                .setAgentId(agentId)
                                                                .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void registerPath(final RegisterPathRequest request,
                           final StreamObserver<RegisterPathResponse> responseObserver) {
    final String path = request.getPath();
    if (this.proxy.getPathMap().containsKey(path))
      logger.info("Overwriting path /{}", path);

    final String agentId = request.getAgentId();
    this.proxy.getPathMap().put(path, agentId);

    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    final boolean valid;
    final long pathId;
    if (agentContext == null) {
      logger.info("Missing AgentContext for agent_id: {}", agentId);
      valid = false;
      pathId = -1;
    }
    else {
      logger.info("Registered path /{} to {} {}", path, agentContext.getRemoteAddr(), agentContext.getHostname());
      valid = true;
      pathId = PATH_ID_GENERATOR.getAndIncrement();
    }
    final RegisterPathResponse response = RegisterPathResponse.newBuilder()
                                                              .setValid(valid)
                                                              .setPathId(pathId)
                                                              .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(final AgentInfo agentInfo, final StreamObserver<ScrapeRequest> responseObserver) {
    final String agentId = agentInfo.getAgentId();
    while (!this.proxy.isStopped()) {
      // Lookup the agentContext each time in case agent has gone away
      final AgentContext agentContext = this.proxy.getAgentContext(agentId);
      if (agentContext == null) {
        logger.info("Missing AgentContext for agent_id: {}", agentId);
        break;
      }

      try {
        final ScrapeRequestContext scrapeRequestContext = agentContext.getScrapeRequestQueue().poll(1,
                                                                                                    TimeUnit.SECONDS);
        if (scrapeRequestContext == null)
          continue;
        responseObserver.onNext(scrapeRequestContext.getScrapeRequest());
      }
      catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<ScrapeResponse> writeResponsesToProxy(StreamObserver<Empty> responseObserver) {
    return new StreamObserver<ScrapeResponse>() {
      @Override
      public void onNext(final ScrapeResponse response) {
        final long scrapeId = response.getScrapeId();
        final ScrapeRequestContext scrapeRequestContext = proxy.removeScrapeRequest(scrapeId);
        if (scrapeRequestContext == null) {
          logger.error("Missing ScrapeRequestContext for scrape_id: {}", scrapeId);
        }
        else {
          scrapeRequestContext.setScrapeResponse(response);
          scrapeRequestContext.markComplete();
        }
      }

      @Override
      public void onError(Throwable t) {
        final Status status = Status.fromThrowable(t);
        logger.info("onError() in writeResponsesToProxy(): {}", status);
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
      }

      @Override
      public void onCompleted() {
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
      }
    };
  }
}
