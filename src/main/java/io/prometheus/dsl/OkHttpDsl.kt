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

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object OkHttpDsl {
    private val OK_HTTP_CLIENT = OkHttpClient()

    fun String.get(block: (Response) -> Unit) {
        OK_HTTP_CLIENT
                .newCall(Request.Builder().url(this).build())
                .execute()
                .use(block)
    }
}
