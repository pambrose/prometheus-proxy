package io.prometheus.proxy;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.prometheus.Proxy;
import io.prometheus.grpc.AgentInfo;
import io.prometheus.grpc.HeartBeatRequest;
import io.prometheus.grpc.HeartBeatResponse;
import io.prometheus.grpc.PathMapSizeRequest;
import io.prometheus.grpc.PathMapSizeResponse;
import io.prometheus.grpc.ProxyServiceGrpc;
import io.prometheus.grpc.RegisterAgentRequest;
import io.prometheus.grpc.RegisterAgentResponse;
import io.prometheus.grpc.RegisterPathRequest;
import io.prometheus.grpc.RegisterPathResponse;
import io.prometheus.grpc.ScrapeRequest;
import io.prometheus.grpc.ScrapeResponse;
import io.prometheus.grpc.UnregisterPathRequest;
import io.prometheus.grpc.UnregisterPathResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static java.lang.String.format;

class ProxyServiceImpl
    extends ProxyServiceGrpc.ProxyServiceImplBase {

  private static final Logger     logger            = LoggerFactory.getLogger(ProxyServiceImpl.class);
  private static final AtomicLong PATH_ID_GENERATOR = new AtomicLong(0);

  private final Proxy proxy;

  public ProxyServiceImpl(final Proxy proxy) {
    this.proxy = proxy;
  }

  @Override
  public void connectAgent(final Empty request, final StreamObserver<Empty> responseObserver) {
    if (this.proxy.isMetricsEnabled())
      this.proxy.getMetrics().connects.inc();
    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void registerAgent(final RegisterAgentRequest request,
                            final StreamObserver<RegisterAgentResponse> responseObserver) {
    final String agentId = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    if (agentContext == null) {
      logger.info("registerAgent() missing AgentContext agentId: {}", agentId);
    }
    else {
      agentContext.setAgentName(request.getAgentName());
      agentContext.setHostname(request.getHostname());
      agentContext.markActivity();
    }

    final RegisterAgentResponse response = RegisterAgentResponse.newBuilder()
                                                                .setValid(agentContext != null)
                                                                .setReason(format("Invalid agentId: %s", agentId))
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
                                                              .setReason(format("Invalid agentId: %s", agentId))
                                                              .setPathCount(this.proxy.pathMapSize())
                                                              .setPathId(agentContext != null
                                                                         ? PATH_ID_GENERATOR.getAndIncrement()
                                                                         : -1)
                                                              .build();
    if (agentContext == null) {
      logger.error("Missing AgentContext for agentId: {}", agentId);
    }
    else {
      this.proxy.addPath(path, agentContext);
      agentContext.markActivity();
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void unregisterPath(final UnregisterPathRequest request,
                             final StreamObserver<UnregisterPathResponse> responseObserver) {
    final String path = request.getPath();
    final String agentId = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);

    final UnregisterPathResponse.Builder responseBuilder = UnregisterPathResponse.newBuilder();

    if (agentContext == null) {
      logger.error("Missing AgentContext for agentId: {}", agentId);
      responseBuilder.setValid(false).setReason(format("Invalid agentId: %s", agentId));
    }
    else {
      this.proxy.removePath(path, agentId, responseBuilder);
      agentContext.markActivity();
    }

    responseObserver.onNext(responseBuilder.build());
    responseObserver.onCompleted();
  }

  @Override
  public void pathMapSize(PathMapSizeRequest request, StreamObserver<PathMapSizeResponse> responseObserver) {
    final PathMapSizeResponse response = PathMapSizeResponse.newBuilder()
                                                            .setPathCount(this.proxy.pathMapSize())
                                                            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void sendHeartBeat(final HeartBeatRequest request, final StreamObserver<HeartBeatResponse> responseObserver) {
    if (this.proxy.isMetricsEnabled())
      this.proxy.getMetrics().heartbeats.inc();

    final String agentId = request.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    if (agentContext == null)
      logger.info("sendHeartBeat() missing AgentContext agentId: {}", agentId);
    else
      agentContext.markActivity();

    responseObserver.onNext(HeartBeatResponse.newBuilder()
                                             .setValid(agentContext != null)
                                             .setReason(format("Invalid agentId: %s", agentId))
                                             .build());
    responseObserver.onCompleted();
  }

  @Override
  public void readRequestsFromProxy(final AgentInfo agentInfo, final StreamObserver<ScrapeRequest> responseObserver) {
    final String agentId = agentInfo.getAgentId();
    final AgentContext agentContext = this.proxy.getAgentContext(agentId);
    if (agentContext != null) {
      while (this.proxy.isRunning() && agentContext.isValid()) {
        final ScrapeRequestWrapper scrapeRequest = agentContext.pollScrapeRequestQueue();
        if (scrapeRequest != null) {
          scrapeRequest.annotateSpan("send-to-agent");
          responseObserver.onNext(scrapeRequest.getScrapeRequest());
        }
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
        final ScrapeRequestWrapper scrapeRequest = proxy.getFromScrapeRequestMap(scrapeId);
        if (scrapeRequest == null) {
          logger.error("Missing ScrapeRequestWrapper for scrape_id: {}", scrapeId);
        }
        else {
          scrapeRequest.setScrapeResponse(response)
                       .markComplete()
                       .annotateSpan("received-from-agent")
                       .getAgentContext().markActivity();
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
          // logger.warn("StatusRuntimeException", e);
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
