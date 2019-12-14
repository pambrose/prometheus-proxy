package io.prometheus

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.ProxyTests.timeoutTest
import io.prometheus.SimpleTests.addRemovePathsTest
import io.prometheus.SimpleTests.invalidAgentUrlTest
import io.prometheus.SimpleTests.invalidPathTest
import io.prometheus.SimpleTests.missingPathTest
import io.prometheus.SimpleTests.threadedAddRemovePathsTest
import org.junit.jupiter.api.Test

abstract class CommonTests(val agent: Agent, val args: ProxyCallTestArgs) {

  @Test
  fun proxyCallTest() = ProxyTests.proxyCallTest(args)

  @Test
  fun missingPathTest() = missingPathTest(simpleClassName)

  @Test
  fun invalidPathTest() = invalidPathTest(simpleClassName)

  @Test
  fun addRemovePathsTest() = addRemovePathsTest(agent.pathManager, simpleClassName)

  @Test
  fun threadedAddRemovePathsTest() = threadedAddRemovePathsTest(agent.pathManager, simpleClassName)

  @Test
  fun invalidAgentUrlTest() = invalidAgentUrlTest(agent.pathManager, simpleClassName)

  @Test
  fun timeoutTest() = timeoutTest(agent.pathManager, simpleClassName)
}