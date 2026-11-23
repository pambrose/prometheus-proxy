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

package io.prometheus.agent.ktor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.io.ByteArrayOutputStream

class RegistryMetricsSourceTest : StringSpec() {
  init {
    val text = "text/plain;version=0.0.4;q=0.5,*/*;q=0.1"
    val openMetrics = "application/openmetrics-text;version=1.0.0;q=0.9,$text"

    val registry = PrometheusRegistry()
    Counter.builder().name("orders").help("Orders placed").register(registry).inc(3.0)
    Gauge.builder().name("queue_depth").help("Orders waiting").register(registry).set(7.0)
    val source = RegistryMetricsSource(registry)

    "writes every family in the text format the scrape accepts" {
      val out = ByteArrayOutputStream()

      val contentType = source.write(accept = text, names = emptySet(), out = out)

      contentType shouldStartWith "text/plain; version=0.0.4"
      out.toString(Charsets.UTF_8) shouldContain "orders_total 3.0"
      out.toString(Charsets.UTF_8) shouldContain "queue_depth 7.0"
    }

    "writes the text format when the scrape names no format" {
      val out = ByteArrayOutputStream()

      source.write(accept = "", names = emptySet(), out = out) shouldStartWith "text/plain; version=0.0.4"
    }

    "writes OpenMetrics when the scrape prefers it" {
      val out = ByteArrayOutputStream()

      val contentType = source.write(accept = openMetrics, names = emptySet(), out = out)

      contentType shouldStartWith "application/openmetrics-text"
      out.toString(Charsets.UTF_8) shouldContain "orders_total 3.0"
      out.toString(Charsets.UTF_8) shouldEndWith "# EOF\n"
    }

    // Prometheus's name[] parameter, which the agent passes on as the names
    "writes only the families the scrape names" {
      val out = ByteArrayOutputStream()

      source.write(accept = text, names = setOf("queue_depth"), out = out)

      out.toString(Charsets.UTF_8) shouldContain "queue_depth 7.0"
      out.toString(Charsets.UTF_8) shouldNotContain "orders"
    }
  }
}
