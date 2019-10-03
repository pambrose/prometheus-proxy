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
import com.google.common.base.Charsets
import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.io.CharStreams
import com.google.common.util.concurrent.Service
import io.ktor.http.HttpStatusCode
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Proxy
import io.prometheus.dsl.MetricsDsl.healthCheck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.slf4j.Logger
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

val localHostName: String by lazy {
    try {
        InetAddress.getLocalHost().hostName
    } catch (e: UnknownHostException) {
        "Unknown"
    }
}

fun getBanner(filename: String, logger: Logger) =
    try {
        logger.javaClass.classLoader.getResourceAsStream(filename)
            .use { inputStream ->
                val utf8 = Charsets.UTF_8.name()
                val banner = CharStreams.toString(InputStreamReader(inputStream ?: throw InternalError(), utf8))
                val lines: List<String> = Splitter.on("\n").splitToList(banner)

                // Trim initial and trailing blank lines, but preserve blank lines in middle;
                var first = -1
                var last = -1
                var lineNum = 0
                lines.forEach { arg1 ->
                    if (arg1.trim { arg2 -> arg2 <= ' ' }.isNotEmpty()) {
                        if (first == -1)
                            first = lineNum
                        last = lineNum
                    }
                    lineNum++
                }

                lineNum = 0

                val vals =
                    lines
                        .filter {
                            val currLine = lineNum++
                            currLine in first..last
                        }
                        .map { arg -> "     $arg" }
                        .toList()

                val noNulls = Joiner.on("\n").skipNulls().join(vals)
                "\n\n$noNulls\n\n"
            }
    } catch (e: Exception) {
        "Banner $filename cannot be found"
    }

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

@ObsoleteCoroutinesApi
@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@ExperimentalTime
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

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@ExperimentalTime
@ObsoleteCoroutinesApi
class VersionValidator : IParameterValidator {
    override fun validate(name: String, value: String) {
        val console = JCommander().console
        console.println(getVersionDesc(false))
        exitProcess(0)
    }
}

@ExperimentalTime
suspend fun delay(duration: Duration) {
    kotlinx.coroutines.delay(duration.toLongMilliseconds())
}

val HttpStatusCode.isSuccessful get() = value in (HttpStatusCode.OK.value..HttpStatusCode.MultipleChoices.value)

val HttpStatusCode.isNotSuccessful get() = !isSuccessful

val <T : Any> T.simpleClassName: String
    get() = this::class.simpleName ?: "None"

fun <R> Boolean.thenElse(trueVal: () -> R, falseVal: () -> R): R = if (this) trueVal() else falseVal()

fun Boolean.thenElse(trueVal: String, falseVal: String): String = if (this) trueVal else falseVal
