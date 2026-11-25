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

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.Parameter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.prometheus.agent.AgentOptions
import io.prometheus.common.BaseOptions
import io.prometheus.common.EnvVars
import io.prometheus.proxy.ProxyOptions
import java.io.File
import java.lang.reflect.Field

// The CLI Reference's tables are what an operator copies an option or an environment variable from, each row a setting:
// its option over its variable, its config key, and its default. The configuration pages repeat a row for each key they
// describe, linked to it. Nothing else ties a row to the code, so an option misspelled or put on the wrong row, a
// variable that doesn't exist, or a link to an anchor that's gone failed nothing (November 23 review, #66). The
// defaults the tables give are WebsiteConfigKeysTest's.
class CliReferenceTest : StringSpec() {
  // A setting as a table row gives it. [options] holds the option and its short forms, and [anchor] the row's
  // <span id>, the target of the links to it.
  private class Row(
    val role: String,
    val options: Set<String>,
    val variable: String?,
    val anchor: String?,
    val key: String?,
    val default: String,
  ) {
    override fun toString() = "$role: ${options.ifEmpty { null } ?: variable ?: key}"
  }

  // The proxy's or the agent's options: the @Parameter fields JCommander reads, the role's own and BaseOptions', and
  // the variables the role's options read, as the sources that resolve them import them.
  private class Role(
    val name: String,
    val optionsClass: Class<out BaseOptions>,
    val build: (Array<String>) -> BaseOptions,
  ) {
    val classes = listOf(optionsClass, BaseOptions::class.java)

    val parameters: List<Field> =
      classes.flatMap { it.declaredFields.toList() }.filter { optionNames(it).isNotEmpty() }

    private val rawSources = classes.map { File("$MAIN_SOURCES/${it.name.replace('.', '/')}.kt").readText() }

    // The sources without their comments, so a KDoc that names a variable or a field pairs nothing
    val sources = rawSources.map(::withoutComments)

    val variables: Set<String> =
      rawSources.flatMap { source -> ENV_VAR_IMPORT.findAll(source).map { it.groupValues[1] } }.toSet()

    fun parameterFor(options: Set<String>) = parameters.firstOrNull { optionNames(it) == options }
  }

  private val roles =
    listOf(
      // exitOnMissingConfig = false builds an embedded agent's options, so a value it rejects throws rather than exits.
      Role("proxy", ProxyOptions::class.java) { args -> ProxyOptions(args) },
      Role("agent", AgentOptions::class.java) { args -> AgentOptions(args, false) },
    )

  private fun role(name: String) = roles.single { it.name == name }

  private val cliRows by lazy { cliReferenceRows(File(CLI_REFERENCE).readText()) }

  private val cliRowsByKey by lazy { cliRows.filter { it.key != null }.associateBy { "${it.role}:${it.key}" } }

  // The options' public state once a role's options are built from [args], or why they couldn't be: each @Parameter
  // field, which holds its resolved value, but -D's own.
  private fun outcome(
    role: Role,
    args: Array<String>,
  ): String =
    runCatching { role.build(args) }.fold(
      onSuccess = { options ->
        role.parameters
          .filterNot { it.isAnnotationPresent(DynamicParameter::class.java) }
          .associate { field -> field.name to field.apply { isAccessible = true }.get(options) }
          .toString()
      },
      onFailure = { "fails: ${it.message}" },
    )

