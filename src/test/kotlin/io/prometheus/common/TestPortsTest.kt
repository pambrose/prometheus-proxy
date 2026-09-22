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

package io.prometheus.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

class TestPortsTest : StringSpec() {
  init {
    // Specs that share a port collide only when their runs overlap, so a duplicate shows up as an intermittent
    // bind failure far from its cause. Checking the values here fails the build on a duplicate, next to the line
    // that added it.
    "every port in TestPorts should be distinct" {
      val ports =
        TestPorts::class.java.declaredFields
          .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
          .associate { it.name to it.getInt(null) }

      ports.size shouldBeGreaterThan 50
      ports.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 } shouldBe emptyMap()
    }

    // An outgoing connection takes its local port from the OS's ephemeral range -- 32768-60999 on Linux, 49152-65535
    // on macOS -- so another test's connection can hold a port a spec is about to bind. That failed CI twice with
    // BindException on 50440 and 50460, both harness gRPC ports. Keeping every bound port below 32768 is outside both
    // ranges. The product default gRPC port is exempt: it is asserted as a value and used inside containers, never
    // bound on this machine.
    "no port in TestPorts should be in an ephemeral port range" {
      val neverBound = setOf("PROXY_AGENT_PORT")
      val ephemeral =
        TestPorts::class.java.declaredFields
          .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
          .filter { it.name !in neverBound }
          .associate { it.name to it.getInt(null) }
          .filterValues { it >= EPHEMERAL_RANGE_START }

      ephemeral shouldBe emptyMap()
    }
  }

  companion object {
    // The lower bound of Linux's default range; macOS's (49152) is higher still.
    private const val EPHEMERAL_RANGE_START = 32768
  }
}
