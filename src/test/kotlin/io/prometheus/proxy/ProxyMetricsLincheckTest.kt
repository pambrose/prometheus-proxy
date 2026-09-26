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

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.prometheus.Proxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.common.Lincheck
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger

// Lincheck races ProxyMetrics' per-path histogram series: a path registering, scrapes of it finishing and recording
// latency and response size, and the path's last registration going away, which removes its series. A scrape that
// finishes just after the removal must not re-create series nothing would remove again, leaving a retired path on
// /metrics forever; observing and removing both run inside the path map's per-key compute to prevent that. After every
// run, validate removes each of the run's paths and checks that no series labelled with them is left in either
// histogram.
//
// The histograms register in Prometheus' default registry, which allows each name once, so every run shares one
// ProxyMetrics and uses its own path names. Stress only, as the shared registry is more than model checking can
// replay.
class ProxyMetricsLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "per-path series never outlive their path's removal under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(ProxyMetricsOps::class)
    }
  }

  companion object {
    private const val ITERATIONS = 20
    private const val INVOCATIONS = 200
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

@Param(name = "path", gen = IntGen::class, conf = "0:2")
class ProxyMetricsOps {
  private val paths = List(PATH_COUNT) { "metrics_lincheck_${RUN_IDS.incrementAndGet()}_$it" }

  // ProxyPathManager.addValidatedPath, as a path registers.
  @Operation
  fun register(
    @Param(name = "path") path: Int,
  ) = METRICS.pathRegistered(paths[path])

  // ProxyHttpRoutes, as a scrape of the path finishes.
  @Operation
  fun scrapeFinished(
    @Param(name = "path") path: Int,
  ) {
    METRICS.observeLatency(paths[path], "success", 0.1)
    METRICS.observeResponseBytes(paths[path], "gzip", 100.0)
  }

  // ProxyPathManager, as the path's last registration goes away.
  @Operation
  fun remove(
    @Param(name = "path") path: Int,
  ) = METRICS.removePathSeries(paths[path])

  @Validate
  fun validate() {
    paths.forEach { METRICS.removePathSeries(it) }
    val left = seriesFor(METRICS.scrapeRequestLatency) + seriesFor(METRICS.scrapeResponseBytes)
    check(left.isEmpty()) { "Series left for removed paths: $left" }
  }

  // The label sets of this run's paths that histogram still holds.
  private fun seriesFor(histogram: Histogram): Set<String> =
    histogram.collect().dataPoints
      .filter { it.labels.get("path") in paths }
      .map { it.labels.toString() }
      .toSet()

  companion object {
    private const val PATH_COUNT = 3
    private val RUN_IDS = AtomicInteger()

    // One ProxyMetrics for every run: its histograms register in the default registry, which accepts each name once.
    private val METRICS: ProxyMetrics by lazy {
      PrometheusRegistry.defaultRegistry.clear()
      Proxy(
        options = ProxyOptions(listOf()),
        inProcessServerName = "proxy-metrics-lincheck",
        testMode = true,
      ).metrics
    }
  }
}
