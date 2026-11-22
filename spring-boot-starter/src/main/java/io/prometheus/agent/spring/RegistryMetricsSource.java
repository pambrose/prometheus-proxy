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

package io.prometheus.agent.spring;

import io.prometheus.agent.MetricsSource;
import io.prometheus.metrics.expositionformats.ExpositionFormatWriter;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link PrometheusRegistry}, such as the one Spring Boot puts Micrometer's meters in, served by the agent in-process:
 * each scrape gets the registry's metrics in the text format it accepts, Prometheus's or OpenMetrics, and only the
 * families it names, when it names any. The starter serves the app's registry with one; a
 * {@link PrometheusProxyAgentCustomizer} can serve another at a path of its own.
 */
public final class RegistryMetricsSource implements MetricsSource {
  private final PrometheusRegistry registry;
  private final ExpositionFormats formats = ExpositionFormats.init();

  /**
   * A source of {@code registry}'s metrics.
   *
   * @param registry the registry the agent reads for each scrape
   */
  public RegistryMetricsSource(PrometheusRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public String write(String accept, Set<String> names, OutputStream out) throws IOException {
    ExpositionFormatWriter writer = formats.findWriter(accept);
    MetricSnapshots snapshots = names.isEmpty() ? registry.scrape() : registry.scrape(names::contains);
    writer.write(out, snapshots);
    return writer.getContentType();
  }
}
