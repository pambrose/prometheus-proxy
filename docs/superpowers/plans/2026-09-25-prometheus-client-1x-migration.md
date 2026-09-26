# Prometheus Java Client 1.x — prometheus-proxy Consumer Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move prometheus-proxy from common-utils 4.1.0 and the Prometheus Java client 0.16.0 (`io.prometheus:simpleclient`) to common-utils 5.0.0 and the 1.x client (`io.prometheus:prometheus-metrics-*` 1.9.0), keeping every proxy and agent series name and label unchanged apart from what the client itself changes.

**Architecture:** The proxy's metrics live in two classes, `ProxyMetrics` and `AgentMetrics`, built from common-utils' `PrometheusDsl` and `SamplerGaugeCollector` plus three direct `Histogram` uses. Swap the dependency, port those classes and their call sites (`labels` → `labelValues`, `Histogram.build()` → `PrometheusDsl.histogram {}`, `Histogram.Timer` → `Timer`, `setToCurrentTime()` → a small helper), pin the histograms to classic buckets so exposition and memory stay as they are, then diff the served `/metrics` before and after and document the differences.

**Tech Stack:** Kotlin 2.4.20 (JVM 17 target, tests on 25), Gradle Kotlin DSL + version catalog, Kotest `StringSpec` + MockK, Lincheck, Testcontainers, kotlinter, detekt, Kover.

**Spec:** Phase 10 ("Consumer follow-up") of `../common-utils/docs/superpowers/plans/2026-09-23-prometheus-client-1x-migration.md`, plus the 5.0.0 entry of `../common-utils/CHANGELOG.md` ("Migrating from 4.x: Prometheus Java client 1.x"), which records the final 5.0.0 API and exposition changes. Read both before starting.

## Global Constraints

- common-utils **5.0.0**; every `io.prometheus:prometheus-metrics-*` artifact at **1.9.0**, the version common-utils 5.0.0 uses. One catalog version entry, commented to stay in sync with common-utils.
- No `io.prometheus:simpleclient*` artifact may remain on any classpath (`runtimeClasspath`, `testRuntimeClasspath`).
- common-utils 5.0.0 is tagged but **not yet on Maven Central** (checked 2026-09-25: the POM URL returns 404). Develop against `mavenLocal()` restricted to `com.pambrose.common-utils`; that repository line must never be committed, and the PR cannot go green in CI until 5.0.0 is on Central.
- Series names, label names and bucket layouts of every `proxy_*` and `agent_*` metric stay exactly as they are today. The only exposition changes allowed are the client's: `_created` series gone, JVM metric renames when those exports are on, formatting.
- Tests: Kotest `StringSpec` with `init {}`, MockK where useful; no MockK in Lincheck specs (see `docs/TESTING.md`). Reference ports through `TestPorts`, never literals.
- Do not rewrite the two string literals `io.prometheus.client.MetricFamily` in `ProxyHttpRoutesTest.kt` and `AgentHttpServiceTest.kt`: they are protobuf `Accept` header values on the wire, not imports.
- Before completing any task: `./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -x test`.
- Keep both `CHANGELOG.md` and `RELEASE_NOTES.md`, each in its own style, under `## [Unreleased]` / `## Unreleased`.
- **Do not commit, push, or open a PR until the user says so** (user policy). Each task ends with a diff review instead of a commit.

## Review Focus

