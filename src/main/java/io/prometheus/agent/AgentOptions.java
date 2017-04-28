package io.prometheus.agent;

import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.prometheus.Agent;
import io.prometheus.common.BaseOptions;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.EnvVars;

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
    this(Iterables.toArray(args != null ? args : ImmutableList.of(), String.class), exitOnMissingConfig);
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

    this.assignMetricsPort(configVals.agent.metrics.port);
    this.assignEnableMetrics(configVals.agent.metrics.enabled);
  }

  public String getProxyHostname() { return this.proxyHostname; }

  public String getAgentName() { return this.agentName; }
}