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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.pambrose.common.service.AdminConfig
import com.pambrose.common.service.MetricsConfig
import com.pambrose.common.service.ZipkinConfig

@Suppress("unused")
internal object ConfigWrappers {
  // The proxy.* and agent.* admin/metrics/zipkin blocks have identical fields but tscfg emits
  // distinct types per path (Proxy2.Admin2 vs Agent.Admin, etc.), so each config needs an overload
  // per role. Each overload only extracts the role-specific fields and delegates to a single private
  // builder, so the actual AdminConfig/MetricsConfig/ZipkinConfig construction is defined once.

  fun newAdminConfig(
    enabled: Boolean,
    port: Int,
    admin: ConfigVals.Proxy2.Admin2,
  ) = adminConfig(enabled, port, admin.pingPath, admin.versionPath, admin.healthCheckPath, admin.threadDumpPath)

  fun newAdminConfig(
    enabled: Boolean,
    port: Int,
    admin: ConfigVals.Agent.Admin,
  ) = adminConfig(enabled, port, admin.pingPath, admin.versionPath, admin.healthCheckPath, admin.threadDumpPath)

  fun newMetricsConfig(
    enabled: Boolean,
    port: Int,
    metrics: ConfigVals.Proxy2.Metrics2,
  ) = metricsConfig(
    enabled,
    port,
    metrics.path,
    metrics.standardExportsEnabled,
    metrics.memoryPoolsExportsEnabled,
    metrics.garbageCollectorExportsEnabled,
    metrics.threadExportsEnabled,
    metrics.classLoadingExportsEnabled,
    metrics.versionInfoExportsEnabled,
  )

  fun newMetricsConfig(
    enabled: Boolean,
    port: Int,
    metrics: ConfigVals.Agent.Metrics,
  ) = metricsConfig(
    enabled,
    port,
    metrics.path,
    metrics.standardExportsEnabled,
    metrics.memoryPoolsExportsEnabled,
    metrics.garbageCollectorExportsEnabled,
    metrics.threadExportsEnabled,
    metrics.classLoadingExportsEnabled,
    metrics.versionInfoExportsEnabled,
  )

  fun newZipkinConfig(zipkin: ConfigVals.Proxy2.Internal2.Zipkin2) =
    zipkinConfig(zipkin.enabled, zipkin.hostname, zipkin.port, zipkin.path, zipkin.serviceName)

  fun newZipkinConfig(zipkin: ConfigVals.Agent.Internal.Zipkin) =
    zipkinConfig(zipkin.enabled, zipkin.hostname, zipkin.port, zipkin.path, zipkin.serviceName)

  private fun adminConfig(
    enabled: Boolean,
    port: Int,
    pingPath: String,
    versionPath: String,
    healthCheckPath: String,
    threadDumpPath: String,
  ) = AdminConfig(
    enabled = enabled,
    port = port,
    pingPath = pingPath,
    versionPath = versionPath,
    healthCheckPath = healthCheckPath,
    threadDumpPath = threadDumpPath,
  )

  // Parameters are in MetricsConfig field order; callers pass the matching tscfg fields in that order.
  private fun metricsConfig(
    enabled: Boolean,
    port: Int,
    path: String,
    standardExportsEnabled: Boolean,
    memoryPoolsExportsEnabled: Boolean,
    garbageCollectorExportsEnabled: Boolean,
    threadExportsEnabled: Boolean,
    classLoadingExportsEnabled: Boolean,
    versionInfoExportsEnabled: Boolean,
  ) = MetricsConfig(
    enabled = enabled,
    port = port,
    path = path,
    standardExportsEnabled = standardExportsEnabled,
    memoryPoolsExportsEnabled = memoryPoolsExportsEnabled,
    garbageCollectorExportsEnabled = garbageCollectorExportsEnabled,
    threadExportsEnabled = threadExportsEnabled,
    classLoadingExportsEnabled = classLoadingExportsEnabled,
    versionInfoExportsEnabled = versionInfoExportsEnabled,
  )

  private fun zipkinConfig(
    enabled: Boolean,
    hostname: String,
    port: Int,
    path: String,
    serviceName: String,
  ) = ZipkinConfig(
    enabled = enabled,
    hostname = hostname,
    port = port,
    path = path,
    serviceName = serviceName,
  )
}