- **Start-time gauges must hold seconds.** 1.x has no `setToCurrentTime()`; a replacement that stores milliseconds would put `time() - proxy_start_time_seconds` (the dashboards' uptime panels) off by 1000× with no error anywhere. Pinned in Task 2 (`UtilsTest`, `ProxyMetricsTest`, `AgentMetricsTest`).
- **Per-path histograms must stay classic-only.** A 1.x `Histogram` keeps a native histogram (up to 160 buckets per series) beside the classic buckets unless built `classicOnly()`. The proxy keeps two histograms per registered path (up to `maxPathsPerAgent` = 20,000 paths per agent), and a Prometheus that negotiates protobuf would start ingesting native histograms. Pinned in Task 3.
- **Proxy and agent share one default registry in one JVM** (the harness, and any host running an embedded agent next to its own metrics). In 1.x a name collision fails every scrape of that registry with HTTP 500 instead of serving duplicates. Pinned in Task 4 by scraping both `/metrics` endpoints of an in-JVM proxy+agent pair, in both formats.
- **Dashboards, alerts and doc queries must name series that are actually exposed.** Verified with jshell against 0.16.0: a counter built with `name("proxy_connect_count")` is exposed only as `proxy_connect_count_total`, so today's Grafana panels and alert rules that query `proxy_connect_count`, `proxy_scrape_requests`, `agent_scrape_request_count` and four others match nothing. 1.x keeps the `_total` form, so the migration is when these get checked. Pinned in Task 5.
- **A retired path's series must still be removed.** `removePathSeries` calls `histogram.remove(path, label)`; in 1.x that API takes label values in `labelNames` declaration order, while snapshots report labels sorted by name, so a careless port of the tests can pass while removal breaks. Covered by the existing `ProxyMetricsTest` removal specs ported in Task 2 (asserting by label name, not position) and by `ProxyMetricsLincheckTest`, run in Task 7.

---

## File Structure

- `gradle/libs.versions.toml` (modify) — `utils` 4.1.0 → 5.0.0; `prometheus` 0.16.0 → 1.9.0; `prometheus-simpleclient` → `prometheus-metrics-core`.
- `build.gradle.kts` (modify) — `implementation(libs.prometheus.metrics.core)`.
- `settings.gradle.kts` (modify temporarily, never committed) — `mavenLocal()` for `com.pambrose.common-utils`.
- `src/main/kotlin/io/prometheus/common/Utils.kt` (modify) — `GaugeDataPoint.setToCurrentTime()`.
- `src/main/kotlin/io/prometheus/proxy/ProxyMetrics.kt`, `src/main/kotlin/io/prometheus/agent/AgentMetrics.kt` (modify) — 1.x types, `histogram {}` DSL, classic-only.
- `src/main/kotlin/io/prometheus/Agent.kt` (modify) — `startTimer(): Timer?`, `labelValues`.
- `src/main/kotlin/io/prometheus/proxy/{ProxyUtils,ProxyServiceImpl}.kt`, `src/main/kotlin/io/prometheus/agent/{AgentGrpcService,AgentHttpService}.kt` (modify) — `labels(` → `labelValues(`.
- Tests (modify): `ProxyMetricsTest`, `AgentMetricsTest`, `ProxyMetricsLincheckTest`, `ProxyServiceImplTest`, `AgentHttpServiceTest`, `AgentTest`, `UtilsTest`, `NettyTestWithAdminMetricsTest`, `ContainersProxyHttpTest`, and the 18 harness specs that only clear the registry.
- Test (create): `src/test/kotlin/io/prometheus/misc/DashboardMetricNamesTest.kt`.
- Dashboards and docs (modify): `grafana/prometheus-proxy.json`, `grafana/prometheus-agents.json`, `src/test/kotlin/website/MonitoringExamples.txt`, `docs/metrics-and-grafana.md`, `website/prometheus-proxy/docs/{monitoring,grafana,troubleshooting,embedded-agent}.md`, `website/prometheus-proxy/docs/configuration/agent.md`, `README.md`, `llms.txt`, `CHANGELOG.md`, `RELEASE_NOTES.md`.
- Scratch (git-ignored, never committed): `out/exposition/` — capture script, 0.16 and 1.x expositions, diff notes. `out/` is already in `.gitignore`.

---

### Task 1: Branch, local common-utils 5.0.0, and the 0.16 baseline exposition

**Files:**
- Create (git-ignored): `out/exposition/capture.sh`, `out/exposition/agent.conf`
- Modify (never committed): `settings.gradle.kts`

**Interfaces:**
- Produces: `out/exposition/capture.sh <label>`, which writes `out/exposition/{proxy,agent}-<label>.{text,om}` and matching `.headers` files. Task 6 reruns it with `1.x`.

- [ ] **Step 1: Create the branch from master**

```bash
git fetch origin
git switch -c prometheus-client-1x origin/master
git status --short
```

Expected: on `prometheus-client-1x`, with only this plan (`docs/superpowers/`) untracked. The catalog has `gradle-plugins = "1.1.5"`; common-utils 5.0.0 moved to 1.1.6, but this plan doesn't need that bump. Leave it unless the user asks for it.

- [ ] **Step 2: Publish common-utils 5.0.0 to the local Maven repository**

```bash
cd ../common-utils && git status --short && git describe --tags && make publish-local
ls ~/.m2/repository/com/pambrose/common-utils/prometheus-utils/5.0.0/
```

Expected: clean tree at tag `5.0.0`; the directory lists `prometheus-utils-5.0.0.jar` and `.pom`.

- [ ] **Step 3: Resolve common-utils from mavenLocal (temporary — never commit)**

In `settings.gradle.kts`, change the `dependencyResolutionManagement.repositories` block to:

```kotlin
  repositories {
    mavenCentral()
    // TEMPORARY: common-utils 5.0.0 is not on Maven Central yet. Remove before committing.
    mavenLocal { content { includeGroup("com.pambrose.common-utils") } }
  }
```

- [ ] **Step 4: Write the capture script**

`out/exposition/agent.conf`:

```hocon
agent {
  pathConfigs: [
    { name: "Proxy self", path: proxy_self, url: "http://localhost:19082/metrics" }
  ]
}
```

`out/exposition/capture.sh`:

```bash
#!/usr/bin/env bash
# Captures the proxy's and agent's own /metrics, in the text and OpenMetrics formats, with every JVM export on.
# Usage: out/exposition/capture.sh <label>   (run from the repo root)
set -euo pipefail
label=$1
dir=out/exposition

./gradlew -q proxyJar agentJar

wait_for() {
  for _ in $(seq 120); do curl -sf -o /dev/null "$1" && return 0; sleep 0.5; done
  echo "timed out waiting for $1" >&2
  return 1
}

flags=(standardExportsEnabled memoryPoolsExportsEnabled garbageCollectorExportsEnabled threadExportsEnabled
  classLoadingExportsEnabled versionInfoExportsEnabled)
proxy_args=(--port 19080 --agent_port 19051 --metrics --metrics_port 19082)
agent_args=(--config "$dir/agent.conf" --proxy localhost:19051 --metrics --metrics_port 19083)
for f in "${flags[@]}"; do
  proxy_args+=("-Dproxy.metrics.$f=true")
  agent_args+=("-Dagent.metrics.$f=true")
done

java -jar build/libs/prometheus-proxy.jar "${proxy_args[@]}" > "$dir/proxy-$label.log" 2>&1 &
proxy_pid=$!
trap 'kill $proxy_pid ${agent_pid:-} 2>/dev/null || true' EXIT
wait_for http://localhost:19082/metrics

java -jar build/libs/prometheus-agent.jar "${agent_args[@]}" > "$dir/agent-$label.log" 2>&1 &
agent_pid=$!
wait_for http://localhost:19080/proxy_self
wait_for http://localhost:19083/metrics

# Populate the counters and histograms: successful scrapes plus one unknown path.
for _ in 1 2 3 4 5; do curl -sf -o /dev/null http://localhost:19080/proxy_self; done
curl -s -o /dev/null http://localhost:19080/no_such_path || true

for pair in proxy:19082 agent:19083; do
  name=${pair%%:*}
  port=${pair##*:}
  curl -sf -D "$dir/$name-$label.text.headers" -o "$dir/$name-$label.text" "http://localhost:$port/metrics"
  curl -sf -H 'Accept: application/openmetrics-text; version=1.0.0' \
    -D "$dir/$name-$label.om.headers" -o "$dir/$name-$label.om" "http://localhost:$port/metrics"
done
echo "captured $label into $dir"
```

```bash
chmod +x out/exposition/capture.sh
```

- [ ] **Step 5: Capture the 0.16 baseline**

Run from the repo root, before touching any dependency:

```bash
out/exposition/capture.sh 0.16
grep -c . out/exposition/proxy-0.16.text out/exposition/agent-0.16.text
grep -E '^proxy_connect_count_total|^proxy_connect_count_created|^jvm_memory_bytes_used|^jvm_info' out/exposition/proxy-0.16.text
```

Expected: both files non-empty; the grep shows `proxy_connect_count_total`, `proxy_connect_count_created`, `jvm_memory_bytes_used{area="heap"}` and `jvm_info{...}` lines.

- [ ] **Step 6: Review**

`git status --short` shows only `settings.gradle.kts` and the untracked `docs/superpowers/`; `out/` does not appear.

---

### Task 2: Swap to common-utils 5.0.0 / client 1.9.0 and port every call site

The dependency bump breaks compilation of both source sets at once, and Kotlin compiles each source set as a unit, so this task ports main and test code together and is verified by the whole unit suite.

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`
- Modify: `src/main/kotlin/io/prometheus/common/Utils.kt`
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyMetrics.kt`, `src/main/kotlin/io/prometheus/agent/AgentMetrics.kt`, `src/main/kotlin/io/prometheus/Agent.kt`
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyUtils.kt:120`, `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:464,495`, `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:331,339,494,512,522,568`, `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:264-265`
- Test: `src/test/kotlin/io/prometheus/common/UtilsTest.kt`, `src/test/kotlin/io/prometheus/proxy/{ProxyMetricsTest,ProxyMetricsLincheckTest,ProxyServiceImplTest}.kt`, `src/test/kotlin/io/prometheus/agent/{AgentMetricsTest,AgentHttpServiceTest,AgentTest}.kt`, and every harness spec that imports `io.prometheus.client.CollectorRegistry`

**Interfaces:**
- Consumes: common-utils 5.0.0 `PrometheusDsl.counter/gauge/histogram(registry = PrometheusRegistry.defaultRegistry, block)` returning the 1.x `Counter`/`Gauge`/`Histogram`; `SamplerGaugeCollector(name, help, labelNames, labelValues, registry, data)` (call sites unchanged).
- Produces:
  - `Utils.setToCurrentTime()`: `fun io.prometheus.metrics.core.datapoints.GaugeDataPoint.setToCurrentTime()`, sets the gauge to Unix time in **seconds**.
  - `ProxyMetrics.scrapeRequestLatency` / `scrapeResponseBytes`: `io.prometheus.metrics.core.metrics.Histogram`, labels `("path", "outcome")` / `("path", "encoding")`.
  - `AgentMetrics.scrapeRequestLatency`: `Histogram`, labels `(launch_id, agent_name)`.
  - `Agent.startTimer(): io.prometheus.metrics.core.datapoints.Timer?` (`observeDuration()` unchanged at the call site).
  - Every counter property is `io.prometheus.metrics.core.metrics.Counter`; a labelled child is `labelValues(...)`, of type `CounterDataPoint`.

- [ ] **Step 1: Bump the catalog and the direct dependency**

In `gradle/libs.versions.toml`:

```toml
# Metrics
# Keep in sync with common-utils: 1.9.0 is the Prometheus Java client common-utils 5.0.0 is built against
prometheus = "1.9.0"
dropwizard = "4.2.40"
```

```toml
# Common Utils
utils = "5.0.0"
```

and replace the library entry

```toml
prometheus-simpleclient = { module = "io.prometheus:simpleclient", version.ref = "prometheus" }
```

with

```toml
prometheus-metrics-core = { module = "io.prometheus:prometheus-metrics-core", version.ref = "prometheus" }
```

In `build.gradle.kts` replace `implementation(libs.prometheus.simpleclient)` with:

```kotlin
  implementation(libs.prometheus.metrics.core)
```

- [ ] **Step 2: Confirm no 0.x artifact is left**

```bash
./gradlew -q dependencies --configuration runtimeClasspath | grep -E 'simpleclient|prometheus-metrics-core|common-utils:prometheus-utils' | sort -u
./gradlew -q dependencies --configuration testRuntimeClasspath | grep -c simpleclient || true
```

Expected: `prometheus-utils:5.0.0` and `prometheus-metrics-core:1.9.0` lines, no `simpleclient` line; the count prints `0`.

- [ ] **Step 3: Write the failing `setToCurrentTime` test**

Add to `UtilsTest` (imports: `io.prometheus.common.Utils.setToCurrentTime`, `io.prometheus.metrics.core.metrics.Gauge`, `io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual`, `io.kotest.matchers.doubles.shouldBeLessThanOrEqual`):

```kotlin
    // ==================== setToCurrentTime Tests ====================

    // The 1.x client has no setToCurrentTime(). The start-time gauges feed `time() - *_start_time_seconds` on the
    // dashboards, so the value must be Unix seconds, not milliseconds.
    "setToCurrentTime should set the gauge to the current Unix time in seconds" {
      val gauge =
        Gauge.builder()
          .name("utils_test_start_time_seconds")
          .help("Utils test start time")
          .labelNames("id")
          .build()

      val before = System.currentTimeMillis() / 1_000.0
      gauge.labelValues("a").setToCurrentTime()
      val after = System.currentTimeMillis() / 1_000.0

      val value = gauge.labelValues("a").get()
      value shouldBeGreaterThanOrEqual before
      value shouldBeLessThanOrEqual after
    }
```

- [ ] **Step 4: Run it to see it fail**

```bash
./gradlew compileTestKotlin 2>&1 | grep -m3 -E "Unresolved reference.*setToCurrentTime|error:"
```

Expected: `Unresolved reference 'setToCurrentTime'` (plus the other 1.x compile errors the rest of this task fixes).

- [ ] **Step 5: Add the helper to `Utils`**

In `src/main/kotlin/io/prometheus/common/Utils.kt`, add the import `io.prometheus.metrics.core.datapoints.GaugeDataPoint`, the constant beside the other private constants:

```kotlin
  private const val MILLIS_PER_SECOND = 1_000.0
```

and, after `toJsonElement`:

```kotlin
  /** Sets this gauge to the current Unix time in seconds, as the 0.x client's `setToCurrentTime()` did. */
  fun GaugeDataPoint.setToCurrentTime() = set(System.currentTimeMillis() / MILLIS_PER_SECOND)
```

- [ ] **Step 6: Port `ProxyMetrics`**

Imports: remove `io.prometheus.client.Histogram`; add `com.pambrose.common.dsl.PrometheusDsl.histogram`, `io.prometheus.common.Utils.setToCurrentTime`, `io.prometheus.metrics.core.metrics.Histogram`. Then:

```kotlin
  val scrapeRequestLatency: Histogram =
    histogram {
      name("proxy_scrape_request_latency_seconds")
      help("Proxy scrape request latency in seconds")
      labelNames("path", "outcome")
      // Up to the proxy's default scrapeRequestTimeoutSecs (90), past the agent's default scrapeTimeoutSecs (15), so
      // a timeout lands in a bucket rather than +Inf.
      classicUpperBounds(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0)
    }

  val scrapeResponseBytes: Histogram =
    histogram {
      name("proxy_scrape_response_bytes")
      help("Proxy scrape response size in bytes")
      labelNames("path", "encoding")
      classicUpperBounds(1_024.0, 10_240.0, 102_400.0, 512_000.0, 1_048_576.0, 5_242_880.0, 10_485_760.0)
    }
```

In `observe`: `histogram.labels(path, label).observe(value)` → `histogram.labelValues(path, label).observe(value)`. `removePathSeries` is unchanged (`histogram.remove(path, label)` exists in 1.x with the same meaning). In `init`: `}.labels(proxy.launchId).setToCurrentTime()` → `}.labelValues(proxy.launchId).setToCurrentTime()`.

- [ ] **Step 7: Port `AgentMetrics` and `Agent`**

`AgentMetrics.kt` — same import changes as Step 6, then:

```kotlin
  val scrapeRequestLatency: Histogram =
    histogram {
      name("agent_scrape_request_latency_seconds")
      help("Agent scrape request latency in seconds")
      labelNames(LAUNCH_ID, AGENT_NAME)
      classicUpperBounds(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0)
    }
