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

package website

// --8<-- [start:embedded-kotlin]
import io.prometheus.Agent

fun main() {
  // Start the agent in the background
  val agentInfo = Agent.startAsyncAgent(
    configFilename = "agent-config.conf",
    exitOnMissingConfig = true,
  )

  println("Agent started: ${agentInfo.agentName}")

  // Your application code runs here...
  // The agent runs in background coroutines

  // Stop the agent when your application exits
  agentInfo.shutdown()
}
// --8<-- [end:embedded-kotlin]
