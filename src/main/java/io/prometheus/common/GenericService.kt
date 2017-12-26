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
import org.slf4j.LoggerFactory
import java.io.Closeable

abstract class GenericService protected constructor(protected val genericConfigVals: ConfigVals,
                                                    adminConfig: AdminConfig,
                                                    metricsConfig: MetricsConfig,
                                                    zipkinConfig: ZipkinConfig,
                                                    val isTestMode: Boolean) : AbstractExecutionThreadService(), Closeable {

    private val metricRegistry = MetricRegistry()
    val healthCheckRegistry = HealthCheckRegistry()

    private val services = mutableListOf<Service>(this)
    private val jmxReporter = JmxReporter.forRegistry(metricRegistry).build()
    private var serviceManager: ServiceManager? = null

    protected val adminService: AdminService?
    protected val metricsService: MetricsService?

    val zipkinReporterService: ZipkinReporterService?

    val zipkinEnabled: Boolean
        get() = zipkinReporterService != null

    val metricsEnabled: Boolean
        get() = metricsService != null

    init {
        if (adminConfig.enabled) {
            adminService = AdminService(this,
                                        adminConfig.port,
                                        adminConfig.pingPath,
                                        adminConfig.versionPath,
                                        adminConfig.healthCheckPath,
                                        adminConfig.threadDumpPath)
            addService(adminService)
        }
        else {
            logger.info("Admin service disabled")
            adminService = null
        }

        if (metricsConfig.enabled) {
            metricsService = MetricsService(metricsConfig.port, metricsConfig.path)
            addService(metricsService)
            SystemMetrics.initialize(metricsConfig.standardExportsEnabled,
                                     metricsConfig.memoryPoolsExportsEnabled,
                                     metricsConfig.garbageCollectorExportsEnabled,
                                     metricsConfig.threadExportsEnabled,
                                     metricsConfig.classLoadingExportsEnabled,
                                     metricsConfig.versionInfoExportsEnabled)
        }
        else {
            logger.info("Metrics service disabled")
            metricsService = null
        }

        if (zipkinConfig.enabled) {
            val url = "http://${zipkinConfig.hostname}:${zipkinConfig.port}/${zipkinConfig.path}"
            zipkinReporterService = ZipkinReporterService(url)
            addService(zipkinReporterService)
        }
        else {
            logger.info("Zipkin reporter service disabled")
            zipkinReporterService = null
        }

        addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    fun initService() {
        serviceManager = ServiceManager(services)
        serviceManager!!.addListener(newListener())
        registerHealthChecks()
    }

    override fun startUp() {
        super.startUp()
        zipkinReporterService?.startAsync()
        jmxReporter?.start()
        metricsService?.startAsync()
        adminService?.startAsync()
        Runtime.getRuntime().addShutdownHook(shutDownHookAction(this))
    }

    override fun shutDown() {
        adminService?.stopAsync()
        metricsService?.stopAsync()
        jmxReporter?.stop()
        zipkinReporterService?.stopAsync()
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
        healthCheckRegistry.register("thread_deadlock", ThreadDeadlockHealthCheck())
        if (metricsEnabled)
            healthCheckRegistry.register("metrics_service", metricsService!!.healthCheck)
        healthCheckRegistry
                .register(
                        "all_services_healthy",
                        object : HealthCheck() {
                            @Throws(Exception::class)
                            override fun check(): HealthCheck.Result {
                                return if (serviceManager!!.isHealthy)
                                    HealthCheck.Result.healthy()
                                else {
                                    val vals = serviceManager!!.servicesByState()
                                            .entries()
                                            .filter { it.key !== Service.State.RUNNING }
                                            .onEach { logger.warn("Incorrect state - ${it.key}: ${it.value}") }
                                            .map { "${it.key}: ${it.value}" }
                                            .toList()
                                    HealthCheck.Result.unhealthy("Incorrect state: ${Joiner.on(", ").join(vals)}")
                                }
                            }
                        })
    }

    private fun newListener(): ServiceManager.Listener {
        val serviceName = javaClass.simpleName
        return object : ServiceManager.Listener() {
            override fun healthy() = logger.info("All $serviceName services healthy")
            override fun stopped() = logger.info("All $serviceName services stopped")
            override fun failure(service: Service?) = logger.info("$serviceName service failed: $service")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GenericService::class.java)
    }
}
