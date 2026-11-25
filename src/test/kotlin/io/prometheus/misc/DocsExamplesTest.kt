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

package io.prometheus.misc

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.prometheus.agent.discovery.FileDiscoverySource
import io.prometheus.common.AgentValues.labelsProblem
import io.prometheus.common.agentOptions
import io.prometheus.common.proxyOptions
import java.io.File

// What a new user runs first: the example configs, which the Docker quick start fetches at the release's tag, the
// docker run commands the docs give (November #27 and #28), and the ports the compose stacks publish.
class DocsExamplesTest : StringSpec() {
  private val exampleConfigs by lazy {
    File(EXAMPLES_DIR).listFiles { file -> file.extension == "conf" }.orEmpty().sortedBy { it.name }
  }

  // Each docker run command in the docs, with its \ continuations joined, and the file it's in.
  private val dockerRunCommands by lazy {
    docFiles().flatMap { file ->
      file
        .readText()
        .replace(CONTINUATION, " ")
        .lines()
        .filter { DOCKER_RUN.containsMatchIn(it) }
        .map { file.path to it.trim().replace(WHITESPACE, " ") }
    }
  }

  // Each port a compose file publishes, with the file it's in: the repo's own compose files, and the compose snippets
  // in the docs.
  private val composePorts by lazy {
    (composeFiles() + docFiles()).flatMap { file -> publishedPorts(file).map { file.path to it } }
  }

  init {
    // The proxy leaves labels it can't serve out of service discovery, and the agent and the proxy each warn about
    // them, so an example with a number for a label value starts with warnings and loses that label.
    "every example config should load with labels service discovery can serve" {
      exampleConfigs.shouldNotBeEmpty()

      exampleConfigs
        .flatMap { file ->
          labelsOf(file).mapNotNull { (name, labels) -> labelsProblem(labels)?.let { "${file.name} '$name': $it" } }
        }.distinct() shouldBe emptyList()
    }

    // In the Docker quick start, the agent's localhost is its own container, so it gets PROXY_HOSTNAME, the proxy
    // container's name, and simple.conf fetches the proxy's metrics from that host. On one host, without it, they're
    // on localhost.
    "simple.conf should fetch the proxy's metrics from PROXY_HOSTNAME when it's set, and from localhost otherwise" {
      proxyMetricsUrl(emptyMap()) shouldBe "http://localhost:8082/metrics"
      proxyMetricsUrl(mapOf("PROXY_HOSTNAME" to "prometheus-proxy")) shouldBe "http://prometheus-proxy:8082/metrics"
    }

    // Docker refuses a docker run with both, so a command that has them never starts its container.
    "no docker run command in the docs should combine --rm with --restart" {
      dockerRunCommands.shouldNotBeEmpty()

      dockerRunCommands
        .filter { (_, command) -> command.hasFlag("--rm") && command.hasFlag("--restart") }
        .map { (path, command) -> "$path: $command" } shouldBe emptyList()
    }

    // These stacks run on one machine, to try the proxy or a demo, and a port Docker publishes on every interface gets
    // past the host's firewall: the quick start's would give anyone who can reach the host the agents' gRPC port, with
    // no token, every path's metrics, and the admin endpoints (November 23 review, #27). The cloud-proxy example, which
    // publishes the agents' port on purpose, with a token and TLS, is the Cloud Platforms page's, and isn't read.
    "every port a compose file or a compose snippet in the docs publishes should be on 127.0.0.1" {
      composePorts.map { (path, _) -> path } shouldContainAll listOf(QUICK_START_COMPOSE, DOCKER_SNIPPETS)

      composePorts
        .filterNot { (_, entry) -> entry.startsWith(LOOPBACK) }
        .map { (path, entry) -> "$path: $entry" } shouldBe emptyList()
    }
  }

  // Each path's name and labels: a discovery targets file's entries, as the agent reads them, or a config's
  // pathConfigs, as the agent and the proxy load them. The agent goes first, since it throws on a file it can't load,
  // where the proxy would exit the test JVM.
  private fun labelsOf(file: File): List<Pair<String, String>> =
    if (ConfigFactory.parseFile(file).root().containsKey(DISCOVERY_PATHS_KEY))
      FileDiscoverySource(file.path).read().map { it.name to it.labels }
    else
      listOf(
        agentOptions(["--config", file.path], false).configVals,
        proxyOptions(["--config", file.path]).configVals,
      ).flatMap { configVals -> configVals.agent.pathConfigs.map { it.name to it.labels } }

