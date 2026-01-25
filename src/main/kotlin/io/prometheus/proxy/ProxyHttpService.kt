/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "UnstableApiUsage")

package io.prometheus.proxy

import com.github.pambrose.common.concurrent.GenericIdleService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.google.common.util.concurrent.MoreExecutors
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine.Configuration
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.prometheus.Proxy
import io.prometheus.common.Utils.lambda
import io.prometheus.proxy.ProxyHttpConfig.configureKtorServer
import io.prometheus.proxy.ProxyHttpRoutes.configureHttpRoutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit.SECONDS

internal class ProxyHttpService(
  private val proxy: Proxy,
  val httpPort: Int,
  isTestMode: Boolean,
) : GenericIdleService() {
  private val idleTimeout =
    with(proxy.proxyConfigVals.http) { (if (idleTimeoutSecs == -1) 45 else idleTimeoutSecs).seconds }

  private val tracing by lazy { proxy.zipkinReporterService.newTracing("proxy-http") }

  private fun getConfig(httpPort: Int): Configuration.() -> Unit =
    lambda {
      connector {
        host = "0.0.0.0"
        port = httpPort
      }
      connectionIdleTimeoutSeconds = idleTimeout.toInt(SECONDS)
    }

  private val httpServer =
    embeddedServer(factory = CIO, configure = getConfig(httpPort)) {
      configureKtorServer(proxy, isTestMode)
      configureHttpRoutes(proxy)
    }

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    httpServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()
    httpServer.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
    // sleep(2.seconds)
  }

  override fun toString() = toStringElements { add("port", httpPort) }

  companion object {
    private val logger = logger {}
  }
}
