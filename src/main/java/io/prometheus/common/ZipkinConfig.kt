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
package io.prometheus.common

data class ZipkinConfig(val enabled: Boolean,
                        val hostname: String,
                        val port: Int,
                        val path: String,
                        val serviceName: String) {

    companion object {
        fun newZipkinConfig(zipkin: ConfigVals.Proxy2.Internal2.Zipkin2) =
                ZipkinConfig(zipkin.enabled,
                             zipkin.hostname,
                             zipkin.port,
                             zipkin.path,
                             zipkin.serviceName)

        fun newZipkinConfig(zipkin: ConfigVals.Agent.Internal.Zipkin) =
                ZipkinConfig(zipkin.enabled,
                             zipkin.hostname,
                             zipkin.port,
                             zipkin.path,
                             zipkin.serviceName)
    }
}