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

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.prometheus.agent.AgentMetrics
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.mockAgentForMetrics
import io.prometheus.common.mockProxyForMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.proxy.ProxyMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// The Grafana dashboards, the alert rules and the PromQL in the docs must query series the proxy and agent expose.
// A counter is exposed only with a _total suffix, so a query for the bare name matches nothing and a panel or alert
// silently shows no data.
class DashboardMetricNamesTest : StringSpec() {
  // The proxy's and agent's metrics, as a scrape of the default registry reports them.
  private val snapshots by lazy {
    PrometheusRegistry.defaultRegistry.clear()
    ProxyMetrics(mockProxyForMetrics())
    AgentMetrics(mockAgentForMetrics())
    PrometheusRegistry.defaultRegistry.scrape().toList()
  }

  // The series names those metrics expose, plus the up series Prometheus records for every scrape target.
  private val exposed by lazy {
    snapshots
      .flatMap { snapshot ->
        val name = snapshot.metadata.name
        when (snapshot) {
          is CounterSnapshot -> listOf("${name}_total")
          is HistogramSnapshot -> listOf("${name}_bucket", "${name}_count", "${name}_sum")
          else -> listOf(name)
        }
      }.toSet() + "up"
  }

  // Every "expr" value in a Grafana dashboard.
  private fun dashboardExprs(json: String): List<String> = exprs(json.toJsonElement())

  // Every selector (a metric name and its label matchers) in a dashboard's queries, for metrics named with prefix.
  private fun selectors(
    dashboard: String,
    prefix: String,
  ): List<String> =
    dashboardExprs(File(dashboard).readText())
      .map { it.replace(GROUPING, "") }
      .flatMap { expr -> SELECTOR.findAll(expr).map { it.value }.filter { it.startsWith(prefix) }.toList() }

  private fun exprs(element: JsonElement): List<String> =
    when (element) {
      is JsonObject -> {
        element.flatMap { (key, value) ->
          if (key == "expr" && value is JsonPrimitive) listOf(value.content) else exprs(value)
        }
      }

      is JsonArray -> {
        element.flatMap { exprs(it) }
      }

      else -> {
        emptyList()
      }
    }

  // The "expr" of every rule in a Prometheus rule file.
  private fun ruleExprs(yaml: String): List<String> =
    Yaml.default.parseToYamlNode(yaml)
      .field<YamlList>("groups")
      .items
      .flatMap { group -> group.field<YamlList>("rules").items }
      .map { rule -> rule.field<YamlScalar>("expr").content }

  private inline fun <reified T : YamlNode> YamlNode.field(key: String): T =
    requireNotNull((this as YamlMap).get<T>(key)) { "missing $key in $this" }

  // The metric names a PromQL expression selects. Label names appear only inside {...} matchers and grouping
  // clauses, so both are removed first; agent_name, for one, is a label, not a metric.
  private fun metricNames(promql: String): Set<String> =
    promql
      .replace(MATCHERS, "")
      .replace(GROUPING, "")
      .let { stripped -> METRIC_NAME.findAll(stripped).map { it.value }.toSet() }

  // Each file with the queries it holds, extracted from its text.
  private val sources: Map<String, (String) -> List<String>> =
    mapOf(
      PROXY_DASHBOARD to ::dashboardExprs,
      AGENTS_DASHBOARD to ::dashboardExprs,
      MONITORING_SNIPPETS to SNIPPET::captures,
      METRICS_DOC to PROMQL_BLOCK::captures,
      ALERT_RULES to ::ruleExprs,
    )

  init {
    sources.forEach { (file, extract) ->
      "every proxy and agent series queried in $file should be exposed" {
        val queries = extract(File(file).readText())
        queries.shouldNotBeEmpty()
        val names = queries.associateWith(::metricNames)
        // Every query names a proxy or agent series, so a query the extractor mangled fails here instead of passing
        // as a query with nothing to check.
        names.filterValues { it.isEmpty() }.keys.shouldBeEmpty()
        names.values.flatten().filterNot { it in exposed }.distinct().shouldBeEmpty()
      }
    }

    // With two proxies (or two environments) in one Prometheus, a proxy panel that ignores the pickers mixes them.
    "every proxy dashboard query should filter by the job and instance pickers" {
      selectors(PROXY_DASHBOARD, "proxy_")
        .filterNot { "job=~\"\$job\"" in it && "instance=~\"\$instance\"" in it }
        .shouldBeEmpty()
    }

    "every agents dashboard query should filter by the agent picker" {
      selectors(AGENTS_DASHBOARD, "agent_")
        .filterNot { "job=~\"\$agent\"" in it }
        .shouldBeEmpty()
    }

    // The exposed name is what dashboards and docs copy, so the source declares it too.
    "every counter should be declared with the _total name it is exposed as" {
      snapshots
        .filterIsInstance<CounterSnapshot>()
        .map { it.metadata.originalName }
        .filterNot { it.endsWith("_total") }
        .shouldBeEmpty()
    }
  }

  companion object {
    private const val PROXY_DASHBOARD = "grafana/prometheus-proxy.json"
    private const val AGENTS_DASHBOARD = "grafana/prometheus-agents.json"
    private const val MONITORING_SNIPPETS = "src/test/kotlin/website/MonitoringExamples.txt"
    private const val METRICS_DOC = "docs/metrics-and-grafana.md"
    private const val ALERT_RULES = "grafana/alerts.yml"
    private val MATCHERS = Regex("""\{[^}]*}""")
    private val GROUPING = Regex("""\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)""")
    private val METRIC_NAME = Regex("""\b(?:(?:proxy|agent)_[a-z_]+|up)\b""")

    // A metric name with its label matchers, if it has any.
    private val SELECTOR = Regex("""\b(?:proxy|agent)_[a-z_]+(?:\{[^}]*})?""")
    private val SNIPPET =
      Regex("""; --8<-- \[start:promql-[^\]]+]\n(.*?)\n; --8<-- \[end:""", RegexOption.DOT_MATCHES_ALL)
    private val PROMQL_BLOCK = Regex("""```promql\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
  }
}

// The first group of every match of this regex in [text].
private fun Regex.captures(text: String): List<String> = findAll(text).map { it.groupValues[1] }.toList()
