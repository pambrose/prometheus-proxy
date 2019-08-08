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

package io.prometheus.proxy

import brave.Tracing
import com.google.common.net.HttpHeaders.*
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.ApplicationRequest
import io.ktor.request.header
import io.ktor.request.path
import io.ktor.response.ApplicationResponse
import io.ktor.response.header
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Proxy
import io.prometheus.common.Millis
import io.prometheus.common.Secs
import io.prometheus.common.sleep
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.guava.GenericIdleService
import io.prometheus.guava.genericServiceListener
import mu.KLogging
import java.net.BindException
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates
import kotlin.system.exitProcess

@KtorExperimentalAPI
class ProxyHttpService(private val proxy: Proxy, val httpPort: Int) : GenericIdleService() {
    private val configVals = proxy.configVals
    private var tracing: Tracing by Delegates.notNull()
    /*
    private val httpServer =
        httpServer {
            initExceptionHandler { e -> sparkExceptionHandler(e, httpPort) }
            port(httpPort)
            threadPool(
                configVals.http.maxThreads,
                configVals.http.minThreads,
                configVals.http.idleTimeoutMillis
            )
            //awaitInitialization()
        }
    */
    val httpServer =
        embeddedServer(
            CIO,
            port = httpPort,
            configure = { connectionIdleTimeoutSeconds = configVals.http.idleTimeoutSecs }) {
            routing {
                get("/*") {
                    call.response.header("cache-control", "must-revalidate,no-cache,no-store")

                    if (!proxy.isRunning) {
                        logger.error { "Proxy stopped" }
                        call.response.status(HttpStatusCode.ServiceUnavailable)
                        updateScrapeRequests("proxy_stopped")
                        call.respondText("", ContentType.Text.Plain)
                        return@get
                    }

                    val path = call.request.path().drop(1)

                    if (path.isEmpty()) {
                        logger.info { "Request missing path" }
                        call.response.status(HttpStatusCode.NotFound)
                        updateScrapeRequests("missing_path")
                        call.respondText("", ContentType.Text.Plain)
                        return@get
                    }

                    if (configVals.internal.blitz.enabled && path == configVals.internal.blitz.path) {
                        call.response.status(HttpStatusCode.OK)
                        call.respondText("42", ContentType.Text.Plain)
                        return@get
                    }

                    val agentContext = proxy.pathManager.getAgentContextByPath(path)

                    if (agentContext == null) {
                        logger.debug { "Invalid path request /\${path" }
                        call.response.status(HttpStatusCode.NotFound)
                        updateScrapeRequests("invalid_path")
                        call.respondText("", ContentType.Text.Plain)
                        return@get
                    }

                    if (!agentContext.isValid.get()) {
                        logger.error { "Invalid AgentContext" }
                        call.response.status(HttpStatusCode.NotFound)
                        updateScrapeRequests("invalid_agent_context")
                        call.respondText("", ContentType.Text.Plain)
                        return@get
                    }

                    val (content, contentType) = submitScrapeRequest(call.request, call.response, agentContext, path)
                    call.respondText(content, contentType)
                }
            }
        }

    init {
        if (proxy.isZipkinEnabled)
            tracing = proxy.zipkinReporterService.newTracing("proxy-http")
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
    }

    override fun startUp() {
/*
        if (proxy.isZipkinEnabled) {
            val sparkTracing = SparkTracing.create(tracing)
            Spark.before(sparkTracing.before())
            Spark.exception(Exception::class.java,
                sparkTracing.exception { _, _, response -> response.body("exception") })
            Spark.afterAfter(sparkTracing.afterAfter())
        }

        httpServer
            .apply {
                get("/"+"*",
                    Route { req, res ->
                        res.header("cache-control", "must-revalidate,no-cache,no-store")

                        if (!proxy.isRunning) {
                            logger.error { "Proxy stopped" }
                            res.status(503)
                            updateScrapeRequests("proxy_stopped")
                            return@Route null
                        }

                        val vals = req.splat()

                        if (vals.isNullOrEmpty()) {
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

                        val agentContext = proxy.pathManager.getAgentContextByPath(path)

                        if (agentContext == null) {
                            logger.debug { "Invalid path request /\${path" }
                            res.status(404)
                            updateScrapeRequests("invalid_path")
                            return@Route null
                        }

                        if (!agentContext.isValid.get()) {
                            logger.error { "Invalid AgentContext" }
                            res.status(404)
                            updateScrapeRequests("invalid_agent_context")
                            return@Route null
                        }

                        return@Route null

                        //return@Route submitScrapeRequest(req, res, agentContext, path)
                    })
                awaitInitialization()
            }
        */

        httpServer.start()
    }

    override fun shutDown() {
        if (proxy.isZipkinEnabled)
            tracing.close()

        // httpServer.stop()
        // httpServer.awaitStop()

        //httpServer.environment.stop()
        httpServer.stop(5, 5, TimeUnit.SECONDS)
        sleep(Secs(2))
    }

    private fun submitScrapeRequest(
        req: ApplicationRequest,
        res: ApplicationResponse,
        agentContext: AgentContext,
        path: String
    ): Pair<String, ContentType> {
        val scrapeRequest = ScrapeRequestWrapper(proxy, agentContext, path, req.header(ACCEPT))
        try {
            proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
            agentContext.addToScrapeRequestQueue(scrapeRequest)

            val timeoutSecs = Secs(configVals.internal.scrapeRequestTimeoutSecs)
            val checkMillis = Millis(configVals.internal.scrapeRequestCheckMillis)
            while (true) {
                // Returns false if timed out
                if (scrapeRequest.waitUntilComplete(checkMillis))
                    break

                // Check if agent is disconnected or agent is hung
                if (scrapeRequest.ageInSecs() >= timeoutSecs || !scrapeRequest.agentContext.isValid.get() || !proxy.isRunning) {
                    res.status(HttpStatusCode.ServiceUnavailable)
                    updateScrapeRequests("time_out")
                    return Pair("", ContentType.Text.Plain)
                }
            }
        } finally {
            proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeRequest.scrapeId)
                ?: logger.error { "Scrape request ${scrapeRequest.scrapeId} missing in map" }
        }

        logger.debug { "Results returned from $agentContext for $scrapeRequest" }

        val scrapeResponse = scrapeRequest.scrapeResponse
        val statusCode = HttpStatusCode.fromValue(scrapeResponse.statusCode)
        res.status(statusCode)

        val contentTypeElems = scrapeResponse.contentType.split("/")
        val contentType =
            if (contentTypeElems.size == 2)
                ContentType(contentTypeElems[0], contentTypeElems[1])
            else
                ContentType.Text.Plain

        // Do not return content on error status codes
        return if (statusCode.value >= 400) {
            updateScrapeRequests("path_not_found")
            Pair("", contentType)
        } else {
            req.header(ACCEPT_ENCODING)?.contains("gzip")?.let { res.header(CONTENT_ENCODING, "gzip") }
            updateScrapeRequests("success")
            Pair(scrapeRequest.scrapeResponse.text, contentType)
        }
    }

    private fun updateScrapeRequests(type: String) {
        if (proxy.isMetricsEnabled)
            proxy.metrics.scrapeRequests.labels(type).inc()
    }

    override fun toString() = toStringElements { add("port", httpPort) }

    companion object : KLogging() {
        fun sparkExceptionHandler(e: Exception, port: Int) {
            if (e is BindException)
                logger.error(e) { "ignite failed to bind to port $port" }
            else
                logger.error(e) { "ignite failed" }
            exitProcess(100)
        }
    }
}
