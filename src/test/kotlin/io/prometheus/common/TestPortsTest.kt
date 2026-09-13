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
  }
}
