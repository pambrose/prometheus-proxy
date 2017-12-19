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
import com.google.common.base.Strings
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
import java.lang.String.format
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
                return format("%n%n%s%n%n", noNulls)
            }
        } catch (e: Exception) {
            return format("Banner %s cannot be found", filename)
        }

    }

    fun readConfig(cliConfig: String?,
                   envConfig: String,
                   configParseOptions: ConfigParseOptions,
                   fallback: Config,
                   exitOnMissingConfig: Boolean): Config {

        val configName = cliConfig ?: System.getenv(envConfig)

        if (Strings.isNullOrEmpty(configName)) {
            if (exitOnMissingConfig) {
                logger.error("A configuration file or url must be specified with --getConfig or \${}", envConfig)
                System.exit(1)
            }
            return fallback
        }

        if (isUrlPrefix(configName)) {
            try {
                val configSyntax = getConfigSyntax(configName)
                return ConfigFactory.parseURL(URL(configName), configParseOptions.setSyntax(configSyntax))
                        .withFallback(fallback)
            } catch (e: Exception) {
                logger.error(if (e.cause is FileNotFoundException)
                                 format("Invalid getConfig url: %s", configName)
                             else
                                 format("Exception: %s - %s", e.javaClass.simpleName, e.message), e)
            }

        }
        else {
            try {
                return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions)
                        .withFallback(fallback)
            } catch (e: Exception) {
                logger.error(if (e.cause is FileNotFoundException)
                                 format("Invalid getConfig filename: %s", configName)
                             else
                                 format("Exception: %s - %s", e.javaClass.simpleName, e.message), e)
            }

        }

        System.exit(1)
        return fallback // Never reached
    }

    private fun getConfigSyntax(configName: String): ConfigSyntax {
        return if (isJsonSuffix(configName))
            ConfigSyntax.JSON
        else if (isPropertiesSuffix(configName))
            ConfigSyntax.PROPERTIES
        else
            ConfigSyntax.CONF
    }

    private fun isUrlPrefix(str: String): Boolean {
        return str.toLowerCase().startsWith("http://") || str.toLowerCase().startsWith("https://")
    }

    private fun isJsonSuffix(str: String): Boolean {
        return str.toLowerCase().endsWith(".json") || str.toLowerCase().endsWith(".jsn")
    }

    private fun isPropertiesSuffix(str: String): Boolean {
        return str.toLowerCase().endsWith(".properties") || str.toLowerCase().endsWith(".props")
    }

    fun queueHealthCheck(queue: Queue<*>, size: Int): HealthCheck {
        return object : HealthCheck() {
            @Throws(Exception::class)
            override fun check(): HealthCheck.Result {
                return if (queue.size < size) HealthCheck.Result.healthy() else HealthCheck.Result.unhealthy("Large size: %d", queue.size)
            }
        }
    }

    fun mapHealthCheck(map: Map<*, *>, size: Int): HealthCheck {
        return object : HealthCheck() {
            @Throws(Exception::class)
            override fun check(): HealthCheck.Result {
                return if (map.size < size) HealthCheck.Result.healthy() else HealthCheck.Result.unhealthy("Large size: %d", map.size)
            }
        }
    }

    fun sleepForMillis(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            // Ignore
        }
    }

    fun sleepForSecs(secs: Long) {
        try {
            Thread.sleep(toMillis(secs))
        } catch (e: InterruptedException) {
            // Ignore
        }
    }

    fun getVersionDesc(asJson: Boolean): String {
        val annotation = Proxy::class.java.`package`.getAnnotation(VersionAnnotation::class.java)
        return if (asJson)
            """{"Version": "${annotation.version}", "Release Date": "${annotation.date}"}"""
        else
            """Version: ${annotation.version} Release Date: ${annotation.date}"""
    }

    fun shutDownHookAction(service: Service): Thread {
        return Thread {
            JCommander.getConsole().println(format("*** %s shutting down ***", service.javaClass.simpleName))
            service.stopAsync()
            JCommander.getConsole().println(format("*** %s shut down complete ***", service.javaClass.simpleName))
        }
    }

    fun toMillis(secs: Long): Long {
        return secs * 1000
    }

    fun toSecs(millis: Long): Long {
        return millis / 1000
    }

    class VersionValidator : IParameterValidator {
        override fun validate(name: String, value: String) {
            val console = JCommander.getConsole()
            console.println(getVersionDesc(false))
            System.exit(0)
        }
    }
}
