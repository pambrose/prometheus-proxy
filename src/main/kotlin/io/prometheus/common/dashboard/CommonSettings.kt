/*
 * Copyright © 2026 Paul Ambrose
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

package io.prometheus.common.dashboard

import com.pambrose.common.service.AdminConfig
import com.pambrose.common.service.MetricsConfig
import com.pambrose.common.service.ZipkinConfig
import io.prometheus.BuildConfig
import io.prometheus.common.BaseOptions
import com.pambrose.common.util.ensureLeadingSlash
import io.prometheus.common.dashboard.SettingText.grpcDefault
import io.prometheus.common.dashboard.SettingText.names
import io.prometheus.common.dashboard.SettingText.onOff
import io.prometheus.common.dashboard.SettingText.secs
import io.prometheus.common.dashboard.SettingText.tlsMaterial
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * What a dashboard keeps and allows, from its side's own `dashboard` config, for the Dashboard group
 * ([CommonSettings.dashboard]): tscfg gives the proxy's and the agent's distinct types.
 */
internal data class DashboardValues(
  val refreshIntervalSecs: Int,
  val pathHistorySize: Int,
  val activityLogSize: Int,
  val scrapeNowEnabled: Boolean,
  val maxSessions: Int,
  val allowedOrigins: List<String>,
  val allowedHosts: List<String>,
  val framingEnabled: Boolean,
)

/**
 * The [SettingsGroup]s the proxy's and the agent's Settings views share: what both resolve through [BaseOptions], and
 * their admin, metrics, and Zipkin settings, from the [AdminConfig], [MetricsConfig], and [ZipkinConfig]
 * [io.prometheus.common.ConfigWrappers] builds for their services. Each side adds its own rows where it has more.
 */
internal object CommonSettings {
  /**
   * The process itself: its version, [leading] (the agent's name), its [launchId], when it started, where its config
   * came from, and the keys of the `-D` overrides it was given, never their values, which can hold a token.
   */
  fun about(
    options: BaseOptions,
    launchId: String,
    upTime: Duration,
    leading: List<Setting> = emptyList(),
  ): SettingsGroup =
    SettingsGroup(
      "About",
      listOf(Setting("Version", "${BuildConfig.APP_VERSION}, released ${BuildConfig.APP_RELEASE_DATE}")) +
        leading +
        listOf(
          Setting("Launch ID", launchId),
          Setting("Started", "", at = Clock.System.now() - upTime),
          Setting("Config", options.configShown),
          Setting("Overrides (-D)", names(options.dynamicParams.keys.sorted(), empty = "none")),
        ),
    )

  /** How the process reaches its peer, and where each piece of its TLS material comes from, never the material. */
  fun tls(
    options: BaseOptions,
    transport: String,
    extra: List<Setting> = emptyList(),
  ): SettingsGroup =
    SettingsGroup(
      "TLS",
      listOf(
        Setting("Transport", transport),
        Setting("Certificate chain", tlsMaterial(options.certChainSource)),
        Setting("Private key", tlsMaterial(options.privateKeySource)),
        Setting("Trusted certificates", tlsMaterial(options.trustCertCollectionSource)),
      ) + extra,
    )

  /** The admin servlets, and the debug servlet, which they serve. */
  fun admin(
    admin: AdminConfig,
    debugEnabled: Boolean,
    extra: List<Setting> = emptyList(),
  ): SettingsGroup =
    SettingsGroup(
      "Admin",
      listOf(
        Setting("Admin", onOff(admin.enabled) { "port ${admin.port}" }),
        Setting(
          "Endpoints",
          listOf(admin.pingPath, admin.versionPath, admin.healthCheckPath, admin.threadDumpPath)
            .joinToString(", ") { it.ensureLeadingSlash() },
        ),
        Setting("Debug servlet", onOff(debugEnabled)),
      ) + extra,
    )

  /**
   * The metrics endpoint, and the JVM metric groups it adds to the process's own. An agent can serve its own metrics
   * through the proxy at [inProcessPath] instead (`agent.metrics.proxyPath`, or an embedded agent's `agentMetricsAt`),
   * or as well, with no port for them; empty without one, as the proxy always is.
   */
  fun metrics(
    metrics: MetricsConfig,
    inProcessPath: String = "",
  ): SettingsGroup =
    SettingsGroup(
      "Metrics",
      listOf(
        Setting("Metrics", onOff(metrics.enabled || inProcessPath.isNotEmpty()) { served(metrics, inProcessPath) }),
        Setting("JVM metrics", names(jvmExports(metrics), empty = "none")),
      ),
    )

