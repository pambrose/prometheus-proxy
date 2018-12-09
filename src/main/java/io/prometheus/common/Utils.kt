/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.common

import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.JCommander
import com.codahale.metrics.health.HealthCheck
import com.google.common.base.Charsets
import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.io.CharStreams
import com.google.common.util.concurrent.Service
import io.prometheus.Proxy
import io.prometheus.dsl.MetricsDsl.healthCheck
import org.slf4j.Logger
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*

val localHostName: String by lazy {
    try {
        InetAddress.getLocalHost().hostName
    } catch (e: UnknownHostException) {
        "Unknown"
    }
}

fun getBanner(filename: String, logger: Logger) =
        try {
            logger.javaClass.classLoader.getResourceAsStream(filename).use {
                val banner = CharStreams.toString(InputStreamReader(it, Charsets.UTF_8.name()))
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

                val vals = lines
                        .asSequence()
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

fun newQueueHealthCheck(queue: Queue<*>, size: Int) =
        healthCheck {
            if (queue.size < size)
                HealthCheck.Result.healthy()
            else
                HealthCheck.Result.unhealthy("Large size: %d", queue.size)
        }

fun newMapHealthCheck(map: Map<*, *>, size: Int) =
        healthCheck {
            if (map.size < size)
                HealthCheck.Result.healthy()
            else
                HealthCheck.Result.unhealthy("Large size: %d", map.size)
        }

fun sleepForMillis(millis: Long) =
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            // Ignore
        }

fun sleepForSecs(secs: Long) =
        try {
            Thread.sleep(secs.toMillis())
        } catch (e: InterruptedException) {
            // Ignore
        }

fun getVersionDesc(asJson: Boolean): String {
    val annotation = Proxy::class.java.`package`.getAnnotation(VersionAnnotation::class.java)
    return if (asJson)
        """{"Version": "${annotation.version}", "Release Date": "${annotation.date}"}"""
    else
        """Version: ${annotation.version} Release Date: ${annotation.date}"""
}

fun shutDownHookAction(service: Service) =
        Thread {
            println("*** ${service.javaClass.simpleName} shutting down ***")
            service.stopAsync()
            println("*** ${service.javaClass.simpleName} shut down complete ***")
        }

class VersionValidator : IParameterValidator {
    override fun validate(name: String, value: String) {
        val console = JCommander.getConsole()
        console.println(getVersionDesc(false))
        System.exit(0)
    }
}

fun Long.toMillis() = this * 1000

fun Long.toSecs() = this / 1000