  init {
    "the CLI Reference should have a row for each option and variable of the proxy and the agent" {
      roles.forEach { role -> cliRows.count { it.role == role.name } shouldBeGreaterThan 30 }
      cliRows.map { it.toString() } shouldContainAll listOf("proxy: [--port, -p]", "agent: [--chunk]")
    }

    // JCommander takes an option by any of its @Parameter names, so a row's names must be one field's, all of them.
    "each CLI Reference row's options should be one option's names, and every option should have a row" {
      roles.forEach { role ->
        val rows = cliRows.filter { it.role == role.name && it.options.isNotEmpty() }
        val parameters = role.parameters.map(::optionNames)

        rows.filter { role.parameterFor(it.options) == null }.map { it.toString() } shouldBe emptyList()
        parameters.filterNot { names -> rows.any { it.options == names } } shouldBe emptyList()
      }
    }

    // A variable the options don't read does nothing, and one they read with no row can't be found.
    "each CLI Reference row's variable should be one its options read, and each they read should have a row" {
      roles.forEach { role ->
        val variables = cliRows.filter { it.role == role.name }.mapNotNull { it.variable }

        variables.filterNot { it in role.variables }.map { "${role.name}: $it" } shouldBe emptyList()
        role.variables.filterNot { it in variables }.map { "${role.name}: $it" } shouldBe emptyList()
      }

      EnvVars.entries.map { it.name }.filterNot { name -> roles.any { name in it.variables } } shouldBe emptyList()
    }

    // The configuration pages link to a row by its key (../cli-reference.md#agent.scrapeTimeoutSecs).
    "each CLI Reference row's anchor should be its key" {
      cliRows.filter { it.anchor != it.key }.map { "$it: #${it.anchor} for ${it.key}" } shouldBe emptyList()
    }

    // The row pairs its option with its key: given on the command line, the option sets what -D sets for the key. A
    // flag sets true. Each option is given a value every option of its type takes, or one both reject alike, such as an
    // invalid log level, which fails before it changes the JVM's logging. A variable set in this environment would
    // override -D but not the option, so its row is left out.
    "each option a CLI Reference row gives should set the row's key, as -D does" {
      val paired = cliRows.filter { it.key != null && it.options.isNotEmpty() }
      paired.size shouldBeGreaterThan 50

      paired
        .filterNot { row -> row.variable != null && System.getenv(row.variable) != null }
        .mapNotNull { row ->
          val role = role(row.role)
          val field = role.parameterFor(row.options) ?: return@mapNotNull "$row: no such option"
          val option = optionNames(field).first()
          val value = valueFor(field.type)
          val viaOption = if (value == null) arrayOf(option) else arrayOf(option, value)
          val viaKey = arrayOf("-D${row.key}=${value ?: "true"}")
          val given = outcome(role, viaOption)
          val set = outcome(role, viaKey)
          "$row (${row.key}): ${viaOption.toList()} gives\n$given\nbut ${viaKey.toList()}\n$set".takeIf { given != set }
        } shouldBe emptyList()
    }

    // A variable can't be set in-process (EnvVars reads System.getenv), so its pairing is read from the options'
    // source: the call that reads it also takes its row's option's field, as resolveIntOption(adminPort, ADMIN_PORT, …)
    // does, or, for a variable with no option, names its key's last part, as
    // DASHBOARD_USERNAME.getEnv(dashboard.username) does.
    "each variable a CLI Reference row gives should be read with its row's option, or for its key, in source" {
      val paired = cliRows.filter { it.key != null && it.variable != null }
      paired.size shouldBeGreaterThan 50

      paired.filterNot { row ->
        val role = role(row.role)
        val variable = requireNotNull(row.variable)
        val partner =
          role.parameterFor(row.options)?.name ?: requireNotNull(row.key).substringAfterLast('.')
        role.sources.any { source -> callsReading(source, variable).any { word(partner).containsMatchIn(it) } }
      }.map { it.toString() } shouldBe emptyList()
    }

    // The CLI Reference is the one place a pairing is stated; a configuration page's row repeats it.
    "each configuration page row linked to the CLI Reference should give its key, option, variable, and default" {
      val linked =
        WebsitePages.prose.filterKeys { it != CLI_REFERENCE_PAGE }.flatMap { (page, prose) -> linkedRows(page, prose) }
      linked.size shouldBeGreaterThan 30

      linked
        .mapNotNull { (where, fragment, row) ->
          val cli = cliRowsByKey["${row.role}:${row.key}"]
          when {
            fragment != row.key -> {
              "$where: links to #$fragment"
            }

            cli == null -> {
              "$where: the CLI Reference has no row for it"
            }

            cli.options != row.options || cli.variable != row.variable || cli.default != row.default -> {
              "$where: ${row.options} ${row.variable} ${row.default}, where the CLI Reference has " +
                "${cli.options} ${cli.variable} ${cli.default}"
            }

            else -> {
              null
            }
          }
        } shouldBe emptyList()
    }

    // A row for a key the CLI Reference gives an option or a variable must say so, as the linked rows do.
    "each configuration page row for a key with an option or a variable should link to its CLI Reference row" {
      val unlinked =
        WebsitePages.prose.filterKeys { it.startsWith(CONFIGURATION_PAGES) }.flatMap { (page, prose) ->
          tableRows(prose).mapNotNull { (cells, _) ->
            PLAIN_KEY.matchEntire(cells.first())?.groupValues?.get(1)?.let { key -> "$page: $key" to key }
          }
        }
      unlinked.size shouldBeGreaterThan 10

      unlinked
        .filter { (_, key) ->
          val row = cliRowsByKey["${key.substringBefore('.')}:$key"]
          row != null && (row.options.isNotEmpty() || row.variable != null)
        }.map { it.first } shouldBe emptyList()
    }

    // A link to a row, or to a section, that has gone lands at the page's top; the site's build doesn't check them.
    "every link to the CLI Reference should land on an anchor it has" {
      val text = File(CLI_REFERENCE).readText()
      val anchors =
        SPAN_ID.findAll(text).map { it.groupValues[1] }.toSet() +
          HEADING.findAll(text).map { it.groups[2]?.value ?: slug(it.groupValues[1]) }
      val links =
        File(SITE_DOCS).walkTopDown().filter { it.isFile && it.extension == "md" }.flatMap { page ->
          val name = page.relativeTo(File(SITE_DOCS)).invariantSeparatorsPath
          CLI_REFERENCE_LINK.findAll(page.readText()).map { Pair("$name: #${it.groupValues[1]}", it.groupValues[1]) }
        }.toList()
      links.size shouldBeGreaterThan 50
      anchors shouldContain "proxy-grpc-options"

      links.filterNot { (_, anchor) -> anchor in anchors }.map { it.first } shouldBe emptyList()
    }

    "the checks should find an option on the wrong row, and the call that reads a variable" {
      val agent = role("agent")
      outcome(agent, arrayOf("--chunk", "1234")) shouldBe outcome(agent, arrayOf("-Dagent.chunkContentSizeKbs=1234"))
      outcome(agent, arrayOf("--chunk", "1234")) shouldNotBe outcome(agent, arrayOf("-Dagent.minGzipSizeBytes=1234"))
      outcome(agent, arrayOf("--log_level", "x")) shouldBe outcome(agent, arrayOf("-Dagent.logLevel=x"))

      val source =
        """
        // CACHE_SIZE is read with maxCacheSize
        maxCacheSize = resolveIntOption(maxCacheSize, CACHE_SIZE, http.maxSize)
        proxyHostname = spec(proxyHostname, System.getenv(PROXY_HOSTNAME.name), proxy)
        username = DASHBOARD_USERNAME.getEnv(dashboard.username)
        """.trimIndent()
      callsReading(withoutComments(source), "CACHE_SIZE") shouldBe listOf("maxCacheSize, CACHE_SIZE, http.maxSize")
      callsReading(source, "PROXY_HOSTNAME").any { "proxyHostname" in it } shouldBe true
      callsReading(source, "DASHBOARD_USERNAME") shouldBe listOf("dashboard.username")
    }
  }

