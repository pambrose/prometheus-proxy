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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.lang.reflect.Modifier

class TestPortsTest : StringSpec() {
  init {
    // Specs that share a port collide only when their runs overlap, so a duplicate shows up as an intermittent
    // bind failure far from its cause. Checking the values here fails the build on a duplicate, next to the line
    // that added it.
    "every port in TestPorts should be distinct" {
      val ports = testPorts()

      ports.size shouldBeGreaterThan 50
      ports.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 } shouldBe emptyMap()
    }

    // An outgoing connection takes its local port from the OS's ephemeral range -- 32768-60999 on Linux, 49152-65535
    // on macOS -- so another test's connection can hold a port a spec is about to bind. That failed CI twice with
    // BindException on 50440 and 50460, both harness gRPC ports. Keeping every bound port below 32768 is outside both
    // ranges. The product defaults, the default gRPC port among them, are exempt: they are asserted as values and used
    // inside containers, never bound on this machine.
    "no port in TestPorts should be in an ephemeral port range" {
      val ephemeral = testPorts().filterKeys { it !in NEVER_BOUND }.filterValues { it >= EPHEMERAL_RANGE_START }

      ephemeral shouldBe emptyMap()
    }

    // The demos publish their ports on this machine, so a spec that binds one while a demo runs fails to bind, or
    // sends its requests to the demo's container (the nginx demo's Prometheus on 9590 did that to a harness spec). The
    // demos' ports are documented and shown on the website, so the spec's port is the one that moves.
    "no port a spec binds should be one a demo publishes on this machine" {
      val ports = testPorts()
      ports.keys shouldContainAll NEVER_BOUND

      val published = demoHostPorts()
      published.filterKeys { it.endsWith(COMPOSE_FILE) }.values.forEach { it.shouldNotBeEmpty() }
      published.filterKeys { "/kind/" in it }.values.flatten().shouldNotBeEmpty()

      val bound = ports.filterKeys { it !in NEVER_BOUND }
      val collisions =
        published.flatMap { (file, hostPorts) ->
          hostPorts.flatMap { port ->
            bound.filterValues { it == port }.keys.map { "$file has host port $port, which TestPorts.$it binds" }
          }
        }

      collisions shouldBe emptyList()
    }

    // A run of every spec puts the harness specs in one JVM and the rest in another, at the same time (unitTest and
    // harnessTest in build.gradle.kts). The harness specs share fixed ports, so a spec elsewhere that started a proxy
    // and agent the harness's way, or bound one of its ports, would collide with whichever harness spec was running,
    // as an intermittent bind failure. The Lincheck specs are exempt: they run on their own (make lincheck-tests).
    "only a harness spec should start a proxy and agent the harness's way, or bind a harness port" {
      val harnessDir = File("src/test/kotlin/io/prometheus/harness")
      val exempt = setOf("TestPorts.kt", "TestPortsTest.kt")
      val outside =
        File("src/test")
          .walkTopDown()
          .filter { it.isFile && it.extension in setOf("kt", "java") }
          .filterNot { it.startsWith(harnessDir) || it.name in exempt || it.name.endsWith("LincheckTest.kt") }
          .filter { file -> file.readLines().filterNot { it.isComment() }.any { HARNESS_USE.containsMatchIn(it) } }
          .map { it.invariantSeparatorsPath }
          .toList()

      outside shouldBe emptyList()
    }
  }

  private fun String.isComment() = trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

  private fun testPorts(): Map<String, Int> =
    TestPorts::class.java.declaredFields
      .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
      .associate { it.name to it.getInt(null) }

  // Each demo's host ports, by the file that names them: its compose file's published ports, its kind clusters' host
  // ports, and the ports its demo.sh reaches on this machine, among them the kubernetes demo's kubectl port-forward,
  // which no config publishes. A port set by a variable counts as the variable's default.
  private fun demoHostPorts(): Map<String, Set<Int>> {
    val sources =
      File("demos")
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .flatMap { demo ->
          val kindConfigs = File(demo, "kind").listFiles { file -> file.extension == "yaml" }.orEmpty()
          listOf(File(demo, COMPOSE_FILE) to COMPOSE_PORT, File(demo, "demo.sh") to SCRIPT_PORT) +
            kindConfigs.map { it to KIND_HOST_PORT }
        }.filter { (file, _) -> file.isFile }

    return sources.associate { (file, pattern) ->
      val ports =
        file.readLines()
          .filterNot { it.trimStart().startsWith("#") }
          .flatMap { line -> pattern.findAll(line).map { it.hostPort() } }
          .toSet()
      file.invariantSeparatorsPath to ports
    }
  }

  // The first group that matched: a compose entry's host port is a variable's default or a number.
  private fun MatchResult.hostPort() = groupValues.drop(1).first { it.isNotEmpty() }.toInt()

  companion object {
    // The lower bound of Linux's default range; macOS's (49152) is higher still.
    private const val EPHEMERAL_RANGE_START = 32768

    // TestPorts' first group: the product's defaults and the container suite's fixed ports, which a spec asserts or
    // uses inside a container but never binds on this machine.
    private val NEVER_BOUND =
      setOf(
        "PROXY_HTTP_PORT",
        "PROXY_METRICS_PORT",
        "PROXY_ADMIN_PORT",
        "PROXY_DASHBOARD_PORT",
        "PROXY_AGENT_PORT",
        "AGENT_METRICS_PORT",
        "AGENT_ADMIN_PORT",
        "AGENT_DASHBOARD_PORT",
        "PROMETHEUS_PORT",
        "NGINX_PORT",
      )

    private const val COMPOSE_FILE = "docker/compose.yml"

    // A compose ports entry with a host port, "[address:]host:container", the host port a ${VAR:-default} or a number.
    private val COMPOSE_PORT = Regex("""^\s*-\s*"?(?:[\d.]+:)?(?:\$\{\w+:-(\d+)\}|(\d+)):\d+""")

    // A kind extraPortMappings entry's host port.
    private val KIND_HOST_PORT = Regex("""^\s*hostPort:\s*(\d+)""")

    // A demo script's port variable, with its default.
    private val SCRIPT_PORT = Regex("""\$\{\w+_PORT:-(\d+)\}""")

    // HarnessSetup and TestUtils' startProxy and startAgent bind the HARNESS_* ports unless told otherwise, and
    // HarnessConstants.PROXY_PORT is one of them.
    private val HARNESS_USE =
      Regex("""HarnessSetup|TestUtils\.start(Proxy|Agent)|HARNESS_[A-Z_]*PORT|HarnessConstants\.PROXY_PORT""")
  }
}
