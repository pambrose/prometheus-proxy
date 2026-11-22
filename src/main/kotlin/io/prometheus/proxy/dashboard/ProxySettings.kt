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

package io.prometheus.proxy.dashboard

import com.pambrose.common.util.ensureLeadingSlash
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.dashboard.CommonSettings
import io.prometheus.common.dashboard.DashboardValues
import io.prometheus.common.dashboard.Setting
import io.prometheus.common.dashboard.SettingText.grpcDefault
import io.prometheus.common.dashboard.SettingText.limit
import io.prometheus.common.dashboard.SettingText.onOff
import io.prometheus.common.dashboard.SettingText.secs
import io.prometheus.common.dashboard.SettingsGroup
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ScrapeDispatch

/**
 * The proxy's settings, as it resolved them from its flags, env vars, config, and defaults, for the dashboard's
 * Settings view. Read once, since none changes while the proxy runs.
 *
 * None is a secret: the shared agent token and each `proxy.auth` identity's token, the dashboard's password, and TLS
 * material given as PEM show only whether they are set, and a `-D` override only its key. What the view does show,
 * identity names and the path globs each may register, TLS file paths, and the dashboard's username, is what an
 * operator checks when an agent is refused or a scrape fails.
 */
internal object ProxySettings {
  fun of(proxy: Proxy): List<SettingsGroup> {
    val options = proxy.options
    val config = proxy.proxyConfigVals
    return listOf(
      CommonSettings.about(options, proxy.launchId, proxy.upTime),
      listeners(proxy),
      authentication(options, config),
      CommonSettings.tls(options, proxy.tlsDescription),
      scrapes(proxy),
      agentLimits(proxy),
      grpc(options),
      serviceDiscovery(options, config.service.discovery),
      dashboard(options, config.dashboard),
      CommonSettings.admin(
        newAdminConfig(options.adminEnabled, options.adminPort, config.admin),
        options.debugEnabled,
        listOf(Setting("Recent requests on /debug", "${config.admin.recentRequestsQueueSize}")),
      ),
      CommonSettings.metrics(newMetricsConfig(options.metricsEnabled, options.metricsPort, config.metrics)),
      CommonSettings.tracing(newZipkinConfig(config.internal.zipkin), config.internal.zipkin.sampleRate),
      CommonSettings.logging(options.logLevel),
    )
  }

  // Where Prometheus and the agents reach the proxy, and the file blitz.io's verification fetches.
  private fun listeners(proxy: Proxy): SettingsGroup {
    val http = proxy.proxyConfigVals.http
    return SettingsGroup(
      "Listeners",
      listOf(
        Setting("Scrapes (Prometheus)", "${http.host}:${proxy.scrapePort}"),
        Setting("Idle scrape connections", "closed after ${secs(proxy.scrapeIdleTimeout.inWholeSeconds)}"),
        Setting("Request logging", onOff(http.requestLoggingEnabled)),
        Setting("Agents (gRPC)", "port ${proxy.options.proxyAgentPort}"),
        Setting(
          "Blitz",
          onOff(proxy.proxyConfigVals.internal.blitz.enabled) { proxy.options.blitzPath.ensureLeadingSlash() },
        ),
      ),
    )
  }

  // Who may register as an agent, and what each identity may register; never a token.
  private fun authentication(
    options: ProxyOptions,
    config: ConfigVals.Proxy2,
  ): SettingsGroup {
    val identities = config.auth
    val identityRows =
      identities.map {
        Setting("Identity ${it.name}", if (it.paths.isEmpty()) "every path" else "paths ${it.paths.joinToString(", ")}")
      }
    return SettingsGroup(
      "Agent authentication",
      listOf(Setting("Authentication", authenticationMode(options, identities.size))) +
        identityRows +
        listOf(
          Setting("gRPC reflection", onOff(!options.reflectionDisabled)),
          CommonSettings.transportFilter(options),
        ),
    )
  }

  // How agents authenticate, and what the startup log warns of: a port any peer can register on, or tokens in the
  // clear.
  internal fun authenticationMode(
    options: ProxyOptions,
    identityCount: Int,
  ): String {
    val shared = options.agentToken.isNotEmpty()
    val identities = if (identityCount == 1) "1 identity" else "$identityCount identities"
    val mode =
      when {
        identityCount == 0 && !shared -> "off"
        identityCount == 0 -> "shared token"
        shared -> "$identities and a shared token"
        else -> identities
      }
    val tls = options.isTlsEnabled
    val warning =
      when {
        ProxyOptions.isAgentPortUnauthenticated(
          options.agentToken,
          identityCount,
          tls,
          options.hasTrustCertCollection,
        ) -> "any peer that reaches the agent port can register"

        ProxyOptions.areAgentTokensSentInCleartext(options.agentToken, identityCount, tls) -> "tokens sent without TLS"

        else -> ""
      }
    return if (warning.isEmpty()) mode else "$mode; $warning"
  }

