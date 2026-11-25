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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.agent.RuntimeLibraries.Library
import io.prometheus.agent.RuntimeLibraries.isAtLeast

// An embedded agent refuses to start on a library its app's dependency management sets below the version it runs on,
// as Spring Boot 4.0 and 4.1 set kotlinx-coroutines and the Prometheus client, naming the property that sets it.
class RuntimeLibrariesTest : StringSpec() {
  // The checked libraries, with [coroutines] and [prometheus] as the versions found
  private fun libraries(
    coroutines: String?,
    prometheus: String?,
  ) = RuntimeLibraries.installed().let { (c, p) ->
    listOf(
      Library(c.name, c.minimum, c.springBootProperty, c.springBootReleases, coroutines),
      Library(p.name, p.minimum, p.springBootProperty, p.springBootReleases, prometheus),
    )
  }

  init {
    "the versions this JVM has are read, and are ones the agent runs on" {
      val (coroutines, prometheus) = RuntimeLibraries.installed()

      coroutines.version.shouldNotBeNull()
      prometheus.version.shouldNotBeNull()
      RuntimeLibraries.requireSupported()
    }

    "Spring Boot 4.2's versions pass" {
      RuntimeLibraries.requireSupported(libraries(coroutines = "1.11.0", prometheus = "1.10.0"))
    }

    "Spring Boot 4.1.1's kotlinx-coroutines is refused, naming kotlin-coroutines.version" {
      val refused =
        shouldThrow<IllegalStateException> {
          RuntimeLibraries.requireSupported(libraries(coroutines = "1.10.2", prometheus = "1.7.0"))
        }

      refused.message shouldBe
        "The agent needs kotlinx-coroutines 1.11.0 or later, and finds 1.10.2, which the app's dependency " +
        "management sets, as Spring Boot 4.0 and 4.1 do: with Spring Boot, set its kotlin-coroutines.version " +
        "property to 1.11.0 or later."
    }

    "Spring Boot 4.0's versions are both refused, in one message naming both properties" {
      val refused =
        shouldThrow<IllegalStateException> {
          RuntimeLibraries.requireSupported(libraries(coroutines = "1.10.2", prometheus = "1.4.3"))
        }

      refused.message shouldBe
        "The agent needs kotlinx-coroutines 1.11.0 or later, and finds 1.10.2, which the app's dependency " +
        "management sets, as Spring Boot 4.0 and 4.1 do: with Spring Boot, set its kotlin-coroutines.version " +
        "property to 1.11.0 or later. The agent needs the Prometheus Java client 1.7.0 or later, and finds 1.4.3, " +
        "which the app's dependency management sets, as Spring Boot 4.0 and 4.1.0 do: with Spring Boot, set its " +
        "prometheus-client.version property to 1.7.0 or later."
    }

    "Spring Boot 4.1.0's Prometheus client is refused" {
      libraries(coroutines = "1.11.0", prometheus = "1.5.1").mapNotNull { it.problem() } shouldHaveSize 1
    }

    "a version that can't be read passes" {
      RuntimeLibraries.requireSupported(libraries(coroutines = null, prometheus = null))
      libraries(coroutines = null, prometheus = "1.4.3").first().problem().shouldBeNull()
    }

    "a version compares by its leading dotted numbers" {
      isAtLeast("1.11.0", "1.11.0").shouldBeTrue()
      isAtLeast("1.11.1", "1.11.0").shouldBeTrue()
      isAtLeast("1.12", "1.11.0").shouldBeTrue()
      isAtLeast("2.0.0", "1.11.0").shouldBeTrue()
      isAtLeast("1.11.0-RC2", "1.11.0").shouldBeTrue()
      isAtLeast("1.11", "1.11.0").shouldBeTrue()

      isAtLeast("1.10.2", "1.11.0").shouldBeFalse()
      isAtLeast("1.9.99", "1.11.0").shouldBeFalse()
      isAtLeast("1.10.2-RC", "1.11.0").shouldBeFalse()
      isAtLeast("0.11.0", "1.11.0").shouldBeFalse()
    }

    "a version with no leading number can't be compared, and passes" {
      isAtLeast("unknown", "1.11.0").shouldBeTrue()
      isAtLeast("", "1.11.0").shouldBeTrue()
    }
  }
}
