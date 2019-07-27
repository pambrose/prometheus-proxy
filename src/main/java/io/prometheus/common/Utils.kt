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
import io.prometheus.Proxy
import io.prometheus.dsl.MetricsDsl.healthCheck
import org.slf4j.Logger
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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
                HealthCheck.Result.unhealthy("Large size: ${queue.size}")
        }

fun newMapHealthCheck(map: Map<*, *>, size: Int) =
        healthCheck {
            if (map.size < size)
                HealthCheck.Result.healthy()
            else
                HealthCheck.Result.unhealthy("Large size: ${map.size}")
        }

inline class Millis(val value: Long) {
    constructor(value: Int) : this(value.toLong())

    fun toSecs() = Secs(value / 1000)
    operator fun plus(other: Millis) = Millis(this.value + other.value)
    operator fun minus(other: Millis) = Millis(this.value - other.value)
    operator fun compareTo(other: Millis) = this.value.compareTo(other.value)
    override fun toString() = "$value"
}

inline class Secs(val value: Long) {
    constructor(value: Int) : this(value.toLong())

    fun toMillis() = Millis(value * 1000)
    operator fun plus(other: Secs) = Secs(this.value + other.value)
    operator fun minus(other: Secs) = Secs(this.value - other.value)
    operator fun compareTo(other: Secs) = this.value.compareTo(other.value)
    override fun toString() = "$value"
}

fun now() = Millis(System.currentTimeMillis())

fun sleep(millis: Millis) =
        try {
            Thread.sleep(millis.value)
        } catch (e: InterruptedException) {
            // Ignore
        }

fun sleep(secs: Secs) =
        try {
            Thread.sleep(secs.toMillis().value)
        } catch (e: InterruptedException) {
            // Ignore
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
            println("*** ${service.javaClass.simpleName} shutting down ***")
            service.stopAsync()
            println("*** ${service.javaClass.simpleName} shut down complete ***")
        }

class VersionValidator : IParameterValidator {
    override fun validate(name: String, value: String) {
        val console = JCommander.getConsole()
        console.println(getVersionDesc(false))
        exitProcess(0)
    }
}

fun CountDownLatch.await(millis: Millis): Boolean {
    return this.await(millis.value, TimeUnit.MILLISECONDS)
}

fun <E> ArrayBlockingQueue<E>.poll(millis: Millis): E? {
    return this.poll(millis.value, TimeUnit.MILLISECONDS)
}