  // The rows of the CLI Reference's settings tables, each with the role whose section it's in, "## Proxy Options" or
  // "## Agent Options".
  private fun cliReferenceRows(text: String): List<Row> =
    sections(text).flatMap { (role, section) ->
      tableRows(section).filter { (_, header) -> "Property" in header }.map { (cells, header) ->
        val first = cells.first()
        Row(
          role = role,
          options = optionsIn(first),
          variable = variableIn(first),
          anchor = SPAN_ID.find(first)?.groupValues?.get(1),
          key = CODE.find(cells[header.indexOf("Property")])?.groupValues?.get(1),
          default = cells[header.indexOf("Default")],
        )
      }
    }

  // The text of each role's section of [text], keyed by the role, each running to the next "## " heading.
  private fun sections(text: String): List<Pair<String, String>> {
    val sections = mutableListOf<Pair<String, StringBuilder>>()
    var current: StringBuilder? = null
    for (line in text.lines()) {
      if (line.startsWith("## ")) {
        val role = ROLE_HEADING.matchEntire(line)?.groupValues?.get(1)?.lowercase()
        current = role?.let { StringBuilder().also { text -> sections += it to text } }
      } else {
        current?.appendLine(line)
      }
    }
    return sections.map { (role, text) -> role to text.toString() }
  }

  // The rows of a page's tables that link a key to its CLI Reference row, with where each is and its link's fragment.
  private fun linkedRows(
    page: String,
    prose: String,
  ): List<Triple<String, String, Row>> =
    tableRows(prose).mapNotNull { (cells, header) ->
      val link = LINKED_KEY.find(cells.first()) ?: return@mapNotNull null
      val key = link.groupValues[1]
      val rest = cells.first().substring(link.range.last + 1)
      val defaultColumn = header.indexOf("Default")
      val row =
        Row(
          role = key.substringBefore('.'),
          options = optionsIn(rest),
          variable = variableIn(rest),
          anchor = null,
          key = key,
          default = if (defaultColumn in cells.indices) cells[defaultColumn] else "",
        )
      Triple("$page: $key", link.groupValues[2], row)
    }

  // Each data row of the Markdown tables in [text], as its cells, with its table's header.
  private fun tableRows(text: String): List<Pair<List<String>, List<String>>> {
    val rows = mutableListOf<Pair<List<String>, List<String>>>()
    var header: List<String>? = null
    for (line in text.lines()) {
      val cells = line.trim().removeSurrounding("|").split("|").map { it.trim() }
      val columns = header
      when {
        !line.startsWith("|") -> header = null
        columns == null -> header = cells
        cells.all { SEPARATOR_CELL.matches(it) } -> Unit
        else -> rows += cells to columns
      }
    }
    return rows
  }

