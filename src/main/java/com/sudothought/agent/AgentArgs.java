package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;
import com.sudothought.common.ConfigVals;

import static com.sudothought.common.EnvVars.AGENT_NAME;
import static java.lang.System.getenv;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  public String proxy_host = null;
  @Parameter(names = {"-n", "--name"}, description = "Agent name")
  public String agent_name = null;


  public void assignArgs(final ConfigVals configVals) {

    if (this.proxy_host == null)
      this.proxy_host = String.format("%s:%d", configVals.agent.grpc.hostname, configVals.agent.grpc.port);

    if (this.agent_name == null)
      this.agent_name = getenv(AGENT_NAME) != null ? getenv(AGENT_NAME) : configVals.agent.name;

    this.assignMetricsPort(configVals.agent.metrics.port);
    this.assignDisableMetrics(!configVals.agent.metrics.enabled);
  }
}
