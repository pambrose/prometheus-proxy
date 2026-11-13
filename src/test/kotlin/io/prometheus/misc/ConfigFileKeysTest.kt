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
import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File

// HOCON ignores a key it doesn't know, so a misnamed key in a config the repo ships or its tests load does nothing, and
// says nothing: harness.conf set proxy.zipkin.enabled and agent.zipkin.enabled, where the keys are under internal, so
// the lines only looked like they kept tracing off (November #38). Every key such a config sets must be one that
// config/config.conf, the schema ConfigVals is generated from, declares.
class ConfigFileKeysTest : StringSpec() {
  private val schema by lazy { ConfigFactory.parseFile(File(REFERENCE_CONFIG)).root() }

  // A discovery targets file isn't a proxy or agent config: FileDiscoverySource reads it, and its paths entries take
  // the fields of an agent.pathConfigs entry.
  private val discoverySchema by lazy {
    ConfigFactory.empty().withValue(DISCOVERY_PATHS_KEY, schema.toConfig().getValue("agent.pathConfigs")).root()
  }

  // Every .conf in these directories is HOCON but the container suite's nginx config, which is nginx's own format.
  private val configFiles by lazy {
    val dirConfigs = CONFIG_DIRS.flatMap { dir -> confFiles(dir).filterNot { it.name == NGINX_CONFIG } }
    (dirConfigs + File(BUNDLED_DEFAULTS)).sortedBy { it.invariantSeparatorsPath }
  }

  private fun confFiles(dir: String) = File(dir).listFiles { file -> file.extension == "conf" }.orEmpty().toList()

  // The settings [value] gives under [path] that [schema] doesn't declare, or gives with another shape, such as a value
  // where the schema has an object. tscfg describes a list's entries by its schema's one element: an object of an
  // entry's fields, as for proxy.auth and agent.pathConfigs, or a type name, such as String for a list of strings.
  private fun undeclared(
    path: String,
    value: ConfigValue,
    schema: ConfigValue?,
  ): List<String> =
    when {
      schema == null -> {
        listOf(path)
      }

      value is ConfigObject && schema is ConfigObject -> {
        value.flatMap { (key, child) -> undeclared(if (path.isEmpty()) key else "$path.$key", child, schema[key]) }
      }

      value is ConfigList && schema is ConfigList -> {
        value.flatMapIndexed { i, entry -> undeclared("$path[$i]", entry, schema.firstOrNull()) }
      }

      value is ConfigObject || value is ConfigList || schema is ConfigObject || schema is ConfigList -> {
        listOf(path)
      }

      else -> {
        emptyList()
      }
    }

  // The settings [text] gives that the schema doesn't declare.
  private fun undeclaredIn(text: String) = undeclared("", ConfigFactory.parseString(text).root(), schema)

