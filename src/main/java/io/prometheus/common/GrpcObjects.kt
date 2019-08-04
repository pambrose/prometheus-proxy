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

import io.prometheus.grpc.*

class GrpcObjects {

    companion object {
        fun newHeartBeatRequest(agentId: String) =
            HeartBeatRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            build()
                        }

        fun newHeartBeatResponse(valid: Boolean, reason: String) =
            HeartBeatResponse.newBuilder()
                        .run {
                            this.valid = valid
                            this.reason = reason
                            build()
                        }

        fun newRegisterAgentRequest(agentId: String, agentName: String, hostName: String) =
            RegisterAgentRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            this.agentName = agentName
                            this.hostName = hostName
                            build()
                        }

        fun newRegisterAgentResponse(valid: Boolean, reason: String, agentId: String) =
            RegisterAgentResponse.newBuilder()
                        .run {
                            this.valid = valid
                            this.reason = reason
                            this.agentId = agentId
                            build()
                        }

        fun newPathMapSizeRequest(agentId: String) =
            PathMapSizeRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            build()
                        }

        fun newPathMapSizeResponse(pathCount: Int) =
            PathMapSizeResponse.newBuilder()
                        .run {
                            this.pathCount = pathCount
                            build()
                        }

        fun newRegisterPathRequest(agentId: String, path: String) =
            RegisterPathRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            this.path = path
                            build()
                        }

        fun newRegisterPathResponse(valid: Boolean, reason: String, pathCount: Int, pathId: Long) =
            RegisterPathResponse.newBuilder()
                        .run {
                            this.valid = valid
                            this.reason = reason
                            this.pathCount = pathCount
                            this.pathId = pathId
                            build()
                        }

        fun newScrapeRequest(agentId: String, scrapeId: Long, path: String, accept: String?) =
            ScrapeRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            this.scrapeId = scrapeId
                            this.path = path
                            if (!accept.isNullOrBlank())
                                this.accept = accept
                            build()
                        }


        fun newScrapeResponse(valid: Boolean,
                              reason: String,
                              agentId: String,
                              scrapeId: Long,
                              statusCode: Int,
                              text: String = "",
                              contentType: String = "") =
            ScrapeResponse.newBuilder()
                        .run {
                            this.agentId = agentId
                            this.scrapeId = scrapeId
                            this.valid = valid
                            this.reason = reason
                            this.statusCode = statusCode
                            this.text = text
                            this.contentType = contentType
                            build()
                        }

        fun newUnregisterPathRequest(agentId: String, path: String) =
            UnregisterPathRequest.newBuilder()
                        .run {
                            this.agentId = agentId
                            this.path = path
                            build()
                        }

        fun newUnregisterPathResponseBuilder() = UnregisterPathResponse.newBuilder()

        fun newAgentInfo(agentId: String) =
            AgentInfo.newBuilder()
                        .run {
                            this.agentId = agentId
                            build()
                        }
    }
}