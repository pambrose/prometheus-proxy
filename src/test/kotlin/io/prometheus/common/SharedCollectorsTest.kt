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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry

class SharedCollectorsTest : StringSpec() {
  private fun registeredNames() = PrometheusRegistry.defaultRegistry.scrape().map { it.metadata.name }

  init {
    "the first acquire should register the collectors, and the last release unregister and clear them" {
      val counter = Counter.builder().name("shared_test_events").labelNames("id").build()
      val sentinel = Gauge.builder().name("shared_test_sentinel").build()
      val shared = SharedCollectors<String>("shared_test_sentinel", listOf(counter, sentinel))

      shared.acquire("a", "first")
      shared.acquire("b", "second")
      counter.labelValues("a").inc()
      registeredNames() shouldContain "shared_test_events"
      shared.instances() shouldContainExactly listOf("first", "second")

      shared.release("a")
      registeredNames() shouldContain "shared_test_sentinel"

      shared.release("b")
      registeredNames() shouldNotContain "shared_test_sentinel"
      registeredNames() shouldNotContain "shared_test_events"
      counter.collect().dataPoints.shouldBeEmpty()
    }

    "an acquire after the registry was cleared should register the collectors again" {
      val sentinel = Gauge.builder().name("shared_test_cleared_sentinel").build()
      val shared = SharedCollectors<String>("shared_test_cleared_sentinel", listOf(sentinel))
      shared.acquire("a", "first")

      PrometheusRegistry.defaultRegistry.unregister(sentinel)
      registeredNames() shouldNotContain "shared_test_cleared_sentinel"
      shared.acquire("b", "second")

      registeredNames() shouldContain "shared_test_cleared_sentinel"
      shared.release("a")
      shared.release("b")
    }

    // A host application's metric can already have one of the names. The collectors registered before the rejected
    // one must not stay behind, or the next acquire, and the host, would find a half-registered set.
    "a collector the registry rejects should leave none of them registered" {
      val hostMetric = Gauge.builder().name("shared_test_conflict").register()
      try {
        val first = Gauge.builder().name("shared_test_first").build()
        val conflicting = Counter.builder().name("shared_test_conflict").build()
        val shared = SharedCollectors<String>("shared_test_first", listOf(first, conflicting))

        shouldThrow<IllegalArgumentException> { shared.acquire("a", "first") }

        registeredNames() shouldNotContain "shared_test_first"
        shared.instances().shouldBeEmpty()
      } finally {
        PrometheusRegistry.defaultRegistry.unregister(hostMetric)
      }
    }
  }
}