  // The test sources, but this spec, whose own check holds a -D key that doesn't exist.
  private val testSources by lazy {
    File(TEST_SOURCES)
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name != SELF }
      .sortedBy { it.invariantSeparatorsPath }
      .toList()
  }

  // The keys of the proxy and agent -D settings [source] passes in a string literal, such as
  // "-Dagent.proxy.port=$port". A key built at run time, as "-Dproxy.internal.$key=-1" and
  // "-D$side.internal.zipkin.enabled=true" are, isn't seen: the pattern takes only a key spelled out in full up to its
  // '='.
  private fun dSettingKeys(source: String) = D_SETTING.findAll(source).map { it.groupValues[1] }.toSet()

  init {
    "every setting in a config the repo ships or its tests load should be declared in config/config.conf" {
      configFiles.map { it.invariantSeparatorsPath } shouldContain HARNESS_CONFIG

      configFiles
        .associate { file ->
          val name = file.invariantSeparatorsPath
          // Includes are read relative to the file, as web-ui-hosts.conf's of web-ui.conf is, and simple.conf's
          // substitution is resolved as the agent resolves it with PROXY_HOSTNAME unset.
          val config = ConfigFactory.parseFile(file).resolve(ConfigResolveOptions.noSystem()).root()
          val fileSchema = if (config.containsKey(DISCOVERY_PATHS_KEY)) discoverySchema else schema
          name to undeclared("", config, fileSchema).filterNot { it in NOT_SETTINGS[name].orEmpty() }
        }.filterValues { it.isNotEmpty() } shouldBe emptyMap()
    }

    // The check is only as good as its walk: a misplaced key, a field a list's entries don't take, and a value where
    // the schema has an object must each be found, and well-formed entries of a list of objects or strings must not.
    "the check should find a setting the schema doesn't declare, in an object or in a list's entries" {
      undeclaredIn("proxy.zipkin.enabled = false\nagent.internal.zipkin.enabled = false") shouldBe
        listOf("proxy.zipkin")
      undeclaredIn("""proxy.auth = [{ name = a, token = t, paths = ["a_*"], path = "b_*" }]""") shouldBe
        listOf("proxy.auth[0].path")
      undeclaredIn("""agent.pathConfigs = [{ name = a, path = a, url = "http://a", label = "{}" }]""") shouldBe
        listOf("agent.pathConfigs[0].label")
      undeclaredIn("proxy.metrics = true") shouldBe listOf("proxy.metrics")
      undeclaredIn("""agent.proxy.endpoints = ["a:1", "b"]""") shouldBe emptyList()
      undeclaredIn("""agent.filters = [{ path = a, metricNameAllow = [], metricNameDeny = ["go_.*"] }]""") shouldBe
        emptyList()
    }

    // A -D setting is merged into the config as a file's is, so a misnamed key does nothing just the same, and the
    // check above, which reads files, can't see one. TestUtils.startAgent passed -Dagent.chunkContentSizeBytes, which
    // AgentOptions derives from agent.chunkContentSizeKbs, so the seven harness specs that meant to chunk small ran at
    // the default 32 KB (November #41).
    "every -D setting a test passes should be declared in config/config.conf" {
      val keysByFile = testSources.associate { it.invariantSeparatorsPath to dSettingKeys(it.readText()) }

      // The scan finds the keys TestUtils passes, so a pattern that matched nothing would fail here, not pass.
      keysByFile[TEST_UTILS].orEmpty() shouldContain "agent.proxy.port"

      keysByFile
        .mapValues { (_, keys) -> keys.filter { key -> undeclaredIn("$key = x").isNotEmpty() } }
        .filterValues { it.isNotEmpty() } shouldBe emptyMap()
    }

    "the -D scan should find a key spelled out in a string literal, and skip one built at run time or in a comment" {
      val source =
        listOf(
          "add(\"-Dagent.chunkContentSizeBytes=\$size\")",
          "proxyOptions([\"-Dproxy.internal.\$key=-1\"])",
          "add(\"-D\$side.internal.zipkin.enabled=true\")",
          "// passes -Dagent.admin.port=1",
        ).joinToString("\n")

      dSettingKeys(source) shouldBe setOf("agent.chunkContentSizeBytes")
      undeclaredIn("agent.chunkContentSizeBytes = x") shouldBe listOf("agent.chunkContentSizeBytes")
      undeclaredIn("agent.chunkContentSizeKbs = x") shouldBe emptyList()
    }
  }

  companion object {
    private const val REFERENCE_CONFIG = "config/config.conf"
    private const val HARNESS_CONFIG = "config/test-configs/harness.conf"
    private const val BUNDLED_DEFAULTS = "src/main/resources/reference.conf"
    private const val NGINX_CONFIG = "nginx-tls.conf"
    private const val DISCOVERY_PATHS_KEY = "paths"
    private val CONFIG_DIRS =
      listOf("config/test-configs", "examples", "testing", "etc/compose", "src/test/resources/containers")

    private const val TEST_SOURCES = "src/test/kotlin"
    private const val TEST_UTILS = "src/test/kotlin/io/prometheus/harness/support/TestUtils.kt"
    private const val SELF = "ConfigFileKeysTest.kt"

    // A proxy or agent -D setting in a string literal: the quote, then the key, made only of the characters a key
    // spelled out in full has, then its '='.
    private val D_SETTING = Regex("\"-D((?:proxy|agent)\\.[A-Za-z0-9_.]+)=")

    // Top-level keys that hold no setting: simple.conf's proxyMetricsHost only feeds a substitution in a pathConfigs
    // url, and neither the proxy nor the agent reads it.
    private val NOT_SETTINGS = mapOf("examples/simple.conf" to setOf("proxyMetricsHost"))
  }
}
