package com.sudothought.proxy;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;
import com.sudothought.common.ConfigVals;

public class ProxyArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--port"}, description = "Proxy listen port")
  public Integer httpPort = null;
  @Parameter(names = {"-g", "--grpc"}, description = "gRPC listen port")
  public Integer grpcPort = null;


  public void assignArgs(final ConfigVals configVals) {

    if (this.httpPort == null)
      this.httpPort = configVals.proxy.http.port;

    if (this.grpcPort == null)
      this.grpcPort = configVals.proxy.grpc.port;

    this.assignMetricsPort(configVals.proxy.metrics.port);
    this.assignDisableMetrics(!configVals.proxy.metrics.enabled);
  }
}