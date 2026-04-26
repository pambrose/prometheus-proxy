/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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
import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.simpleClassName
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.BaseOptions.Companion.DEBUG
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
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import kotlin.system.exitProcess

// @Parameters(separators = "=")
abstract class BaseOptions protected constructor(
  private val progName: String,
  private val args: Array<String>,
  private val envConfig: String,
  private val exitOnMissingConfig: Boolean = false,
) {
  /**
   * Path or URL to the HOCON config file. Accepts a local filesystem path, an `http://`/`https://` URL, or a
   * `.json`/`.properties` file (syntax inferred from the extension).
   * Empty falls back to the env var named by the subclass-specific `envConfig` parameter
   * (`PROXY_CONFIG` for the Proxy, `AGENT_CONFIG` for the Agent).
   */
  @Parameter(names = ["-c", "--conf", "--config"], description = "Configuration file or url")
  private var configSource = ""

  /**
   * Enables the admin servlets (`/ping`, `/version`, `/healthcheck`, `/threaddump`) on this process.
   * Resolved from CLI → [ADMIN_ENABLED] env var → `<role>.admin.enabled` config (default `false`).
   */
  @Parameter(names = ["-r", "--admin"], description = "Admin servlets enabled")
  var adminEnabled = false
    private set

  /**
   * Listen port for this process's admin servlets.
   * `-1` means "fall back to [ADMIN_PORT] env var, then `<role>.admin.port` config (Proxy default `8092`,
   * Agent default `8093`)".
   */
  @Parameter(names = ["-i", "--admin_port"], description = "Admin servlets port")
  var adminPort: Int = -1
    private set

  /**
   * Enables this process's Prometheus metrics endpoint.
   * Resolved from CLI → [METRICS_ENABLED] env var → `<role>.metrics.enabled` config (default `false`).
   */
  @Parameter(names = ["-e", "--metrics"], description = "Metrics enabled")
  var metricsEnabled = false
    private set

  /**
   * Listen port for this process's Prometheus metrics endpoint.
   * `-1` means "fall back to [METRICS_PORT] env var, then `<role>.metrics.port` config (Proxy default `8082`,
   * Agent default `8083`)".
   */
  @Parameter(names = ["-m", "--metrics_port"], description = "Metrics listen port")
  var metricsPort = -1
    private set

  /**
   * Enables the `/debug` admin servlet on this process — exposes recent activity / scrape-request introspection.
   * Distinct from [DEBUG] (the servlet path constant). Resolved from CLI → [DEBUG_ENABLED] env var →
   * `<role>.admin.debugEnabled` config (default `false`).
   */
  @Parameter(names = ["-b", "--debug"], description = "Debug option enabled")
  var debugEnabled = false
    private set

  /**
   * Disables the gRPC transport filter that records remote-peer information per call. Set when running behind
   * an L7 reverse proxy (e.g. nginx) that already strips this information.
   *
   * Both `--tf-disabled` (current) and `--tf_disabled` (legacy typo) are accepted to preserve backwards
   * compatibility for existing deployments.
   */
  @Parameter(names = ["--tf-disabled", "--tf_disabled"], description = "Transport filter disabled")
  var transportFilterDisabled = false
    private set

  /**
   * Filesystem path to the TLS certificate chain (PEM). Must be paired with [privateKeyFilePath]; supplying
   * one without the other is rejected by [validateTlsConfig]. Empty disables TLS on this side and exposes
   * plaintext gRPC. See also [isTlsEnabled].
   */
  @Parameter(names = ["-t", "--cert"], description = "Certificate chain file path")
  var certChainFilePath = ""
    private set

  /**
   * Filesystem path to the TLS private key (PEM) matching [certChainFilePath]. Must be paired with the cert
   * file; supplying one without the other is rejected by [validateTlsConfig]. Empty disables TLS on this side.
   */
  @Parameter(names = ["-k", "--key"], description = "Private key file path")
  var privateKeyFilePath = ""
    private set

  /**
   * Filesystem path to the trust-store certificate collection (PEM) used to validate the peer's certificate.
   * Required for mTLS; for one-way TLS the JDK default trust store is used when this is empty.
   */
  @Parameter(names = ["-s", "--trust"], description = "Trust certificate collection file path")
  var trustCertCollectionFilePath = ""
    private set

  /**
   * gRPC keepalive ping interval, in seconds — how often this side sends a keepalive ping on an idle channel.
   * `-1L` means "fall back to [KEEPALIVE_TIME_SECS] env var, then `<role>.grpc.keepAliveTimeSecs` config;
   * `-1L` after resolution leaves the gRPC default in place".
   */
  @Parameter(names = ["--keepalive_time_secs"], description = "gRPC KeepAlive time (secs)")
  var keepAliveTimeSecs = -1L
    private set

  /**
   * gRPC keepalive ping timeout, in seconds — how long this side waits for a keepalive ack before considering
   * the connection dead.
   * `-1L` means "fall back to [KEEPALIVE_TIMEOUT_SECS] env var, then `<role>.grpc.keepAliveTimeoutSecs` config;
   * `-1L` after resolution leaves the gRPC default in place".
   */
  @Parameter(names = ["--keepalive_timeout_secs"], description = "gRPC KeepAlive timeout (secs)")
  var keepAliveTimeoutSecs = -1L
    private set

  /**
   * Logback log level for this process: one of `all`, `trace`, `debug`, `info`, `warn`, `error`, `off`.
   * Empty falls back to the role-specific env var ([io.prometheus.common.EnvVars.PROXY_LOG_LEVEL] or
   * [io.prometheus.common.EnvVars.AGENT_LOG_LEVEL]) and then `<role>.logLevel` config; if still empty,
   * the level configured in `logback.xml` is used.
   *
   * Settable by subclasses (e.g. [io.prometheus.proxy.ProxyOptions], [io.prometheus.agent.AgentOptions])
   * during [assignConfigVals].
   */
  @Parameter(names = ["--log_level"], description = "Log level")
  var logLevel = ""
    protected set

  /** When set, prints `name version (build-date)` to stdout and exits with code `0`. Not retained as state. */
  @Parameter(names = ["-v", "--version"], description = "Print version info and exit")
  private var version = false

  /**
   * When set, JCommander prints the auto-generated usage banner to stdout and the process exits with code `0`.
   * Marked `help = true` so JCommander does not enforce required-parameter checks when this flag is present.
   */
  @Parameter(names = ["-u", "--usage"], help = true)
  private var usage = false

  /**
   * Dynamic HOCON property overrides accepted as `-Dkey=value` (multiple `-D` flags allowed). Each pair is
   * merged into the resolved config with highest precedence over the loaded config file, and any surrounding
   * double-quotes on the value are stripped. Useful for one-off overrides that don't have a dedicated flag.
   */
  @DynamicParameter(names = ["-D"], description = "Dynamic property assignment")
  var dynamicParams = mutableMapOf<String, String>()
    private set

  private var cliArgs: Array<String> = emptyArray()

  private lateinit var config: Config

  lateinit var configVals: ConfigVals
    private set

  val isTlsEnabled: Boolean
    get() = certChainFilePath.isNotEmpty() && privateKeyFilePath.isNotEmpty()

  private fun isFlagInArgs(vararg names: String): Boolean =
    cliArgs.any { arg -> names.any { name -> arg.equals(name, ignoreCase = true) } }

  protected fun resolveBooleanOption(
    cliValue: Boolean,
    envVar: EnvVars,
    configDefault: Boolean,
  ): Boolean = resolveBoolean(cliValue, envVar.name, System.getenv(envVar.name), configDefault)

  protected fun resolveBooleanOption(
    cliValue: Boolean,
    envVar: EnvVars,
    configDefault: Boolean,
    vararg cliNames: String,
  ): Boolean {
    val cliExplicitlySet = isFlagInArgs(*cliNames)
    return resolveBoolean(cliValue, cliExplicitlySet, envVar.name, System.getenv(envVar.name), configDefault)
  }

  protected fun validateTlsConfig() {
    val hasCert = certChainFilePath.isNotEmpty()
    val hasKey = privateKeyFilePath.isNotEmpty()
    require(hasCert == hasKey) {
      if (hasCert)
        "Incomplete TLS configuration: certificate chain file is set but private key file is missing (use --key)"
      else
        "Incomplete TLS configuration: private key file is set but certificate chain file is missing (use --cert)"
    }
  }

  protected abstract fun assignConfigVals()

  protected fun parseOptions() {
    cliArgs = args

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

        if (version) {
          jcom.console.println(Utils.getVersionDesc(false))
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
    adminEnabled = resolveBooleanOption(adminEnabled, ADMIN_ENABLED, defaultVal, "-r", "--admin")
    logger.info { "adminEnabled: $adminEnabled" }
  }

  protected fun assignAdminPort(defaultVal: Int) {
    if (adminPort == -1)
      adminPort = ADMIN_PORT.getEnv(defaultVal)
    logger.info { "adminPort: $adminPort" }
  }

  protected fun assignMetricsEnabled(defaultVal: Boolean) {
    metricsEnabled = resolveBooleanOption(metricsEnabled, METRICS_ENABLED, defaultVal, "-e", "--metrics")
    logger.info { "metricsEnabled: $metricsEnabled" }
  }

  protected fun assignDebugEnabled(defaultVal: Boolean) {
    debugEnabled = resolveBooleanOption(debugEnabled, DEBUG_ENABLED, defaultVal, "-b", "--debug")
    logger.info { "debugEnabled: $debugEnabled" }
  }

  protected fun assignMetricsPort(defaultVal: Int) {
    if (metricsPort == -1)
      metricsPort = METRICS_PORT.getEnv(defaultVal)
    logger.info { "metricsPort: $metricsPort" }
  }

  protected fun assignTransportFilterDisabled(defaultVal: Boolean) {
    transportFilterDisabled =
      resolveBooleanOption(
        transportFilterDisabled,
        TRANSPORT_FILTER_DISABLED,
        defaultVal,
        "--tf-disabled",
        "--tf_disabled",
      )
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
    // .resolve() Unnecessary

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
            .parseURL(URI(configName).toURL(), configParseOptions.setSyntax(configSyntax))
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

  internal companion object {
    private val logger = logger {}
    private val PROPS = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
    const val DEBUG = "debug"
    const val HTTP_PREFIX = "http://"
    const val HTTPS_PREFIX = "https://"

    // Priority: CLI (if explicitly set) > env > config
    internal fun resolveBoolean(
      cliValue: Boolean,
      cliExplicitlySet: Boolean,
      envVarName: String,
      envVarValue: String?,
      configDefault: Boolean,
    ): Boolean =
      when {
        cliExplicitlySet -> cliValue
        envVarValue != null -> EnvVars.parseBooleanStrict(envVarName, envVarValue)
        else -> configDefault
      }

    // Legacy overload: assumes CLI was explicitly set when cliValue is true.
    // With JCommander arity-0 booleans, this is correct for the enable case.
    // Use the overload with cliExplicitlySet for proper disable-from-CLI support.
    internal fun resolveBoolean(
      cliValue: Boolean,
      envVarName: String,
      envVarValue: String?,
      configDefault: Boolean,
    ): Boolean = resolveBoolean(cliValue, cliValue, envVarName, envVarValue, configDefault)
  }
}
