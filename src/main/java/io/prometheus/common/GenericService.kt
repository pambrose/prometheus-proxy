/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheck
import com.codahale.metrics.health.HealthCheckRegistry
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck
import com.codahale.metrics.jmx.JmxReporter
import com.google.common.base.Joiner
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ServiceManager
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.dsl.GuavaDsl.serviceManager
import io.prometheus.dsl.GuavaDsl.serviceManagerListener
import io.prometheus.dsl.MetricsDsl.healthCheck
import io.prometheus.guava.GenericExecutionThreadService
import io.prometheus.guava.genericServiceListener
import mu.KLogging
import java.io.Closeable
import kotlin.properties.Delegates

@KtorExperimentalAPI
abstract class GenericService protected constructor(
    val genericConfigVals: ConfigVals,
    adminConfig: AdminConfig,
    metricsConfig: MetricsConfig,
    zipkinConfig: ZipkinConfig,
    val isTestMode: Boolean
) :
    GenericExecutionThreadService(),
    Closeable {
    protected val healthCheckRegistry = HealthCheckRegistry()

    private val services = mutableListOf<Service>()

    private lateinit var serviceManager: ServiceManager

    val isAdminEnabled = adminConfig.enabled
    val isMetricsEnabled = metricsConfig.enabled
    val isZipkinEnabled = zipkinConfig.enabled

    private var jmxReporter: JmxReporter by Delegates.notNull()
    var adminService: AdminService by Delegates.notNull()
    var metricsService: MetricsService by Delegates.notNull()
    var zipkinReporterService: ZipkinReporterService by Delegates.notNull()

    init {
        if (isAdminEnabled) {
            adminService = AdminService(
                healthCheckRegistry = healthCheckRegistry,
                port = adminConfig.port,
                pingPath = adminConfig.pingPath,
                versionPath = adminConfig.versionPath,
                healthCheckPath = adminConfig.healthCheckPath,
                threadDumpPath = adminConfig.threadDumpPath
            ) { addService(this) }
        } else {
            logger.info { "Admin service disabled" }
        }

        if (isMetricsEnabled) {
            metricsService = MetricsService(metricsConfig.port, metricsConfig.path) { addService(this) }
            SystemMetrics.initialize(
                enableStandardExports = metricsConfig.standardExportsEnabled,
                enableMemoryPoolsExports = metricsConfig.memoryPoolsExportsEnabled,
                enableGarbageCollectorExports = metricsConfig.garbageCollectorExportsEnabled,
                enableThreadExports = metricsConfig.threadExportsEnabled,
                enableClassLoadingExports = metricsConfig.classLoadingExportsEnabled,
                enableVersionInfoExports = metricsConfig.versionInfoExportsEnabled
            )
            jmxReporter = JmxReporter.forRegistry(MetricRegistry()).build()
        } else {
            logger.info { "Metrics service disabled" }
        }

        if (isZipkinEnabled) {
            val url = "http://${zipkinConfig.hostname}:${zipkinConfig.port}/${zipkinConfig.path}"
            zipkinReporterService = ZipkinReporterService(url) { addService(this) }
        } else {
            logger.info { "Zipkin reporter service disabled" }
        }
    }

    fun initService() {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        addService(this)
        serviceManager =
            serviceManager(services) {
                addListener(
                    serviceManagerListener {
                        healthy { logger.info { "All $simpleClassName services healthy" } }
                        stopped { logger.info { "All $simpleClassName services stopped" } }
                        failure { logger.info { "$simpleClassName service failed: $it" } }
                    })
            }
        registerHealthChecks()
    }

    override fun startUp() {
        super.startUp()
        if (isZipkinEnabled)
            zipkinReporterService.startSync()

        if (isMetricsEnabled) {
            metricsService.startSync()
            jmxReporter.start()
        }

        if (isAdminEnabled)
            adminService.startSync()

        Runtime.getRuntime().addShutdownHook(shutDownHookAction(this))
    }

    override fun shutDown() {
        if (isAdminEnabled)
            adminService.stopSync()

        if (isMetricsEnabled) {
            metricsService.stopSync()
            jmxReporter.stop()
        }

        if (isZipkinEnabled)
            zipkinReporterService.stopSync()

        super.shutDown()
    }

    override fun close() {
        stopSync()
    }

    private fun addService(service: Service) {
        logger.info { "Adding service $service" }
        services += service
    }

    protected fun addServices(service: Service, vararg services: Service) {
        addService(service)
        services.forEach { addService(it) }
    }

    protected open fun registerHealthChecks() {
        healthCheckRegistry
            .apply {
                register("thread_deadlock", ThreadDeadlockHealthCheck())
                if (isMetricsEnabled)
                    register("metrics_service", metricsService.healthCheck)
                register(
                    "all_services_healthy",
                    healthCheck {
                        if (serviceManager.isHealthy)
                            HealthCheck.Result.healthy()
                        else {
                            val vals =
                                serviceManager
                                    .servicesByState()
                                    .entries()
                                    .filter { it.key !== Service.State.RUNNING }
                                    .onEach { logger.warn { "Incorrect state - ${it.key}: ${it.value}" } }
                                    .map { "${it.key}: ${it.value}" }
                                    .toList()
                            HealthCheck.Result.unhealthy("Incorrect state: ${Joiner.on(", ").join(vals)}")
                        }
                    })
            }
    }

    companion object : KLogging()
}