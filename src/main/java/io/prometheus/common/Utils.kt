/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
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
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import io.prometheus.Proxy
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

object Utils {

    private val logger = LoggerFactory.getLogger(Utils::class.java)

    val hostName: String
        get() {
            try {
                return InetAddress.getLocalHost().hostName
            } catch (e: UnknownHostException) {
                return "Unknown"
            }
        }

    fun getBanner(filename: String): String {
        try {
            logger.javaClass.classLoader.getResourceAsStream(filename).use { `in` ->
                val banner = CharStreams.toString(InputStreamReader(`in`, Charsets.UTF_8.name()))
                val lines: List<String> = Splitter.on("\n").splitToList(banner)

                // Use Atomic values because filter requires finals
                // Trim initial and trailing blank lines, but preserve blank lines in middle;
                val first = AtomicInteger(-1)
                val last = AtomicInteger(-1)
                val lineNum = AtomicInteger(0)
                lines.forEach {
                    if (it.trim { it <= ' ' }.length > 0) {
                        if (first.get() == -1)
                            first.set(lineNum.get())
                        last.set(lineNum.get())
                    }
                    lineNum.incrementAndGet()
                }

                lineNum.set(0)

                val vals: List<String> = lines.stream()
                        .filter {
                            val currLine = lineNum.getAndIncrement()
                            currLine >= first.get() && currLine <= last.get()
                        }
                        .map { "     " + it }
                        .collect(Collectors.toList())

                val noNulls = Joiner.on("\n")
                        .skipNulls()
                        .join(vals)
                return "\n\n$noNulls\n\n"
            }
        } catch (e: Exception) {
            return "Banner $filename cannot be found"
        }
    }

    fun readConfig(cliConfig: String?,
                   envConfig: String,
                   configParseOptions: ConfigParseOptions,
                   fallback: Config,
                   exitOnMissingConfig: Boolean): Config {

        val configName = cliConfig ?: System.getenv(envConfig)

        if (configName.isNullOrBlank()) {
            if (exitOnMissingConfig) {
                logger.error("A configuration file or url must be specified with --getConfig or \$${envConfig}")
                System.exit(1)
            }
            return fallback
        }

        if (configName.isUrlPrefix()) {
            try {
                val configSyntax = getConfigSyntax(configName)
                return ConfigFactory.parseURL(URL(configName), configParseOptions.setSyntax(configSyntax))
                        .withFallback(fallback)
            } catch (e: Exception) {
                logger.error(if (e.cause is FileNotFoundException)
                                 "Invalid getConfig url: $configName"
                             else
                                 "Exception: ${e.javaClass.simpleName} - ${e.message}",
                             e)
            }

        }
        else {
            try {
                return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions)
                        .withFallback(fallback)
            } catch (e: Exception) {
                logger.error(if (e.cause is FileNotFoundException)
                                 "Invalid getConfig filename: $configName"
                             else
                                 "Exception: ${e.javaClass.simpleName} - ${e.message}",
                             e)
            }
        }

        System.exit(1)
        return fallback // Never reached
    }

    private fun getConfigSyntax(configName: String): ConfigSyntax =
            if (configName.isJsonSuffix())
                ConfigSyntax.JSON
            else if (configName.isPropertiesSuffix())
                ConfigSyntax.PROPERTIES
            else
                ConfigSyntax.CONF

    private fun String.isUrlPrefix() =
            this.toLowerCase().startsWith("http://") || this.toLowerCase().startsWith("https://")

    private fun String.isJsonSuffix() =
            this.toLowerCase().endsWith(".json") || this.toLowerCase().endsWith(".jsn")

    private fun String.isPropertiesSuffix() =
            this.toLowerCase().endsWith(".properties") || this.toLowerCase().endsWith(".props")

    fun queueHealthCheck(queue: Queue<*>, size: Int) =
            object : HealthCheck() {
                @Throws(Exception::class)
                override fun check(): HealthCheck.Result {
                    return if (queue.size < size) HealthCheck.Result.healthy() else HealthCheck.Result.unhealthy("Large size: %d", queue.size)
                }
            }

    fun mapHealthCheck(map: Map<*, *>, size: Int) =
            object : HealthCheck() {
                @Throws(Exception::class)
                override fun check(): HealthCheck.Result {
                    return if (map.size < size) HealthCheck.Result.healthy() else HealthCheck.Result.unhealthy("Large size: %d", map.size)
                }
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

    fun shutDownHookAction(service: Service): Thread =
            Thread {
                JCommander.getConsole().println("*** ${service.javaClass.simpleName} shutting down ***")
                service.stopAsync()
                JCommander.getConsole().println("*** ${service.javaClass.simpleName} shut down complete ***")
            }

    class VersionValidator : IParameterValidator {
        override fun validate(name: String, value: String) {
            val console = JCommander.getConsole()
            console.println(getVersionDesc(false))
            System.exit(0)
        }
    }
}

fun Long.toMillis(): Long = this * 1000

fun Long.toSecs(): Long = this / 1000

