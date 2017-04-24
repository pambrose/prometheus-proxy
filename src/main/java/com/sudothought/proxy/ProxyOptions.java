package com.sudothought.proxy;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseOptions;
import com.sudothought.common.ConfigVals;

import static com.sudothought.common.EnvVars.AGENT_PORT;
import static com.sudothought.common.EnvVars.PROXY_PORT;

public class ProxyOptions
    extends BaseOptions {

  @Parameter(names = {"-p", "--port"}, description = "Listen port for Prometheus")
  private Integer httpPort = null;
  @Parameter(names = {"-a", "--agent_port"}, description = "Listen port for agents")
  private Integer grpcPort = null;


  public void assignOptions(final ConfigVals configVals) {

    if (this.httpPort == null)
      this.httpPort = PROXY_PORT.getEnv(configVals.proxy.http.port);

    if (this.grpcPort == null)
      this.grpcPort = AGENT_PORT.getEnv(configVals.proxy.grpc.port);

    this.assignMetricsPort(configVals.proxy.metrics.port);
    this.assignEnableMetrics(configVals.proxy.metrics.enabled);
  }

  public Integer getHttpPort() { return this.httpPort; }

  public Integer getGrpcPort() { return this.grpcPort; }
}