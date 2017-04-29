package io.prometheus;

import io.prometheus.agent.AgentOptions;
import io.prometheus.common.Utils;
import io.prometheus.proxy.ProxyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(String serverName, boolean metrics_enabled)
      throws IOException, TimeoutException {

    ProxyOptions options = new ProxyOptions(TestConstants.argv);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    Proxy proxy = new Proxy(options, TestConstants.PROXY_PORT, serverName, true);
    proxy.startAsync();
    proxy.awaitRunning(5, TimeUnit.SECONDS);
    return proxy;
  }

  public static Agent startAgent(String serverName, boolean metrics_enabled)
      throws IOException, TimeoutException {

    AgentOptions options = new AgentOptions(TestConstants.argv, false);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc());

    Agent agent = new Agent(options, serverName, true);
    agent.startAsync();
    agent.awaitRunning(5, TimeUnit.SECONDS);
    return agent;
  }
}
