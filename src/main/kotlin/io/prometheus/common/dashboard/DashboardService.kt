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

package io.prometheus.common.dashboard

import com.codahale.metrics.health.HealthCheck
import com.google.common.util.concurrent.MoreExecutors
import com.pambrose.common.concurrent.GenericIdleService
import com.pambrose.common.concurrent.genericServiceListener
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger

/**
 * A dashboard as a service of the proxy or the agent: its [server]'s lifecycle, its health check, and how it describes
 * itself. A dashboard's own part is its [server]'s [DashboardSite].
 */
internal abstract class DashboardService<V : Any, S : Any> : GenericIdleService() {
  /** The dashboard's server, serving its site. */
  protected abstract val server: DashboardServer<V, S>

  val healthCheck =
    healthCheck {
      if (isRunning)
        HealthCheck.Result.healthy()
      else
        HealthCheck.Result.unhealthy("$simpleClassName is not running")
    }

  /** How many browsers have the page open. */
  val sessionsOpen: Int get() = server.sessionsOpen

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    server.start()
    logger.info { "Started $simpleClassName on ${server.settings.host}:${server.settings.port} at ${server.basePath}" }
  }

  override fun shutDown() {
    server.stop()
  }

  override fun toString() =
    toStringElements {
      add("host", server.settings.host)
      add("port", server.settings.port)
      add("path", server.basePath)
      add("login", if (server.loginEnabled) "on" else "off")
      add("sessions", server.sessionsOpen)
    }

  private companion object {
    private val logger = logger {}
  }
}
