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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Runs an embedded agent with the app, as {@link PrometheusProxyAgent}, unless {@code prometheus-proxy.agent.enabled}
 * is false. An app's own {@code PrometheusProxyAgent} bean takes the place of the starter's.
 * {@link PrometheusProxyAgentHealthAutoConfiguration} reports its connection.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "prometheus-proxy.agent", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(PrometheusProxyAgentProperties.class)
public final class PrometheusProxyAgentAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  PrometheusProxyAgent prometheusProxyAgent(PrometheusProxyAgentProperties properties, ApplicationContext context) {
    return new PrometheusProxyAgent(properties, context);
  }
}
