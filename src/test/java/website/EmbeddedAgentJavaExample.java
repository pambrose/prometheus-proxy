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

package website;

// --8<-- [start:embedded-java]
import io.prometheus.Agent;
import io.prometheus.agent.EmbeddedAgentInfo;

public class EmbeddedAgentJavaExample {
  public static void main(String[] args) {
    // Start the agent in the background
    EmbeddedAgentInfo agentInfo =
      Agent.startAsyncAgent("agent-config.conf", true, true);

    System.out.println("Agent started: " + agentInfo.getAgentName());

    // Your application code runs here...
    // The agent runs in background threads

    // Stop the agent when your application exits
    agentInfo.shutdown();
  }
}
// --8<-- [end:embedded-java]