```

and `}.labels(agent.launchId).setToCurrentTime()` → `}.labelValues(agent.launchId).setToCurrentTime()`.

`Agent.kt` — replace the import `io.prometheus.client.Histogram` with `io.prometheus.metrics.core.datapoints.Timer`, and line 473 with:

```kotlin
  internal fun startTimer(): Timer? = metrics.scrapeRequestLatency.labelValues(launchId, agentName).startTimer()
```

- [ ] **Step 8: Rename the remaining `labels(` call sites in main**

Every remaining `.labels(` in `src/main` is a Prometheus child lookup (verify first):

```bash
grep -rn '\.labels(' src/main/kotlin
grep -rl '\.labels(' src/main/kotlin | xargs sed -i '' 's/\.labels(/.labelValues(/g'
grep -rn 'io\.prometheus\.client\|\.labels(' src/main/kotlin
```

Expected: the first grep lists only `Agent.kt`, `ProxyUtils.kt`, `ProxyServiceImpl.kt`, `AgentGrpcService.kt`, `AgentHttpService.kt` lines; the last grep prints nothing.

- [ ] **Step 9: Port the registry-clearing harness and unit specs**

```bash
grep -rl 'import io.prometheus.client.CollectorRegistry' src/test/kotlin | xargs sed -i '' \
  -e 's/import io\.prometheus\.client\.CollectorRegistry/import io.prometheus.metrics.model.registry.PrometheusRegistry/' \
  -e 's/CollectorRegistry\.defaultRegistry\.clear()/PrometheusRegistry.defaultRegistry.clear()/g'
grep -rln 'CollectorRegistry' src/test/kotlin
```

Expected: the last grep lists only `ProxyMetricsTest.kt` and `AgentMetricsTest.kt` (their `metricFamilySamples()` calls are ported by hand in Step 11).

- [ ] **Step 10: Rename `labels(` in the tests**

```bash
grep -rl '\.labels(' src/test/kotlin | xargs sed -i '' 's/\.labels(/.labelValues(/g'
```

This covers `ProxyMetricsTest`, `AgentMetricsTest`, `AgentHttpServiceTest` (lines ~2076 and ~2194: `CounterDataPoint.get()` still exists) and the `every { ... }` stubs in `ProxyServiceImplTest`.

- [ ] **Step 11: Port the snapshot reads**

`ProxyMetricsTest` — replace the `seriesPaths` helper:

```kotlin
  // Every path label value across the per-path histograms' series.
  private fun seriesPaths(metrics: ProxyMetrics): Set<String> =
    listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
      .flatMap { it.collect().dataPoints }
      .mapNotNull { it.labels.get("path") }
      .toSet()
```

In "removePathSeries should remove only the series the path recorded, without scanning the histograms", replace the final assertion (1.x snapshots sort labels by name, so read them by name, never by position):

```kotlin
      metrics.scrapeRequestLatency.collect().dataPoints
        .map { listOf(it.labels.get("path"), it.labels.get("outcome")) }
        .toSet() shouldBe setOf(listOf("retired", "unrecorded"))
```

In "scrapeRequestLatency should record observations with path and outcome labels":

```kotlin
      val latencyMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "proxy_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
      val outcomes = latencyMetric.dataPoints.mapNotNull { it.labels.get("outcome") }.toSet()
      outcomes shouldContain "success"
      outcomes shouldContain "timed_out"
```

In "scrapeResponseBytes should record observations with labels":

```kotlin
      val bytesMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "proxy_scrape_response_bytes" }
      bytesMetric.shouldNotBeNull()
```

Add, next to the other gauge specs (imports: `io.prometheus.metrics.model.snapshots.GaugeSnapshot`, `io.kotest.matchers.doubles.shouldBeLessThanOrEqual`):

```kotlin
    "proxy start time gauge should hold the start time in Unix seconds" {
      val before = System.currentTimeMillis() / 1_000.0
      ProxyMetrics(createMockProxy())
      val after = System.currentTimeMillis() / 1_000.0

      val gauge =
        PrometheusRegistry.defaultRegistry.scrape()
          .filterIsInstance<GaugeSnapshot>()
          .single { it.metadata.name == "proxy_start_time_seconds" }
      val value = gauge.dataPoints.single().value
      value shouldBeGreaterThanOrEqual before
      value shouldBeLessThanOrEqual after
    }
```

`AgentMetricsTest` — in "scrapeRequestLatency should record observations with labels":

```kotlin
      val latencyMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "agent_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
```

Replace "start time gauge should be registered and set" (it only checked `>= 0.0`) with:

```kotlin
    "start time gauge should hold the start time in Unix seconds" {
      val before = System.currentTimeMillis() / 1_000.0
      AgentMetrics(createMockAgent())
      val after = System.currentTimeMillis() / 1_000.0

      val gauge =
        PrometheusRegistry.defaultRegistry.scrape()
          .filterIsInstance<GaugeSnapshot>()
          .single { it.metadata.name == "agent_start_time_seconds" }
      val value = gauge.dataPoints.single().value
      value shouldBeGreaterThanOrEqual before
      value shouldBeLessThanOrEqual after
    }
```

and in "SamplerGaugeCollector should be constructable with agent metrics":

```kotlin
      val names = PrometheusRegistry.defaultRegistry.scrape().map { it.metadata.name }
      names shouldContainAll listOf("agent_scrape_backlog_size", "agent_client_cache_size")
```

(add `io.kotest.matchers.collections.shouldContainAll` and `io.kotest.matchers.doubles.shouldBeLessThanOrEqual` if missing, and drop imports that become unused).

`ProxyServiceImplTest` — replace `import io.prometheus.client.Counter` with `import io.prometheus.metrics.core.metrics.Counter` and `import io.prometheus.metrics.core.datapoints.CounterDataPoint`, and in `ChunkFailureCounters`:

```kotlin
    val chunkStage = mockk<CounterDataPoint>(relaxed = true)
    val summaryStage = mockk<CounterDataPoint>(relaxed = true)
```

`ProxyMetricsLincheckTest` — replace both `io.prometheus.client` imports with `io.prometheus.metrics.core.metrics.Histogram` and `io.prometheus.metrics.model.registry.PrometheusRegistry`, `CollectorRegistry.defaultRegistry.clear()` with `PrometheusRegistry.defaultRegistry.clear()`, and `seriesFor` with:

```kotlin
  // The label sets of this run's paths that histogram still holds.
  private fun seriesFor(histogram: Histogram): Set<String> =
    histogram.collect().dataPoints
      .filter { it.labels.get("path") in paths }
      .map { it.labels.toString() }
      .toSet()
```

(`left` in `validate` becomes a `Set<String>`; the check message still prints it.)

- [ ] **Step 12: Format and confirm nothing of the 0.x API remains**

```bash
./gradlew formatKotlin
grep -rn 'io\.prometheus\.client' src/main/kotlin src/test/kotlin
```

Expected: `formatKotlin` re-sorts the replaced imports; the grep prints exactly the two `MetricFamily` `Accept` literals (`ProxyHttpRoutesTest.kt` ~901, `AgentHttpServiceTest.kt` ~97).

- [ ] **Step 13: Build, lint, and run the unit suite**

```bash
./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test
./gradlew test
```

Expected: all green, including the new `UtilsTest` and start-time specs.

If an unrelated spec fails, check these other common-utils 5.0.0 changes before debugging the port: `LambdaServlet` no longer appends a line separator (the `/debug` body); `ServletGroup.addServlet` adds a missing leading slash; the Jetty admin and metrics servers send no `Server:` header; `GrpcDsl.server`/`channel` throw `IllegalArgumentException` for a Netty port out of range or a missing host; `initMetricsAndHealthChecks()` fails fast on a second call.

- [ ] **Step 14: Review**

`git diff --stat` — only the files listed for this task (plus the uncommitted `settings.gradle.kts`).

---

### Task 3: Keep the histograms classic-only

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyMetrics.kt`, `src/main/kotlin/io/prometheus/agent/AgentMetrics.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/ProxyMetricsTest.kt`, `src/test/kotlin/io/prometheus/agent/AgentMetricsTest.kt`

**Interfaces:**
- Consumes: the Task 2 `histogram {}` definitions.
- Produces: the three histograms built with `classicOnly()`; no behavior other tasks depend on.

- [ ] **Step 1: Write the failing tests**

`ProxyMetricsTest` (imports: `io.kotest.inspectors.forAll`, `io.kotest.matchers.booleans.shouldBeFalse`):

```kotlin
    // ==================== Histogram Shape Tests ====================

    // A 1.x histogram also keeps a native histogram (up to 160 buckets per series) unless it is built classicOnly().
    // The proxy holds two histograms per registered path, and a Prometheus scraping with protobuf would ingest the
    // native data, so both stay classic-only, as they were under the 0.x client.
    "per-path histograms should keep classic buckets only" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("p")
      metrics.observeLatency("p", "success", 0.1)
      metrics.observeResponseBytes("p", ProxyMetrics.ENCODING_PLAIN, 1_024.0)

      listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
        .flatMap { it.collect().dataPoints }
        .forAll { it.hasNativeHistogramData().shouldBeFalse() }
    }

    "scrapeRequestLatency should keep its bucket layout" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("p")
      metrics.observeLatency("p", "success", 0.1)

      val buckets = metrics.scrapeRequestLatency.collect().dataPoints.single().classicBuckets
      List(buckets.size()) { buckets.getUpperBound(it) } shouldBe
        listOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0, Double.POSITIVE_INFINITY)
    }

    "scrapeResponseBytes should keep its bucket layout" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("p")
      metrics.observeResponseBytes("p", ProxyMetrics.ENCODING_PLAIN, 1_024.0)

      val buckets = metrics.scrapeResponseBytes.collect().dataPoints.single().classicBuckets
      List(buckets.size()) { buckets.getUpperBound(it) } shouldBe
        listOf(1_024.0, 10_240.0, 102_400.0, 512_000.0, 1_048_576.0, 5_242_880.0, 10_485_760.0, Double.POSITIVE_INFINITY)
    }
```

`AgentMetricsTest` (same imports):

```kotlin
    "scrapeRequestLatency should keep classic buckets only, with its bucket layout" {
      val metrics = AgentMetrics(createMockAgent())
      metrics.scrapeRequestLatency.labelValues("test-launch-id", "test-agent").observe(0.1)

      val point = metrics.scrapeRequestLatency.collect().dataPoints.single()
      point.hasNativeHistogramData().shouldBeFalse()
      List(point.classicBuckets.size()) { point.classicBuckets.getUpperBound(it) } shouldBe
        listOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, Double.POSITIVE_INFINITY)
    }
```

- [ ] **Step 2: Run them to see the native-data checks fail**

```bash
./gradlew test --tests 'io.prometheus.proxy.ProxyMetricsTest' --tests 'io.prometheus.agent.AgentMetricsTest'
```

Expected: FAIL in "per-path histograms should keep classic buckets only" and "scrapeRequestLatency should keep classic buckets only, with its bucket layout" (`hasNativeHistogramData()` is `true`). The two layout-only specs already pass; they pin the `buckets` → `classicUpperBounds` port.

- [ ] **Step 3: Add `classicOnly()`**

In each of the three `histogram {}` blocks, add after `labelNames(...)`:

```kotlin
      classicOnly()
```

and put this comment above `scrapeRequestLatency` in `ProxyMetrics` (once):

```kotlin
  // Classic buckets only: a 1.x histogram otherwise also keeps a native histogram per series, which the per-path
  // series would multiply by every registered path.
```

- [ ] **Step 4: Run them to see them pass**

```bash
./gradlew test --tests 'io.prometheus.proxy.ProxyMetricsTest' --tests 'io.prometheus.agent.AgentMetricsTest'
```

Expected: PASS.

- [ ] **Step 5: Lint and review**

```bash
./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test
git diff --stat
```

---

### Task 4: End-to-end `/metrics` exposition checks

**Files:**
- Test: `src/test/kotlin/io/prometheus/harness/NettyTestWithAdminMetricsTest.kt`
- Test: `src/test/kotlin/io/prometheus/containers/ContainersProxyHttpTest.kt:150-187`

**Interfaces:**
- Consumes: `TestPorts.HARNESS_PROXY_METRICS_PORT` / `HARNESS_AGENT_METRICS_PORT` (bound by `TestUtils.startProxy` / `startAgent` when `metricsEnabled = true`); `KtorDsl.get(url, setUp, block)`; `String.withPrefix()`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Add the harness specs**

In `NettyTestWithAdminMetricsTest`, after "should return debug info from admin endpoints" (imports: `io.kotest.matchers.string.shouldContain`, `shouldNotContain`, `shouldStartWith`, `shouldEndWith`, `io.ktor.client.request.header`, `io.ktor.http.HttpHeaders`, `io.prometheus.common.TestPorts.HARNESS_AGENT_METRICS_PORT`, `io.prometheus.common.TestPorts.HARNESS_PROXY_METRICS_PORT`):

```kotlin
    // The proxy and agent run in this JVM and share Prometheus' default registry, so each metrics endpoint serves
    // both components' families. Under the 1.x client a name collision between them fails every scrape with a 500
    // rather than serving duplicates, so both endpoints are scraped.
    "metrics endpoints should serve both components' series in the text format" {
      listOf(HARNESS_PROXY_METRICS_PORT, HARNESS_AGENT_METRICS_PORT).forEach { port ->
        withHttpClient {
          get("$port/metrics".withPrefix()) { response ->
            val body = response.bodyAsText()
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType].orEmpty() shouldStartWith "text/plain; version=0.0.4"
            body shouldContain "\nproxy_connect_count_total "
            body shouldContain "\nproxy_agent_map_size "
            body shouldContain "\nproxy_start_time_seconds{launch_id=\""
            body shouldContain "\nagent_start_time_seconds{launch_id=\""
            body shouldContain "\nagent_scrape_backlog_size{launch_id=\""
            // The 1.x client no longer exposes the counters' and histograms' _created series by default.
            body shouldNotContain "_created"
          }
        }
      }
    }

    // Prometheus 3 asks for OpenMetrics first.
    "metrics endpoint should serve OpenMetrics when the Accept header asks for it" {
      withHttpClient {
        get(
          "$HARNESS_PROXY_METRICS_PORT/metrics".withPrefix(),
          setUp = { header(HttpHeaders.Accept, "application/openmetrics-text; version=1.0.0") },
        ) { response ->
          val body = response.bodyAsText()
          response.status shouldBe HttpStatusCode.OK
          response.headers[HttpHeaders.ContentType].orEmpty() shouldStartWith "application/openmetrics-text"
          body shouldContain "# TYPE proxy_connect_count counter"
          body.trimEnd() shouldEndWith "# EOF"
        }
      }
    }
```

- [ ] **Step 2: Run the spec**

```bash
./gradlew test --tests 'io.prometheus.harness.NettyTestWithAdminMetricsTest'
```

Expected: PASS. (These are guards on the finished port, not red-first tests: on 0.16 the `_created` assertion would fail.) If a scrape returns 500, read the proxy log for the colliding name; do not weaken the assertion.

- [ ] **Step 3: Tighten the container checks to the exposed names**

In `ContainersProxyHttpTest`, "proxy exposes its own Prometheus metrics":

```kotlin
          body shouldContain "proxy_scrape_requests_total{"
```

and "agent exposes its own Prometheus metrics":

```kotlin
          httpClient.bodyOf(agentMetrics("/metrics")) shouldContain "agent_scrape_request_count_total{"
```

These run in Task 7 with Docker (`make container-tests`); by then the containers have served scrapes, so the labelled series exist.

- [ ] **Step 4: Lint and review**

```bash
./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test
git diff --stat
```

---

### Task 5: Dashboards, alerts and doc queries name the exposed series

Pre-existing bug, surfaced by the migration's exposition check: both clients expose a counter only as `<name>_total`, and the dashboards and docs query seven counters by the bare name.

**Files:**
- Create: `src/test/kotlin/io/prometheus/misc/DashboardMetricNamesTest.kt`
- Modify: `grafana/prometheus-proxy.json`, `grafana/prometheus-agents.json`, `src/test/kotlin/website/MonitoringExamples.txt`, `docs/metrics-and-grafana.md`, `website/prometheus-proxy/docs/{monitoring,grafana,troubleshooting}.md`, `website/prometheus-proxy/docs/configuration/agent.md`, `README.md`

**Interfaces:**
- Consumes: `ProxyMetrics(proxy)`, `AgentMetrics(agent)` from Task 2, registered on `PrometheusRegistry.defaultRegistry`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing guard test**

`src/test/kotlin/io/prometheus/misc/DashboardMetricNamesTest.kt` (license header copied from a neighbouring file):

```kotlin
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.HttpClientCache
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.concurrent.atomics.AtomicInt

// The Grafana dashboards, the alert rules and the PromQL in the docs must query series the proxy and agent expose.
// A counter is exposed only with a _total suffix, whatever name it is built with, so a query for the bare name
// matches nothing and a panel or alert silently shows no data.
class DashboardMetricNamesTest : StringSpec() {
  private fun exposedNames(): Set<String> {
    PrometheusRegistry.defaultRegistry.clear()
    ProxyMetrics(mockProxy())
    AgentMetrics(mockAgent())
    return PrometheusRegistry.defaultRegistry.scrape()
      .flatMap { snapshot ->
        val name = snapshot.metadata.name
        when (snapshot) {
          is CounterSnapshot -> listOf("${name}_total")
          is HistogramSnapshot -> listOf("${name}_bucket", "${name}_count", "${name}_sum")
          else -> listOf(name)
        }
      }.toSet()
  }

  private fun mockProxy(): Proxy {
    val pathManager = mockk<ProxyPathManager>(relaxed = true)
    every { pathManager.pathMapSize } returns 0
    return mockk<Proxy>(relaxed = true).also {
      every { it.agentContextManager } returns AgentContextManager(isTestMode = true)
      every { it.pathManager } returns pathManager
      every { it.scrapeRequestManager } returns ScrapeRequestManager()
    }
  }

  private fun mockAgent(): Agent {
    val cache = mockk<HttpClientCache>(relaxed = true)
    every { cache.currentCacheSize() } returns 0
    val httpService = mockk<AgentHttpService>(relaxed = true)
    every { httpService.httpClientCache } returns cache
    return mockk<Agent>(relaxed = true).also {
      every { it.launchId } returns "test-launch-id"
      every { it.scrapeRequestBacklogSize } returns AtomicInt(0)
      every { it.agentHttpService } returns httpService
    }
  }

  // Every "expr" value in a Grafana dashboard.
  private fun dashboardExprs(element: JsonElement): List<String> =
    when (element) {
      is JsonObject ->
        element.flatMap { (key, value) ->
          if (key == "expr" && value is JsonPrimitive) listOf(value.content) else dashboardExprs(value)
        }
      is JsonArray -> element.flatMap { dashboardExprs(it) }
      else -> emptyList()
    }

  private fun read(path: String) = File(path).readText()

  // The metric names a PromQL expression selects. Label names appear only inside {...} matchers and grouping
  // clauses, so both are removed first; agent_name, for one, is a label, not a metric.
  private fun metricNames(promql: String): Set<String> =
    promql
      .replace(MATCHERS, "")
      .replace(GROUPING, "")
      .let { stripped -> METRIC_NAME.findAll(stripped).map { it.value }.toSet() }

  private val sources: Map<String, () -> List<String>> =
    mapOf(
      "grafana/prometheus-proxy.json" to { dashboardExprs(Json.parseToJsonElement(read("grafana/prometheus-proxy.json"))) },
      "grafana/prometheus-agents.json" to { dashboardExprs(Json.parseToJsonElement(read("grafana/prometheus-agents.json"))) },
      MONITORING_SNIPPETS to { SNIPPET.findAll(read(MONITORING_SNIPPETS)).map { it.groupValues[1] }.toList() },
      METRICS_DOC to { PROMQL_BLOCK.findAll(read(METRICS_DOC)).map { it.groupValues[1] }.toList() },
      GRAFANA_PAGE to { YAML_EXPR.findAll(read(GRAFANA_PAGE)).map { it.groupValues[1] }.toList() },
    )

  init {
    sources.forEach { (file, queries) ->
      "every proxy and agent series queried in $file should be exposed" {
        val exposed = exposedNames()
        val queried = queries()
        queried.shouldNotBeEmpty()
        queried.flatMap { metricNames(it) }.filterNot { it in exposed }.distinct().shouldBeEmpty()
      }
    }
  }

  companion object {
    private const val MONITORING_SNIPPETS = "src/test/kotlin/website/MonitoringExamples.txt"
    private const val METRICS_DOC = "docs/metrics-and-grafana.md"
    private const val GRAFANA_PAGE = "website/prometheus-proxy/docs/grafana.md"
    private val MATCHERS = Regex("""\{[^}]*}""")
    private val GROUPING = Regex("""\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)""")
    private val METRIC_NAME = Regex("""\b(?:proxy|agent)_[a-z_]+\b""")
    private val SNIPPET = Regex("""; --8<-- \[start:promql-[^\]]+]\n(.*?)\n; --8<-- \[end:""", RegexOption.DOT_MATCHES_ALL)
    private val PROMQL_BLOCK = Regex("""```promql\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    private val YAML_EXPR = Regex("""^\s*expr:\s*(.+)$""", RegexOption.MULTILINE)
  }
}
```

If `AgentContextManager`, `ProxyPathManager` or `ScrapeRequestManager` are not visible from `io.prometheus.misc`, copy the constructor calls exactly as `ProxyMetricsTest.createMockProxy()` makes them; they are `internal` to the module, which the test source set shares.

- [ ] **Step 2: Run it to see it fail**

```bash
./gradlew test --tests 'io.prometheus.misc.DashboardMetricNamesTest'
```

Expected: FAIL for every source, listing the bare names: `proxy_connect_count`, `proxy_eviction_count`, `proxy_heartbeat_count`, `proxy_scrape_requests` (proxy dashboard); `agent_connect_count`, `agent_scrape_request_count`, `agent_scrape_result_count` (agents dashboard); `proxy_scrape_requests` (snippets and doc); `proxy_scrape_requests`, `proxy_eviction_count`, `agent_connect_count` (alert rules). If a source fails with an empty `queried` list instead, the extractor regex does not match that file's layout: fix the regex, not the file.

- [ ] **Step 3: Add `_total` to the counters in queries and docs**

```bash
perl -pi -e 's/\b(proxy_connect_count|proxy_eviction_count|proxy_heartbeat_count|proxy_scrape_requests|agent_scrape_request_count|agent_scrape_result_count|agent_connect_count|agent_filter_lines_dropped|agent_filter_bytes_saved)\b/$1_total/g' \
  grafana/prometheus-proxy.json grafana/prometheus-agents.json \
  src/test/kotlin/website/MonitoringExamples.txt docs/metrics-and-grafana.md \
  website/prometheus-proxy/docs/monitoring.md website/prometheus-proxy/docs/grafana.md \
  website/prometheus-proxy/docs/troubleshooting.md website/prometheus-proxy/docs/configuration/agent.md README.md
