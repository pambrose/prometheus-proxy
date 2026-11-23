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
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;

// The worker demo's oven's main (demos/worker/app/src/main/kotlin/bakery/Main.kt), in Java, for the Worker Demo page's
// Java tab: replicaName() is below the snippet, as in Main.kt, and the oven and its queue are stand-ins.
public class WorkerDemoJavaExample {
  // --8<-- [start:worker-java]
  public static void main(String[] args) {
    // The host name, the container's ID in Docker, so each oven's paths are its own
    String replica = replicaName();
    Oven oven = new Oven(new OrderQueue(System.getenv("REDIS_URL")), PrometheusRegistry.defaultRegistry);

    // On SIGTERM, the JVM runs its shutdown hooks, then exits without returning from main. This one has the oven
    // finish the order it's baking and take no more, then waits for main to return, by which time try-with-resources
    // has stopped the agent.
    Thread mainThread = Thread.currentThread();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      oven.stop();
      try {
        mainThread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    EmbeddedAgentConfig config =
      EmbeddedAgentConfig.builder()
        .name("oven-" + replica)
        .fromEnvironment()
        .source("oven_" + replica + "_metrics", PrometheusRegistry.defaultRegistry)
        .agentMetricsAt("oven_" + replica + "_agent_metrics")
        .build();
    // Null when the agent stays off, which try-with-resources takes: the oven runs either way
    try (EmbeddedAgentInfo agent = config.startIfConfigured(false)) {
      oven.runUntilStopped();
    }
  }
  // --8<-- [end:worker-java]

  // The oven's name among the others: HOSTNAME, which Docker sets to the container's ID, or else this machine's host
  // name, as on a developer's machine, whose shell doesn't usually export HOSTNAME. Any character but a letter, a
  // digit, '.', '_', or '-' becomes '-', so it can name a proxy path, which can't hold whitespace, '?', or '#'.
  static String replicaName() {
    String name = System.getenv("HOSTNAME");
    if (name == null || name.isBlank()) {
      try {
        name = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        name = "local";
      }
    }
    return name.trim().replaceAll("[^A-Za-z0-9._-]", "-");
  }

  // A stand-in for the demo's queue, a Redis list.
  static final class OrderQueue {
    OrderQueue(String url) {
    }
  }

  // A stand-in for the demo's oven, which bakes the queue's orders until it's stopped, and records its metrics in the
  // registry.
  static final class Oven {
    Oven(OrderQueue queue, PrometheusRegistry registry) {
    }

    void runUntilStopped() {
    }

    void stop() {
    }
  }
}
