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
import io.kotest.matchers.shouldBe
import io.prometheus.agent.AgentMetrics
import io.prometheus.common.MetricBuilders.SCRAPE_LATENCY_BUCKETS
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.mockAgentForMetrics
import io.prometheus.common.mockProxyForMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.proxy.ProxyFailure
import io.prometheus.proxy.ProxyHttpRoutes
import io.prometheus.proxy.ProxyMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// The Grafana dashboards, the alert rules and the PromQL in the docs must query series the proxy and agent expose, and
// the website must name them. A counter is exposed only with a _total suffix, so a query for the bare name matches
// nothing and a panel or alert silently shows no data.
class DashboardMetricNamesTest : StringSpec() {
  // The proxy's and agent's metrics, built before the spec and closed after it. Each joins the collectors every proxy
  // or agent in the JVM shares, so one left open would keep them registered, and counted, for the rest of the JVM.
  private val openMetrics = mutableListOf<AutoCloseable>()

  // The proxy's and agent's metrics, as a scrape of the default registry reports them.
  private val snapshots by lazy { PrometheusRegistry.defaultRegistry.scrape().toList() }

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

  // The series a page's prose may name: every exposed series, and a histogram's family name, which the pages use for
  // the histogram as a whole. A counter's bare name is not one, since it is exposed only with _total.
  private val documented by lazy {
    exposed + snapshots.filterIsInstance<HistogramSnapshot>().map { it.metadata.name }
  }

  // A name ending in * stands for the series that start with the rest of it, and must match at least one.
  private fun isDocumented(name: String) =
    if (name.endsWith("*")) documented.any { it.startsWith(name.removeSuffix("*")) } else name in documented

  // The proxy and agent series an inline code span names. Label matchers are removed first, so a type="..." value is
  // not read as a series.
  private fun seriesNamedIn(code: String): List<String> =
    PROSE_SERIES.findAll(code.replace(MATCHERS, "")).map { it.value }.filterNot { it in NOT_SERIES }.toList()

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

