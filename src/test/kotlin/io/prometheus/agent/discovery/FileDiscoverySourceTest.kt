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

package io.prometheus.agent.discovery

import com.typesafe.config.ConfigException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class FileDiscoverySourceTest : StringSpec() {
  private fun writeTemp(content: String): String {
    val file = File.createTempFile("discovery", ".conf")
    file.deleteOnExit()
    file.writeText(content)
    return file.absolutePath
  }

  init {
    "parses a valid HOCON discovery file (labels/name defaulted)" {
      val path = writeTemp(
        """
        paths = [
          { name = "a", path = "a_metrics", url = "http://a/m", labels = "{}" }
          { path = "b_metrics", url = "http://b/m" }
        ]
        """.trimIndent(),
      )

      val result = FileDiscoverySource(path).read()

      result.size shouldBe 2
      result[0] shouldBe DiscoveredPath("a", "a_metrics", "http://a/m", "{}")
      result[1].name shouldBe "b_metrics" // name defaults to path
      result[1].labels shouldBe "{}" // labels defaults to "{}"
    }

    "parses a valid JSON discovery file" {
      val path = writeTemp("""{ "paths": [ { "path": "a_metrics", "url": "http://a/m" } ] }""")

      val result = FileDiscoverySource(path).read()

      result.size shouldBe 1
      result[0].path shouldBe "a_metrics"
    }

    "a valid-but-empty paths list returns an empty list" {
      FileDiscoverySource(writeTemp("paths = []")).read().shouldBeEmpty()
    }

    "a file with no paths key returns an empty list" {
      FileDiscoverySource(writeTemp("{}")).read().shouldBeEmpty()
    }

    "a missing file throws (not treated as empty)" {
      shouldThrow<ConfigException> { FileDiscoverySource("/nonexistent/discovery-file.conf").read() }
    }

    "a malformed file throws" {
      shouldThrow<ConfigException> { FileDiscoverySource(writeTemp("paths = [ { path = ")).read() }
    }

    "an element missing a required field throws" {
      shouldThrow<ConfigException> { FileDiscoverySource(writeTemp("""paths = [ { name = "a" } ]""")).read() }
    }

    "an empty file path is rejected" {
      shouldThrow<IllegalArgumentException> { FileDiscoverySource("").read() }
    }

    // parseFile() returns an unresolved Config, so without an explicit resolve() any HOCON
    // substitution throws ConfigException.NotResolved on the first getString(). PathDiscoveryService
    // swallows that into a per-tick warning, so discovery would be silently dead for anyone using
    // the substitution syntax that BaseOptions already supports for the main agent/proxy configs.
    "resolves HOCON substitutions against the file's own keys" {
      val path = writeTemp(
        $$"""
        host = "app.internal"
        paths = [ { path = "a_metrics", url = "http://"${host}":9090/metrics" } ]
        """.trimIndent(),
      )

      val result = FileDiscoverySource(path).read()

      result.size shouldBe 1
      result[0].url shouldBe "http://app.internal:9090/metrics"
    }

    "resolves optional substitutions that fall back to a literal" {
      val path = writeTemp(
        $$"""
        port = 9090
        port = ${?DISCOVERY_TEST_PORT_UNSET}
        paths = [ { path = "a_metrics", url = "http://a:"${port}"/metrics" } ]
        """.trimIndent(),
      )

      val result = FileDiscoverySource(path).read()

      result[0].url shouldBe "http://a:9090/metrics"
    }
  }
}
