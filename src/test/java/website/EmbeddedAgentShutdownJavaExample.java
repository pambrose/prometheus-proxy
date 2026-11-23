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

import io.prometheus.agent.EmbeddedAgentConfig;
import io.prometheus.agent.EmbeddedAgentInfo;

// The Embedded Agent page's program that runs until it's stopped, as a queue consumer does: the hook that has SIGTERM
// stop its work first, and its agent after it. Worker, below the snippet, is a stand-in for the program's work.
public class EmbeddedAgentShutdownJavaExample {
  // --8<-- [start:embedded-java-shutdown]
  public static void main(String[] args) {
    Worker worker = new Worker(args);

    // On SIGTERM, the JVM runs its shutdown hooks, then exits without returning from main. This one has the worker
    // finish what it's doing, then waits for main to return, by which time try-with-resources has stopped the agent.
    Thread mainThread = Thread.currentThread();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      worker.stop();
      try {
        mainThread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    EmbeddedAgentConfig config =
      EmbeddedAgentConfig.builder()
        // The agent's settings
        .fromEnvironment()
        .build();
    // Null when the agent stays off, which try-with-resources takes: the worker runs either way
    try (EmbeddedAgentInfo agent = config.startIfConfigured()) {
      worker.run();
    }
  }
  // --8<-- [end:embedded-java-shutdown]

  // A stand-in for the program's work, which runs until it's stopped.
  static final class Worker {
    final String[] args;

    Worker(String[] args) {
      this.args = args;
    }

    void run() {
      // Does the work, until stop()
    }

    void stop() {
      // Has run() return once the work in hand is done
    }
  }
}
