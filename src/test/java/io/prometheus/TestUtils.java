package io.prometheus;

import io.prometheus.agent.AgentOptions;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.EnvVars;
import io.prometheus.common.Utils;
import io.prometheus.proxy.ProxyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(String serverName, boolean metrics_enabled)
      throws IOException {

    ProxyOptions proxyOptions = new ProxyOptions(Proxy.class.getName());
    proxyOptions.parseArgs(TestConstants.argv);
    proxyOptions.readConfig(EnvVars.PROXY_CONFIG.name(), false);

    ConfigVals proxyConfigVals = new ConfigVals(proxyOptions.getConfig());
    proxyOptions.assignOptions(proxyConfigVals);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    Proxy proxy = new Proxy(proxyConfigVals,
                            proxyOptions.getAgentPort(),
                            TestConstants.PROXY_PORT,
                            metrics_enabled,
                            proxyOptions.getMetricsPort(),
                            serverName,
                            true);
    proxy.start();

    return proxy;
  }

  public static Agent startAgent(String serverName, boolean metrics_enabled)
      throws IOException {

    AgentOptions agentOptions = new AgentOptions(Agent.class.getName());
    agentOptions.parseArgs(TestConstants.argv);
    agentOptions.readConfig(EnvVars.AGENT_CONFIG.name(), true);

    ConfigVals configVals = new ConfigVals(agentOptions.getConfig());
    agentOptions.assignOptions(configVals);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc());

    Agent agent = new Agent(configVals,
                            serverName,
                            agentOptions.getAgentName(),
                            agentOptions.getProxyHostname(),
                            metrics_enabled,
                            agentOptions.getMetricsPort(),
                            true);
    agent.start();

    return agent;
  }
}
