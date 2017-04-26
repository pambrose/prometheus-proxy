package io.prometheus;

import io.prometheus.agent.AgentOptions;
import io.prometheus.common.Utils;
import io.prometheus.proxy.ProxyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(String serverName, boolean metrics_enabled)
      throws IOException {

    ProxyOptions options = new ProxyOptions(Proxy.class.getName(), TestConstants.argv);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    Proxy proxy = new Proxy(options.getConfigVals(),
                            options.getAgentPort(),
                            TestConstants.PROXY_PORT,
                            metrics_enabled,
                            options.getMetricsPort(),
                            serverName,
                            true);
    proxy.start();

    return proxy;
  }

  public static Agent startAgent(String serverName, boolean metrics_enabled)
      throws IOException {

    AgentOptions options = new AgentOptions(Agent.class.getName(), TestConstants.argv, false);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc());

    Agent agent = new Agent(options.getConfigVals(),
                            serverName,
                            options.getAgentName(),
                            options.getProxyHostname(),
                            metrics_enabled,
                            options.getMetricsPort(),
                            true);
    agent.start();

    return agent;
  }
}
