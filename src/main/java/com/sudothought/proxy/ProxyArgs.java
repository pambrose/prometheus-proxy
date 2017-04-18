package com.sudothought.proxy;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;

public class ProxyArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--port"}, description = "Proxy listen port")
  public Integer http_port    = null;
  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public Integer metrics_port = null;
  @Parameter(names = {"-g", "--grpc"}, description = "gRPC listen port")
  public Integer grpc_port    = null;

}