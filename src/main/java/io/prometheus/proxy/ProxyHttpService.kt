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

import brave.Span
import brave.Tracer
import com.github.kristofa.brave.sparkjava.BraveTracing
import com.google.common.base.MoreObjects
import com.google.common.net.HttpHeaders.*
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import io.prometheus.common.GenericServiceListener
import org.slf4j.LoggerFactory
import spark.*

class ProxyHttpService(private val proxy: Proxy, val port: Int) : AbstractIdleService() {
    private val http: Service = Service.ignite()
    private val tracer: Tracer?
    private val configVals: ConfigVals.Proxy2

    init {
        this.http.port(this.port)
        this.http.threadPool(this.proxy.configVals.http.maxThreads,
                             this.proxy.configVals.http.minThreads,
                             this.proxy.configVals.http.idleTimeoutMillis)
        this.tracer = this.proxy.zipkinReporterService?.newTracer("proxy-http")
        this.configVals = this.proxy.configVals

        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    override fun startUp() {
        if (this.proxy.zipkinEnabled) {
            val tracing = BraveTracing.create(this.proxy.brave)
            this.http.before(tracing.before())

            val impl = object : ExceptionHandlerImpl<Exception>(Exception::class.java) {
                override fun handle(e: Exception, request: Request, response: Response) {
                    response.status(404)
                    logger.error("Error in ProxyHttpService", e)
                }
            }
            val handler = tracing.exception(impl)
            this.http.exception(Exception::class.java, handler)
            this.http.afterAfter(tracing.afterAfter())
        }

        this.http.get("/*",
                      Route { req, res ->
                          res.header("cache-control", "must-revalidate,no-cache,no-store")

                          val span =
                                  tracer?.newTrace()
                                          ?.name("round-trip")
                                          ?.tag("version", "1.3.0")
                                          ?.start()

                          try {
                              if (!this@ProxyHttpService.proxy.isRunning) {
                                  logger.error("Proxy stopped")
                                  res.status(503)
                                  this@ProxyHttpService.updateScrapeRequests("proxy_stopped")
                                  return@Route null
                              }

                              val vals = req.splat()
                              if (vals == null || vals.isEmpty()) {
                                  logger.info("Request missing path")
                                  res.status(404)
                                  this@ProxyHttpService.updateScrapeRequests("missing_path")
                                  return@Route null
                              }

                              val path = vals[0]

                              if (configVals.internal.blitz.enabled && path == configVals.internal.blitz.path) {
                                  res.status(200)
                                  res.type("text/plain")
                                  return@Route "42"
                              }

                              val agentContext = this@ProxyHttpService.proxy.getAgentContextByPath(path)

                              if (agentContext == null) {
                                  logger.debug("Invalid path request /\${path")
                                  res.status(404)
                                  this@ProxyHttpService.updateScrapeRequests("invalid_path")
                                  return@Route null
                              }

                              if (!agentContext.valid) {
                                  logger.error("Invalid AgentContext")
                                  res.status(404)
                                  this@ProxyHttpService.updateScrapeRequests("invalid_agent_context")
                                  return@Route null
                              }

                              span?.tag("path", path)

                              return@Route submitScrapeRequest(req, res, agentContext, path, span)

                          } finally {
                              span?.finish()
                          }
                      })
    }

    override fun shutDown() {
        this.http.stop()
    }

    private fun submitScrapeRequest(req: Request,
                                    res: Response,
                                    agentContext: AgentContext?,
                                    path: String,
                                    span: Span?): String? {
        val scrapeRequest = ScrapeRequestWrapper(this.proxy,
                                                 agentContext!!,
                                                 span,
                                                 path,
                                                 req.headers(ACCEPT))
        try {
            this.proxy.addToScrapeRequestMap(scrapeRequest)
            agentContext.addToScrapeRequestQueue(scrapeRequest)

            val timeoutSecs = this.configVals.internal.scrapeRequestTimeoutSecs
            val checkMillis = this.configVals.internal.scrapeRequestCheckMillis
            while (true) {
                // Returns false if timed out
                if (scrapeRequest.waitUntilCompleteMillis(checkMillis.toLong()))
                    break

                // Check if agent is disconnected or agent is hung
                if (scrapeRequest.ageInSecs() >= timeoutSecs
                    || !scrapeRequest.agentContext.valid
                    || !this.proxy.isRunning) {
                    res.status(503)
                    this.updateScrapeRequests("time_out")
                    return null
                }
            }
        } finally {
            val prev = this.proxy.removeFromScrapeRequestMap(scrapeRequest.scrapeId)
            if (prev == null)
                logger.error("Scrape request ${scrapeRequest.scrapeId} missing in map")
        }

        logger.debug("Results returned from $agentContext for $scrapeRequest")

        val scrapeResponse = scrapeRequest.scrapeResponse
        val statusCode = scrapeResponse.statusCode
        res.status(statusCode)

        // Do not return content on error status codes
        return if (statusCode >= 400) {
            this.updateScrapeRequests("path_not_found")
            null
        }
        else {
            val acceptEncoding = req.headers(ACCEPT_ENCODING)
            if (acceptEncoding != null && acceptEncoding.contains("gzip"))
                res.header(CONTENT_ENCODING, "gzip")
            res.type(scrapeResponse.contentType)
            this.updateScrapeRequests("success")
            scrapeRequest.scrapeResponse.text
        }
    }

    private fun updateScrapeRequests(type: String) {
        if (this.proxy.metricsEnabled)
            this.proxy.metrics!!.scrapeRequests.labels(type).inc()
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("port", port)
                .toString()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProxyHttpService::class.java)
    }
}
