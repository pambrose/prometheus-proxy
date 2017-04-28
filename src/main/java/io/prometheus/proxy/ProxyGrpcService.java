package io.prometheus.proxy;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractIdleService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessServerBuilder;
import io.prometheus.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static com.google.common.base.Strings.isNullOrEmpty;

public class ProxyGrpcService
    extends AbstractIdleService {

  private static final Logger logger = LoggerFactory.getLogger(ProxyGrpcService.class);

  private final String  serverName;
  private final boolean inProcessServer;
  private final Server  grpcServer;

  private ProxyGrpcService(final Proxy proxy, final int grpcPort, final String serverName) {
    this.serverName = serverName;
    this.inProcessServer = !isNullOrEmpty(serverName);

    final List<ServerInterceptor> interceptors = Lists.newArrayList(new ProxyInterceptor());

    /*
    if (proxy.getConfigVals().grpc.metricsEnabled)
      interceptors.add(MonitoringServerInterceptor.create(proxy.getConfigVals().grpc.allMetricsReported
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (proxy.isZipkinEnabled() && proxy.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcServerInterceptor.create(proxy.getZipkinReporterService().getBrave()));
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

  public static ProxyGrpcService create(final Proxy proxy, final int grpcPort) {
    return new ProxyGrpcService(proxy, grpcPort, null);
  }

  public static ProxyGrpcService create(final Proxy proxy, final String serverName) {
    return new ProxyGrpcService(proxy, -1, Preconditions.checkNotNull(serverName));
  }

  @Override
  protected void startUp()
      throws IOException {
    this.grpcServer.start();
    if (this.inProcessServer)
      logger.info("Started InProcess gRPC server {}", this.serverName);
    else
      logger.info("Started gRPC server listening on {}", this.grpcServer.getPort());
  }

  @Override
  protected void shutDown() {
    this.grpcServer.shutdown();
  }

  public int getPort() { return this.grpcServer.getPort(); }
}
