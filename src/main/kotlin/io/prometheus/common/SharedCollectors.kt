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

import io.prometheus.metrics.core.metrics.StatefulMetric
import io.prometheus.metrics.model.registry.Collector
import io.prometheus.metrics.model.registry.PrometheusRegistry

/**
 * The collectors that every instance of a component in the JVM, such as every agent, shares in the default registry.
 *
 * The registry rejects a second collector with the same name and label names, so an instance can't register its own:
 * with metrics on, a second agent in one JVM, or one started again after a shutdown, failed on its first connect and
 * retried forever. These collectors are built once, registered when the first instance [acquire]s them, and
 * unregistered and cleared when the last one [release]s them, so a stopped instance's series don't outlive it. If the
 * registry is cleared in between, as the tests do, the next [acquire] registers them again.
 *
 * @param sentinelName the name of a metric among [collectors], used to tell whether they are still registered
 */
internal class SharedCollectors<T : Any>(
  private val sentinelName: String,
  private val collectors: List<Collector>,
) {
  private val registry get() = PrometheusRegistry.defaultRegistry

  // Keyed by launch ID. Guarded by this.
  private val instances = LinkedHashMap<String, T>()

  /** The instances holding the collectors, for the gauges that sample them on each scrape. */
  @Synchronized
  fun instances(): List<T> = instances.values.toList()

  @Synchronized
  fun acquire(
    key: String,
    instance: T,
  ) {
    if (registry.scrape { it == sentinelName }.none())
      register()
    instances[key] = instance
  }

  @Synchronized
  fun release(key: String) {
    if (instances.remove(key) != null && instances.isEmpty())
      collectors.forEach { collector ->
        registry.unregister(collector)
        (collector as? StatefulMetric<*, *>)?.clear()
      }
  }

  // All or nothing: when the registry rejects one, say for a host application's metric with the same name, the ones
  // already registered are unregistered again, so the failed start leaves nothing behind.
  private fun register() {
    val registered = mutableListOf<Collector>()
    runCatching {
      collectors.forEach { collector ->
        // A no-op unless the registry was cleared of only some of them.
        registry.unregister(collector)
        registry.register(collector)
        registered += collector
      }
    }.onFailure { e ->
      registered.forEach { registry.unregister(it) }
      throw e
    }
  }
}
