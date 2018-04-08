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

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.typesafe.config.*
import io.prometheus.common.EnvVars.*
import mu.KLogging
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import kotlin.properties.Delegates

abstract class BaseOptions protected constructor(private val progName: String,
                                                 private val argv: Array<String>,
                                                 private val envConfig: String,
                                                 private val exitOnMissingConfig: Boolean = false) {

    @Parameter(names = ["-c", "--conf", "--config"], description = "Configuration file or url")
    private var configName: String = ""

    @Parameter(names = ["-r", "--admin"], description = "Admin servlets enabled")
    var adminEnabled: Boolean = false
        private set

    @Parameter(names = ["-i", "--admin_port"], description = "Admin servlets port")
    var adminPort: Int = -1
        private set

    @Parameter(names = ["-e", "--metrics"], description = "Metrics enabled")
    var metricsEnabled: Boolean = false
        private set

    @Parameter(names = ["-m", "--metrics_port"], description = "Metrics listen port")
    var metricsPort: Int = -1
        private set

    @Parameter(names = ["-v", "--version"],
               description = "Print version info and exit",
               validateWith = [VersionValidator::class])
    private var version = false

    @Parameter(names = ["-u", "--usage"], help = true)
    private var usage = false

    @DynamicParameter(names = ["-D"], description = "Dynamic property assignment")
    var dynamicParams = mutableMapOf<String, String>()
        private set

    private var config: Config by Delegates.notNull()

    var configVals: ConfigVals by Delegates.notNull()
        private set

    protected abstract fun assignConfigVals()

    protected fun parseOptions() {
        parseArgs(argv)
        readConfig(envConfig, exitOnMissingConfig)
        configVals = ConfigVals(config)
        assignConfigVals()
    }

    private fun parseArgs(argv: Array<String>?) {
        try {
            val jcom =
                    JCommander(this).apply {
                        this.programName = progName
                        setCaseSensitiveOptions(false)
                        parse(*argv ?: arrayOf<String>())
                    }

            if (usage) {
                jcom.usage()
                System.exit(0)
            }
        } catch (e: ParameterException) {
            logger.error(e) { e.message }
            System.exit(1)
        }
    }

    protected fun assignAdminEnabled(defaultVal: Boolean) {
        if (!adminEnabled)
            adminEnabled = ADMIN_ENABLED.getEnv(defaultVal)
    }

    protected fun assignAdminPort(defaultVal: Int) {
        if (adminPort == -1)
            adminPort = ADMIN_PORT.getEnv(defaultVal)
    }

    protected fun assignMetricsEnabled(defaultVal: Boolean) {
        if (!metricsEnabled)
            metricsEnabled = METRICS_ENABLED.getEnv(defaultVal)
    }

    protected fun assignMetricsPort(defaultVal: Int) {
        if (metricsPort == -1)
            metricsPort = METRICS_PORT.getEnv(defaultVal)
    }

    private fun readConfig(envConfig: String, exitOnMissingConfig: Boolean) {
        config = readConfig(if (configName.isNotEmpty()) configName else System.getenv(envConfig) ?: "",
                            envConfig,
                            ConfigParseOptions.defaults().setAllowMissing(false),
                            ConfigFactory.load().resolve(),
                            exitOnMissingConfig)
                .resolve(ConfigResolveOptions.defaults())
                .resolve()

        dynamicParams.forEach { k, v ->
            // Strip quotes
            val qval = if (v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1) else v
            val prop = "$k=$qval"
            System.setProperty(k, prop)
            val newConfig = ConfigFactory.parseString(prop, PROPS)
            config = newConfig.withFallback(config).resolve()
        }
    }

    private fun readConfig(configName: String,
                           envConfig: String,
                           configParseOptions: ConfigParseOptions,
                           fallback: Config,
                           exitOnMissingConfig: Boolean): Config {

        when {
            configName.isBlank()     -> {
                if (exitOnMissingConfig) {
                    logger.error { "A configuration file or url must be specified with --config or \$$envConfig" }
                    System.exit(1)
                }
                return fallback
            }

            configName.isUrlPrefix() -> {
                try {
                    val configSyntax = getConfigSyntax(configName)
                    return ConfigFactory.parseURL(URL(configName), configParseOptions.setSyntax(configSyntax))
                            .withFallback(fallback)
                } catch (e: Exception) {
                    if (e.cause is FileNotFoundException)
                        logger.error { "Invalid getConfig url: $configName" }
                    else
                        logger.error(e) { "Exception: ${e.javaClass.simpleName} - ${e.message}" }
                }

            }
            else                     -> {
                try {
                    return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions).withFallback(fallback)
                } catch (e: Exception) {
                    if (e.cause is FileNotFoundException)
                        logger.error { "Invalid getConfig filename: $configName" }
                    else
                        logger.error(e) { "Exception: ${e.javaClass.simpleName} - ${e.message}" }
                }
            }
        }

        System.exit(1)
        return fallback // Never reached
    }

    private fun getConfigSyntax(configName: String) =
            when {
                configName.isJsonSuffix()       -> ConfigSyntax.JSON
                configName.isPropertiesSuffix() -> ConfigSyntax.PROPERTIES
                else                            -> ConfigSyntax.CONF
            }

    private fun String.isUrlPrefix() = toLowerCase().startsWith("http://") || toLowerCase().startsWith("https://")

    private fun String.isJsonSuffix() = toLowerCase().endsWith(".json") || toLowerCase().endsWith(".jsn")

    private fun String.isPropertiesSuffix() = toLowerCase().endsWith(".properties") || toLowerCase().endsWith(".props")

    companion object : KLogging() {
        private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    }
}
