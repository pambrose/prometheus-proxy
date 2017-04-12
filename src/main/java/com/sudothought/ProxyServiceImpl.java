package com.sudothought;

import com.cinch.grpc.AgentInfo;
import com.cinch.grpc.AgentRegisterRequest;
import com.cinch.grpc.AgentRegisterResponse;
import com.cinch.grpc.PathRegisterRequest;
import com.cinch.grpc.PathRegisterResponse;
import com.cinch.grpc.ProxyServiceGrpc;
import com.cinch.grpc.ScrapeRequest;
import com.cinch.grpc.ScrapeResponse;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

class ProxyServiceImpl
    extends ProxyServiceGrpc.ProxyServiceImplBase {

  private static final Logger logger = Logger.getLogger(ProxyServiceImpl.class.getName());

  private static final AtomicLong PATH_ID_GENERATOR = new AtomicLong(0);

  private final Proxy proxy;

  public ProxyServiceImpl(Proxy proxy) {
    this.proxy = proxy;
  }

  @Override
  public void registerAgent(AgentRegisterRequest request, StreamObserver<AgentRegisterResponse> responseObserver) {
    final AgentContext agentContext = new AgentContext(request.getHostname());
    this.proxy.getAgentContextMap().put(agentContext.getAgentId(), agentContext);
    final AgentRegisterResponse response = AgentRegisterResponse.newBuilder()
                                                                .setAgentId(agentContext.getAgentId())
                                                                .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void registerPath(PathRegisterRequest request, StreamObserver<PathRegisterResponse> responseObserver) {
    this.proxy.getPathMap().put(request.getPath(), request.getAgentId());
    final PathRegisterResponse response = PathRegisterResponse.newBuilder()
                                                              .setPathId(PATH_ID_GENERATOR.getAndIncrement())
                                                              .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(AgentInfo request, StreamObserver<ScrapeRequest> responseObserver) {
  }

  @Override
  public void writeResponseToProxy(ScrapeResponse request, StreamObserver<Empty> responseObserver) {
  }

  @Override
  public StreamObserver<ScrapeResponse> exchangeScrapeMsgs(StreamObserver<ScrapeRequest> requests) {

    final ExecutorService executorService = Executors.newFixedThreadPool(1);

    executorService.submit(() -> {
      while (true) {
        try {
          final ScrapeRequestContext scrapeRequestContext = proxy.getScrapeRequestQueue().take();
          requests.onNext(scrapeRequestContext.getScrapeRequest());
        }
        catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });

    return new StreamObserver<ScrapeResponse>() {
      @Override
      public void onNext(ScrapeResponse response) {
        final long scrapeId = response.getScrapeId();
        final ScrapeRequestContext scrapeRequestContext = proxy.getScrapeRequestMap().remove(scrapeId);
        scrapeRequestContext.getScrapeResponse().set(response);
        scrapeRequestContext.markComplete();
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.WARNING, "Encountered error in routeChat", t);
      }

      @Override
      public void onCompleted() {
        requests.onCompleted();
      }
    };
  }
}
