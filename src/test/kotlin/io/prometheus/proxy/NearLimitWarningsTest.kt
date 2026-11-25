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

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

// The proxy warns that an agent nears maxPathsPerAgent, and an identity maxPathsPerIdentity or maxAgentsPerIdentity,
// as the count reaches 80% of the limit, and again only once it has fallen below 70%. Each warning compared the count
// with the threshold alone, so a count that hovered there warned on each climb back.
class NearLimitWarningsTest : StringSpec() {
  init {
    "a count should be warned of as it reaches 80% of its limit, and again only once it has fallen below 70%" {
      val warnings = NearLimitWarnings<String>()

      (1..10).map { warnings.rose("agent", it, 10) } shouldBe List(10) { it + 1 == 8 }
      // 70% isn't below it: still warned of.
      warnings.fell("agent", 7)
      warnings.rose("agent", 8, 10) shouldBe false
      warnings.fell("agent", 6)
      warnings.rose("agent", 7, 10) shouldBe false
      warnings.rose("agent", 8, 10) shouldBe true
    }

    "each key should be warned of on its own" {
      val warnings = NearLimitWarnings<String>()

      warnings.rose("team-a", 4, 5) shouldBe true
      warnings.rose("team-b", 4, 5) shouldBe true
      warnings.fell("team-a", 0)
      warnings.rose("team-a", 4, 5) shouldBe true
      warnings.rose("team-b", 5, 5) shouldBe false
    }

    // 80% of a small limit is a fraction of a count: the first whole count at or past it is warned of.
    "a limit whose 80% isn't a whole count should be warned of at the count past it" {
      val warnings = NearLimitWarnings<String>()

      warnings.rose("agent", 2, 3) shouldBe false
      warnings.rose("agent", 3, 3) shouldBe true
    }

    "a limit of 0, which means none, should never be warned of" {
      val warnings = NearLimitWarnings<String>()

      warnings.rose("agent", 1_000_000, 0) shouldBe false
      warnings.fell("agent", 0)
      warnings.rose("agent", 1_000_000, -1) shouldBe false
    }

    // The comparisons multiply the count and the limit, which as Ints would wrap into a warning, or out of one.
    "a limit near Int.MAX_VALUE should be compared without overflow" {
      val warnings = NearLimitWarnings<String>()

      warnings.rose("identity", Int.MAX_VALUE / 2, Int.MAX_VALUE) shouldBe false
      warnings.rose("identity", Int.MAX_VALUE, Int.MAX_VALUE) shouldBe true
      warnings.fell("identity", Int.MAX_VALUE / 2)
      warnings.rose("identity", Int.MAX_VALUE, Int.MAX_VALUE) shouldBe true
    }
  }
}
