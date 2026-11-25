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
import com.typesafe.config.ConfigValueType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.ConfigVals
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

  // Every .conf in these directories is HOCON. nginx's configs, in nginx's own format, live in directories of their
  // own, which the scan, not descending into subdirectories, doesn't reach: the container suite's in containers/nginx,
  // and the nginx demo's in demos/nginx/nginx.
  private val configFiles by lazy {
    (CONFIG_DIRS.flatMap(::confFiles) + File(BUNDLED_DEFAULTS)).sortedBy { it.invariantSeparatorsPath }
  }

  private fun confFiles(dir: String) = File(dir).listFiles { file -> file.extension == "conf" }.orEmpty().toList()

  // The configs a user runs, as opposed to ones only the tests load.
  private val shippedConfigs by lazy {
    SHIPPED_CONFIG_DIRS.flatMap(::confFiles).sortedBy { it.invariantSeparatorsPath }
  }

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

  // The literal key of every setting() call in [source], setting("http.clientTimeoutSecs", 30) or setting(key = …), in
  // Kotlin, Java, or a page's prose. A key built at run time isn't seen.
  private fun settingKeys(source: String) = SETTING_CALL.findAll(source).map { it.groupValues[1] }.toSet()

  // Whether the schema declares [key] under agent, as setting() takes it: a value, or a list, which setting() takes
  // whole, as setting("proxy.endpoints", listOf(…)) does.
  private fun settingDeclared(key: String): Boolean =
    undeclaredIn("agent.$key = x").isEmpty() ||
      runCatching { schema.toConfig().getValue("agent.$key").valueType() == ConfigValueType.LIST }.getOrDefault(false)

  // Where code or a page can call setting(): the test sources (the docs site's snippet files among them), the main
  // sources' KDoc, both subprojects', the demos' apps, and the docs readers copy from.
  private val settingSources by lazy {
    (
      SETTING_SOURCE_DIRS.flatMap { dir -> File(dir).walkTopDown().toList() } +
        markdownDocs + File("README.md") + File("llms.txt")
    ).filter { it.isFile && it.extension in setOf("kt", "java", "txt", "md") && it.name != SELF }
      .distinct()
      .sortedBy { it.invariantSeparatorsPath }
  }

  // The Markdown readers copy configs from: the docs site's pages, the maintainer docs, and the demos' READMEs. Not the
  // code reviews, which quote what they find.
  private val markdownDocs by lazy {
    (
      File(SITE_DOCS).walkTopDown().toList() +
        File("docs").listFiles().orEmpty() +
        File("demos").listFiles().orEmpty().map { File(it, "README.md") }
    ).filter { it.isFile && it.extension == "md" && !it.name.startsWith("CODE_REVIEW_") }
  }

  // Each fenced HOCON block in [text], without the indentation of a block in a tab or an admonition, and with the
  // snippet lines the docs site replaces with a file, or a section of one, replaced as it replaces them.
  private fun hoconBlocks(text: String): List<String> =
    HOCON_BLOCK.findAll(text).map { match ->
      val indent = match.groupValues[1]
      match.groupValues[2].lines().joinToString("\n") { line ->
        val unindented = line.removePrefix(indent)
        SNIPPET_LINE.matchEntire(unindented.trim())?.let { snippet(it.groupValues[1]) } ?: unindented
      }
    }.toList()

  // A snippet's text as the docs site includes it (zensical.toml's pymdownx.snippets): a file found under one of its
  // base paths, whole or only the lines between its "--8<-- [start:name]" and "--8<-- [end:name]" markers, without any
  // marker line.
  private fun snippet(spec: String): String {
    val name = spec.substringBefore(':')
    val section = spec.substringAfter(':', "")
    val file = SNIPPET_BASE_PATHS.map { File(it, name) }.firstOrNull { it.isFile }
    requireNotNull(file) { "No snippet file $name under $SNIPPET_BASE_PATHS" }
    val lines = file.readLines()
    val selected =
      if (section.isEmpty()) {
        lines
      } else {
        val start = lines.indexOfFirst { "--8<-- [start:$section]" in it }
        val end = lines.indexOfFirst { "--8<-- [end:$section]" in it }
        require(start in 0..<end) { "No section $section in ${file.path}" }
        lines.subList(start + 1, end)
      }
    return selected.filterNot { SNIPPET_MARKER.containsMatchIn(it) }.joinToString("\n")
  }

  // The settings a docs block gives that the schema doesn't declare. A block is a proxy or agent config, a discovery
  // targets file, or a lone entry of a list of objects, such as an agent.filters entry, checked against the list it
  // fits best. Top-level keys that hold no setting are left out.
  private fun undeclaredInBlock(block: String): List<String> {
    val config = ConfigFactory.parseString(block).root()
    val roots = config.keys - NOT_SETTING_ROOTS
    return when {
      config.containsKey(DISCOVERY_PATHS_KEY) -> {
        undeclared("", config, discoverySchema)
      }

      roots.any { it == "proxy" || it == "agent" } || roots.isEmpty() -> {
        undeclared("", NOT_SETTING_ROOTS.fold(config) { obj, key -> obj.withoutKey(key) }, schema)
      }

      else -> {
        listEntrySchemas.map { (list, entry) -> undeclared(list, config, entry) }.minBy { it.size }
      }
    }
  }

  // Each list of objects the schema declares, by its path, with the object its entries take.
  private val listEntrySchemas by lazy {
    listOf("proxy.auth", "agent.pathConfigs", "agent.filters").associateWith { path ->
      (schema.toConfig().getList(path).first() as ConfigObject)
    }
  }

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

    // Declared keys aren't enough: a list entry's fields have no defaults, so an agent.filters entry without
    // metricNameAllow failed ConfigVals at startup, and the demo's agent-east, started on such a file, restarted in a
    // loop. Every config a user runs must load as BaseOptions loads it: the file over the bundled defaults, resolved.
    "every config the repo ships should load as the proxy and agent load it" {
      shippedConfigs.shouldNotBeEmpty()

      shippedConfigs
        .associate { file ->
          val load =
            runCatching { ConfigVals(ConfigFactory.parseFile(file).withFallback(ConfigFactory.load()).resolve()) }
          file.invariantSeparatorsPath to load.exceptionOrNull()?.message
        }.filterValues { it != null } shouldBe emptyMap()
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
          $$"add(\"-Dagent.chunkContentSizeBytes=$size\")",
          $$"proxyOptions([\"-Dproxy.internal.$key=-1\"])",
          $$"add(\"-D$side.internal.zipkin.enabled=true\")",
          "// passes -Dagent.admin.port=1",
        ).joinToString("\n")

      dSettingKeys(source) shouldBe setOf("agent.chunkContentSizeBytes")
      undeclaredIn("agent.chunkContentSizeBytes = x") shouldBe listOf("agent.chunkContentSizeBytes")
      undeclaredIn("agent.chunkContentSizeKbs = x") shouldBe emptyList()
    }

    // EmbeddedAgentConfig.setting() takes a key relative to agent and doesn't check it, so a misnamed one does nothing,
    // as a misnamed key in a config file does. The starter's and the plugin's settings.<key> call it too.
    "every key a setting() call in the tests, the code, or the docs gives should be declared in config/config.conf" {
      val keysByFile = settingSources.associate { it.invariantSeparatorsPath to settingKeys(it.readText()) }
      keysByFile.values.flatten().toSet() shouldContainAll listOf("scrapeTimeoutSecs", "http.clientTimeoutSecs")

      keysByFile
        .mapValues { (file, keys) ->
          keys.filterNot { key -> key in NOT_SETTING_KEYS[file].orEmpty() || settingDeclared(key) }
        }.filterValues { it.isNotEmpty() } shouldBe emptyMap()
    }

    // The configs the docs show, the snippet files' sections they include among them, are what readers copy, and a
    // misnamed key in one does nothing just the same. A block that doesn't parse fails too.
    "every setting in a HOCON block of the docs should be declared in config/config.conf" {
      val blocks =
        (markdownDocs + File("README.md") + File("llms.txt"))
          .sortedBy { it.invariantSeparatorsPath }
          .flatMap { file -> hoconBlocks(file.readText()).map { file.invariantSeparatorsPath to it } }
      blocks.size shouldBeGreaterThan 50

      blocks
        .mapNotNull { (file, block) ->
          val problems = runCatching { undeclaredInBlock(block) }.getOrElse { listOf("doesn't parse: ${it.message}") }
          if (problems.isEmpty()) null else "$file: $problems in\n$block"
        } shouldBe emptyList()
    }

    "the setting() and docs block checks should find a misnamed key, and the snippet a block includes" {
      settingKeys("""setting("http.clientTimeout", 9)\n.setting(key = "scrapeTimeoutSecs", value = 1)""") shouldBe
        setOf("http.clientTimeout", "scrapeTimeoutSecs")
      settingKeys($$"setting(\"internal.$key\", 1)") shouldBe emptySet()

      settingDeclared("http.clientTimeoutSecs") shouldBe true
      settingDeclared("proxy.endpoints") shouldBe true
      settingDeclared("http.clientTimeout") shouldBe false
      settingDeclared("http") shouldBe false

      undeclaredInBlock("agent { scrapeTimeoutSecs = 5, http.clientTimeout = 9 }") shouldBe
        listOf("agent.http.clientTimeout")
      undeclaredInBlock("""paths = [{ name = a, path = a, url = "http://a", label = "{}" }]""") shouldBe
        listOf("paths[0].label")
      undeclaredInBlock("""{ path: a, metricNameAllow: [], metricNameDenied: [] }""") shouldBe
        listOf("agent.filters.metricNameDenied")
      undeclaredInBlock("proxyMetricsHost = localhost\nproxy.http.port = 8080") shouldBe emptyList()

      val included = hoconBlocks("```hocon\n--8<-- \"ConfigExamples.txt:proxy-grpc-config\"\n```").single()
      included shouldContain "proxy"
      included shouldNotContain "--8<--"
    }
  }

  companion object {
    private const val REFERENCE_CONFIG = "config/config.conf"
    private const val HARNESS_CONFIG = "config/test-configs/harness.conf"
    private const val BUNDLED_DEFAULTS = "src/main/resources/reference.conf"
    private const val DISCOVERY_PATHS_KEY = "paths"

    // Every demo's config directory, found as TestPortsTest finds the demos, so a new demo's configs are checked too
    private val DEMO_CONFIG_DIRS =
      File("demos")
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .map { demo -> "demos/${demo.name}/config" }
        .sorted()

    private val CONFIG_DIRS =
      listOf(
        "config/test-configs",
        "examples",
        "examples/cloud-proxy",
        "testing",
        "etc/compose",
        "src/test/resources/containers",
      ) + DEMO_CONFIG_DIRS

    private val SHIPPED_CONFIG_DIRS = listOf("examples", "examples/cloud-proxy", "etc/compose") + DEMO_CONFIG_DIRS

    private const val TEST_SOURCES = "src/test/kotlin"
    private const val TEST_UTILS = "src/test/kotlin/io/prometheus/harness/support/TestUtils.kt"
    private const val SELF = "ConfigFileKeysTest.kt"

    // A proxy or agent -D setting in a string literal: the quote, then the key, made only of the characters a key
    // spelled out in full has, then its '='.
    private val D_SETTING = Regex("\"-D((?:proxy|agent)\\.[A-Za-z0-9_.]+)=")

    // Top-level keys that hold no setting: simple.conf's proxyMetricsHost only feeds a substitution in a pathConfigs
    // url, and neither the proxy nor the agent reads it.
    private val NOT_SETTINGS = mapOf("examples/simple.conf" to setOf("proxyMetricsHost"))

    // A setting() call with its key spelled out: the call, then the key's string literal, named or not.
    private val SETTING_CALL = Regex("""\bsetting\(\s*(?:key\s*=\s*)?"([^"$]+)"""")

    private val SETTING_SOURCE_DIRS =
      listOf(
        "src/test",
        "src/main/kotlin",
        "ktor-plugin/src",
        "spring-boot-starter/src",
      ) + File("demos").listFiles().orEmpty().map { "demos/${it.name}/app/src" }

    // Keys that are wrong on purpose: toString() must leave out a secret whose key is in another case, as Spring Boot
    // gives a settings.<key> read from an environment variable.
    private val NOT_SETTING_KEYS =
      mapOf(
        "src/test/kotlin/io/prometheus/agent/EmbeddedAgentConfigTest.kt" to
          setOf("agenttoken", "tls.privatekeypem", "dashboard.PASSWORD"),
      )

    private const val SITE_DOCS = "website/prometheus-proxy/docs"

    // A fenced HOCON block, indented when it's in a tab or an admonition
    private val HOCON_BLOCK =
      Regex(
        """^([ \t]*)```[ \t]*hocon\b[^\n]*\n(.*?)^[ \t]*```""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
      )

    // A line the docs site replaces with a snippet file, or a section of one:
    // --8<-- "ConfigExamples.txt:proxy-grpc-config"
    private val SNIPPET_LINE = Regex("""--8<--\s+"([^"]+)"""")
    private val SNIPPET_MARKER = Regex("""--8<-- \[(start|end):""")

    // zensical.toml's pymdownx.snippets base_path, from the repository's root
    private val SNIPPET_BASE_PATHS = listOf("src/test/kotlin/website", "src/test/java/website", ".")

    // Top-level keys of a docs block that hold no setting of the proxy or agent: simple.conf's proxyMetricsHost
    // (above), and the Ktor plugin's prometheus-proxy section, which takes the Spring Boot starter's property names
    // (PrometheusProxyAgentConfigTest checks the plugin reads them).
    private val NOT_SETTING_ROOTS = setOf("proxyMetricsHost", "prometheus-proxy")
  }
}
