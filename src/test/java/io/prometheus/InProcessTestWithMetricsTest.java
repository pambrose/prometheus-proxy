package io.prometheus;

import io.prometheus.client.CollectorRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

public class InProcessTestWithMetricsTest {

  private static final Logger logger = LoggerFactory.getLogger(InProcessTestWithMetricsTest.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException, TimeoutException {
    CollectorRegistry.defaultRegistry.clear();
    PROXY = TestUtils.startProxy("withmetrics", true, true);
    AGENT = TestUtils.startAgent("withmetrics", true, true);

    AGENT.awaitInitialConnection(10, SECONDS);
  }

  @AfterClass
  public static void takeDown()
      throws InterruptedException, TimeoutException {
    PROXY.stopAsync();
    PROXY.awaitTerminated(5, SECONDS);
    AGENT.stopAsync();
    AGENT.awaitTerminated(5, SECONDS);
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
    Tests.proxyCallTest(AGENT, 25, 50, 500);
  }

}
