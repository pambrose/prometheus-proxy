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
    final String agent_id = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContextMap().get(agent_id);
    final boolean valid;
    if (agentContext == null) {
      logger.info("Missing AgentContext agent_id: {}", agent_id);
      valid = false;
    }
    else {
      agentContext.setHostname(request.getHostname());
      valid = true;
    }
    final RegisterAgentResponse response = RegisterAgentResponse.newBuilder()
                                                                .setValid(valid)
                                                                .setAgentId(agent_id)
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

    final String agent_id = request.getAgentId();
    this.proxy.getPathMap().put(path, agent_id);

    final AgentContext agentContext = this.proxy.getAgentContextMap().get(agent_id);
    final boolean valid;
    final long path_id;
    if (agentContext == null) {
      logger.info("Missing AgentContext for agent_id: {}", agent_id);
      valid = false;
      path_id = -1;
    }
    else {
      logger.info("Registered path /{} to {} {}", path, agentContext.getRemoteAddr(), agentContext.getHostname());
      valid = true;
      path_id = PATH_ID_GENERATOR.getAndIncrement();
    }
    final RegisterPathResponse response = RegisterPathResponse.newBuilder()
                                                              .setValid(valid)
                                                              .setPathId(path_id)
                                                              .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(final AgentInfo agentInfo, final StreamObserver<ScrapeRequest> responseObserver) {
    final String agent_id = agentInfo.getAgentId();
    while (!this.proxy.isStopped()) {
      // Lookup the agentContext each time in case agent has gone away
      final AgentContext agentContext = this.proxy.getAgentContextMap().get(agent_id);
      if (agentContext == null) {
        logger.info("Missing AgentContext for agent_id: {}", agent_id);
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
  public void writeResponseToProxy(final ScrapeResponse response, final StreamObserver<Empty> responseObserver) {
    final long scrape_id = response.getScrapeId();
    final ScrapeRequestContext scrapeRequestContext = this.proxy.getScrapeRequestMap().remove(scrape_id);
    if (scrapeRequestContext == null) {
      logger.error("Missing ScrapeRequestContext for scrape_id: {}", scrape_id);
    }
    else {
      scrapeRequestContext.setScrapeResponse(response);
      scrapeRequestContext.markComplete();
    }

    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }
}
