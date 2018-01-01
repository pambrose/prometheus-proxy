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

package io.prometheus.agent

import com.google.common.base.MoreObjects
import com.google.common.net.HttpHeaders.ACCEPT
import io.prometheus.grpc.ScrapeRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException

class PathContext(private val okHttpClient: OkHttpClient,
                  private val pathId: Long,
                  private val path: String,
                  val url: String) {
    private val request: Request.Builder = Request.Builder().url(url)

    @Throws(IOException::class)
    fun fetchUrl(scrapeRequest: ScrapeRequest): Response =
            try {
                logger.debug("Fetching $this")
                val request =
                        with(request) {
                            if (!scrapeRequest.accept.isNullOrEmpty())
                                header(ACCEPT, scrapeRequest.accept)
                            build()
                        }

                okHttpClient.newCall(request).execute()
            } catch (e: IOException) {
                logger.info("Failed HTTP request: $url [${e.javaClass.simpleName}: ${e.message}]")
                throw e
            }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("path", "/" + path)
                    .add("url", url)
                    .toString()

    companion object {
        private val logger = LoggerFactory.getLogger(PathContext::class.java)
    }
}
