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

package io.prometheus.dsl

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.client.response.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.runBlocking


@KtorExperimentalAPI
object KtorDsl {
    private const val prefix = "http://localhost:"

    fun newHttpClient() = HttpClient(CIO)

    fun blockingGet(url: String, block: suspend (HttpResponse) -> Unit) {
        newHttpClient()
            .use { httpClient ->
                runBlocking {
                    val prefixedUrl = if (url.startsWith(prefix)) url else (prefix + url)
                    httpClient.get(prefixedUrl, block)
                }
            }
    }

    suspend fun HttpClient.get(url: String, block: suspend (HttpResponse) -> Unit) {
        val prefixedUrl = if (url.startsWith(prefix)) url else (prefix + url)
        val resp = this.call(prefixedUrl) { method = HttpMethod.Get }
        block(resp.response)
    }
}