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

// The Embedded Agent page's program that runs until it's stopped, as a queue consumer does: the hook that has SIGTERM
// stop its work first, and its agent after it. Worker, below the snippet, is a stand-in for the program's work.

// --8<-- [start:embedded-kotlin-shutdown]
import io.prometheus.agent.embeddedAgent

fun main(args: Array<String>) {
  val worker = Worker(args)

  // On SIGTERM, the JVM runs its shutdown hooks, then exits without returning from main. This one has the worker
  // finish what it's doing, then waits for main to return, by which time use { } has stopped the agent.
  val mainThread = Thread.currentThread()
  Runtime.getRuntime().addShutdownHook(
    Thread {
      worker.stop()
      mainThread.join()
    },
  )

  embeddedAgent {
    // The agent's settings
    fromEnvironment()
  }.startIfConfigured().use { worker.run() }
}
// --8<-- [end:embedded-kotlin-shutdown]

// A stand-in for the program's work, which runs until it's stopped.
class Worker(
  val args: Array<String>,
) {
  // Does the work, until stop()
  fun run() = Unit

  // Has run() return once the work in hand is done
  fun stop() = Unit
}
