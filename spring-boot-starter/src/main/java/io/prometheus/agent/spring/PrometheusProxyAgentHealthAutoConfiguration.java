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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Reports the agent's connection as Actuator's {@code prometheusAgent} health component, when Spring Boot's health
 * support is present and the app has one {@link PrometheusProxyAgent}, the starter's or its own.
 * {@code management.health.prometheusagent.enabled} turns it off.
 */
// After the agent's auto-configuration, which the single-candidate check needs to have made its bean. An app without
// spring-boot-health skips it on the class check alone: the enabled check's condition is in spring-boot-health, so it's
// on the bean method, which is read only once the class check has passed. Its name is lowercase, as Spring Boot's are.
@AutoConfiguration(after = PrometheusProxyAgentAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
public final class PrometheusProxyAgentHealthAutoConfiguration {
  // Only for one agent: an app with agents of its own, more than one, starts without the indicator, which couldn't
  // choose between them
  @Bean
  @ConditionalOnEnabledHealthIndicator("prometheusagent")
  @ConditionalOnSingleCandidate(PrometheusProxyAgent.class)
  @ConditionalOnMissingBean(name = "prometheusAgentHealthIndicator")
  PrometheusProxyAgentHealthIndicator prometheusAgentHealthIndicator(PrometheusProxyAgent agent) {
    return new PrometheusProxyAgentHealthIndicator(agent);
  }
}
