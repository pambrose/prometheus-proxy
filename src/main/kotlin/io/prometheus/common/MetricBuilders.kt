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

package io.prometheus.common

import io.prometheus.grpc.PathRejectionCause.UNRECOGNIZED
import io.prometheus.grpc.PathRejectionCause.REJECTION_CAUSE_UNSPECIFIED
import io.prometheus.grpc.PathRejectionCause
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.metrics.core.datapoints.GaugeDataPoint
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.Collector
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Builds the proxy's and agent's metrics with the settings they all share. They aren't registered: every agent, and
 * every proxy, in the JVM shares one set, which [SharedCollectors] registers in the default registry.
 *
 * No metric keeps exemplars: the proxy and agent trace with Brave, not OpenTelemetry, so they never have one to
 * record, and a 1.x metric otherwise keeps an exemplar sampler per series and schedules a task as it records.
 * Histograms are also classic-only: a native histogram per series would multiply by every registered path.
 */
internal object MetricBuilders {
  private const val MILLIS_PER_SECOND = 1_000.0

  private val logger = logger {}

  /**
   * The proxy's and agent's scrape latency buckets, in seconds. They reach the proxy's default
   * `scrapeRequestTimeoutSecs` (90), past the agent's default `scrapeTimeoutSecs` (15), so a timeout lands in a bucket
   * rather than `+Inf`.
   */
  val SCRAPE_LATENCY_BUCKETS =
    doubleArrayOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0)

  // Each counter built with startingAtZero, with those values. The counters are built once per JVM and never let go.
  private val zeroStarted = ConcurrentHashMap<Collector, List<String>>()

  /**
   * A counter, with a series at zero for each of [startingAtZero], the known values of its one label, whenever
   * [SharedCollectors] registers it ([startKnownSeriesAtZero]). A series created by its first increment starts at 1,
   * which increase() and rate() can't tell from a counter that was always 1, so a dashboard or an alert on a rare
   * event, such as one path refused, saw nothing until it happened twice. Only for a label whose values are a known,
   * bounded set.
   */
  fun counter(
    startingAtZero: Iterable<String> = emptyList(),
    block: Counter.Builder.() -> Unit,
  ): Counter =
    Counter.builder().withoutExemplars().apply(block).build().also { counter ->
      startingAtZero.toList().takeIf { it.isNotEmpty() }?.also { zeroStarted[counter] = it }
    }

  /** Creates [collector]'s series at zero, if it's a counter built with values starting at zero. */
  fun startKnownSeriesAtZero(collector: Collector) {
    zeroStarted[collector]?.forEach { (collector as Counter).labelValues(it) }
  }

  /** The enum entry as a metric's label gives it: its name, lowercased, such as `not_authorized`. */
  val Enum<*>.metricLabel: String get() = name.lowercase()

  // The causes a refused path can carry: not an accepted path's, nor the one protobuf gives an unknown value.
  private val REJECTED = PathRejectionCause.entries - setOf(REJECTION_CAUSE_UNSPECIFIED, UNRECOGNIZED)

  /** The `cause` labels of the path rejection counters, the proxy's and the agent's: each cause a refusal carries. */
  val REJECTION_CAUSES = REJECTED.map { it.metricLabel }

  /** [cause] as a path rejection counter's `cause` label gives it; `unspecified` from a proxy predating the field. */
  fun rejectionCauseLabel(cause: PathRejectionCause): String =
    if (cause in REJECTED) cause.metricLabel else "unspecified"

  fun gauge(block: Gauge.Builder.() -> Unit): Gauge = Gauge.builder().withoutExemplars().apply(block).build()

  fun histogram(block: Histogram.Builder.() -> Unit): Histogram =
    Histogram.builder().classicOnly().withoutExemplars().apply(block).build()

  /**
   * A gauge whose values [sample] reports on each scrape. A value that throws is reported as NaN: the callback runs
   * inside the scrape, and must not fail every other metric with it.
   */
  fun sampledGauge(
    name: String,
    help: String,
    vararg labelNames: String,
    sample: SampledGauge.() -> Unit,
  ): GaugeWithCallback =
    GaugeWithCallback.builder()
      .name(name)
      .help(help)
      .labelNames(*labelNames)
      .callback { callback -> SampledGauge(name, callback).sample() }
      .build()

  class SampledGauge internal constructor(
    private val name: String,
    private val callback: GaugeWithCallback.Callback,
  ) {
    fun report(
      vararg labelValues: String,
      value: () -> Double,
    ) {
      val sampled =
        runCatching(value).getOrElse { e ->
          logger.warn(e) { "Sampler for metric \"$name\" threw; reporting NaN" }
          Double.NaN
        }
      callback.call(sampled, *labelValues)
    }
  }

  /** Sets this gauge to the current Unix time in seconds, as the 0.x client's `setToCurrentTime()` did. */
  fun GaugeDataPoint.setToCurrentTime() = set(Clock.System.now().toEpochMilliseconds() / MILLIS_PER_SECOND)
}
