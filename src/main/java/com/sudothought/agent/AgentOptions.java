package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseOptions;
import com.sudothought.common.ConfigVals;

import static com.sudothought.common.EnvVars.AGENT_NAME;
import static com.sudothought.common.EnvVars.PROXY_HOSTNAME;
import static java.lang.String.format;

public class AgentOptions
    extends BaseOptions {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  private String proxyHostname = null;
  @Parameter(names = {"-n", "--name"}, description = "Agent name")
  private String agentName     = null;

  public AgentOptions(String programName) {
    super(programName);
  }

  public void assignOptions(final ConfigVals configVals) {

    if (this.proxyHostname == null) {
      final String configHostname = configVals.agent.proxy.hostname;
      this.proxyHostname = PROXY_HOSTNAME.getEnv(configHostname.contains(":") ? configHostname
                                                                              : format("%s:%d",
                                                                                       configHostname,
                                                                                       configVals.agent.proxy.port));
    }

    if (this.agentName == null)
      this.agentName = AGENT_NAME.getEnv(configVals.agent.name);

    this.assignMetricsPort(configVals.agent.metrics.port);
    this.assignEnableMetrics(configVals.agent.metrics.enabled);
  }

  public String getProxyHostname() { return this.proxyHostname; }

  public String getAgentName() { return this.agentName; }
}