  private fun optionsIn(cell: String) =
    CODE.findAll(cell).map { it.groupValues[1] }.filter { it.startsWith("-") }
      .flatMap { it.split(",").map(String::trim) }.toSet()

  private fun variableIn(cell: String) = CODE.findAll(cell).map { it.groupValues[1] }.firstOrNull(ENV_NAME::matches)

  // The value to give an option of [type], or null for a flag.
  private fun valueFor(type: Class<*>): String? =
    when (type) {
      Boolean::class.javaPrimitiveType -> null
      Int::class.javaPrimitiveType, Long::class.javaPrimitiveType -> "1234"
      else -> "example"
    }

  // A heading's anchor as the docs site makes one: lowercase, without punctuation, spaces as hyphens.
  private fun slug(heading: String) = heading.trim().lowercase().replace(PUNCTUATION, "").replace(' ', '-')

  companion object {
    private const val MAIN_SOURCES = "src/main/kotlin"
    private const val SITE_DOCS = "website/prometheus-proxy/docs"
    private const val CLI_REFERENCE_PAGE = "cli-reference.md"
    private const val CLI_REFERENCE = "$SITE_DOCS/$CLI_REFERENCE_PAGE"
    private const val CONFIGURATION_PAGES = "configuration/"

    private val ROLE_HEADING = Regex("""^## (Proxy|Agent) Options$""")
    private val SPAN_ID = Regex("""<span id="([^"]+)"></span>""")
    private val CODE = Regex("""`([^`]+)`""")
    private val ENV_NAME = Regex("""[A-Z][A-Z0-9_]+""")
    private val SEPARATOR_CELL = Regex(""":?-+:?""")
    private val ENV_VAR_IMPORT =
      Regex("""^import io\.prometheus\.common\.EnvVars\.([A-Z0-9_]+)$""", RegexOption.MULTILINE)

    // A cell that starts with a key linked to its CLI Reference row: [`agent.scrapeTimeoutSecs`](../cli-reference.md#…)
    private val LINKED_KEY = Regex("""^\[`([^`]+)`]\([./]*cli-reference\.md#([^)]+)\)""")

    // A cell that is a key alone
    private val PLAIN_KEY = Regex("""^`((?:proxy|agent)\.[A-Za-z0-9.]+)`$""")

    // A link to an anchor of the CLI Reference, from any page
    private val CLI_REFERENCE_LINK = Regex("""\]\([./]*cli-reference\.md#([^)]+)\)""")
    private val HEADING = Regex("""^#{2,6} (.+?)(?: \{ #([a-z0-9-]+) })?$""", RegexOption.MULTILINE)
    private val PUNCTUATION = Regex("""[^a-z0-9 _-]""")

    // A field's JCommander names, or none for a field that isn't an option
    private fun optionNames(field: Field): Set<String> =
      field.getAnnotation(Parameter::class.java)?.names?.toSet()
        ?: field.getAnnotation(DynamicParameter::class.java)?.names?.toSet()
        ?: emptySet()

    private fun word(name: String) = Regex("""\b${Regex.escape(name)}\b""")

    // The argument lists of the calls in [source] that read [variable]: those that take it as an argument, with no
    // other variable beside it, as a call that reads several, such as one that gathers settings, pairs none; and
    // [variable].getEnv(…), whose arguments are the default it falls back to.
    private fun callsReading(
      source: String,
      variable: String,
    ): List<String> =
      CALL.findAll(source).mapNotNull { call ->
        val open = call.range.last
        val close = closingParen(source, open) ?: return@mapNotNull null
        val args = source.substring(open + 1, close).trim()
        val receiver = call.groupValues[1]
        val variables = ENV_NAME_WORD.findAll(args).map { it.value }.filter { it in ENV_VAR_NAMES }.toSet()
        when {
          receiver == "$variable.getEnv" -> args
          word(variable).containsMatchIn(args) && variables.size <= 1 -> args
          else -> null
        }
      }.toList()

    private val CALL = Regex("""([A-Za-z_][A-Za-z0-9_.]*)\(""")
    private val ENV_NAME_WORD = Regex("""\b[A-Z][A-Z0-9_]+\b""")
    private val ENV_VAR_NAMES = EnvVars.entries.map { it.name }.toSet()

    // The index of the parenthesis that closes the one at [open], or null.
    private fun closingParen(
      source: String,
      open: Int,
    ): Int? {
      var depth = 0
      for (i in open until source.length) {
        when (source[i]) {
          '(' -> depth++
          ')' -> if (--depth == 0) return i
        }
      }
      return null
    }

    // [source] without its comments, // to the line's end and /* … */, outside string literals.
    private fun withoutComments(source: String) =
      STRING_OR_COMMENT.replace(source) { if (it.value.startsWith('"')) it.value else "" }

    private val STRING_OR_COMMENT = Regex(""""(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
  }
}
