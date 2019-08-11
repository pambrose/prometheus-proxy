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

package io.prometheus.dsl

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.response.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.common.simpleClassName
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.io.IOException


@KtorExperimentalAPI

object KtorDsl : KLogging() {

    fun newHttpClient() = HttpClient(CIO)

    fun blockingGet(url: String, block: suspend (HttpResponse) -> Unit) {
        newHttpClient()
            .use { httpClient ->
                runBlocking {
                    httpClient.get(url, {}, block)
                }
            }
    }

    suspend fun HttpClient.get(
        url: String,
        setUp: HttpRequestBuilder.() -> Unit = {},
        block: suspend (HttpResponse) -> Unit
    ) {
        try {
            val clientCall =
                call(url) {
                    method = HttpMethod.Get
                    setUp()
                }
            clientCall.response.use { resp -> block(resp) }
        } catch (e: IOException) {
            logger.info { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
            throw e
        }
    }
}