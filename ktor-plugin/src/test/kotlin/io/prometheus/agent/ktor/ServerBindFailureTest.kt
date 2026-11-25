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

package io.prometheus.agent.ktor

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// An app whose server can't bind its port, run in a JVM of its own (TakenPortApp), as a process manager runs one:
// Ktor raises ApplicationStarted, which starts the agent, before its engine binds, and then throws without disposing
// the app, so nothing stops the agent. Its thread kept the JVM up, the app answering nothing with its agent connected,
// where the process should have died and been restarted (November 23 review, #10). An embedded agent's thread is now a
// daemon.
class ServerBindFailureTest : StringSpec() {
  init {
    "an app whose port is taken exits, rather than run on with its agent" {
      ServerSocket(TAKEN_PORT, 50, InetAddress.getLoopbackAddress()).use {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
          ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            TakenPortApp::class.java.name,
            "$TAKEN_PORT",
            "${closedPort()}",
          ).redirectErrorStream(true).start()
        val output = StringBuffer()
        val reader =
          thread(isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
          }

        try {
          val exited = process.waitFor(EXIT_WAIT_SECS, TimeUnit.SECONDS)
          reader.join(TimeUnit.SECONDS.toMillis(5))
          // Captured as values first, so each check below reports on its own inside assertSoftly
          val exitValue = if (exited) process.exitValue() else null
          val shown = output.toString()

          assertSoftly {
            withClue(shown) {
              exited shouldBe true
              // main() threw
              exitValue shouldBe 1
              shown shouldContain "Agent started: ${TakenPortApp.AGENT_NAME}"
              shown shouldContain "BindException"
              // By Ktor's shutdown hook, which stops the agent through the plugin
              shown shouldContain "App disposed"
            }
          }
        } finally {
          process.destroyForcibly()
        }
      }
    }
  }

  private companion object {
    // Fixed, below the ephemeral range, as the integration test's are; the root project's TestPorts lists it
    // (KTOR_PLUGIN_TAKEN_PORT), so no root spec binds it.
    const val TAKEN_PORT = 11404

    // How long the app has to exit once it starts; it takes a few seconds
    const val EXIT_WAIT_SECS = 30L
  }
}
