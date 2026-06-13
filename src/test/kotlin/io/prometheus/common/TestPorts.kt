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

/**
 * Canonical port numbers shared across the test suite (unit, harness, and Testcontainers specs).
 *
 * These mirror the proxy/agent config defaults and the fixed ports used by the container suite. They
 * live in a neutral test-support object so any test package can reference them without depending on
 * the Testcontainers support harness.
 */
object TestPorts {
  const val PROXY_HTTP_PORT = 8080
  const val PROXY_METRICS_PORT = 8082
  const val PROXY_ADMIN_PORT = 8092
  const val PROXY_AGENT_PORT = 50051

  const val AGENT_METRICS_PORT = 8083
  const val AGENT_ADMIN_PORT = 8093

  const val PROMETHEUS_PORT = 9090
  const val NGINX_PORT = 80
}