  // Where the metrics are served: on their port, at the proxy's [inProcessPath], read in-process, or both.
  private fun served(
    metrics: MetricsConfig,
    inProcessPath: String,
  ): String =
    listOfNotNull(
      "port ${metrics.port}, ${metrics.path.ensureLeadingSlash()}".takeIf { metrics.enabled },
      "in-process, at the proxy's ${inProcessPath.ensureLeadingSlash()}".takeIf { inProcessPath.isNotEmpty() },
    ).joinToString(", and ")

  private fun jvmExports(metrics: MetricsConfig): List<String> =
    with(metrics) {
      listOf(
        "standard" to standardExportsEnabled,
        "memory pools" to memoryPoolsExportsEnabled,
        "garbage collector" to garbageCollectorExportsEnabled,
        "threads" to threadExportsEnabled,
        "class loading" to classLoadingExportsEnabled,
        "version info" to versionInfoExportsEnabled,
        "buffer pools" to bufferPoolExportsEnabled,
        "compilation" to compilationExportsEnabled,
        "native memory" to nativeMemoryExportsEnabled,
      ).filter { it.second }.map { it.first }
    }

  /**
   * The dashboard itself, which a page can show only while it's on: where it listens, its login, never the password,
   * its limits, and what it keeps; then [extra], the side's own.
   */
  fun dashboard(
    options: BaseOptions,
    values: DashboardValues,
    extra: List<Setting> = emptyList(),
  ): SettingsGroup =
    with(values) {
      SettingsGroup(
        "Dashboard",
        listOf(
          Setting(
            "Listening on",
            "${options.dashboardHost}:${options.dashboardPort}${options.dashboardPath.ensureLeadingSlash()}",
          ),
          Setting("Login", onOff(options.dashboardLogin != null) { "username ${options.dashboardUsername}" }),
          Setting("Refresh", "every ${secs(refreshIntervalSecs)}"),
          Setting("Path history", "$pathHistorySize scrapes"),
          Setting("Activity feed", "$activityLogSize lines"),
          Setting("Scrape now", onOff(scrapeNowEnabled)),
          Setting("Max sessions", "$maxSessions"),
          Setting("Allowed origins", names(allowedOrigins, empty = "the dashboard's own host")),
          Setting("Allowed hosts", names(allowedHosts, empty = "any")),
          Setting("Framing", if (framingEnabled) "allowed, by any site" else "refused"),
        ) + extra,
      )
    }

  /** Zipkin tracing, and the share of traces it keeps, [sampleRate]. */
  fun tracing(
    zipkin: ZipkinConfig,
    sampleRate: Double,
  ): SettingsGroup =
    SettingsGroup(
      "Tracing",
      listOf(
        Setting(
          "Zipkin",
          onOff(zipkin.enabled) {
            "${zipkin.hostname}:${zipkin.port}${zipkin.path.ensureLeadingSlash()}, service ${zipkin.serviceName}, " +
              "sampling ${percent(sampleRate)}"
          },
        ),
      ),
    )

  // [fraction] as a percentage, without a binary fraction's noise: 0.07 is 7%, not 7.000000000000001%.
  private fun percent(fraction: Double): String =
    "${BigDecimal(fraction.toString()).movePointRight(2).stripTrailingZeros().toPlainString()}%"

  /** The log level, [logLevel], as the startup log names it. */
  fun logging(logLevel: String): SettingsGroup =
    SettingsGroup("Logging", listOf(Setting("Log level", logLevel.ifEmpty { "info" })))

  /** The transport filter, which both sides turn off alike, behind a reverse proxy such as nginx. */
  fun transportFilter(options: BaseOptions): Setting =
    Setting("Transport filter", if (options.transportFilterDisabled) "disabled" else "enabled")

  /** The gRPC keepalive both sides resolve alike, each -1 left to gRPC's default. */
  fun keepalive(options: BaseOptions): List<Setting> =
    listOf(
      Setting("Keepalive time", grpcDefault(options.keepAliveTimeSecs)),
      Setting("Keepalive timeout", grpcDefault(options.keepAliveTimeoutSecs)),
    )
}
