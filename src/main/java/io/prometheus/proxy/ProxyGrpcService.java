/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessServerBuilder;
import io.prometheus.Proxy;
import io.prometheus.common.GenericServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static com.google.common.base.Strings.isNullOrEmpty;

public class ProxyGrpcService
    extends AbstractIdleService {

  private static final Logger logger = LoggerFactory.getLogger(ProxyGrpcService.class);

  private final String  serverName;
  private final int     port;
  private final boolean inProcessServer;
  private final Server  grpcServer;

  private ProxyGrpcService(final Proxy proxy, final int port, final String serverName) {
    this.port = port;
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
                                           : ServerBuilder.forPort(this.port)
                                                          .addService(serviceDef)
                                                          .addTransportFilter(new ProxyTransportFilter(proxy))
                                                          .build();
    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
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
  }

  @Override
  protected void shutDown() { this.grpcServer.shutdown(); }

  public HealthCheck getHealthCheck() {
    return new HealthCheck() {
      @Override
      protected Result check()
          throws Exception {
        return grpcServer.isShutdown() || grpcServer.isShutdown() ? Result.unhealthy("gRPC Server is not runing")
                                                                  : Result.healthy();
      }
    };
  }

  public int getPort() { return this.grpcServer.getPort(); }

  @Override
  public String toString() {
    final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);
    if (this.inProcessServer) {
      helper.add("serverType", "InProcess");
      helper.add("serverName", this.serverName);
    }
    else {
      helper.add("serverType", "Netty");
      helper.add("port", this.port);
    }
    return helper.toString();
  }
}
