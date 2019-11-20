/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import java.lang.System.getenv

enum class EnvVars {

  // Proxy
  PROXY_CONFIG,
  PROXY_PORT,
  AGENT_PORT,

  // Agent
  AGENT_CONFIG,
  PROXY_HOSTNAME,
  AGENT_NAME,

  // Common
  DEBUG_ENABLED,
  METRICS_ENABLED,
  METRICS_PORT,
  ADMIN_ENABLED,
  ADMIN_PORT;

  fun getEnv(defaultVal: String) = getenv(name) ?: defaultVal

  fun getEnv(defaultVal: Boolean) = getenv(name)?.toBoolean() ?: defaultVal

  fun getEnv(defaultVal: Int) = getenv(name)?.toInt() ?: defaultVal
}
