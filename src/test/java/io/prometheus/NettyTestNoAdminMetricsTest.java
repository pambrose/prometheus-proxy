/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus;

import io.prometheus.client.CollectorRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

public class NettyTestNoAdminMetricsTest {

  private static final Logger logger = LoggerFactory.getLogger(NettyTestNoAdminMetricsTest.class);

  private static Proxy PROXY = null;
  private static Agent AGENT = null;

  @BeforeClass
  public static void setUp()
      throws IOException, InterruptedException, TimeoutException {
    CollectorRegistry.defaultRegistry.clear();
    PROXY = TestUtils.startProxy(null, false, false, Collections.emptyList());
    AGENT = TestUtils.startAgent(null, false, false, Collections.emptyList());

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

  // proxyCallTest() called in InProcess tests
}
