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

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

// The metadata the configuration processor writes into the jar, from which an IDE completes and describes the
// properties. It takes each description from a field's Javadoc, which an incremental compile that doesn't recompile
// the properties class drops, so the build compiles the starter in full each time.
class ConfigurationMetadataTest {
  @Test
  void everyPropertyHasADescription() throws IOException {
    List<String> undescribed = metadata().read("$.properties[?(!@.description)].name");

    assertThat(undescribed).isEmpty();
  }

  @Test
  void theDefaultsAreThePropertiesInitialValues() throws IOException {
    DocumentContext metadata = metadata();

    assertThat(defaultOf(metadata, "prometheus-proxy.agent.enabled")).containsExactly(true);
    assertThat(defaultOf(metadata, "prometheus-proxy.agent.source")).containsExactly("registry");
    assertThat(defaultOf(metadata, "prometheus-proxy.agent.dashboard.username")).containsExactly("admin");
  }

  private static DocumentContext metadata() throws IOException {
    try (InputStream in =
        ConfigurationMetadataTest.class.getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
      assertThat(in).isNotNull();
      return JsonPath.parse(in);
    }
  }

  private static List<Object> defaultOf(DocumentContext metadata, String name) {
    return metadata.read("$.properties[?(@.name == '" + name + "')].defaultValue");
  }
}
