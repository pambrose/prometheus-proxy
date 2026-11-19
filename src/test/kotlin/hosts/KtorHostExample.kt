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

package hosts

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import website.startPrometheusAgent

// The Cloud Platforms page's Ktor example: where a Ktor app starts and stops the embedded agent. Here, outside
// src/test/kotlin/website, since consumer-check compiles that directory against the published POM alone, which puts no
// Ktor server on a host's compile classpath.

// --8<-- [start:ktor-host]
fun Application.module() {
  routing {
    get("/metrics") { call.respondText("my_app_up 1\n") }
  }

  // Started once the routes are in place, so the agent's first scrape finds /metrics
  val agent = startPrometheusAgent(appVersion = "1.0.0", appPort = 8080)

  // Stopped once the application has stopped. In development mode, a reload stops the old application, and its
  // agent with it, before the new one starts its own.
  monitor.subscribe(ApplicationStopped) { agent?.shutdown() }
}
// --8<-- [end:ktor-host]