  // simple.conf's proxy_metrics url, with [env] standing in for the environment.
  private fun proxyMetricsUrl(env: Map<String, String>): String =
    ConfigFactory
      .parseMap(env)
      .withFallback(ConfigFactory.parseFile(File(SIMPLE_CONF)))
      .resolve(ConfigResolveOptions.noSystem())
      .getConfigList("agent.pathConfigs")
      .single { it.getString("path") == "proxy_metrics" }
      .getString("url")

  private fun docFiles(): List<File> =
    buildList {
      addAll(listOf(File(README), File(LLMS_TXT)))
      addAll(File(WEBSITE_DOCS_DIR).walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.path })
      addAll(File(WEBSITE_SNIPPETS_DIR).listFiles { file -> file.extension == "txt" }.orEmpty().sortedBy { it.name })
    }

  private fun composeFiles(): List<File> =
    buildList {
      addAll(File(COMPOSE_DIR).listFiles { file -> file.extension == "yml" }.orEmpty().sortedBy { it.name })
      addAll(
        File(DEMOS_DIR)
          .listFiles { file -> file.isDirectory }
          .orEmpty()
          .map { File(it, DEMO_COMPOSE) }
          .filter { it.isFile }
          .sortedBy { it.path },
      )
    }

  // The entries of each block-style ports: list in [file], without their quotes and comments, that are in the short
  // syntax every compose file here uses, "[address:]host:container". A Kubernetes manifest's ports, which are maps,
  // aren't.
  private fun publishedPorts(file: File): List<String> =
    buildList {
      var listIndent = NOT_IN_LIST
      file
        .readLines()
        .filterNot { it.isBlank() || it.trim().startsWith("#") }
        .forEach { line ->
          val text = line.trim()
          val indent = line.length - line.trimStart().length
          // A list's entries may sit as deep as its key, or deeper; anything else that deep ends the list.
          when {
            text == "ports:" -> listIndent = indent
            listIndent == NOT_IN_LIST -> Unit
            text.startsWith("- ") && indent >= listIndent -> add(text.listEntry())
            indent <= listIndent -> listIndent = NOT_IN_LIST
          }
        }
    }.filter { PORT_ENTRY.matches(it) }

  // A YAML list entry's value, without its dash, comment, and quotes.
  private fun String.listEntry() =
    removePrefix("- ").substringBefore(" #").trim().removeSurrounding("\"").removeSurrounding("'")

  private fun String.hasFlag(flag: String) = split(WHITESPACE).any { it == flag || it.startsWith("$flag=") }

  companion object {
    private const val EXAMPLES_DIR = "examples"
    private const val SIMPLE_CONF = "$EXAMPLES_DIR/simple.conf"
    private const val DISCOVERY_PATHS_KEY = "paths"
    private const val README = "README.md"
    private const val LLMS_TXT = "llms.txt"
    private const val WEBSITE_DOCS_DIR = "website/prometheus-proxy/docs"
    private const val WEBSITE_SNIPPETS_DIR = "src/test/kotlin/website"
    private const val COMPOSE_DIR = "etc/compose"
    private const val QUICK_START_COMPOSE = "$COMPOSE_DIR/proxy.yml"
    private const val DOCKER_SNIPPETS = "$WEBSITE_SNIPPETS_DIR/DockerExamples.txt"
    private const val DEMOS_DIR = "demos"
    private const val DEMO_COMPOSE = "docker/compose.yml"
    private const val LOOPBACK = "127.0.0.1:"
    private const val NOT_IN_LIST = -1

    // A line that ends in \ continues on the next, as the shell reads a command.
    private val CONTINUATION = Regex("""\\[ \t]*\r?\n""")

    // A command line, in a code block or a tab, that runs a container.
    private val DOCKER_RUN = Regex("""^\s*(?:\$\s+)?docker\s+run\s""")
    private val WHITESPACE = Regex("""\s+""")

    // A compose ports entry in the short syntax: no spaces, and not a map.
    private val PORT_ENTRY = Regex("""[^\s{]\S*""")
  }
}
