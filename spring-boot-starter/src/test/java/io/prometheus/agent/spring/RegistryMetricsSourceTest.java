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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistryMetricsSourceTest {
  private static final String TEXT = "text/plain;version=0.0.4;q=0.5,*/*;q=0.1";
  private static final String OPEN_METRICS = "application/openmetrics-text;version=1.0.0;q=0.9," + TEXT;

  private final PrometheusRegistry registry = new PrometheusRegistry();
  private final RegistryMetricsSource source = new RegistryMetricsSource(registry);

  @BeforeEach
  void registerMetrics() {
    Counter.builder().name("beans_roasted").help("Batches of beans roasted").register(registry).inc(3);
    Gauge.builder().name("roaster_temperature_celsius").help("The roaster's temperature").register(registry).set(212);
  }

  @Test
  void writesEveryFamilyInTheTextFormatTheScrapeAccepts() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    String contentType = source.write(TEXT, Set.of(), out);

    assertThat(contentType).startsWith("text/plain; version=0.0.4");
    assertThat(out.toString(UTF_8)).contains("beans_roasted_total 3.0", "roaster_temperature_celsius 212.0");
  }

  @Test
  void writesOpenMetricsWhenTheScrapePrefersIt() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    String contentType = source.write(OPEN_METRICS, Set.of(), out);

    assertThat(contentType).startsWith("application/openmetrics-text");
    assertThat(out.toString(UTF_8)).contains("beans_roasted_total 3.0").endsWith("# EOF\n");
  }

  // Prometheus's name[] parameter, which the agent passes on as the names
  @Test
  void writesOnlyTheFamiliesTheScrapeNames() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    source.write(TEXT, Set.of("roaster_temperature_celsius"), out);

    assertThat(out.toString(UTF_8)).contains("roaster_temperature_celsius 212.0").doesNotContain("beans_roasted");
  }
}
