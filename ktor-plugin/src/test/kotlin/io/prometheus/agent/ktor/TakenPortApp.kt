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

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

// A Ktor app with the plugin, as the Ktor demo's coffee shop starts one, which ServerBindFailureTest runs in a JVM of
// its own: it serves on the port its first argument names, which the test holds, so its start fails, as an app's does
// when a second copy or a stale process has its port. Its agent's proxy is the port its second argument names.
object TakenPortApp {
  @JvmStatic
  fun main(args: Array<String>) {
    val (port, proxyPort) = args.map(String::toInt)
    embeddedServer(factory = CIO, host = "127.0.0.1", port = port) {
      install(PrometheusProxyAgent) {
        agent {
          name = AGENT_NAME
          proxy = "localhost:$proxyPort"
        }
      }
      // After the plugin's handler, which started the agent, so the test knows the agent ran before the bind failed
      monitor.subscribe(ApplicationStarted) { println("Agent started: ${it.prometheusProxyAgent?.agentName}") }
      // Raised once the app is disposed, after the plugin's ApplicationStopping handler has stopped the agent: here by
      // Ktor's shutdown hook, as the JVM exits
      monitor.subscribe(ApplicationStopped) { println("App disposed") }
    }.start(wait = true)
  }

  const val AGENT_NAME = "taken-port-app"
}
