/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.containers

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.NGINX_PORT
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.Network
import java.net.InetAddress

/**
 * Asserts the invariant [io.prometheus.containers.support.LoopbackPortBindings] exists to enforce, against
 * a real container: every published port binds `127.0.0.1` rather than the `0.0.0.0` wildcard, and the
 * mapped port stays reachable over loopback. That class's KDoc carries the failure mode this prevents.
 */
class ContainersPortBindingTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Published ports bind loopback (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      "a published port should bind the loopback interface rather than the wildcard address" {
        val network = Network.newNetwork()
        val stub = metricsStub(network)
        try {
          stub.start()

          val bindings = stub.containerInfo.networkSettings.ports.bindings
          val published = bindings.entries.filter { it.value != null }
          published.shouldNotBeEmpty()
          published.forEach { (exposed, hostBindings) ->
            hostBindings.forEach { binding ->
              withClue("container port $exposed published on ${binding.hostIp}:${binding.hostPortSpec}") {
                binding.hostIp shouldBe LOOPBACK_HOST
              }
            }
          }

          // The mapped port must still be reachable the way every other spec reaches it. Testcontainers
          // reports its host as the name "localhost" rather than the literal address, so resolve it: what
          // matters is that specs reach the container over loopback, where the binding now lives.
          stub.getMappedPort(NGINX_PORT) shouldBeGreaterThan 0
          InetAddress.getByName(stub.host).isLoopbackAddress.shouldBeTrue()
        } finally {
          stopQuietly(stub)
          closeQuietly(network)
        }
      }
    }
  }
}
