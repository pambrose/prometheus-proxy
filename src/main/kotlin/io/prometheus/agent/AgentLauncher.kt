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

@file:JvmName("AgentLauncher")

package io.prometheus.agent

import io.prometheus.Agent
import io.prometheus.common.Utils.suppressKotlinLoggingStartupMessage

// The Main-Class of prometheus-agent.jar. Agent can't be it: its GenericService superclass creates a kotlin-logging
// logger in its static initializer, which runs before Agent.main, and kotlin-logging prints its startup line when the
// first logger is created. Nothing here creates a logger, so the line can be turned off before Agent is loaded.
internal fun main(args: Array<String>) {
  suppressKotlinLoggingStartupMessage()
  Agent.main(args)
}
