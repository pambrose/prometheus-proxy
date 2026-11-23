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

import io.prometheus.agent.EmbeddedAgentConfig;

/**
 * Changes the agent's settings in code, for what properties can't hold, such as a certificate read from a vault, or a
 * second registry, which the builder's {@code source(path, registry)} serves at a path of its own, as it does a
 * {@link io.prometheus.agent.MetricsSource}. Each customizer bean is called, in its {@code @Order}, after the
 * {@code prometheus-proxy.agent.*} properties are applied, and just before the agent starts. A builder method it calls
 * replaces what a property set, but not a {@code settings.<key>} property: that's a {@code setting()}, which takes
 * precedence over the builder's methods whatever their order. To change one, call {@code setting()} with its key.
 *
 * <pre>{@code
 * @Bean
 * PrometheusProxyAgentCustomizer agentCertificate(Vault vault) {
 *   return builder -> builder.trustCertificatePem(vault.read("proxy-ca"), "prometheus-proxy");
 * }
 * }</pre>
 */
@FunctionalInterface
public interface PrometheusProxyAgentCustomizer {
  /**
   * Changes the settings the agent starts with.
   *
   * @param builder the agent's settings, with the properties already applied
   */
  void customize(EmbeddedAgentConfig.Builder builder);
}
