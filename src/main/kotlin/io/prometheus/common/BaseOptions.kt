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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.github.pambrose.common.util.simpleClassName
import com.typesafe.config.*
import io.prometheus.common.EnvVars.*
import mu.KLogging
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import kotlin.properties.Delegates.notNull
import kotlin.system.exitProcess

//@Parameters(separators = "=")
abstract class BaseOptions protected constructor(private val progName: String,
                                                 private val argv: Array<String>,
                                                 private val envConfig: String,
                                                 private val exitOnMissingConfig: Boolean = false) {

  @Parameter(names = ["-c", "--conf", "--config"], description = "Configuration file or url")
  private var configSource = ""

  @Parameter(names = ["-r", "--admin"], description = "Admin servlets enabled")
  var adminEnabled = false
    private set

  @Parameter(names = ["-i", "--admin_port"], description = "Admin servlets port")
  var adminPort: Int = -1
    private set

  @Parameter(names = ["-e", "--metrics"], description = "Metrics enabled")
  var metricsEnabled = false
    private set

  @Parameter(names = ["-m", "--metrics_port"], description = "Metrics listen port")
  var metricsPort = -1
    private set

  @Parameter(names = ["-b", "--debug"], description = "Debug option enabled")
  var debugEnabled = false
    private set

  @Parameter(names = ["-t", "--cert"], description = "Certificate chain file path")
  var certChainFilePath = ""
    private set

  @Parameter(names = ["-k", "--key"], description = "Private key file path")
  var privateKeyFilePath = ""
    private set

  @Parameter(names = ["-s", "--trust"], description = "Trust certificate collection file path")
  var trustCertCollectionFilePath = ""
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

  private var config: Config by notNull()

  var configVals: ConfigVals by notNull()
    private set

  protected abstract fun assignConfigVals()

  protected fun parseOptions() {
    fun parseArgs(argv: Array<String>?) {
      try {
        val jcom =
          JCommander(this)
            .apply {
              programName = progName
              setCaseSensitiveOptions(false)
              parse(*argv ?: arrayOf())
            }

        if (usage) {
          jcom.usage()
          exitProcess(0)
        }
      }
      catch (e: ParameterException) {
        logger.error(e) { e.message }
        exitProcess(1)
      }
    }

    parseArgs(argv)
    readConfig(envConfig, exitOnMissingConfig)
    configVals = ConfigVals(config)
    assignConfigVals()
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

  protected fun assignDebugEnabled(defaultVal: Boolean) {
    if (!debugEnabled)
      debugEnabled = DEBUG_ENABLED.getEnv(defaultVal)
  }

  protected fun assignMetricsPort(defaultVal: Int) {
    if (metricsPort == -1)
      metricsPort = METRICS_PORT.getEnv(defaultVal)
  }

  protected fun assignCertChainFilePath(defaultVal: String) {
    if (certChainFilePath.isEmpty())
      certChainFilePath = CERT_CHAIN_FILE_PATH.getEnv(defaultVal)
  }

  protected fun assignPrivateKeyFilePath(defaultVal: String) {
    if (privateKeyFilePath.isEmpty())
      privateKeyFilePath = PRIVATE_KEY_FILE_PATH.getEnv(defaultVal)
  }

  protected fun assignTrustCertCollectionFilePath(defaultVal: String) {
    if (trustCertCollectionFilePath.isEmpty())
      trustCertCollectionFilePath = TRUST_CERT_COLLECTION_FILE_PATH.getEnv(defaultVal)
  }

  private fun readConfig(envConfig: String, exitOnMissingConfig: Boolean) {
    config = readConfig(if (configSource.isNotEmpty()) configSource else System.getenv(envConfig).orEmpty(),
                        envConfig,
                        ConfigParseOptions.defaults().setAllowMissing(false),
                        ConfigFactory.load().resolve(),
                        exitOnMissingConfig)
      .resolve(ConfigResolveOptions.defaults())
      .resolve()

    dynamicParams.forEach { (k, v) ->
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

    fun String.isUrlPrefix() = toLowerCase().startsWith(HTTP_PREFIX) || toLowerCase().startsWith(HTTPS_PREFIX)

    fun String.isJsonSuffix() = toLowerCase().endsWith(".json") || toLowerCase().endsWith(".jsn")

    fun String.isPropertiesSuffix() = toLowerCase().endsWith(".properties") || toLowerCase().endsWith(".props")

    fun getConfigSyntax(configName: String) =
      when {
        configName.isJsonSuffix() -> ConfigSyntax.JSON
        configName.isPropertiesSuffix() -> ConfigSyntax.PROPERTIES
        else -> ConfigSyntax.CONF
      }

    when {
      configName.isBlank() -> {
        if (exitOnMissingConfig) {
          logger.error { "A configuration file or url must be specified with --config or \$$envConfig" }
          exitProcess(1)
        }
        return fallback
      }

      configName.isUrlPrefix() -> {
        try {
          val configSyntax = getConfigSyntax(configName)
          return ConfigFactory.parseURL(URL(configName), configParseOptions.setSyntax(configSyntax))
            .withFallback(fallback)
        }
        catch (e: Exception) {
          if (e.cause is FileNotFoundException)
            logger.error { "Invalid config url: $configName" }
          else
            logger.error(e) { "Exception: ${e.simpleClassName} - ${e.message}" }
        }

      }
      else -> {
        try {
          return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions).withFallback(fallback)
        }
        catch (e: Exception) {
          if (e.cause is FileNotFoundException)
            logger.error { "Invalid config filename: $configName" }
          else
            logger.error(e) { "Exception: ${e.simpleClassName} - ${e.message}" }
        }
      }
    }

    exitProcess(1)
  }

  companion object : KLogging() {
    private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    const val DEBUG = "debug"
    const val HTTP_PREFIX = "http://"
    const val HTTPS_PREFIX = "https://"
  }
}
