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

package io.prometheus.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import io.prometheus.common.MetricBuilders.counter
import io.prometheus.common.MetricBuilders.gauge
import io.prometheus.common.MetricBuilders.histogram
import io.prometheus.common.MetricBuilders.setToCurrentTime
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.Labels

class MetricBuildersTest : StringSpec() {
  private val traceLabels = Labels.of("trace_id", "t")

  init {
    beforeEach {
      PrometheusRegistry.defaultRegistry.clear()
    }

    "counter should keep no exemplars" {
      val counter =
        counter {
          name("metric_builders_test_total")
          help("Metric builders test counter")
        }
      counter.incWithExemplar(traceLabels)

      counter.collect().dataPoints.single().exemplar.shouldBeNull()
    }

    "gauge should keep no exemplars" {
      val gauge =
        gauge {
          name("metric_builders_test_gauge")
          help("Metric builders test gauge")
        }
      gauge.setWithExemplar(1.0, traceLabels)

      gauge.collect().dataPoints.single().exemplar.shouldBeNull()
    }

    "histogram should keep classic buckets only and no exemplars" {
      val histogram =
        histogram {
          name("metric_builders_test_seconds")
          help("Metric builders test histogram")
          classicUpperBounds(1.0)
        }
      histogram.observeWithExemplar(0.5, traceLabels)

      val point = histogram.collect().dataPoints.single()
      point.hasNativeHistogramData().shouldBeFalse()
      point.exemplars.size() shouldBe 0
    }

    // The 1.x client has no setToCurrentTime(). The start-time gauges feed `time() - *_start_time_seconds` on the
    // dashboards, so the value must be Unix seconds, not milliseconds.
    "setToCurrentTime should set the gauge to the current Unix time in seconds" {
      val gauge =
        Gauge.builder()
          .name("metric_builders_test_start_time_seconds")
          .help("Metric builders test start time")
          .labelNames("id")
          .build()

      val before = System.currentTimeMillis() / 1_000.0
      gauge.labelValues("a").setToCurrentTime()
      val after = System.currentTimeMillis() / 1_000.0

      gauge.labelValues("a").get() shouldBeIn before..after
    }
  }
}
