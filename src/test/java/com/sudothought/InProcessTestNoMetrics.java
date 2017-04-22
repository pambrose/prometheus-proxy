package com.sudothought;

import com.sudothought.agent.Agent;
import com.sudothought.proxy.Proxy;
import io.prometheus.client.CollectorRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static java.util.concurrent.TimeUnit.SECONDS;

public class InProcessTestNoMetrics {

  private static final Logger logger = LoggerFactory.getLogger(InProcessTestNoMetrics.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException {
    CollectorRegistry.defaultRegistry.clear();
    PROXY = Utils.startProxy("nometrics", false);
    AGENT = Utils.startAgent("nometrics", false);

    AGENT.awaitInitialConnection(10, SECONDS);
  }

  @AfterClass
  public static void takeDown()
      throws InterruptedException {
    PROXY.stop();
    PROXY.waitUntilShutdown(5, SECONDS);
    AGENT.stop();
    AGENT.waitUntilShutdown(5, SECONDS);
  }


  @Test
  public void missingPathTest()
      throws Exception {
    Tests.missingPathTest();
  }

  @Test
  public void invalidPathTest()
      throws Exception {
    Tests.invalidPathTest();
  }

  @Test
  public void addRemovePathsTest()
      throws Exception {
    Tests.addRemovePathsTest(AGENT);
  }

  @Test
  public void threadedAddRemovePathsTest()
      throws Exception {
    Tests.threadedAddRemovePathsTest(AGENT);
  }

  @Test
  public void invalidAgentUrlTest()
      throws Exception {
    Tests.invalidAgentUrlTest(AGENT);
  }

  @Test
  public void timeoutTest()
      throws Exception {
    Tests.timeoutTest(AGENT);
  }


  @Test
  public void proxyCallTest()
      throws Exception {
    Tests.proxyCallTest(AGENT, 5, 50, 100);
  }

}
