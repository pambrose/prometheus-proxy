/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.agent;

import com.beust.jcommander.Parameter;
import com.google.common.collect.Iterables;
import io.prometheus.Agent;
import io.prometheus.common.BaseOptions;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.EnvVars;

import java.util.Collections;
import java.util.List;

import static io.prometheus.common.EnvVars.AGENT_CONFIG;
import static io.prometheus.common.EnvVars.PROXY_HOSTNAME;
import static java.lang.String.format;

public class AgentOptions
    extends BaseOptions {

  @Parameter(names = {"-p", "--proxy"}, description = "Proxy hostname")
  private String proxyHostname = null;
  @Parameter(names = {"-n", "--name"}, description = "Agent name")
  private String agentName     = null;

  public AgentOptions(final List<String> args, final boolean exitOnMissingConfig) {
    this(Iterables.toArray(args != null ? args : Collections.emptyList(), String.class), exitOnMissingConfig);
  }

  public AgentOptions(final String[] argv, final boolean exitOnMissingConfig) {
    super(Agent.class.getName(), argv, AGENT_CONFIG.name(), exitOnMissingConfig);
    this.assignConfigVals(this.getConfigVals());
  }

  @Override
  protected void assignConfigVals(final ConfigVals configVals) {
    if (this.proxyHostname == null) {
      final String configHostname = configVals.agent.proxy.hostname;
      this.proxyHostname = PROXY_HOSTNAME.getEnv(configHostname.contains(":") ? configHostname
                                                                              : format("%s:%d",
                                                                                       configHostname,
                                                                                       configVals.agent.proxy.port));
    }

    if (this.agentName == null)
      this.agentName = EnvVars.AGENT_NAME.getEnv(configVals.agent.name);

    this.assignAdminEnabled(configVals.agent.admin.enabled);
    this.assignAdminPort(configVals.agent.admin.port);
    this.assignMetricsEnabled(configVals.agent.metrics.enabled);
    this.assignMetricsPort(configVals.agent.metrics.port);
  }

  public String getProxyHostname() { return this.proxyHostname; }

  public String getAgentName() { return this.agentName; }
}