git diff --stat
grep -rn '_total_total' grafana docs website/prometheus-proxy/docs src/test/kotlin/website README.md
```

Expected: the last grep prints nothing. Do not run it on `CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/archive/`, or source code comments (`ScrapeRecord.kt`, `ProxyFailure.kt` name the family, which is fine).

- [ ] **Step 4: Tidy the tables**

`git diff docs/metrics-and-grafana.md website/prometheus-proxy/docs/monitoring.md website/prometheus-proxy/docs/configuration/agent.md` — re-pad the edited Markdown table columns so each table's pipes line up again, and add one sentence under each metrics table heading that lists counters: "Counters are exposed with a `_total` suffix; query them by that name."

- [ ] **Step 5: Run the guard and the website snippet check**

```bash
./gradlew test --tests 'io.prometheus.misc.DashboardMetricNamesTest'
```

Expected: PASS. Then build the docs site with the `docs-site` skill's build command to confirm the edited `MonitoringExamples.txt` snippets still resolve.

- [ ] **Step 6: Lint and review**

```bash
./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test
git diff --stat
```

---

### Task 6: Exposition diff, docs, and release notes

**Files:**
- Create (git-ignored): `out/exposition/{proxy,agent}-1.x.*`, `out/exposition/diff-notes.md`
- Modify: `docs/metrics-and-grafana.md`, `website/prometheus-proxy/docs/monitoring.md`, `website/prometheus-proxy/docs/embedded-agent.md`, `llms.txt`, `CHANGELOG.md`, `RELEASE_NOTES.md`

**Interfaces:**
- Consumes: `out/exposition/capture.sh` and the `0.16` capture from Task 1.
- Produces: `out/exposition/diff-notes.md`, the classified difference list the release notes are written from.

- [ ] **Step 1: Capture 1.x**

```bash
out/exposition/capture.sh 1.x
```

- [ ] **Step 2: Diff series names, types and headers**

```bash
cd out/exposition
series() { grep -v '^#' "$1" | sed -E 's/[{ ].*//' | sort -u; }
types() { grep '^# TYPE' "$1" | sort -u; }
for who in proxy agent; do
  echo "== $who series"; diff <(series $who-0.16.text) <(series $who-1.x.text) || true
  echo "== $who types";  diff <(types $who-0.16.text) <(types $who-1.x.text) || true
  echo "== $who headers"; diff <(grep -i '^content-type' $who-0.16.text.headers) <(grep -i '^content-type' $who-1.x.text.headers) || true
  diff <(grep -i '^content-type' $who-0.16.om.headers) <(grep -i '^content-type' $who-1.x.om.headers) || true
