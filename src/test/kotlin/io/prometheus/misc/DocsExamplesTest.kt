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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.prometheus.agent.discovery.FileDiscoverySource
import io.prometheus.common.Utils.labelsProblem
import io.prometheus.common.agentOptions
import io.prometheus.common.proxyOptions
import java.io.File

// What a new user runs first: the example configs, which the Docker quick start fetches from master, and the docker
// run commands the docs give (November #27 and #28).
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
    listOf(File(README), File(LLMS_TXT)) +
      File(WEBSITE_DOCS_DIR).walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.path } +
      File(WEBSITE_SNIPPETS_DIR).listFiles { file -> file.extension == "txt" }.orEmpty().sortedBy { it.name }

  private fun String.hasFlag(flag: String) = split(WHITESPACE).any { it == flag || it.startsWith("$flag=") }

  companion object {
    private const val EXAMPLES_DIR = "examples"
    private const val SIMPLE_CONF = "$EXAMPLES_DIR/simple.conf"
    private const val DISCOVERY_PATHS_KEY = "paths"
    private const val README = "README.md"
    private const val LLMS_TXT = "llms.txt"
    private const val WEBSITE_DOCS_DIR = "website/prometheus-proxy/docs"
    private const val WEBSITE_SNIPPETS_DIR = "src/test/kotlin/website"

    // A line that ends in \ continues on the next, as the shell reads a command.
    private val CONTINUATION = Regex("""\\[ \t]*\r?\n""")

    // A command line, in a code block or a tab, that runs a container.
    private val DOCKER_RUN = Regex("""^\s*(?:\$\s+)?docker\s+run\s""")
    private val WHITESPACE = Regex("""\s+""")
  }
}
