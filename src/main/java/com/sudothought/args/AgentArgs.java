package com.sudothought.args;

import com.beust.jcommander.Parameter;

public class AgentArgs
    extends BaseArgs {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy url")
  public String proxy = "localhost:50051";
  @Parameter(names = {"-c", "--config"}, required = true, description = "Configuration .yml file")
  public String config;

}
