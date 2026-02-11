/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import com.github.pambrose.common.util.runCatchingCancellable
import com.github.pambrose.common.util.simpleClassName
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.EnvVars.ADMIN_ENABLED
import io.prometheus.common.EnvVars.ADMIN_PORT
import io.prometheus.common.EnvVars.CERT_CHAIN_FILE_PATH
import io.prometheus.common.EnvVars.DEBUG_ENABLED
import io.prometheus.common.EnvVars.KEEPALIVE_TIMEOUT_SECS
import io.prometheus.common.EnvVars.KEEPALIVE_TIME_SECS
import io.prometheus.common.EnvVars.METRICS_ENABLED
import io.prometheus.common.EnvVars.METRICS_PORT
import io.prometheus.common.EnvVars.PRIVATE_KEY_FILE_PATH
import io.prometheus.common.EnvVars.TRANSPORT_FILTER_DISABLED
import io.prometheus.common.EnvVars.TRUST_CERT_COLLECTION_FILE_PATH
import io.prometheus.common.Utils.VersionValidator
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import kotlin.system.exitProcess

// @Parameters(separators = "=")
abstract class BaseOptions protected constructor(
  private val progName: String,
  private val args: Array<String>,
  private val envConfig: String,
  private val exitOnMissingConfig: Boolean = false,
) {
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

  // Use both options here to avoid breaking people with the typo fix
  @Parameter(names = ["--tf-disabled", "--tf_disabled"], description = "Transport filter disabled")
  var transportFilterDisabled = false
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

  @Parameter(names = ["--keepalive_time_secs"], description = "gRPC KeepAlive time (secs)")
  var keepAliveTimeSecs = -1L
    private set

  @Parameter(names = ["--keepalive_timeout_secs"], description = "gRPC KeepAlive timeout (secs)")
  var keepAliveTimeoutSecs = -1L
    private set

  @Parameter(names = ["--log_level"], description = "Log level")
  var logLevel = ""
    protected set

  @Parameter(
    names = ["-v", "--version"],
    description = "Print version info and exit",
    validateWith = [VersionValidator::class],
  )
  private var version = false

  @Parameter(names = ["-u", "--usage"], help = true)
  private var usage = false

  @DynamicParameter(names = ["-D"], description = "Dynamic property assignment")
  var dynamicParams = mutableMapOf<String, String>()
    private set

  private lateinit var config: Config

  lateinit var configVals: ConfigVals
    private set

  protected abstract fun assignConfigVals()

  protected fun parseOptions() {
    fun parseArgs(args: Array<String>?) {
      try {
        val jcom =
          JCommander(this)
            .apply {
              programName = progName
              setCaseSensitiveOptions(false)
              parse(*(args.orEmpty()))
            }

        if (usage) {
          jcom.usage()
          exitProcess(0)
        }
      } catch (e: ParameterException) {
        logger.error(e) { e.message }
        exitProcess(1)
      }
    }

    parseArgs(args)
    readConfig(envConfig, exitOnMissingConfig)
    configVals = ConfigVals(config)
    assignConfigVals()
  }

  protected fun assignKeepAliveTimeSecs(defaultVal: Long) {
    if (keepAliveTimeSecs == -1L)
      keepAliveTimeSecs = KEEPALIVE_TIME_SECS.getEnv(defaultVal)
    logger.info {
      "grpc.keepAliveTimeSecs: ${if (keepAliveTimeSecs == -1L) "unset (gRPC default)" else keepAliveTimeSecs}"
    }
  }

  protected fun assignKeepAliveTimeoutSecs(defaultVal: Long) {
    if (keepAliveTimeoutSecs == -1L)
      keepAliveTimeoutSecs = KEEPALIVE_TIMEOUT_SECS.getEnv(defaultVal)
    logger.info {
      "grpc.keepAliveTimeoutSecs: ${if (keepAliveTimeoutSecs == -1L) "unset (gRPC default)" else keepAliveTimeoutSecs}"
    }
  }

  protected fun assignAdminEnabled(defaultVal: Boolean) {
    if (!adminEnabled)
      adminEnabled = ADMIN_ENABLED.getEnv(defaultVal)
    logger.info { "adminEnabled: $adminEnabled" }
  }

  protected fun assignAdminPort(defaultVal: Int) {
    if (adminPort == -1)
      adminPort = ADMIN_PORT.getEnv(defaultVal)
    logger.info { "adminPort: $adminPort" }
  }

  protected fun assignMetricsEnabled(defaultVal: Boolean) {
    if (!metricsEnabled)
      metricsEnabled = METRICS_ENABLED.getEnv(defaultVal)
    logger.info { "metricsEnabled: $metricsEnabled" }
  }

  protected fun assignDebugEnabled(defaultVal: Boolean) {
    if (!debugEnabled)
      debugEnabled = DEBUG_ENABLED.getEnv(defaultVal)
    logger.info { "debugEnabled: $debugEnabled" }
  }

  protected fun assignMetricsPort(defaultVal: Int) {
    if (metricsPort == -1)
      metricsPort = METRICS_PORT.getEnv(defaultVal)
    logger.info { "metricsPort: $metricsPort" }
  }

  protected fun assignTransportFilterDisabled(defaultVal: Boolean) {
    if (!transportFilterDisabled)
      transportFilterDisabled = TRANSPORT_FILTER_DISABLED.getEnv(defaultVal)
    logger.info { "transportFilterDisabled: $transportFilterDisabled" }
  }

  protected fun assignCertChainFilePath(defaultVal: String) {
    if (certChainFilePath.isEmpty())
      certChainFilePath = CERT_CHAIN_FILE_PATH.getEnv(defaultVal)
    logger.info { "certChainFilePath: $certChainFilePath" }
  }

  protected fun assignPrivateKeyFilePath(defaultVal: String) {
    if (privateKeyFilePath.isEmpty())
      privateKeyFilePath = PRIVATE_KEY_FILE_PATH.getEnv(defaultVal)
    logger.info { "privateKeyFilePath: $privateKeyFilePath" }
  }

  protected fun assignTrustCertCollectionFilePath(defaultVal: String) {
    if (trustCertCollectionFilePath.isEmpty())
      trustCertCollectionFilePath = TRUST_CERT_COLLECTION_FILE_PATH.getEnv(defaultVal)
    logger.info { "trustCertCollectionFilePath: $trustCertCollectionFilePath" }
  }

  private fun readConfig(
    envConfig: String,
    exitOnMissingConfig: Boolean,
  ) {
    config =
      readConfig(
        configSource.ifEmpty { System.getenv(envConfig).orEmpty() },
        envConfig,
        ConfigParseOptions.defaults().setAllowMissing(false),
        ConfigFactory.load().resolve(),
        exitOnMissingConfig,
      )
        .resolve(ConfigResolveOptions.defaults())
        .resolve()

    dynamicParams
      .forEach { (k, v) ->
        // Strip quotes
        val qval = v.removeSurrounding("\"")
        val prop = "$k=$qval"
        val newConfig = ConfigFactory.parseString(prop, PROPS)
        config = newConfig.withFallback(config).resolve()
      }
  }

  private fun String.isUrlPrefix() = lowercase().startsWith(HTTP_PREFIX) || lowercase().startsWith(HTTPS_PREFIX)

  private fun String.isJsonSuffix() = lowercase().endsWith(".json") || lowercase().endsWith(".jsn")

  private fun String.isPropertiesSuffix() = lowercase().endsWith(".properties") || lowercase().endsWith(".props")

  private fun getConfigSyntax(configName: String) =
    when {
      configName.isJsonSuffix() -> ConfigSyntax.JSON
      configName.isPropertiesSuffix() -> ConfigSyntax.PROPERTIES
      else -> ConfigSyntax.CONF
    }

  private fun readConfig(
    configName: String,
    envConfig: String,
    configParseOptions: ConfigParseOptions,
    fallback: Config,
    exitOnMissingConfig: Boolean,
  ): Config {
    when {
      configName.isBlank() -> {
        if (exitOnMissingConfig) {
          logger.error { $$"A configuration file or url must be specified with --config or $$$envConfig" }
          exitProcess(1)
        }
        return fallback
      }

      configName.isUrlPrefix() -> {
        runCatchingCancellable {
          val configSyntax = getConfigSyntax(configName)
          return ConfigFactory
            .parseURL(URL(configName), configParseOptions.setSyntax(configSyntax))
            .withFallback(fallback)
        }.onFailure { e ->
          if (e.cause is FileNotFoundException)
            logger.error { "Invalid config url: $configName" }
          else
            logger.error(e) { "Exception: ${e.simpleClassName} - ${e.message}" }
        }
      }

      else -> {
        runCatchingCancellable {
          return ConfigFactory.parseFileAnySyntax(File(configName), configParseOptions).withFallback(fallback)
        }.onFailure { e ->
          if (e.cause is FileNotFoundException)
            logger.error { "Invalid config filename: $configName" }
          else
            logger.error(e) { "Exception: ${e.simpleClassName} - ${e.message}" }
        }
      }
    }

    exitProcess(1)
  }

  companion object {
    private val logger = logger {}
    private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    const val DEBUG = "debug"
    const val HTTP_PREFIX = "http://"
    const val HTTPS_PREFIX = "https://"
  }
}
