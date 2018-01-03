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

package io.prometheus.proxy

import brave.Tracing
import brave.sparkjava.SparkTracing
import com.google.common.net.HttpHeaders.*
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.Proxy
import io.prometheus.common.sleepForSecs
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.SparkDsl.httpServer
import io.prometheus.guava.GenericIdleService
import io.prometheus.guava.genericServiceListener
import mu.KLogging
import spark.Request
import spark.Response
import spark.Route
import spark.Spark
import java.net.BindException
import kotlin.properties.Delegates

class ProxyHttpService(private val proxy: Proxy, val port: Int) : GenericIdleService() {
    private val configVals = proxy.configVals
    private var tracing: Tracing by Delegates.notNull()
    private val httpServer =
            httpServer {
                initExceptionHandler { e -> sparkExceptionHandler(e, port) }
                port(port)
                threadPool(configVals.http.maxThreads,
                           configVals.http.minThreads,
                           configVals.http.idleTimeoutMillis)
            }

    init {
        if (proxy.isZipkinEnabled)
            tracing = proxy.zipkinReporterService.newTracing("proxy-http")
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
    }

    override fun startUp() {
        if (proxy.isZipkinEnabled) {
            val sparkTracing = SparkTracing.create(tracing)
            Spark.before(sparkTracing.before())
            Spark.exception(Exception::class.java,
                            sparkTracing.exception { _, _, response -> response.body("exception") })
            Spark.afterAfter(sparkTracing.afterAfter())
        }

        httpServer
                .apply {
                    get("/*",
                        Route { req, res ->
                            res.header("cache-control", "must-revalidate,no-cache,no-store")

                            if (!proxy.isRunning) {
                                logger.error { "Proxy stopped" }
                                res.status(503)
                                updateScrapeRequests("proxy_stopped")
                                return@Route null
                            }

                            val vals = req.splat()

                            if (vals == null || vals.isEmpty()) {
                                logger.info { "Request missing path" }
                                res.status(404)
                                updateScrapeRequests("missing_path")
                                return@Route null
                            }

                            val path = vals[0]

                            if (configVals.internal.blitz.enabled && path == configVals.internal.blitz.path) {
                                res.status(200)
                                res.type("text/plain")
                                return@Route "42"
                            }

                            val agentContext = proxy.getAgentContextByPath(path)

                            if (agentContext == null) {
                                logger.debug { "Invalid path request /\${path" }
                                res.status(404)
                                updateScrapeRequests("invalid_path")
                                return@Route null
                            }

                            if (!agentContext.isValid) {
                                logger.error { "Invalid AgentContext" }
                                res.status(404)
                                updateScrapeRequests("invalid_agent_context")
                                return@Route null
                            }

                            return@Route submitScrapeRequest(req, res, agentContext, path)
                        })
                    awaitInitialization()
                }

    }

    override fun shutDown() {
        if (proxy.isZipkinEnabled)
            tracing.close()

        httpServer.stop()
        sleepForSecs(3)
    }

    private fun submitScrapeRequest(req: Request,
                                    res: Response,
                                    agentContext: AgentContext,
                                    path: String): String? {
        val scrapeRequest = ScrapeRequestWrapper(proxy, agentContext, path, req.headers(ACCEPT))
        try {
            proxy.addToScrapeRequestMap(scrapeRequest)
            agentContext.addToScrapeRequestQueue(scrapeRequest)

            val timeoutSecs = configVals.internal.scrapeRequestTimeoutSecs
            val checkMillis = configVals.internal.scrapeRequestCheckMillis
            while (true) {
                // Returns false if timed out
                if (scrapeRequest.waitUntilCompleteMillis(checkMillis.toLong()))
                    break

                // Check if agent is disconnected or agent is hung
                if (scrapeRequest.ageInSecs() >= timeoutSecs || !scrapeRequest.agentContext.isValid || !proxy.isRunning) {
                    res.status(503)
                    updateScrapeRequests("time_out")
                    return null
                }
            }
        } finally {
            proxy.removeFromScrapeRequestMap(scrapeRequest.scrapeId) ?: logger.error { "Scrape request ${scrapeRequest.scrapeId} missing in map" }
        }

        logger.debug { "Results returned from $agentContext for $scrapeRequest" }

        val scrapeResponse = scrapeRequest.scrapeResponse
        val statusCode = scrapeResponse.statusCode
        res.status(statusCode)

        // Do not return content on error status codes
        return if (statusCode >= 400) {
            updateScrapeRequests("path_not_found")
            null
        }
        else {
            val acceptEncoding = req.headers(ACCEPT_ENCODING)
            if (acceptEncoding != null && acceptEncoding.contains("gzip"))
                res.header(CONTENT_ENCODING, "gzip")
            res.type(scrapeResponse.contentType)
            updateScrapeRequests("success")
            scrapeRequest.scrapeResponse.text
        }
    }

    private fun updateScrapeRequests(type: String) {
        if (proxy.isMetricsEnabled)
            proxy.metrics.scrapeRequests.labels(type).inc()
    }

    override fun toString() = toStringElements { add("port", port) }

    companion object : KLogging() {
        fun sparkExceptionHandler(e: Exception, port: Int) {
            if (e is BindException)
                logger.error(e) { "ignite failed to bind to port $port" }
            else
                logger.error(e) { "ignite failed" }
            System.exit(100)
        }

    }
}
