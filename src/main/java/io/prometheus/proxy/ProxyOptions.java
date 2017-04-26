package io.prometheus.proxy;

import com.beust.jcommander.Parameter;
import com.google.common.collect.Iterables;
import io.prometheus.common.BaseOptions;
import io.prometheus.common.ConfigVals;

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

  public ProxyOptions(final String programName, final List<String> args) {
    this(programName, Iterables.toArray(args, String.class));
  }

  public ProxyOptions(final String programName, final String[] argv) {
    super(programName, argv, PROXY_CONFIG.name(), false);
    this.assignConfigVals(this.getConfigVals());
  }

  @Override
  protected void assignConfigVals(final ConfigVals configVals) {
    if (this.proxyPort == null)
      this.proxyPort = PROXY_PORT.getEnv(configVals.proxy.http.port);

    if (this.agentPort == null)
      this.agentPort = AGENT_PORT.getEnv(configVals.proxy.agent.port);

    this.assignMetricsPort(configVals.proxy.metrics.port);
    this.assignEnableMetrics(configVals.proxy.metrics.enabled);
  }

  public int getProxyPort() { return this.proxyPort; }

  public int getAgentPort() { return this.agentPort; }
}