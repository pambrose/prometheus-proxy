/*
 * Copyright © 2026 Paul Ambrose
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

package io.prometheus.proxy

import com.google.protobuf.ByteString
import io.grpc.Context
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.scrapeResponse
import io.prometheus.grpc.summaryData
import java.util.zip.CRC32

// What an agent sends ProxyServiceImpl, for the specs that call its RPCs directly: a result, a chunked transfer or its
// parts, and the connection a call arrives on.

/** Runs [block] as a call on the connection the transport filter assigned [connectionAgentId] to. */
internal suspend fun <T> onConnection(
  connectionAgentId: String,
  block: suspend () -> T,
): T {
  val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, connectionAgentId)
  val previous = context.attach()
  return try {
    block()
  } finally {
    context.detach(previous)
  }
}

/** A successful plain-text result for scrape [id] from agent [sender]. */
internal fun resultFrom(
  sender: String,
  id: Long,
) = scrapeResponse {
  agentId = sender
  scrapeId = id
  validResponse = true
  statusCode = 200
  contentAsText = "metric 1"
}

/** The header of a chunked transfer for [scrapeId] from [agentId]. */
internal fun chunkedHeaderFrom(
  agentId: String,
  scrapeId: Long,
) = chunkedScrapeResponse {
  header = headerData {
    headerValidResponse = true
    headerScrapeId = scrapeId
    headerAgentId = agentId
    headerStatusCode = 200
    headerContentType = "text/plain"
  }
}

/** A chunk of [data] for [scrapeId], numbered [count], whose checksum is [checksum]'s. */
internal fun chunkFor(
  scrapeId: Long,
  data: ByteArray,
  count: Int = 1,
  checksum: CRC32 = CRC32().apply { update(data) },
) = chunkedScrapeResponse {
  chunk = chunkData {
    chunkScrapeId = scrapeId
    chunkCount = count
    chunkByteCount = data.size
    chunkChecksum = checksum.value
    chunkBytes = ByteString.copyFrom(data)
  }
}

/** The summary of a transfer for [scrapeId] whose one chunk was [data]. */
internal fun summaryFor(
  scrapeId: Long,
  data: ByteArray,
) = chunkedScrapeResponse {
  summary = summaryData {
    summaryScrapeId = scrapeId
    summaryChunkCount = 1
    summaryByteCount = data.size
    summaryChecksum = CRC32().apply { update(data) }.value
  }
}

/** A whole chunked transfer for [id] from [sender]: its header, one chunk, and its summary. */
internal fun transferFrom(
  sender: String,
  id: Long,
): List<ChunkedScrapeResponse> {
  val data = "metric 1".toByteArray()
  return listOf(chunkedHeaderFrom(sender, id), chunkFor(id, data), summaryFor(id, data))
}
