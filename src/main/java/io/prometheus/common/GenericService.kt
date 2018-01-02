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

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheck
import com.codahale.metrics.health.HealthCheckRegistry
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck
import com.codahale.metrics.jmx.JmxReporter
import com.google.common.base.Joiner
import com.google.common.util.concurrent.AbstractExecutionThreadService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ServiceManager
import io.prometheus.dsl.GuavaDsl.serviceManagerListener
import io.prometheus.dsl.MetricsDsl.healthCheck
import org.slf4j.LoggerFactory
import java.io.Closeable
import kotlin.properties.Delegates

abstract class GenericService protected constructor(protected val genericConfigVals: ConfigVals,
                                                    private val adminConfig: AdminConfig,
                                                    private val metricsConfig: MetricsConfig,
                                                    private val zipkinConfig: ZipkinConfig,
                                                    val isTestMode: Boolean) : AbstractExecutionThreadService(), Closeable {
    protected val healthCheckRegistry = HealthCheckRegistry()

    private val services = mutableListOf<Service>()

    private lateinit var serviceManager: ServiceManager

    val isAdminEnabled: Boolean
        get() = adminConfig.enabled

    val isMetricsEnabled: Boolean
        get() = metricsConfig.enabled

    val isZipkinEnabled: Boolean
        get() = zipkinConfig.enabled

    private var jmxReporter: JmxReporter by Delegates.notNull()
    var adminService: AdminService by Delegates.notNull()
    var metricsService: MetricsService by Delegates.notNull()
    var zipkinReporterService: ZipkinReporterService by Delegates.notNull()

    init {
        if (isAdminEnabled) {
            adminService = AdminService(healthCheckRegistry,
                                        adminConfig.port,
                                        adminConfig.pingPath,
                                        adminConfig.versionPath,
                                        adminConfig.healthCheckPath,
                                        adminConfig.threadDumpPath).apply {
                addService(this)
            }
        }
        else {
            logger.info("Admin service disabled")
        }

        if (isMetricsEnabled) {
            metricsService = MetricsService(metricsConfig.port, metricsConfig.path).apply { addService(this) }
            SystemMetrics.initialize(metricsConfig.standardExportsEnabled,
                                     metricsConfig.memoryPoolsExportsEnabled,
                                     metricsConfig.garbageCollectorExportsEnabled,
                                     metricsConfig.threadExportsEnabled,
                                     metricsConfig.classLoadingExportsEnabled,
                                     metricsConfig.versionInfoExportsEnabled)
            jmxReporter = JmxReporter.forRegistry(MetricRegistry()).build()
        }
        else {
            logger.info("Metrics service disabled")
        }

        if (isZipkinEnabled) {
            val url = "http://${zipkinConfig.hostname}:${zipkinConfig.port}/${zipkinConfig.path}"
            zipkinReporterService = ZipkinReporterService(url).apply { addService(this) }
        }
        else {
            logger.info("Zipkin reporter service disabled")
        }
    }

    fun initService() {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        addService(this)
        val clazzName = javaClass.simpleName
        serviceManager =
                ServiceManager(services).apply {
                    addListener(
                            serviceManagerListener {
                                healthy { logger.info("All $clazzName services healthy") }
                                stopped { logger.info("All $clazzName services stopped") }
                                failure { service -> logger.info("$clazzName service failed: $service") }
                            })
                }
        registerHealthChecks()
    }

    override fun startUp() {
        super.startUp()
        if (isZipkinEnabled)
            zipkinReporterService.startAsync()
        if (isMetricsEnabled) {
            metricsService.startAsync()
            jmxReporter.start()
        }
        if (isAdminEnabled)
            adminService.startAsync()
        Runtime.getRuntime().addShutdownHook(shutDownHookAction(this))
    }

    override fun shutDown() {
        if (isAdminEnabled)
            adminService.stopAsync()
        if (isMetricsEnabled) {
            metricsService.stopAsync()
            jmxReporter.stop()
        }
        if (isZipkinEnabled)
            zipkinReporterService.stopAsync()
        super.shutDown()
    }

    override fun close() {
        stopAsync()
    }

    private fun addService(service: Service) {
        logger.info("Adding service $service")
        services.add(service)
    }

    protected fun addServices(service: Service, vararg services: Service) {
        addService(service)
        services.forEach { addService(it) }
    }

    protected open fun registerHealthChecks() {
        healthCheckRegistry.apply {
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
                                    serviceManager.servicesByState()
                                            .entries()
                                            .filter { it.key !== Service.State.RUNNING }
                                            .onEach { logger.warn("Incorrect state - ${it.key}: ${it.value}") }
                                            .map { "${it.key}: ${it.value}" }
                                            .toList()
                            HealthCheck.Result.unhealthy("Incorrect state: ${Joiner.on(", ").join(vals)}")
                        }
                    })
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GenericService::class.java)
    }
}