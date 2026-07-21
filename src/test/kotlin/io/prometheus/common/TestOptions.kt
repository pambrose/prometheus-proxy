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

import com.typesafe.config.ConfigFactory
import io.prometheus.agent.AgentOptions
import io.prometheus.proxy.ProxyOptions

// Test-only factories that build ProxyOptions/AgentOptions from a List<String>.
// They pin the List<String> constructor overload so call sites can pass a collection
// literal (e.g. proxyOptions(["-p", "8080"])) without hitting the Array-vs-List overload
// ambiguity that a bare literal would create against the raw constructors.
fun proxyOptions(args: List<String>): ProxyOptions = ProxyOptions(args)

fun agentOptions(
  args: List<String>,
  exitOnMissingConfig: Boolean,
): AgentOptions = AgentOptions(args, exitOnMissingConfig)

/**
 * Builds [ConfigVals] from a HOCON fragment the way `BaseOptions.readConfig` does in production.
 *
 * The reference-config merge is mandatory, not cosmetic: tscfg emits an unguarded `c.getList()` for
 * every list-typed key, so a fixture built from a bare `parseString` throws `ConfigException.Missing`
 * the moment a new list is added to the schema — and [ConfigVals] eagerly constructs *both* the agent
 * and proxy trees, so an agent-side list breaks proxy-only fixtures too. Adding `agent.proxy.endpoints`
 * broke three such fixtures at once.
 */
fun testConfigVals(hocon: String): ConfigVals =
  ConfigVals(ConfigFactory.parseString(hocon.trimIndent()).withFallback(ConfigFactory.load()).resolve())
