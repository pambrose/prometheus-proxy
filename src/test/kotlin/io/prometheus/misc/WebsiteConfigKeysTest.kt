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
import com.typesafe.config.ConfigValueType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

// HOCON doesn't reject a key it doesn't know, so an operator who copies a misnamed key from the website gets no error,
// and the setting silently keeps its default. Every proxy. and agent. key the pages name must be in the reference
// config, which declares them all with their defaults, and a default the pages give must be the one it declares.
class WebsiteConfigKeysTest : StringSpec() {
  private val reference by lazy { ConfigFactory.parseFile(File(REFERENCE_CONFIG)) }

  // The config key an inline code span or a table cell names, if it names one, alone or with a value assigned to it.
  private fun keyNamedIn(code: String): String? = CONFIG_KEY.matchEntire(code.trim())?.groupValues?.get(1)

  // Each key a table row names, with the row's cell under a Default column, for the tables that have one. A blank
  // or quoted default is given as the string it stands for.
  private fun documentedDefaults(prose: String): List<Pair<String, String>> {
    val defaults = mutableListOf<Pair<String, String>>()
    var header: List<String>? = null
    for (line in prose.lines()) {
      val columns = header
      val cells = line.trim().removeSurrounding("|").split("|").map { it.trim() }
      when {
        !line.startsWith("|") -> {
          header = null
        }

        columns == null -> {
          header = cells
        }

        else -> {
          val column = columns.indexOf("Default")
          val key =
            cells.firstNotNullOfOrNull { cell ->
              if (cell.length > 1 && cell.startsWith("`") && cell.endsWith("`")) keyNamedIn(cell.trim('`')) else null
            }
          if (key != null && column in cells.indices)
            defaults += key to cells[column].removeSurrounding("\"")
        }
      }
    }
    return defaults
  }

  // Whether the reference config declares [default] for [key]. Lists and objects have no one default to give, and a
  // tscfg spec such as "long | -1" leaves the setting to gRPC's own default, which the pages give instead.
  private fun declares(
    key: String,
    default: String,
  ): Boolean {
    val value = reference.getValue(key)
    return when (value.valueType()) {
      ConfigValueType.NUMBER, ConfigValueType.BOOLEAN -> value.unwrapped().toString() == default
      ConfigValueType.STRING -> (value.unwrapped() as String).let { it.matches(TSCFG_SPEC) || it == default }
      else -> true
    }
  }

  init {
    "every proxy and agent config key named on the website should be in config/config.conf" {
      val named =
        WebsitePages.inlineCode.mapValues { (page, code) ->
          code.mapNotNull(::keyNamedIn).filterNot { it in NOT_KEYS + NOT_KEYS_ON_PAGE[page].orEmpty() }
        }
      named.values.flatten().shouldNotBeEmpty()
      named
        .mapValues { (_, keys) -> keys.filterNot(reference::hasPath).distinct() }
        .filterValues { it.isNotEmpty() } shouldBe emptyMap()
    }

    // A key the reference config doesn't declare is left to the check above.
    "every proxy and agent config key default in the website's tables should be the one config/config.conf has" {
      val defaults =
        WebsitePages.prose.mapValues { (_, prose) ->
          documentedDefaults(prose).filter { (key, _) -> reference.hasPath(key) && key !in NOT_CONFIG_DEFAULTS }
        }
      defaults.values.flatten().shouldNotBeEmpty()
      defaults
        .mapValues { (_, rows) -> rows.filterNot { (key, default) -> declares(key, default) } }
        .filterValues { it.isNotEmpty() } shouldBe emptyMap()
    }
  }

  companion object {
    private const val REFERENCE_CONFIG = "config/config.conf"

    // A dotted proxy. or agent. path, optionally followed by "= value".
    private val CONFIG_KEY = Regex("""((?:proxy|agent)(?:\.[A-Za-z][A-Za-z0-9]*)+)(?:\s*=.*)?""")
    private val TSCFG_SPEC = Regex("""\w+ \| .*""")

    // Spans shaped like a key that aren't one, on any page: agent.conf is the file the pages have you write the agent's
    // config in.
    private val NOT_KEYS = setOf("agent.conf")

    // Spans shaped like a key that aren't one, on the page named. Getting Started's Helm tab sets the prometheus-agent
    // chart's proxy.hostname value (charts/prometheus-agent/values.yaml), which the chart passes to the agent as
    // PROXY_HOSTNAME; the agent's own key is agent.proxy.hostname.
    private val NOT_KEYS_ON_PAGE = mapOf("getting-started.md" to setOf("proxy.hostname"))

    // Keys whose documented default is the effective one rather than the declared one: an empty logLevel leaves the
    // level logback.xml sets, info, which the CLI reference gives.
    private val NOT_CONFIG_DEFAULTS = setOf("proxy.logLevel", "agent.logLevel")
  }
}
