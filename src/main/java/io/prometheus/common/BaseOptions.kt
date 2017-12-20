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

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.typesafe.config.*
import io.prometheus.common.EnvVars.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.URL

abstract class BaseOptions protected constructor(private val programName: String,
                                                 private val argv: Array<String>,
                                                 private val envConfig: String,
                                                 private val exitOnMissingConfig: Boolean) {

    private var configRef: Config? = null

    var configVals: ConfigVals? = null
        private set

    @Parameter(names = arrayOf("-c", "--conf", "--config"), description = "Configuration file or url")
    private var configName: String? = null

    @Parameter(names = arrayOf("-r", "--admin"), description = "Admin servlets enabled")
    private var _adminEnabled: Boolean? = null

    @Parameter(names = arrayOf("-i", "--admin_port"), description = "Admin servlets port")
    var adminPort: Int? = null
        private set

    @Parameter(names = arrayOf("-e", "--metrics"), description = "Metrics enabled")
    private var _metricsEnabled: Boolean? = null

    @Parameter(names = arrayOf("-m", "--metrics_port"), description = "Metrics listen port")
    var metricsPort: Int? = null
        private set

    @Parameter(names = arrayOf("-v", "--version"), description = "Print version info and exit", validateWith = arrayOf(Utils.VersionValidator::class))
    private var version = false

    @Parameter(names = arrayOf("-u", "--usage"), help = true)
    private var usage = false

    @DynamicParameter(names = arrayOf("-D"), description = "Dynamic property assignment")
    var dynamicParams = mutableMapOf<String, String>()
        private set

    val adminEnabled: Boolean
        get() = this._adminEnabled ?: false

    val metricsEnabled: Boolean
        get() = this._metricsEnabled ?: false


    protected fun parseOptions() {
        this.parseArgs(this.argv)
        this.readConfig(this.envConfig, this.exitOnMissingConfig)
        this.configVals = ConfigVals(this.configRef)
        this.assignConfigVals()
    }

    protected abstract fun assignConfigVals()

    private fun parseArgs(argv: Array<String>?) {
        try {
            val jcom =
                    JCommander(this).apply {
                        programName = this.programName
                        setCaseSensitiveOptions(false)
                        parse(*argv ?: arrayOf<String>())
                    }

            if (this.usage) {
                jcom.usage()
                System.exit(0)
            }
        } catch (e: ParameterException) {
            logger.error(e.message, e)
            System.exit(1)
        }
    }

    protected fun assignAdminEnabled(defaultVal: Boolean) {
        if (this._adminEnabled == null)
            this._adminEnabled = ADMIN_ENABLED.getEnv(defaultVal)
    }

    protected fun assignAdminPort(defaultVal: Int) {
        if (this.adminPort == null)
            this.adminPort = ADMIN_PORT.getEnv(defaultVal)
    }

    protected fun assignMetricsEnabled(defaultVal: Boolean) {
        if (this._metricsEnabled == null)
            this._metricsEnabled = METRICS_ENABLED.getEnv(defaultVal)
    }

    protected fun assignMetricsPort(defaultVal: Int) {
        if (this.metricsPort == null)
            this.metricsPort = METRICS_PORT.getEnv(defaultVal)
    }

    private fun readConfig(envConfig: String, exitOnMissingConfig: Boolean) {
        val config =
                readConfig(this.configName,
                           envConfig,
                           ConfigParseOptions.defaults().setAllowMissing(false),
                           ConfigFactory.load().resolve(),
                           exitOnMissingConfig)
                        .resolve(ConfigResolveOptions.defaults())
        this.configRef = config.resolve()

        this.dynamicParams.forEach { k, v ->
            // Strip quotes
            val qval = if (v.startsWith("\"") && v.endsWith("\""))
                v.substring(1, v.length - 1)
            else
                v
            val prop = "$k=$qval"
            System.setProperty(k, prop)
            val newConfig = ConfigFactory.parseString(prop, PROPS)
            configRef = newConfig.withFallback(this.configRef).resolve()
        }
    }

    private fun readConfig(cliConfig: String?,
                           envConfig: String,
                           configParseOptions: ConfigParseOptions,
                           fallback: Config,
                           exitOnMissingConfig: Boolean): Config {

        val configName = cliConfig ?: System.getenv(envConfig)

        when {
            configName.isNullOrBlank() -> {
                if (exitOnMissingConfig) {
                    logger.error("A configuration file or url must be specified with --getConfig or \$${envConfig}")
                    System.exit(1)
                }
                return fallback
            }

            configName.isUrlPrefix()   -> {
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
            else                       -> {
                try {
                    return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions).withFallback(fallback)
                } catch (e: Exception) {
                    logger.error(if (e.cause is FileNotFoundException)
                                     "Invalid getConfig filename: $configName"
                                 else
                                     "Exception: ${e.javaClass.simpleName} - ${e.message}",
                                 e)
                }
            }
        }

        System.exit(1)
        return fallback // Never reached
    }

    private fun getConfigSyntax(configName: String): ConfigSyntax =
            when {
                configName.isJsonSuffix()       -> ConfigSyntax.JSON
                configName.isPropertiesSuffix() -> ConfigSyntax.PROPERTIES
                else                            -> ConfigSyntax.CONF
            }

    private fun String.isUrlPrefix() =
            this.toLowerCase().startsWith("http://") || this.toLowerCase().startsWith("https://")

    private fun String.isJsonSuffix() =
            this.toLowerCase().endsWith(".json") || this.toLowerCase().endsWith(".jsn")

    private fun String.isPropertiesSuffix() =
            this.toLowerCase().endsWith(".properties") || this.toLowerCase().endsWith(".props")

    companion object {
        private val logger = LoggerFactory.getLogger(BaseOptions::class.java)
        private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    }
}
