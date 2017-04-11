package com.sudothought;

import com.cinch.grpc.AgentInfo;
import com.cinch.grpc.AgentRegisterRequest;
import com.cinch.grpc.AgentRegisterResponse;
import com.cinch.grpc.PathRegisterRequest;
import com.cinch.grpc.PathRegisterResponse;
import com.cinch.grpc.ProxyServiceGrpc;
import com.cinch.grpc.ScrapeRequest;
import com.cinch.grpc.ScrapeResponse;
import com.google.common.collect.Maps;
import com.google.protobuf.Empty;
import com.sudothought.args.ProxyArgs;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

public class Proxy {

  private static final Logger logger = Logger.getLogger(Proxy.class.getName());

  private final Map<Long, AgentContext> agentContextMap = Maps.newConcurrentMap();

  private final int    port;
  private final Server server;

  public Proxy(int port)
      throws IOException {
    this.port = port;
    this.server = ServerBuilder.forPort(this.port)
                               .addService(new ProxyImpl(this))
                               .build()
                               .start();
  }

  public static void main(final String[] argv)
      throws Exception {

    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    Proxy proxy = new Proxy(proxyArgs.grpc_port);
    proxy.start();
    proxy.blockUntilShutdown();
  }

  private void start()
      throws IOException {
    logger.info(String.format("gRPC server started listening on %s", port));
    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down gRPC server since JVM is shutting down");
                 Proxy.this.stop();
                 System.err.println("*** gRPC server shut down");
               }));
  }

  private void stop() {
    if (this.server != null)
      this.server.shutdown();
  }

  private void blockUntilShutdown()
      throws InterruptedException {
    if (this.server != null)
      this.server.awaitTermination();
  }

  static class ProxyImpl
      extends ProxyServiceGrpc.ProxyServiceImplBase {

    private final Proxy proxy;

    public ProxyImpl(Proxy proxy) {
      this.proxy = proxy;
    }

    @Override
    public void registerAgent(AgentRegisterRequest request, StreamObserver<AgentRegisterResponse> responseObserver) {
      final AgentContext agentContext = new AgentContext(request.getHostname());
      this.proxy.agentContextMap.put(agentContext.getAgentId(), agentContext);

      AgentRegisterResponse response = AgentRegisterResponse.newBuilder().setAgentId(agentContext.getAgentId()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void registerPath(PathRegisterRequest request, StreamObserver<PathRegisterResponse> responseObserver) {
    }

    @Override
    public void readRequestsFromProxy(AgentInfo request, StreamObserver<ScrapeRequest> responseObserver) {
    }

    @Override
    public void writeResponseToProxy(ScrapeResponse request, StreamObserver<Empty> responseObserver) {
    }

    /*
    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
    */
  }

}
