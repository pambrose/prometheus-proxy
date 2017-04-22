package com.sudothought;

import com.google.common.collect.Lists;
import com.sudothought.agent.Agent;
import com.sudothought.agent.AgentArgs;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.Utils;
import com.sudothought.proxy.Proxy;
import com.sudothought.proxy.ProxyArgs;
import com.typesafe.config.Config;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.EnvVars.PROXY_CONFIG;
import static com.sudothought.common.Utils.sleepForSecs;
import static org.assertj.core.api.Assertions.assertThat;

public class InProcessTest {

  private static Proxy proxy = null;
  private static Agent agent = null;

  @BeforeClass
  public static void setUp()
      throws Exception {

    final String[] argv = {"--config", "https://dl.dropboxusercontent.com/u/481551/prometheus/proxy.conf"};
    final ProxyArgs proxyArgs = new ProxyArgs();
    proxyArgs.parseArgs(Proxy.class.getName(), argv);

    final Config proxyConfig = Utils.readConfig(proxyArgs.config, PROXY_CONFIG, false);
    final ConfigVals proxyConfigVals = new ConfigVals(proxyConfig);
    proxyArgs.assignArgs(proxyConfigVals);

    proxy = new Proxy(proxyConfigVals,
                      proxyArgs.grpc_port,
                      proxyArgs.http_port,
                      !proxyArgs.disable_metrics,
                      proxyArgs.metrics_port,
                      "server1");
    proxy.start();

    final AgentArgs agentArgs = new AgentArgs();
    agentArgs.parseArgs(Agent.class.getName(), argv);

    final Config agentConfig = Utils.readConfig(agentArgs.config, AGENT_CONFIG, true);
    final ConfigVals configVals = new ConfigVals(agentConfig);
    agentArgs.assignArgs(configVals);

    agent = new Agent(configVals,
                      "server1",
                      agentArgs.agent_name,
                      agentArgs.proxy_host,
                      !agentArgs.disable_metrics,
                      agentArgs.metrics_port);
    agent.start();

    sleepForSecs(5);
  }

  @AfterClass
  public static void takeDown() {
    proxy.stop();
    agent.stop();
    sleepForSecs(5);
  }

  @Test
  public void addRemovePathsTest()
      throws ConnectException {
    int cnt = 0;
    for (int i = 0; i < 1000; i++) {
      final String path = "test-" + i;
      agent.registerPath(path, "http://localhost:9500/" + path);
      cnt++;
      assertThat(agent.pathMapSize()).isEqualTo(cnt);
      agent.unregisterPath(path);
      cnt--;
      assertThat(agent.pathMapSize()).isEqualTo(cnt);
    }
  }

  @Test
  public void threadedAddRemovePathsTest()
      throws ConnectException, InterruptedException {
    int reps = 1000;
    final ExecutorService executorService = Executors.newCachedThreadPool();
    final List<String> paths = Lists.newArrayList();
    final AtomicInteger cnt = new AtomicInteger(0);
    final CountDownLatch latch1 = new CountDownLatch(reps);
    final CountDownLatch latch2 = new CountDownLatch(reps);
    for (int i = 0; i < reps; i++) {
      executorService.submit(
          () -> {
            final String path = "test-" + cnt.getAndIncrement();
            synchronized (paths) {
              paths.add(path);
            }
            try {
              agent.registerPath(path, "http://localhost:9500/" + path);
            }
            catch (ConnectException e) {
              e.printStackTrace();
            }
            latch1.countDown();
          });
    }

    assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(paths.size()).isEqualTo(reps);
    assertThat(agent.pathMapSize()).isEqualTo(reps);

    for (String path : paths) {
      executorService.submit(
          () -> {
            try {
              agent.unregisterPath(path);
            }
            catch (ConnectException e) {
              e.printStackTrace();
            }
            latch2.countDown();
          });
    }

    // Wait for all unregistrations to complete
    assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(agent.pathMapSize()).isEqualTo(0);

    executorService.shutdownNow();
  }

}
