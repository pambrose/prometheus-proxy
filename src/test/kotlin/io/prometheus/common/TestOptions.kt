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
