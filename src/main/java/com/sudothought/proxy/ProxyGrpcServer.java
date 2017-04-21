package com.sudothought.proxy;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class ProxyGrpcServer {

  private static final Logger logger = LoggerFactory.getLogger(ProxyGrpcServer.class);

  private final String  serverName;
  private final boolean inProcessServer;
  private final Server  grpcServer;

  private ProxyGrpcServer(final Proxy proxy, final int grpcPort, final String serverName) {
    this.serverName = serverName;
    this.inProcessServer = !Strings.isNullOrEmpty(serverName);

    final List<ServerInterceptor> interceptors = Lists.newArrayList(new ProxyInterceptor());

    /*
    if (proxy.getConfigVals().grpc.metricsEnabled)
      interceptors.add(MonitoringServerInterceptor.create(proxy.getConfigVals().grpc.allMetricsReported
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (proxy.isZipkinEnabled() && proxy.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcServerInterceptor.create(proxy.getZipkinReporter().getBrave()));
    */

    final ProxyServiceImpl proxyService = new ProxyServiceImpl(proxy);
    final ServerServiceDefinition serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptors);

    this.grpcServer = this.inProcessServer ? InProcessServerBuilder.forName(this.serverName)
                                                                   .addService(serviceDef)
                                                                   .addTransportFilter(new ProxyTransportFilter(proxy))
                                                                   .build()
                                           : ServerBuilder.forPort(grpcPort)
                                                          .addService(serviceDef)
                                                          .addTransportFilter(new ProxyTransportFilter(proxy))
                                                          .build();
  }

  public static ProxyGrpcServer create(final Proxy proxy, final int grpcPort) {
    return new ProxyGrpcServer(proxy, grpcPort, null);
  }

  public static ProxyGrpcServer create(final Proxy proxy, final String serverName) {
    return new ProxyGrpcServer(proxy, -1, Preconditions.checkNotNull(serverName));
  }

  public Server start()
      throws IOException {
    final Server server = this.grpcServer.start();
    if (this.inProcessServer)
      logger.info("Started InProcess gRPC server {}", this.serverName);
    else
      logger.info("Started gRPC server listening on {}", this.grpcServer.getPort());
    return server;
  }

  public int getPort() { return this.grpcServer.getPort(); }

  public Server shutdown() { return inProcessServer ? null : this.grpcServer.shutdown(); }

  public void awaitTermination()
      throws InterruptedException { this.grpcServer.awaitTermination(); }
}
