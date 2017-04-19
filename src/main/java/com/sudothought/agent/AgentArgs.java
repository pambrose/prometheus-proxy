package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  public String  proxy_host   = null;
  @Parameter(names = {"-n", "--name"}, description = "Agent name")
  public String  agent_name   = null;
  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public Integer metrics_port = null;
}
