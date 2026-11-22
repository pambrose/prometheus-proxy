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

package website;

// --8<-- [start:embedded-java-source]
import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;
import io.prometheus.agent.MetricsSource;
import io.prometheus.metrics.expositionformats.ExpositionFormatWriter;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public class EmbeddedAgentSourceJavaExample {
  // The app's registry, which the agent reads in-process: Micrometer's PrometheusMeterRegistry gives its own with
  // getPrometheusRegistry(). Written in the format the scrape asks for, and only the families it names, if it names
  // any.
  public static MetricsSource registrySource(PrometheusRegistry registry) {
    ExpositionFormats formats = ExpositionFormats.init();
    return (accept, names, out) -> {
      ExpositionFormatWriter writer = formats.findWriter(accept);
      writer.write(out, names.isEmpty() ? registry.scrape() : registry.scrape(names::contains));
      return writer.getContentType();
    };
  }

  // The app needs no /metrics endpoint for the agent, and no credentials for one: the agent reads the registry itself.
  public static EmbeddedAgentInfo startPrometheusAgent(PrometheusRegistry registry, String proxyEndpoint) {
    return EmbeddedAgentConfig.builder()
      .name("my-app")
      .proxy(proxyEndpoint)
      .source("my_app_metrics", registrySource(registry))
      .build()
      .start(false);
  }
}
// --8<-- [end:embedded-java-source]
