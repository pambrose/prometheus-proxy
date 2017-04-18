package com.sudothought.agent;

import com.beust.jcommander.Parameter;
import com.sudothought.common.BaseArgs;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-c", "--conf", "--config"}, required = true, description = "Configuration file or url")
  public String config;
  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  public String  proxy_hostname = null;
  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public Integer metrics_port   = null;
}
