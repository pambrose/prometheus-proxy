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
import com.github.kristofa.brave.Brave
import com.google.common.base.Joiner
import com.google.common.collect.Lists
import com.google.common.util.concurrent.AbstractExecutionThreadService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ServiceManager
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.lang.String.format
import java.util.stream.Collectors

abstract class GenericService protected constructor(protected val genericConfigVals: ConfigVals,
                                                    adminConfig: AdminConfig,
                                                    metricsConfig: MetricsConfig,
                                                    zipkinConfig: ZipkinConfig,
                                                    val isTestMode: Boolean) : AbstractExecutionThreadService(), Closeable {

    val metricRegistry = MetricRegistry()
    val healthCheckRegistry = HealthCheckRegistry()

    private val services = Lists.newArrayList<Service>(this)
    private val jmxReporter = JmxReporter.forRegistry(this.metricRegistry).build()
    private var serviceManager: ServiceManager? = null

    protected val adminService: AdminService?
    protected val metricsService: MetricsService?
    val zipkinReporterService: ZipkinReporterService?

    val zipkinEnabled: Boolean
        get() = this.zipkinReporterService != null

    val adminEnabled: Boolean
        get() = this.adminService != null

    val metricsEnabled: Boolean
        get() = this.metricsService != null

    val brave: Brave
        get() = this.zipkinReporterService!!.brave

    init {
        if (adminConfig.enabled) {
            this.adminService = AdminService(this,
                                             adminConfig.port,
                                             adminConfig.pingPath,
                                             adminConfig.versionPath,
                                             adminConfig.healthCheckPath,
                                             adminConfig.threadDumpPath)
            this.addService(this.adminService)
        }
        else {
            logger.info("Admin service disabled")
            this.adminService = null
        }

        if (metricsConfig.enabled) {
            val port = metricsConfig.port
            val path = metricsConfig.path
            this.metricsService = MetricsService(port, path)
            this.addService(this.metricsService)
            SystemMetrics.initialize(metricsConfig.standardExportsEnabled,
                                     metricsConfig.memoryPoolsExportsEnabled,
                                     metricsConfig.garbageCollectorExportsEnabled,
                                     metricsConfig.threadExportsEnabled,
                                     metricsConfig.classLoadingExportsEnabled,
                                     metricsConfig.versionInfoExportsEnabled)
        }
        else {
            logger.info("Metrics service disabled")
            this.metricsService = null
        }

        if (zipkinConfig.enabled) {
            val zipkinUrl = format("http://%s:%d/%s",
                                   zipkinConfig.hostname, zipkinConfig.port, zipkinConfig.path)
            this.zipkinReporterService = ZipkinReporterService(zipkinUrl, zipkinConfig.serviceName)
            this.addService(this.zipkinReporterService)
        }
        else {
            logger.info("Zipkin reporter service disabled")
            this.zipkinReporterService = null
        }

        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    fun init() {
        this.serviceManager = ServiceManager(this.services)
        this.serviceManager!!.addListener(this.newListener())
        this.registerHealthChecks()
    }

    @Throws(Exception::class)
    override fun startUp() {
        super.startUp()
        if (this.jmxReporter != null)
            this.jmxReporter.start()
        if (this.metricsEnabled)
            this.metricsService!!.startAsync()
        if (this.adminEnabled)
            this.adminService!!.startAsync()
        Runtime.getRuntime().addShutdownHook(Utils.shutDownHookAction(this))
    }

    @Throws(Exception::class)
    override fun shutDown() {
        if (this.adminEnabled)
            this.adminService!!.shutDown()
        if (this.metricsEnabled)
            this.metricsService!!.stopAsync()
        if (this.zipkinEnabled)
            this.zipkinReporterService!!.shutDown()
        if (this.jmxReporter != null)
            this.jmxReporter.stop()
        super.shutDown()
    }

    @Throws(IOException::class)
    override fun close() {
        this.stopAsync()
    }

    protected fun addService(service: Service) {
        this.services.add(service)
    }

    protected fun addServices(service: Service, vararg services: Service) {
        this.services.addAll(Lists.asList(service, services))
    }

    protected open fun registerHealthChecks() {
        this.healthCheckRegistry.register("thread_deadlock", ThreadDeadlockHealthCheck())
        if (this.metricsEnabled)
            this.healthCheckRegistry.register("metrics_service", this.metricsService!!.healthCheck)
        this.healthCheckRegistry
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
                                            .stream()
                                            .filter { kv -> kv.key !== Service.State.RUNNING }
                                            .peek { kv ->
                                                logger.warn("Incorrect state - {}: {}",
                                                            kv.key, kv.value)
                                            }
                                            .map { kv -> format("%s: %s", kv.key, kv.value) }
                                            .collect(Collectors.toList())

                                    HealthCheck.Result.unhealthy(format("Incorrect state: %s",
                                                                        Joiner.on(", ")
                                                                                .join(vals)))
                                }
                            }
                        })
    }

    protected fun newListener(): ServiceManager.Listener {
        val serviceName = this.javaClass.simpleName
        return object : ServiceManager.Listener() {
            override fun healthy() {
                logger.info("All {} services healthy", serviceName)
            }

            override fun stopped() {
                logger.info("All {} services stopped", serviceName)
            }

            override fun failure(service: Service?) {
                logger.info("{} service failed: {}", serviceName, service)
            }
        }
    }

    companion object {

        private val logger = LoggerFactory.getLogger(GenericService::class.java)
    }
}
