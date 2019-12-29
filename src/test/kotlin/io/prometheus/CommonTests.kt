@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.ProxyTests.timeoutTest
import io.prometheus.SimpleTests.addRemovePathsTest
import io.prometheus.SimpleTests.invalidAgentUrlTest
import io.prometheus.SimpleTests.invalidPathTest
import io.prometheus.SimpleTests.missingPathTest
import io.prometheus.SimpleTests.threadedAddRemovePathsTest
import org.junit.jupiter.api.Test

abstract class CommonTests(private val args: ProxyCallTestArgs) {

  @Test
  fun proxyCallTest() = ProxyTests.proxyCallTest(args)

  @Test
  fun missingPathTest() = missingPathTest(simpleClassName)

  @Test
  fun invalidPathTest() = invalidPathTest(simpleClassName)

  @Test
  fun addRemovePathsTest() = addRemovePathsTest(args.agent.pathManager, simpleClassName)

  @Test
  fun threadedAddRemovePathsTest() = threadedAddRemovePathsTest(args.agent.pathManager, simpleClassName)

  @Test
  fun invalidAgentUrlTest() = invalidAgentUrlTest(args.agent.pathManager, simpleClassName)

  @Test
  fun timeoutTest() = timeoutTest(args.agent.pathManager, simpleClassName)

  companion object {
    const val HTTP_SERVER_COUNT = 5
    const val PATH_COUNT = 50
    const val SEQUENTIAL_QUERY_COUNT = 200
    const val PARALLEL_QUERY_COUNT = 25
  }
}