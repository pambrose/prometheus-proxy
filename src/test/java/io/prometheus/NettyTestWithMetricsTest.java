package io.prometheus;

import io.prometheus.client.CollectorRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static io.prometheus.common.Utils.sleepForSecs;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NettyTestWithMetricsTest {

  private static final Logger logger = LoggerFactory.getLogger(NettyTestWithMetricsTest.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException {
    CollectorRegistry.defaultRegistry.clear();
    PROXY = TestUtils.startProxy(null, true);
    AGENT = TestUtils.startAgent(null, true);

    AGENT.awaitInitialConnection(10, SECONDS);

    // Wait long enough to trigger heartbeat for code coverage
    sleepForSecs(15);
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

  // proxyCallTest() called in InProcess tests
}