  // Each histogram a table row names, with the row's cell under a Buckets column, for the tables that have one.
  private fun bucketRanges(markdown: String): Map<String, String> {
    val ranges = mutableMapOf<String, String>()
    var header: List<String>? = null
    for (line in markdown.lines()) {
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
          val column = columns.indexOf("Buckets")
          if (column in cells.indices && cells.first().startsWith("`"))
            ranges[cells.first().trim('`')] = cells[column]
        }
      }
    }
    return ranges
  }

  // The bounds the "Latency buckets:" line in [text] lists, in seconds, or null when it has none. The metrics doc gives
  // them in seconds (`.005, ... 90` seconds), the website with units (5ms, ... 90s).
  private fun latencyBounds(text: String): List<Double>? =
    text
      .lines()
      .singleOrNull { it.startsWith(LATENCY_BOUNDS) }
      ?.substringAfter(LATENCY_BOUNDS)
      ?.substringBefore(" seconds")
      ?.replace("`", "")
      ?.split(",")
      ?.map(::seconds)

  // A bucket bound, such as 5ms, 2.5s, or a bare .005, in seconds.
  private fun seconds(bound: String): Double =
    bound.trim().let {
      if (it.endsWith("ms")) it.removeSuffix("ms").toDouble() / MILLIS_PER_SECOND else it.removeSuffix("s").toDouble()
    }

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
    beforeSpec {
      PrometheusRegistry.defaultRegistry.clear()
      openMetrics += ProxyMetrics(mockProxyForMetrics())
      openMetrics += AgentMetrics(mockAgentForMetrics())
    }

    // The shared collectors are unregistered only when the last proxy or agent holding them closes, so once this
    // spec's metrics are closed, none of the proxy's or agent's metrics is left in the registry it cleared.
    afterSpec {
      openMetrics.forEach { it.close() }
      openMetrics.clear()
      PrometheusRegistry.defaultRegistry
        .scrape { it.startsWith("proxy_") || it.startsWith("agent_") }
        .map { it.metadata.name }
        .shouldBeEmpty()
    }

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

    // The website names series in its tables and in the troubleshooting steps an operator turns into a query or an
    // alert, where a name that isn't exposed matches nothing, as silently as in a dashboard.
    "every proxy and agent series named on the website should be exposed" {
      val named = WebsitePages.inlineCode.mapValues { (_, code) -> code.flatMap(::seriesNamedIn) }
      named.values.flatten().shouldNotBeEmpty()
      named
        .mapValues { (_, names) -> names.filterNot(::isDocumented).distinct() }
        .filterValues { it.isNotEmpty() } shouldBe emptyMap()
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

    // The proxy's and agent's latency histograms are built with SCRAPE_LATENCY_BUCKETS, but the metrics doc kept giving
    // the agent's range as 5ms–10s after the agent moved to them (November #29).
    "the latency histograms' bucket ranges in $METRICS_DOC should span SCRAPE_LATENCY_BUCKETS" {
      val latencyHistograms =
        snapshots
          .filterIsInstance<HistogramSnapshot>()
          .map { it.metadata.name }
          .filter { it.endsWith(LATENCY_SUFFIX) }
      val ranges = bucketRanges(File(METRICS_DOC).readText()).filterKeys { it.endsWith(LATENCY_SUFFIX) }
      ranges.keys shouldBe latencyHistograms.toSet()
      ranges.values
        .map { range -> range.split("–").map(::seconds) }
        .distinct() shouldBe listOf(listOf(SCRAPE_LATENCY_BUCKETS.first(), SCRAPE_LATENCY_BUCKETS.last()))
    }

    "the latency bucket bounds $METRICS_DOC and the website's Monitoring page list should be SCRAPE_LATENCY_BUCKETS" {
      listOf(File(METRICS_DOC).readText(), WebsitePages.prose.getValue(MONITORING_PAGE))
        .map(::latencyBounds) shouldBe List(2) { SCRAPE_LATENCY_BUCKETS.toList() }
    }
  }

  companion object {
    private const val PROXY_DASHBOARD = "grafana/prometheus-proxy.json"
    private const val AGENTS_DASHBOARD = "grafana/prometheus-agents.json"
    private const val MONITORING_SNIPPETS = "src/test/kotlin/website/MonitoringExamples.txt"
    private const val METRICS_DOC = "docs/metrics-and-grafana.md"
    private const val ALERT_RULES = "grafana/alerts.yml"
    private const val MONITORING_PAGE = "monitoring.md"
    private const val LATENCY_SUFFIX = "_latency_seconds"
    private const val LATENCY_BOUNDS = "Latency buckets:"
    private const val MILLIS_PER_SECOND = 1_000.0
    private val MATCHERS = Regex("""\{[^}]*}""")
    private val GROUPING = Regex("""\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)""")
    private val METRIC_NAME = Regex("""\b(?:(?:proxy|agent)_[a-z_]+|up)\b""")

    // A metric name with its label matchers, if it has any.
    private val SELECTOR = Regex("""\b(?:proxy|agent)_[a-z_]+(?:\{[^}]*})?""")

    // A proxy or agent series name in prose, or a prefix of names ending in *. A word that only contains one, such as
    // the --agent_port flag or the proxy_service.proto file, is not a series.
    private val PROSE_SERIES = Regex("""(?<![\w./-])(?:proxy|agent)_[a-z0-9_]+\*?(?![\w.])""")

    // The words with a series' prefix that the pages give as label values and label names, not series: the scrape
    // outcomes the proxy records in proxy_scrape_requests_total's type label, taken from the code that records them,
    // and the label agent_scrape_request_latency_seconds carries the agent's name in.
    private val NOT_SERIES =
      ProxyFailure.entries.map { it.label }.toSet() +
        ProxyHttpRoutes.IN_FLIGHT_LIMIT_LABEL +
        ProxyHttpRoutes.BACKLOG_FULL_LABEL +
        "agent_name"

    private val SNIPPET =
      Regex("""; --8<-- \[start:promql-[^\]]+]\n(.*?)\n; --8<-- \[end:""", RegexOption.DOT_MATCHES_ALL)
    private val PROMQL_BLOCK = Regex("""```promql\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
  }
}

// The first group of every match of this regex in [text].
private fun Regex.captures(text: String): List<String> = findAll(text).map { it.groupValues[1] }.toList()