done
cd -
```

- [ ] **Step 3: Classify every difference in `out/exposition/diff-notes.md`**

One line per difference, as removed / renamed / new / changed type or help / changed labels / header. Expected, per the common-utils 5.0.0 changelog:

- removed: every `proxy_*_created` and `agent_*_created` series
- renamed (JVM exports on): `jvm_memory_bytes_{used,committed,max,init}` → `jvm_memory_{used,committed,max,init}_bytes`; `jvm_memory_pool_bytes_*` → `jvm_memory_pool_*_bytes`; `jvm_info` → `jvm_runtime_info`
- new: `jvm_memory_pool_allocated_bytes_total`
- unchanged: every other `proxy_*` / `agent_*` series and label, the `Content-Type` headers

Anything else in the diff (a `proxy_*`/`agent_*` rename, a changed label, a changed bucket layout) is a regression in Tasks 2–3: stop and fix it there.

- [ ] **Step 4: Update the JVM metrics docs**

In `docs/metrics-and-grafana.md` ("JVM and gRPC Metrics") and `website/prometheus-proxy/docs/monitoring.md` (same section), after the config block add:

```markdown
The JVM metrics come from the Prometheus Java client 1.x. Since the release that moved to it, the memory metrics
put the unit last (`jvm_memory_used_bytes`, not `jvm_memory_bytes_used`; `jvm_memory_pool_used_bytes`, not
`jvm_memory_pool_bytes_used`), `jvm_info` is `jvm_runtime_info`, and `memoryPoolsExportsEnabled` also adds
`jvm_memory_pool_allocated_bytes_total`.
```

Adjust the wording to match the Step 3 notes exactly.

- [ ] **Step 5: Note the registry for embedders**

In `website/prometheus-proxy/docs/embedded-agent.md`, near the lifecycle/options section, add:

```markdown
The embedded agent's metrics register in the Prometheus Java client 1.x default registry
(`io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry`). A host that serves its own metrics
from that registry exposes the agent's too; a host still on the 0.x client (`io.prometheus:simpleclient`) does
not see them.
```

In `llms.txt` line ~227 replace `- **Metrics**: Prometheus Java SimpleClient` with:

```markdown
- **Metrics**: Prometheus Java client 1.x (`io.prometheus:prometheus-metrics-*`), through common-utils' `prometheus-utils`
```

- [ ] **Step 6: CHANGELOG entry**

Under `## [Unreleased]` add, in the file's style:

