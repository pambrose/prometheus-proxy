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

import com.pambrose.common.dsl.PrometheusDsl
import io.prometheus.metrics.core.datapoints.GaugeDataPoint
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram

/**
 * Builds the proxy's and agent's metrics in the default registry, with the settings they all share.
 *
 * No metric keeps exemplars: the proxy and agent trace with Brave, not OpenTelemetry, so they never have one to
 * record, and a 1.x metric otherwise keeps an exemplar sampler per series and schedules a task as it records.
 * Histograms are also classic-only: a native histogram per series would multiply by every registered path.
 */
internal object MetricBuilders {
  private const val MILLIS_PER_SECOND = 1_000.0

  fun counter(block: Counter.Builder.() -> Unit): Counter =
    PrometheusDsl.counter {
      withoutExemplars()
      block()
    }

  fun gauge(block: Gauge.Builder.() -> Unit): Gauge =
    PrometheusDsl.gauge {
      withoutExemplars()
      block()
    }

  fun histogram(block: Histogram.Builder.() -> Unit): Histogram =
    PrometheusDsl.histogram {
      classicOnly()
      withoutExemplars()
      block()
    }

  /** Sets this gauge to the current Unix time in seconds, as the 0.x client's `setToCurrentTime()` did. */
  fun GaugeDataPoint.setToCurrentTime() = set(System.currentTimeMillis() / MILLIS_PER_SECOND)
}
