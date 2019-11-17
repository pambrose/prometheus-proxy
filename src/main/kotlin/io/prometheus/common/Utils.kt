/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.JCommander
import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.google.common.util.concurrent.Service
import io.ktor.http.HttpStatusCode
import io.prometheus.Proxy
import kotlin.system.exitProcess
import kotlin.time.Duration

fun newBacklogHealthCheck(backlogSize: Int, size: Int) =
  healthCheck {
    if (backlogSize < size)
      HealthCheck.Result.healthy()
    else
      HealthCheck.Result.unhealthy("Large size: $backlogSize")
  }

fun newMapHealthCheck(map: Map<*, *>, size: Int) =
  healthCheck {
    if (map.size < size)
      HealthCheck.Result.healthy()
    else
      HealthCheck.Result.unhealthy("Large size: ${map.size}")
  }

fun getVersionDesc(asJson: Boolean): String {
  val annotation = Proxy::class.java.`package`.getAnnotation(VersionAnnotation::class.java)
  return if (asJson)
    """{"Version": "${annotation.version}", "Release Date": "${annotation.date}"}"""
  else
    "Version: ${annotation.version} Release Date: ${annotation.date}"
}

fun shutDownHookAction(service: Service) =
  Thread {
    System.err.println("*** ${service.simpleClassName} shutting down ***")
    service.stopAsync()
    System.err.println("*** ${service.simpleClassName} shut down complete ***")
  }

class VersionValidator : IParameterValidator {
  override fun validate(name: String, value: String) {
    val console = JCommander().console
    console.println(getVersionDesc(false))
    exitProcess(0)
  }
}

suspend fun delay(duration: Duration) = kotlinx.coroutines.delay(duration.toLongMilliseconds())

val HttpStatusCode.isSuccessful get() = value in (HttpStatusCode.OK.value..HttpStatusCode.MultipleChoices.value)

val HttpStatusCode.isNotSuccessful get() = !isSuccessful

val <T : Any> T.simpleClassName: String
  get() = this::class.simpleName ?: "None"

fun <R> Boolean.thenElse(trueVal: () -> R, falseVal: () -> R): R = if (this) trueVal() else falseVal()

fun Boolean.thenElse(trueVal: String, falseVal: String): String = if (this) trueVal else falseVal
