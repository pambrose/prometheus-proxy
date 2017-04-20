package com.sudothought.proxy;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;
import com.sudothought.common.ConfigVals;

public class ProxyArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--port"}, description = "Proxy listen port")
  public Integer http_port    = null;
  @Parameter(names = {"-g", "--grpc"}, description = "gRPC listen port")
  public Integer grpc_port    = null;


  public void assignArgs(final ConfigVals configVals) {

    if (this.http_port == null)
      this.http_port = configVals.proxy.http.port;

    if (this.grpc_port == null)
      this.grpc_port = configVals.proxy.grpc.port;

    this.assignMetricsPort(configVals.proxy.metrics.port);
    this.assignDisableMetrics(!configVals.proxy.metrics.enabled);
  }
}