```markdown
### Breaking Changes

- Move to common-utils 5.0.0 and the Prometheus Java client 1.9.0 (`io.prometheus:prometheus-metrics-*`), replacing `io.prometheus:simpleclient` 0.16.0, which gets no further releases. Every `proxy_*` and `agent_*` series keeps its name, labels and buckets. Differences on `/metrics`: the counters' and histograms' `_created` series are gone (set `IO_PROMETHEUS_EXPORTER_INCLUDE_CREATED_TIMESTAMPS=true` to bring them back); with the JVM exports on, the memory metrics put the unit last (`jvm_memory_bytes_used` → `jvm_memory_used_bytes`, `jvm_memory_pool_bytes_used` → `jvm_memory_pool_used_bytes`, and the same for `committed`, `max` and `init`), `jvm_info` is `jvm_runtime_info`, and `memoryPoolsExportsEnabled` adds `jvm_memory_pool_allocated_bytes_total`. The endpoint can now also answer protobuf when a scraper asks for it. Embedders get `prometheus-metrics-core` 1.9.0 instead of `simpleclient`, and the embedded agent's metrics register in the 1.x `PrometheusRegistry.defaultRegistry`

### Bug Fixes

- Fix the Grafana dashboards, the alert rules on the Grafana page, and the PromQL examples, which queried seven counters by their bare names (`proxy_connect_count`, `proxy_eviction_count`, `proxy_heartbeat_count`, `proxy_scrape_requests`, `agent_scrape_request_count`, `agent_scrape_result_count`, `agent_connect_count`). A counter is exposed only as `<name>_total`, so those panels and alerts showed no data. They now use the `_total` names, and `DashboardMetricNamesTest` checks every query against the series the proxy and agent expose

### Dependencies

- Update common-utils 4.1.0 → 5.0.0 and the Prometheus Java client `simpleclient` 0.16.0 → `prometheus-metrics-core` 1.9.0
```

