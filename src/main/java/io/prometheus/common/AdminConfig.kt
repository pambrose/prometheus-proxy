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

data class AdminConfig(val enabled: Boolean,
                       val port: Int,
                       val pingPath: String,
                       val versionPath: String,
                       val healthCheckPath: String,
                       val threadDumpPath: String) {

    companion object {
        fun newAdminConfig(enabled: Boolean, port: Int, admin: ConfigVals.Proxy2.Admin2) =
                AdminConfig(enabled,
                            port,
                            admin.pingPath,
                            admin.versionPath,
                            admin.healthCheckPath,
                            admin.threadDumpPath)

        fun newAdminConfig(enabled: Boolean, port: Int, admin: ConfigVals.Agent.Admin) =
                AdminConfig(enabled,
                            port,
                            admin.pingPath,
                            admin.versionPath,
                            admin.healthCheckPath,
                            admin.threadDumpPath)
    }
}


