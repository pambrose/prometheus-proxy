/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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
import com.github.pambrose.common.util.sleep
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.prometheus.Proxy
import io.prometheus.proxy.Installs.configServer
import mu.KLogging
import kotlin.time.seconds

internal class ProxyHttpService(private val proxy: Proxy, val httpPort: Int, isTestMode: Boolean) :
    GenericIdleService() {
  private val proxyConfigVals = proxy.configVals.proxy
  private val idleTimeout =
    if (proxyConfigVals.http.idleTimeoutSecs == -1) 45.seconds else proxyConfigVals.http.idleTimeoutSecs.seconds

  private val tracing by lazy { proxy.zipkinReporterService.newTracing("proxy-http") }

  private val config: CIOApplicationEngine.Configuration.() -> Unit =
    { connectionIdleTimeoutSeconds = idleTimeout.inSeconds.toInt() }
  private val httpServer = embeddedServer(CIO, port = httpPort, configure = config) { configServer(proxy, isTestMode) }

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    httpServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()
    httpServer.stop(5.seconds.toLongMilliseconds(), 5.seconds.toLongMilliseconds())
    sleep(2.seconds)
  }

  override fun toString() = toStringElements { add("port", httpPort) }

  companion object : KLogging()
}
