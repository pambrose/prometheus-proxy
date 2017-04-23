package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;
import com.sudothought.common.ConfigVals;

import static com.sudothought.common.EnvVars.AGENT_NAME;
import static java.lang.String.format;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  public String proxyHost = null;
  @Parameter(names = {"-n", "--name"}, description = "Agent name")
  public String agentName = null;

  public void assignArgs(final ConfigVals configVals) {

    if (this.proxyHost == null)
      this.proxyHost = format("%s:%d", configVals.agent.grpc.hostname, configVals.agent.grpc.port);

    if (this.agentName == null)
      this.agentName = AGENT_NAME.getEnv(configVals.agent.name);

    this.assignMetricsPort(configVals.agent.metrics.port);
    this.assignDisableMetrics(!configVals.agent.metrics.enabled);
  }
}
