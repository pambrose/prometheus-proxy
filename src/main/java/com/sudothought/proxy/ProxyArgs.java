package com.sudothought.proxy;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;

public class ProxyArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--port"}, description = "Proxy listen port")
  public int http_port    = 8080;
  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public int metrics_port = 8082;
  @Parameter(names = {"-g", "--grpc"}, description = "gRPC listen port")
  public int grpc_port    = 50051;

}