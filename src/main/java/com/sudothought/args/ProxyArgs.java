package com.sudothought.args;

import com.beust.jcommander.Parameter;

public class ProxyArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--port"}, description = "Proxy listen port")
  public int proxy_port = 8080;
  @Parameter(names = {"-g", "--grpc"}, description = "gRPC listen port")
  public int grpc_port  = 50051;

}