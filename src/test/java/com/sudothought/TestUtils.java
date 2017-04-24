package com.sudothought;

import com.sudothought.agent.AgentOptions;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.Utils;
import com.sudothought.proxy.ProxyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(String serverName, boolean metrics_enabled)
      throws IOException {

    ProxyOptions proxyOptions = new ProxyOptions();
    proxyOptions.parseArgs(Proxy.class.getName(), TestConstants.argv);
    proxyOptions.readConfig(PROXY_CONFIG.getText(), false);
    proxyOptions.applyDynamicParams();

    ConfigVals proxyConfigVals = new ConfigVals(proxyOptions.getConfig());
    proxyOptions.assignOptions(proxyConfigVals);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    Proxy proxy = new Proxy(proxyConfigVals,
                            proxyOptions.getGrpcPort(),
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

    AgentOptions agentOptions = new AgentOptions();
    agentOptions.parseArgs(Agent.class.getName(), TestConstants.argv);
    agentOptions.readConfig(AGENT_CONFIG.getText(), true);
    agentOptions.applyDynamicParams();

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
