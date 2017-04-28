package io.prometheus;

import com.google.common.util.concurrent.MoreExecutors;
import io.prometheus.agent.AgentOptions;
import io.prometheus.common.GenericServiceListener;
import io.prometheus.common.MetricsConfig;
import io.prometheus.common.Utils;
import io.prometheus.common.ZipkinConfig;
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
    final MetricsConfig metricsConfig = MetricsConfig.create(metrics_enabled,
                                                             options.getMetricsPort(),
                                                             options.getConfigVals().proxy.metrics);

    final ZipkinConfig zipkinConfig = ZipkinConfig.create(options.getConfigVals().proxy.internal.zipkin);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    Proxy proxy = new Proxy(options,
                            metricsConfig,
                            zipkinConfig,
                            TestConstants.PROXY_PORT,
                            serverName,
                            true);
    proxy.addListener(new GenericServiceListener(proxy), MoreExecutors.directExecutor());
    proxy.startAsync();
    proxy.awaitRunning(5, TimeUnit.SECONDS);
    return proxy;
  }

  public static Agent startAgent(String serverName, boolean metrics_enabled)
      throws IOException, TimeoutException {

    AgentOptions options = new AgentOptions(TestConstants.argv, false);
    final MetricsConfig metricsConfig = MetricsConfig.create(options.getMetricsEnabled(),
                                                             options.getMetricsPort(),
                                                             options.getConfigVals().agent.metrics);
    final ZipkinConfig zipkinConfig = ZipkinConfig.create(options.getConfigVals().agent.internal.zipkin);

    logger.info(Utils.getBanner("banners/agent.txt"));
    logger.info(Utils.getVersionDesc());

    Agent agent = new Agent(options,
                            metricsConfig,
                            zipkinConfig,
                            serverName,
                            true);
    agent.addListener(new GenericServiceListener(agent), MoreExecutors.directExecutor());
    agent.startAsync();
    agent.awaitRunning(5, TimeUnit.SECONDS);
    return agent;
  }
}
