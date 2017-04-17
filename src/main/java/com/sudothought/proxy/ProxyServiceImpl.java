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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    if (this.proxy.containsPath(path))
      logger.info("Overwriting path /{}", path);

    final String agentId = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    final RegisterPathResponse response = RegisterPathResponse.newBuilder()
                                                              .setValid(agentContext != null)
                                                              .setPathId(agentContext != null
                                                                         ? PATH_ID_GENERATOR.getAndIncrement()
                                                                         : -1)
                                                              .build();
    if (agentContext == null)
      logger.error("Missing AgentContext for agent_id: {}", agentId);
    else
      this.proxy.addPath(path, agentContext);

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
        // logger.info("Missing AgentContext for agent_id: {}", agentId);
        break;
      }

      final ScrapeRequestWrapper scrapeRequest = agentContext.pollScrapeRequestQueue(1000);
      if (scrapeRequest != null)
        responseObserver.onNext(scrapeRequest.getScrapeRequest());
    }
    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<ScrapeResponse> writeResponsesToProxy(StreamObserver<Empty> responseObserver) {
    return new StreamObserver<ScrapeResponse>() {
      @Override
      public void onNext(final ScrapeResponse response) {
        final long scrapeId = response.getScrapeId();
        final ScrapeRequestWrapper scrapeRequest = proxy.removeScrapeRequest(scrapeId);
        if (scrapeRequest == null) {
          logger.error("Missing ScrapeRequestWrapper for scrape_id: {}", scrapeId);
        }
        else {
          scrapeRequest.setScrapeResponse(response);
          scrapeRequest.markComplete();
        }
      }

      @Override
      public void onError(Throwable t) {
        final Status status = Status.fromThrowable(t);
        if (status != Status.CANCELLED)
          logger.info("Error in writeResponsesToProxy(): {}", status);
        try {
          responseObserver.onNext(Empty.getDefaultInstance());
          responseObserver.onCompleted();
        }
        catch (StatusRuntimeException e) {
          // Ignore
        }
      }

      @Override
      public void onCompleted() {
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
      }
    };
  }
}
