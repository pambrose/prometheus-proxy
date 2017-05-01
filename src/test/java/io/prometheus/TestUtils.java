package io.prometheus;

import io.prometheus.agent.AgentOptions;
import io.prometheus.common.Utils;
import io.prometheus.proxy.ProxyOptions;
import org.assertj.core.util.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;

public class TestUtils {

  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static Proxy startProxy(String serverName, boolean adminEnabled, boolean metricsEnabled, List<String> argv)
      throws IOException, TimeoutException {

    final List<String> args = Lists.newArrayList(TestConstants.args);
    args.addAll(argv);
    args.add(format("-Dproxy.admin.enabled=%s", adminEnabled));
    args.add(format("-Dproxy.metrics.enabled=%s", metricsEnabled));
    ProxyOptions options = new ProxyOptions(args);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc(false));

    Proxy proxy = new Proxy(options, TestConstants.PROXY_PORT, serverName, true);
    proxy.startAsync();
    proxy.awaitRunning(5, TimeUnit.SECONDS);
    return proxy;
  }

  public static Agent startAgent(String serverName, boolean adminEnabled, boolean metricsEnabled, List<String> argv)
      throws IOException, TimeoutException {

    final List<String> args = Lists.newArrayList(TestConstants.args);
    args.addAll(argv);
    args.add(format("-Dagent.admin.enabled=%s", adminEnabled));
    args.add(format("-Dagent.metrics.enabled=%s", metricsEnabled));
    AgentOptions options = new AgentOptions(args, false);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc(false));

    Agent agent = new Agent(options, serverName, true);
    agent.startAsync();
    agent.awaitRunning(5, TimeUnit.SECONDS);
    return agent;
  }
}
