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
import java.lang.String.format
import java.util.*
import java.util.concurrent.atomic.AtomicReference

abstract class BaseOptions protected constructor(private val programName: String,
                                                 private val argv: Array<String>,
                                                 private val envConfig: String,
                                                 private val exitOnMissingConfig: Boolean) {

    private val configRef = AtomicReference<Config>()
    private val _configVals = AtomicReference<ConfigVals>()
    var configVals
        get() = this._configVals.get()
        set(configVals) = this._configVals.set(configVals)

    @Parameter(names = arrayOf("-c", "--conf", "--config"), description = "Configuration file or url")
    private var configName: String? = null
    @Parameter(names = arrayOf("-r", "--admin"), description = "Admin servlets enabled")
    private var adminEnabled: Boolean? = null
    @Parameter(names = arrayOf("-i", "--admin_port"), description = "Admin servlets port")
    private var adminPort: Int? = null
    @Parameter(names = arrayOf("-e", "--metrics"), description = "Metrics enabled")
    private var metricsEnabled: Boolean? = null
    @Parameter(names = arrayOf("-m", "--metrics_port"), description = "Metrics listen port")
    private var metricsPort: Int? = null
    @Parameter(names = arrayOf("-v", "--version"), description = "Print version info and exit", validateWith = arrayOf(Utils.VersionValidator::class))
    private var version = false
    @Parameter(names = arrayOf("-u", "--usage"), help = true)
    private var usage = false
    @DynamicParameter(names = arrayOf("-D"), description = "Dynamic property assignment")
    var dynamicParams: Map<String, String> = HashMap()

    val isAdminEnabled: Boolean
        get() = this.adminEnabled ?: false

    val isMetricsEnabled: Boolean
        get() = this.metricsEnabled ?: false


    protected fun parseOptions() {
        this.parseArgs(argv)
        this.readConfig(envConfig, exitOnMissingConfig)
        this.configVals = ConfigVals(this.configRef.get())
        this.assignConfigVals()
    }

    protected abstract fun assignConfigVals()

    private fun parseArgs(argv: Array<String>?) {
        try {
            val jcom = JCommander(this)
            jcom.programName = this.programName
            jcom.setCaseSensitiveOptions(false)
            jcom.parse(*argv ?: arrayOf<String>())

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
        if (this.adminEnabled == null)
            this.adminEnabled = ADMIN_ENABLED.getEnv(defaultVal)
    }

    protected fun assignAdminPort(defaultVal: Int) {
        if (this.adminPort == null)
            this.adminPort = ADMIN_PORT.getEnv(defaultVal)
    }

    protected fun assignMetricsEnabled(defaultVal: Boolean) {
        if (this.metricsEnabled == null)
            this.metricsEnabled = METRICS_ENABLED.getEnv(defaultVal)
    }

    protected fun assignMetricsPort(defaultVal: Int) {
        if (this.metricsPort == null)
            this.metricsPort = METRICS_PORT.getEnv(defaultVal)
    }

    private fun readConfig(envConfig: String, exitOnMissingConfig: Boolean) {
        val config = Utils.readConfig(this.configName,
                                      envConfig,
                                      ConfigParseOptions.defaults().setAllowMissing(false),
                                      ConfigFactory.load().resolve(),
                                      exitOnMissingConfig)
                .resolve(ConfigResolveOptions.defaults())
        this.configRef.set(config.resolve())

        this.dynamicParams.forEach { key, value ->
            // Strip quotes
            val prop = format("%s=%s", key, if (value.startsWith("\"") && value.endsWith("\""))
                value.substring(1, value.length - 1)
            else
                value)
            System.setProperty(key, prop)
            val newConfig = ConfigFactory.parseString(prop, PROPS)
            configRef.set(newConfig.withFallback(this.configRef.get()).resolve())
        }
    }

    fun getAdminPort(): Int {
        return this.adminPort!!
    }

    fun getMetricsPort(): Int {
        return this.metricsPort!!
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BaseOptions::class.java)
        private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    }
}
