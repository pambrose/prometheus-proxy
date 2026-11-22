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

package io.prometheus.agent.dashboard

import io.prometheus.Agent
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentOptions
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.dashboard.CommonSettings
import io.prometheus.common.dashboard.DashboardValues
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.Setting
import io.prometheus.common.dashboard.SettingText.grpcDefault
import io.prometheus.common.dashboard.SettingText.onOff
import io.prometheus.common.dashboard.SettingText.secret
import io.prometheus.common.dashboard.SettingText.secs
import io.prometheus.common.dashboard.SettingText.yesNo
import io.prometheus.common.dashboard.SettingsGroup

/**
 * The agent's settings, as it resolved them from its flags, env vars, config, and defaults, for the dashboard's
 * Settings view. Read once, since none changes while the agent runs.
 *
 * None is a secret: the agent's token, the trust store's password, the dashboard's password, and TLS material given as
 * PEM show only whether they are set, and a `-D` override only its key.
 */
internal object AgentSettings {
  fun of(agent: Agent): List<SettingsGroup> {
    val options = agent.options
    val config = agent.agentConfigVals
    return listOf(
      CommonSettings.about(
        options,
        agent.launchId,
        agent.upTime,
        leading = listOf(Setting("Agent name", agent.agentName)),
      ),
      proxyConnection(options, config.internal),
      scraping(agent),
      paths(agent),
      CommonSettings.tls(
        options,
        agent.grpcService.tlsDescription,
        listOf(Setting("Override authority", options.overrideAuthority.ifEmpty { DASH })),
      ),
      dashboard(options, config.dashboard),
      CommonSettings.admin(newAdminConfig(options.adminEnabled, options.adminPort, config.admin), options.debugEnabled),
      CommonSettings.metrics(newMetricsConfig(options.metricsEnabled, options.metricsPort, config.metrics)),
      CommonSettings.tracing(newZipkinConfig(config.internal.zipkin)),
      CommonSettings.logging(options),
    )
  }

  // How the agent reaches its proxies and keeps the connection: the endpoints it tries, in order, its token, never the
  // token itself, its gRPC settings, and its heartbeat.
  private fun proxyConnection(
    options: AgentOptions,
    internal: ConfigVals.Agent.Internal,
  ): SettingsGroup =
    SettingsGroup(
      "Proxy connection",
      listOf(
        Setting("Proxy endpoints", options.proxyHostname.split(",").joinToString(", ")),
        Setting("Token", secret(options.agentToken)),
        Setting("Consolidated", yesNo(options.consolidated)),
        Setting("Transport filter", if (options.transportFilterDisabled) "disabled" else "enabled"),
        Setting("Reconnect pause", secs(internal.reconnectPauseSecs)),
        Setting("Unary deadline", secs(options.unaryDeadlineSecs)),
        Setting("Keepalive time", grpcDefault(options.keepAliveTimeSecs)),
        Setting("Keepalive timeout", grpcDefault(options.keepAliveTimeoutSecs)),
        Setting("Keepalive without calls", yesNo(options.keepAliveWithoutCalls)),
        Setting(
          "Heartbeat",
          onOff(internal.heartbeatEnabled) {
            "after ${secs(internal.heartbeatMaxInactivitySecs)} idle, checked every " +
              "${internal.heartbeatCheckPauseMillis} ms"
          },
        ),
      ),
    )

  // How the agent fetches its targets: its limits, its HTTP clients, and whether it verifies a target's certificate.
  private fun scraping(agent: Agent): SettingsGroup =
    with(agent.options) {
      // The timeout the clients use, which the deprecated agent.internal.cioTimeoutSecs can still set.
      val clientTimeout =
        AgentHttpService.resolveTimeoutSecs(agent.agentConfigVals.internal.cioTimeoutSecs, httpClientTimeoutSecs)
      SettingsGroup(
        "Scraping",
        listOf(
          Setting("Scrape timeout", secs(scrapeTimeoutSecs)),
          Setting("Scrape retries", "$scrapeMaxRetries"),
          Setting("Chunked above", "$chunkContentSizeKbs KB"),
          Setting("Gzipped above", "$minGzipSizeBytes bytes"),
          Setting("Largest scrape", "$maxContentLengthMBytes MB"),
          Setting("HTTP clients", "$maxConcurrentHttpClients at once, ${secs(clientTimeout)} timeout"),
          Setting(
            "HTTP client cache",
            "$maxCacheSize clients, $maxCacheAgeMins min old, $maxCacheIdleMins min idle, " +
              "cleaned every $cacheCleanupIntervalMins min",
          ),
          Setting("Target certificates", if (trustAllX509Certificates) "not verified" else "verified"),
          Setting("Trust store", httpsTrustStorePath.ifEmpty { "JDK default" }),
          Setting("Trust store password", secret(httpsTrustStorePassword)),
        ),
      )
    }

  // The agent's paths as configured: how many, their filters, how a refused one is retried, and discovery, whose
  // state the Connection view shows.
  private fun paths(agent: Agent): SettingsGroup {
    val config = agent.agentConfigVals
    val internal = config.internal
    val discovery = config.discovery
    return SettingsGroup(
      "Paths",
      listOf(
        Setting("Configured paths", "${agent.pathManager.pathConfigs.size}"),
        Setting("Metric filters", "${config.filters.size}"),
        Setting(
          "Refused path retry",
          "after ${secs(internal.rejectedPathRetrySecs)}, doubling up to ${secs(internal.rejectedPathRetryMaxSecs)}",
        ),
        Setting("Backlog", "unhealthy at ${internal.scrapeRequestBacklogUnhealthySize}"),
        Setting(
          "Discovery",
          onOff(discovery.enabled) {
            "file ${discovery.file.path}, read every ${secs(discovery.reconcileIntervalSecs)}"
          },
        ),
      ),
    )
  }

  // The dashboard's settings both sides have.
  private fun dashboard(
    options: AgentOptions,
    dashboard: ConfigVals.Agent.Dashboard,
  ): SettingsGroup =
    with(dashboard) {
      CommonSettings.dashboard(
        options = options,
        values =
          DashboardValues(
            refreshIntervalSecs = refreshIntervalSecs,
            pathHistorySize = pathHistorySize,
            activityLogSize = activityLogSize,
            scrapeNowEnabled = scrapeNowEnabled,
            maxSessions = maxSessions,
            allowedOrigins = allowedOrigins,
            allowedHosts = allowedHosts,
          ),
      )
    }
}
