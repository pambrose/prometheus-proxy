/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.google.protobuf.ByteString
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkData
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeaderData
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.HeartBeatResponse
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.PathMapSizeResponse
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.SummaryData
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import java.util.zip.CRC32

object GrpcObjects {

  const val EMPTY_AGENTID = "Empty agentId"
  const val EMPTY_PATH = "Empty path"

  fun newHeartBeatRequest(agentId: String): HeartBeatRequest =
      HeartBeatRequest.newBuilder().run {
        this.agentId = agentId
        build()
      }

  fun newHeartBeatResponse(valid: Boolean, reason: String): HeartBeatResponse =
      HeartBeatResponse.newBuilder().run {
        this.valid = valid
        this.reason = reason
        build()
      }

  fun newRegisterAgentRequest(agentId: String, agentName: String, hostName: String): RegisterAgentRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    return RegisterAgentRequest.newBuilder().run {
      this.agentId = agentId
      this.agentName = agentName
      this.hostName = hostName
      build()
    }
  }

  fun newRegisterAgentResponse(valid: Boolean, reason: String, agentId: String): RegisterAgentResponse {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    return RegisterAgentResponse.newBuilder().run {
      this.valid = valid
      this.reason = reason
      this.agentId = agentId
      build()
    }
  }

  fun newPathMapSizeRequest(agentId: String): PathMapSizeRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    return PathMapSizeRequest.newBuilder().run {
      this.agentId = agentId
      build()
    }
  }

  fun newPathMapSizeResponse(pathCount: Int): PathMapSizeResponse =
      PathMapSizeResponse.newBuilder().run {
        this.pathCount = pathCount
        build()
      }

  fun newRegisterPathRequest(agentId: String, path: String): RegisterPathRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    require(path.isNotEmpty()) { EMPTY_PATH }
    return RegisterPathRequest.newBuilder().run {
      this.agentId = agentId
      this.path = path
      build()
    }
  }

  fun newRegisterPathResponse(valid: Boolean,
                              reason: String,
                              pathCount: Int,
                              pathId: Long): RegisterPathResponse =
      RegisterPathResponse.newBuilder().run {
        this.valid = valid
        this.reason = reason
        this.pathCount = pathCount
        this.pathId = pathId
        build()
      }

  fun newScrapeRequest(agentId: String,
                       scrapeId: Long,
                       path: String,
                       encodedQueryParams: String,
                       accept: String?,
                       debugEnabled: Boolean): ScrapeRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    return ScrapeRequest.newBuilder().let { builder ->
      builder.agentId = agentId
      builder.scrapeId = scrapeId
      builder.path = path
      builder.encodedQueryParams = encodedQueryParams
      builder.debugEnabled = debugEnabled
      if (!accept.isNullOrBlank())
        builder.accept = accept
      builder.build()
    }
  }


  fun ScrapeResponse.toScrapeResults(): ScrapeResults =
      ScrapeResults(
          agentId = agentId,
          scrapeId = scrapeId,
          validResponse = validResponse,
          statusCode = statusCode,
          contentType = contentType,
          zipped = zipped,
          failureReason = failureReason,
          url = url
      ).also { results ->
        if (zipped)
          results.contentAsZipped = contentAsZipped.toByteArray()
        else
          results.contentAsText = contentAsText
      }

  fun ScrapeResults.toScrapeResponse(): ScrapeResponse =
      ScrapeResponse.newBuilder().let { builder ->
        builder.agentId = agentId
        builder.scrapeId = scrapeId
        builder.validResponse = validResponse
        builder.statusCode = statusCode
        builder.contentType = contentType
        builder.zipped = zipped
        if (zipped)
          builder.contentAsZipped = ByteString.copyFrom(contentAsZipped)
        else
          builder.contentAsText = contentAsText
        builder.failureReason = failureReason
        builder.url = url
        builder.build()
      }

  fun ScrapeResults.toScrapeResponseHeader(): ChunkedScrapeResponse =
      ChunkedScrapeResponse.newBuilder().let { builder ->
        builder.header =
            HeaderData.newBuilder().run {
              headerAgentId = agentId
              headerScrapeId = scrapeId
              headerValidResponse = validResponse
              headerStatusCode = statusCode
              headerContentType = contentType
              headerFailureReason = failureReason
              headerUrl = url
              build()
            }
        builder.build()
      }

  fun newScrapeResponseChunk(scrapeId: Long,
                             totalChunkCount: Int,
                             readByteCount: Int,
                             checksum: CRC32,
                             buffer: ByteArray): ChunkedScrapeResponse =
      ChunkedScrapeResponse.newBuilder().let { builder ->
        builder.chunk =
            ChunkData.newBuilder().run {
              chunkScrapeId = scrapeId
              chunkCount = totalChunkCount
              chunkByteCount = readByteCount
              chunkChecksum = checksum.value
              chunkBytes = ByteString.copyFrom(buffer)
              build()
            }
        builder.build()
      }

  fun newScrapeResponseSummary(scrapeId: Long,
                               totalChunkCount: Int,
                               totalByteCount: Int,
                               checksum: CRC32): ChunkedScrapeResponse =
      ChunkedScrapeResponse.newBuilder().let { builder ->
        builder.summary =
            SummaryData.newBuilder().run {
              summaryScrapeId = scrapeId
              summaryChunkCount = totalChunkCount
              summaryByteCount = totalByteCount
              summaryChecksum = checksum.value
              build()
            }
        builder.build()
      }

  fun newUnregisterPathRequest(agentId: String, path: String): UnregisterPathRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    require(path.isNotEmpty()) { EMPTY_PATH }
    return UnregisterPathRequest.newBuilder().run {
      this.agentId = agentId
      this.path = path
      build()
    }
  }

  fun newUnregisterPathResponseBuilder(): UnregisterPathResponse.Builder = UnregisterPathResponse.newBuilder()

  fun newAgentInfo(agentId: String): AgentInfo {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    return AgentInfo.newBuilder().run {
      this.agentId = agentId
      build()
    }
  }
}