Edit the lists to match Step 3's notes.

- [ ] **Step 7: RELEASE_NOTES entry**

Under `## Unreleased`, before `### Bug Fixes`, add:

```markdown
### Before you upgrade

- **The Prometheus client underneath moved to 1.x.** The proxy and agent now use the Prometheus Java client
  1.9.0 instead of the unmaintained 0.16.0. Every `proxy_*` and `agent_*` metric keeps its name, labels and
  buckets. What changes on `/metrics`:
  - The `_created` series of every counter and histogram are gone. Set
    `IO_PROMETHEUS_EXPORTER_INCLUDE_CREATED_TIMESTAMPS=true` to bring them back.
  - With the JVM exports on, the memory metrics put the unit last: `jvm_memory_bytes_used` is now
    `jvm_memory_used_bytes`, `jvm_memory_pool_bytes_used` is `jvm_memory_pool_used_bytes`, and the same for
    `committed`, `max` and `init`. `jvm_info` is `jvm_runtime_info`. Update dashboards and alerts that use them.
- **Embedders:** the agent brings `io.prometheus:prometheus-metrics-core` 1.9.0 instead of
  `io.prometheus:simpleclient`, and registers its metrics in the 1.x default registry. A host that serves the 0.x
  registry no longer sees the agent's metrics.
```

