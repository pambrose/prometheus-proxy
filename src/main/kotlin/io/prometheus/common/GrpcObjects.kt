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
import io.prometheus.grpc.*
import java.util.zip.CRC32

internal object GrpcObjects {

  const val EMPTY_AGENT_ID = "Empty agentId"
  const val EMPTY_PATH = "Empty path"

  fun newHeartBeatRequest(agentId: String): HeartBeatRequest =
    heartBeatRequest {
      this.agentId = agentId
    }

  fun newHeartBeatResponse(valid: Boolean, reason: String): HeartBeatResponse =
    heartBeatResponse {
      this.valid = valid
      this.reason = reason
    }

  fun newRegisterAgentRequest(agentId: String,
                              launchId: String,
                              agentName: String,
                              hostName: String,
                              consolidated: Boolean): RegisterAgentRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    return registerAgentRequest {
      this.agentId = agentId
      this.launchId = launchId
      this.agentName = agentName
      this.hostName = hostName
      this.consolidated = consolidated
    }
  }

  fun newRegisterAgentResponse(agentId: String, valid: Boolean, reason: String): RegisterAgentResponse {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    return registerAgentResponse {
      this.agentId = agentId
      this.valid = valid
      this.reason = reason
    }
  }

  fun newPathMapSizeRequest(agentId: String): PathMapSizeRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    return pathMapSizeRequest {
      this.agentId = agentId
    }
  }

  fun newPathMapSizeResponse(pathCount: Int): PathMapSizeResponse =
    pathMapSizeResponse {
      this.pathCount = pathCount
    }

  fun newRegisterPathRequest(agentId: String, path: String): RegisterPathRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }
    require(path.isNotEmpty()) { EMPTY_PATH }

    return registerPathRequest {
      this.agentId = agentId
      this.path = path
    }
  }

  fun newRegisterPathResponse(pathId: Long,
                              valid: Boolean,
                              reason: String,
                              pathCount: Int): RegisterPathResponse =
    registerPathResponse {
      this.pathId = pathId
      this.valid = valid
      this.reason = reason
      this.pathCount = pathCount
    }

  fun newScrapeRequest(agentId: String,
                       scrapeId: Long,
                       path: String,
                       encodedQueryParams: String,
                       accept: String?,
                       debugEnabled: Boolean): ScrapeRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    return scrapeRequest {
      this.agentId = agentId
      this.scrapeId = scrapeId
      this.path = path
      this.encodedQueryParams = encodedQueryParams
      this.debugEnabled = debugEnabled
      if (!accept.isNullOrBlank())
        this.accept = accept
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
    scrapeResponse {
      val other = this@toScrapeResponse
      agentId = other.agentId
      scrapeId = other.scrapeId
      validResponse = other.validResponse
      statusCode = other.statusCode
      contentType = other.contentType
      zipped = other.zipped
      if (zipped)
        contentAsZipped = ByteString.copyFrom(other.contentAsZipped)
      else
        contentAsText = other.contentAsText
      failureReason = other.failureReason
      url = other.url
    }

  fun ScrapeResults.toScrapeResponseHeader(): ChunkedScrapeResponse =
    chunkedScrapeResponse {
      header =
        headerData {
          val other = this@toScrapeResponseHeader
          headerAgentId = other.agentId
          headerScrapeId = other.scrapeId
          headerValidResponse = other.validResponse
          headerStatusCode = other.statusCode
          headerContentType = other.contentType
          headerFailureReason = other.failureReason
          headerUrl = other.url
        }
    }

  fun newScrapeResponseChunk(scrapeId: Long,
                             totalChunkCount: Int,
                             readByteCount: Int,
                             checksum: CRC32,
                             buffer: ByteArray): ChunkedScrapeResponse =
    chunkedScrapeResponse {
      chunk =
        chunkData {
          chunkScrapeId = scrapeId
          chunkCount = totalChunkCount
          chunkByteCount = readByteCount
          chunkChecksum = checksum.value
          chunkBytes = ByteString.copyFrom(buffer)
        }
    }

  fun newScrapeResponseSummary(scrapeId: Long,
                               totalChunkCount: Int,
                               totalByteCount: Int,
                               checksum: CRC32): ChunkedScrapeResponse =
    chunkedScrapeResponse {
      summary =
        summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = totalChunkCount
          summaryByteCount = totalByteCount
          summaryChecksum = checksum.value
        }
    }

  fun newUnregisterPathRequest(agentId: String, path: String): UnregisterPathRequest {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }
    require(path.isNotEmpty()) { EMPTY_PATH }
    return unregisterPathRequest {
      this.agentId = agentId
      this.path = path
    }
  }

  fun newAgentInfo(agentId: String): AgentInfo {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }
    return agentInfo { this.agentId = agentId }
  }

  private fun heartBeatRequest(block: HeartBeatRequest.Builder.() -> Unit): HeartBeatRequest =
    HeartBeatRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun heartBeatResponse(block: HeartBeatResponse.Builder.() -> Unit): HeartBeatResponse =
    HeartBeatResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun registerAgentRequest(block: RegisterAgentRequest.Builder.() -> Unit): RegisterAgentRequest =
    RegisterAgentRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun registerAgentResponse(block: RegisterAgentResponse.Builder.() -> Unit): RegisterAgentResponse =
    RegisterAgentResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun pathMapSizeRequest(block: PathMapSizeRequest.Builder.() -> Unit): PathMapSizeRequest =
    PathMapSizeRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun pathMapSizeResponse(block: PathMapSizeResponse.Builder.() -> Unit): PathMapSizeResponse =
    PathMapSizeResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun registerPathRequest(block: RegisterPathRequest.Builder.() -> Unit): RegisterPathRequest =
    RegisterPathRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun registerPathResponse(block: RegisterPathResponse.Builder.() -> Unit): RegisterPathResponse =
    RegisterPathResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun scrapeRequest(block: ScrapeRequest.Builder.() -> Unit): ScrapeRequest =
    ScrapeRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun scrapeResponse(block: ScrapeResponse.Builder.() -> Unit): ScrapeResponse =
    ScrapeResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun headerData(block: HeaderData.Builder.() -> Unit): HeaderData =
    HeaderData.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun chunkData(block: ChunkData.Builder.() -> Unit): ChunkData =
    ChunkData.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun summaryData(block: SummaryData.Builder.() -> Unit): SummaryData =
    SummaryData.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun chunkedScrapeResponse(block: ChunkedScrapeResponse.Builder.() -> Unit): ChunkedScrapeResponse =
    ChunkedScrapeResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun unregisterPathRequest(block: UnregisterPathRequest.Builder.() -> Unit): UnregisterPathRequest =
    UnregisterPathRequest.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  internal fun unregisterPathResponse(block: UnregisterPathResponse.Builder.() -> Unit): UnregisterPathResponse =
    UnregisterPathResponse.newBuilder().let {
      block.invoke(it)
      it.build()
    }

  private fun agentInfo(block: AgentInfo.Builder.() -> Unit): AgentInfo =
    AgentInfo.newBuilder().let {
      block.invoke(it)
      it.build()
    }
}