  // What the proxy allows a scrape: its time, how many at once, and how large.
  private fun scrapes(proxy: Proxy): SettingsGroup {
    val internal = proxy.proxyConfigVals.internal
    val options = proxy.options
    val backlogFull = ScrapeDispatch.maxAgentBacklog(proxy)
    return SettingsGroup(
      "Scrapes",
      listOf(
        Setting("Scrape timeout", secs(internal.scrapeRequestTimeoutSecs)),
        Setting("Max in flight", "${internal.maxInFlightScrapeRequests} scrapes"),
        Setting("Agent backlog", "unhealthy at ${internal.scrapeRequestBacklogUnhealthySize}, full at $backlogFull"),
        Setting("Scrapes in flight", "unhealthy at ${options.scrapeRequestMapUnhealthySize}"),
        Setting("Chunked transfers", "unhealthy at ${options.chunkContextMapUnhealthySize}"),
        Setting("Largest zipped content", "${internal.maxZippedContentSizeMBytes} MB"),
        Setting("Largest unzipped content", "${internal.maxUnzippedContentSizeMBytes} MB"),
      ),
    )
  }

  // When an idle agent is evicted, and the bounds on what one agent may register and report.
  private fun agentLimits(proxy: Proxy): SettingsGroup {
    val internal = proxy.proxyConfigVals.internal
    val pause = secs(internal.staleAgentCheckPauseSecs)
    val inactivity = secs(internal.maxAgentInactivitySecs)
    // The cleanup runs as configured, or forced on by a disabled transport filter (see Proxy.agentCleanupService).
    val forced = if (internal.staleAgentCheckEnabled) "" else ", forced on by the disabled transport filter"
    return SettingsGroup(
      "Agent limits",
      listOf(
        Setting(
          "Stale agent check",
          onOff(proxy.isStaleAgentCheckRunning) { "every $pause, evicting after $inactivity idle$forced" },
        ),
        Setting("Paths per agent", limit(internal.maxPathsPerAgent)),
        Setting("Path length", limit(internal.maxPathLength, "characters")),
        Setting("Labels size", limit(internal.maxLabelsSizeBytes, "bytes")),
        Setting("Agent field length", limit(internal.maxAgentFieldLength, "characters")),
        Setting("Proxy endpoints kept", limit(internal.maxAgentProxyEndpoints)),
        Setting("Target URL length", limit(internal.maxTargetUrlLength, "characters")),
        Setting("Agents per identity, transport filter disabled", limit(internal.maxAgentsPerIdentity)),
      ),
    )
  }

  // The agent port's gRPC server, each -1 left to gRPC's default, as the startup log names it.
  private fun grpc(options: ProxyOptions): SettingsGroup =
    SettingsGroup(
      "gRPC server",
      listOf(
        Setting(
          "Handshake timeout",
          grpcDefault(options.handshakeTimeoutSecs, secs(ProxyOptions.GRPC_HANDSHAKE_TIMEOUT_SECS)),
        ),
      ) + CommonSettings.keepalive(options) +
        listOf(
          Setting("Keepalive without calls", if (options.permitKeepAliveWithoutCalls) "permitted" else "not permitted"),
          Setting(
            "Min keepalive interval",
            grpcDefault(options.permitKeepAliveTimeSecs, secs(ProxyOptions.GRPC_PERMIT_KEEPALIVE_TIME_SECS)),
          ),
          Setting("Max connection idle", grpcDefault(options.maxConnectionIdleSecs, "no limit")),
          Setting("Max connection age", grpcDefault(options.maxConnectionAgeSecs, "no limit")),
          Setting("Connection age grace", grpcDefault(options.maxConnectionAgeGraceSecs, "no limit")),
        ),
    )

  // The HTTP service discovery endpoint Prometheus can read the paths from.
  private fun serviceDiscovery(
    options: ProxyOptions,
    discovery: ConfigVals.Proxy2.Service.Discovery2,
  ): SettingsGroup =
    SettingsGroup(
      "Service discovery",
      listOf(
        Setting("Service discovery", onOff(options.sdEnabled) { options.sdPath.ensureLeadingSlash() }),
        Setting("Target", options.sdTargetPrefix),
        Setting("Agents' job and instance labels", if (discovery.reserveJobAndInstanceLabels) "dropped" else "kept"),
      ),
    )

  // The dashboard's settings both sides have, then the proxy's own: how long a gone path stays, and the Links column.
  private fun dashboard(
    options: ProxyOptions,
    dashboard: ConfigVals.Proxy2.Dashboard2,
  ): SettingsGroup {
    val links =
      dashboard.links.map { link ->
        Setting("Link ${link.name}", link.url + if (link.icon.isEmpty()) "" else " (${link.icon} icon)")
      }
    return with(dashboard) {
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
        extra = listOf(Setting("Departed paths kept", secs(departedPathRetentionSecs))) +
          links.ifEmpty { listOf(Setting("Links", "none")) },
      )
    }
  }
}