and under `### Bug Fixes`:

```markdown
- **The bundled Grafana dashboards show connection, eviction, heartbeat and scrape counts again.** Their panels
  (and the alert rules on the Grafana page) queried counters without the `_total` suffix they are exposed with,
  so they showed no data. Re-import `grafana/prometheus-proxy.json` and `grafana/prometheus-agents.json`.
```

- [ ] **Step 8: Review**

`git diff --stat`; read the whole CHANGELOG and RELEASE_NOTES diff once against `out/exposition/diff-notes.md`.

---

### Task 7: Full verification and PR readiness

**Files:**
- Modify (revert): `settings.gradle.kts`

- [ ] **Step 1: Lint, build, and the full check**

```bash
./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test
./gradlew --rerun-tasks check
```

Expected: all green.

- [ ] **Step 2: Per-class coverage, in its own invocation**

```bash
./gradlew koverVerifyPerClass
```

Expected: PASS (`Utils`, `ProxyMetrics`, `AgentMetrics` stay above the per-class floor).

- [ ] **Step 3: Lincheck (the metrics spec changed)**

```bash
make lincheck-tests
```

Expected: `ProxyMetricsLincheckTest` and the other Lincheck specs pass.

- [ ] **Step 4: Container suite (fat JARs, real network, Prometheus 3)**

Requires Docker.

```bash
make container-tests
```

Expected: PASS, including the tightened `ContainersProxyHttpTest` checks. If image builds fail with "content digest not found", run `make docker-clean` and retry (image-store bloat, not a regression).

- [ ] **Step 5: Confirm no 0.x client in the fat JARs**

```bash
./gradlew -q proxyJar agentJar
unzip -l build/libs/prometheus-proxy.jar | grep -c 'io/prometheus/client/' || true
unzip -l build/libs/prometheus-agent.jar | grep -c 'io/prometheus/client/' || true
```

Expected: `0` for both.

- [ ] **Step 6: Remove the temporary repository**

Revert the Task 1 `mavenLocal` edit:

```bash
git restore settings.gradle.kts
git diff settings.gradle.kts
```

Expected: no diff.

- [ ] **Step 7: Gate on Maven Central**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://repo1.maven.org/maven2/com/pambrose/common-utils/prometheus-utils/5.0.0/prometheus-utils-5.0.0.pom
```

Expected: `200`. Until it is, CI (which resolves from Maven Central only) cannot build the branch: hold the PR. Once it is `200`, rerun `./gradlew --refresh-dependencies build -x test` without `mavenLocal` to prove resolution from Central.

- [ ] **Step 8: Hand back**

Report the results of Steps 1–7 and the Task 6 diff notes to the user, and wait for an instruction to commit and open the PR (no `(#N)` in the PR title).

---

## Out of scope

- Exposing common-utils 5.0.0's new `SystemMetrics` flags (`enableBufferPoolExports`, `enableCompilationExports`, `enableNativeMemoryExports`) as `proxy.metrics.*` / `agent.metrics.*` settings. That needs `config/config.conf`, a `ConfigVals` regeneration (`make tsconfig`), CLI docs and tests; do it as its own change if wanted.
- The `metrics.grpc.metricsEnabled` / `allMetricsReported` settings: nothing in the proxy or common-utils reads them today. Unrelated to this migration; worth its own issue.
