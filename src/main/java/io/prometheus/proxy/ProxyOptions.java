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

package io.prometheus.proxy;

import com.beust.jcommander.Parameter;
import com.google.common.collect.Iterables;
import io.prometheus.Proxy;
import io.prometheus.common.BaseOptions;
import io.prometheus.common.ConfigVals;

import java.util.Collections;
import java.util.List;

import static io.prometheus.common.EnvVars.AGENT_PORT;
import static io.prometheus.common.EnvVars.PROXY_CONFIG;
import static io.prometheus.common.EnvVars.PROXY_PORT;

public class ProxyOptions
    extends BaseOptions {

  @Parameter(names = {"-p", "--port"}, description = "Listen port for Prometheus")
  private Integer proxyPort = null;
  @Parameter(names = {"-a", "--agent_port"}, description = "Listen port for agents")
  private Integer agentPort = null;

  public ProxyOptions(final List<String> args) {
    this(Iterables.toArray(args != null ? args : Collections.emptyList(), String.class));
  }

  public ProxyOptions(final String[] argv) {
    super(Proxy.class.getSimpleName(), argv, PROXY_CONFIG.name(), false);
    this.assignConfigVals(this.getConfigVals());
  }

  @Override
  protected void assignConfigVals(final ConfigVals configVals) {
    if (this.proxyPort == null)
      this.proxyPort = PROXY_PORT.getEnv(configVals.proxy.http.port);

    if (this.agentPort == null)
      this.agentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port);

    this.assignAdminEnabled(configVals.proxy.admin.enabled);
    this.assignAdminPort(configVals.proxy.admin.port);
    this.assignMetricsEnabled(configVals.proxy.metrics.enabled);
    this.assignMetricsPort(configVals.proxy.metrics.port);
  }

  public int getProxyPort() { return this.proxyPort; }

  public int getAgentPort() { return this.agentPort; }
}