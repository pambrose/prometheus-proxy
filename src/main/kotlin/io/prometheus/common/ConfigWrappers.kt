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

import com.github.pambrose.common.service.AdminConfig
import com.github.pambrose.common.service.MetricsConfig
import com.github.pambrose.common.service.ZipkinConfig

@Suppress("unused")
internal object ConfigWrappers {
  fun newAdminConfig(
    enabled: Boolean,
    port: Int,
    admin: ConfigVals.Proxy2.Admin2,
  ) = AdminConfig(
    enabled = enabled,
    port = port,
    pingPath = admin.pingPath,
    versionPath = admin.versionPath,
    healthCheckPath = admin.healthCheckPath,
    threadDumpPath = admin.threadDumpPath,
  )

  fun newAdminConfig(
    enabled: Boolean,
    port: Int,
    admin: ConfigVals.Agent.Admin,
  ) = AdminConfig(
    enabled = enabled,
    port = port,
    pingPath = admin.pingPath,
    versionPath = admin.versionPath,
    healthCheckPath = admin.healthCheckPath,
    threadDumpPath = admin.threadDumpPath,
  )

  fun newMetricsConfig(
    enabled: Boolean,
    port: Int,
    metrics: ConfigVals.Proxy2.Metrics2,
  ) = MetricsConfig(
    enabled = enabled,
    port = port,
    path = metrics.path,
    standardExportsEnabled = metrics.standardExportsEnabled,
    memoryPoolsExportsEnabled = metrics.memoryPoolsExportsEnabled,
    garbageCollectorExportsEnabled = metrics.garbageCollectorExportsEnabled,
    threadExportsEnabled = metrics.threadExportsEnabled,
    classLoadingExportsEnabled = metrics.classLoadingExportsEnabled,
    versionInfoExportsEnabled = metrics.versionInfoExportsEnabled,
  )

  fun newMetricsConfig(
    enabled: Boolean,
    port: Int,
    metrics: ConfigVals.Agent.Metrics,
  ) = MetricsConfig(
    enabled = enabled,
    port = port,
    path = metrics.path,
    standardExportsEnabled = metrics.standardExportsEnabled,
    memoryPoolsExportsEnabled = metrics.memoryPoolsExportsEnabled,
    garbageCollectorExportsEnabled = metrics.garbageCollectorExportsEnabled,
    threadExportsEnabled = metrics.threadExportsEnabled,
    classLoadingExportsEnabled = metrics.classLoadingExportsEnabled,
    versionInfoExportsEnabled = metrics.versionInfoExportsEnabled,
  )

  fun newZipkinConfig(zipkin: ConfigVals.Proxy2.Internal2.Zipkin2) =
    ZipkinConfig(
      enabled = zipkin.enabled,
      hostname = zipkin.hostname,
      port = zipkin.port,
      path = zipkin.path,
      serviceName = zipkin.serviceName,
    )

  fun newZipkinConfig(zipkin: ConfigVals.Agent.Internal.Zipkin) =
    ZipkinConfig(
      enabled = zipkin.enabled,
      hostname = zipkin.hostname,
      port = zipkin.port,
      path = zipkin.path,
      serviceName = zipkin.serviceName,
    )
}
