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
import io.ktor.http.HttpHeaders
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
import io.prometheus.common.isNotSuccessful
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.guava.GenericIdleService
import io.prometheus.guava.genericServiceListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates


@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class ProxyHttpService(private val proxy: Proxy, val httpPort: Int) : GenericIdleService() {
    private val configVals = proxy.genericConfigVals.proxy
    private var tracing: Tracing by Delegates.notNull()
    private val idleTimeoutSecs = if (configVals.http.idleTimeoutSecs == -1) 45 else configVals.http.idleTimeoutSecs

    private val httpServer =
        embeddedServer(
            CIO,
            port = httpPort,
            configure = { connectionIdleTimeoutSeconds = idleTimeoutSecs }) {
            routing {
                get("/*") {
                    call.response.header(HttpHeaders.CacheControl, "must-revalidate,no-cache,no-store")

                    val path = call.request.path().drop(1)
                    val agentContext = proxy.pathManager[path]
                    val arg = ResponseArg()

                    when {
                        !proxy.isRunning -> {
                            logger.error { "Proxy stopped" }
                            arg.apply {
                                updateMsg = "proxy_stopped"
                                statusCode = HttpStatusCode.ServiceUnavailable
                            }
                        }
                        path.isEmpty() -> {
                            logger.info { "Request missing path" }
                            arg.apply {
                                updateMsg = "missing_path"
                                statusCode = HttpStatusCode.NotFound
                            }
                        }
                        configVals.internal.blitz.enabled && path == configVals.internal.blitz.path ->
                            arg.contentText = "42"
                        agentContext == null -> {
                            logger.debug { "Invalid path request /\${path" }
                            arg.apply {
                                updateMsg = "invalid_path"
                                statusCode = HttpStatusCode.NotFound
                            }
                        }
                        !agentContext.isValid() -> {
                            logger.error { "Invalid AgentContext" }
                            arg.apply {
                                updateMsg = "invalid_agent_context"
                                statusCode = HttpStatusCode.NotFound
                            }
                        }
                        else -> {
                            val resp = submitScrapeRequest(call.request, call.response, agentContext, path)
                            arg.apply {
                                contentText = resp.contentText
                                contentType = resp.contentType
                                statusCode = resp.statusCode
                                updateMsg = resp.updateMsg
                            }
                        }
                    }

                    updateScrapeRequests(arg.updateMsg)
                    call.respondText(text = arg.contentText, contentType = arg.contentType, status = arg.statusCode)
                }
            }
        }

    class ResponseArg(
        var contentText: String = "",
        var contentType: ContentType = ContentType.Text.Plain,
        var statusCode: HttpStatusCode = HttpStatusCode.OK,
        var updateMsg: String = ""
    )

    init {
        if (proxy.isZipkinEnabled)
            tracing = proxy.zipkinReporterService.newTracing("proxy-http")
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
    }

    override fun startUp() {
        httpServer.start()
    }

    override fun shutDown() {
        if (proxy.isZipkinEnabled)
            tracing.close()

        httpServer.stop(5, 5, TimeUnit.SECONDS)

        runBlocking {
            delay(Secs(2).toMillis().value)
        }
    }

    private class ScrapeRequestResponse(
        var contentText: String = "",
        var contentType: ContentType = ContentType.Text.Plain,
        val statusCode: HttpStatusCode,
        val updateMsg: String
    )

    private suspend fun submitScrapeRequest(
        req: ApplicationRequest,
        res: ApplicationResponse,
        agentContext: AgentContext,
        path: String
    ): ScrapeRequestResponse {

        val scrapeRequest = ScrapeRequestWrapper(proxy, agentContext, path, req.header(ACCEPT))

        try {
            val timeoutSecs = Secs(configVals.internal.scrapeRequestTimeoutSecs)
            val checkMillis = Millis(configVals.internal.scrapeRequestCheckMillis)

            proxy.scrapeRequestManager.addToScrapeRequestMap(scrapeRequest)
            agentContext.writeScrapeRequest(scrapeRequest)

            // Returns false if timed out
            while (!scrapeRequest.suspendUntilComplete(checkMillis)) {
                // Check if agent is disconnected or agent is hung
                if (scrapeRequest.ageInSecs() >= timeoutSecs || !scrapeRequest.agentContext.isValid() || !proxy.isRunning)
                    return ScrapeRequestResponse(
                        statusCode = HttpStatusCode.ServiceUnavailable,
                        updateMsg = "timed_out"
                    )
            }
        } finally {
            proxy.scrapeRequestManager.removeFromScrapeRequestMap(scrapeRequest.scrapeId)
                ?: logger.error { "Scrape request ${scrapeRequest.scrapeId} missing in map" }
        }

        logger.debug { "Results returned from $agentContext for $scrapeRequest" }

        val scrapeResponse = scrapeRequest.scrapeResponse
        val statusCode = HttpStatusCode.fromValue(scrapeResponse.statusCode)
        val contentTypeElems = scrapeResponse.contentType.split("/")
        val contentType =
            if (contentTypeElems.size == 2)
                ContentType(contentTypeElems[0], contentTypeElems[1])
            else
                ContentType.Text.Plain

        // Do not return content on error status codes
        return if (statusCode.isNotSuccessful) {
            ScrapeRequestResponse(
                contentType = contentType,
                statusCode = statusCode,
                updateMsg = "path_not_found"
            )
        } else {
            req.header(ACCEPT_ENCODING)?.contains("gzip")?.let { res.header(CONTENT_ENCODING, "gzip") }
            ScrapeRequestResponse(
                contentText = scrapeRequest.scrapeResponse.contentText,
                contentType = contentType,
                statusCode = statusCode,
                updateMsg = "success"
            )
        }
    }

    private fun updateScrapeRequests(type: String) {
        if (proxy.isMetricsEnabled && type.isNotEmpty())
            proxy.metrics.scrapeRequests.labels(type).inc()
    }

    override fun toString() = toStringElements { add("port", httpPort) }

    companion object : KLogging()
}
