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

package io.prometheus.agent

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.pambrose.common.dsl.GrpcDsl.server
import io.grpc.Server
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.common.TestPorts.RETENTION_NETTY_PORT
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// Found while fixing November #41: at a 5-byte chunk size, the in-process harness ran out of heap, holding every chunk
// the agent had sent. The agent builds its channel with retry on, so gRPC's RetriableStream keeps each message a call
// sends until the call commits, which for a client stream the proxy answers only at its end means until the buffer
// passes its per-RPC limit (1 MiB). That limit is counted from the message sizes the transport reports, and the
// in-process transport reports none (unless GRPC_EXPERIMENTAL_SUPPORT_TRACING_MESSAGE_SIZES is set), so the agent's
// writeResponsesToProxy and writeChunkedResponsesToProxy streams keep every scrape result and chunk they send for as
// long as the connection lasts. Netty reports sizes, so its streams commit once they pass 1 MiB and let go. The agent
// now builds an in-process channel without retry (November #43).
class AgentGrpcStreamRetentionTest : StringSpec() {
  private fun mockAgent(proxyHostname: String): Agent {
    val options = mockk<AgentOptions>(relaxed = true)
    every { options.proxyHostname } returns proxyHostname
    every { options.certChainFilePath } returns ""
    every { options.privateKeyFilePath } returns ""
    every { options.trustCertCollectionFilePath } returns ""
    // No AgentClientInterceptor, which reads the agent ID from the proxy's headers; this proxy sends none.
    every { options.transportFilterDisabled } returns true
    every { options.keepAliveTimeSecs } returns -1L
    every { options.keepAliveTimeoutSecs } returns -1L
    every { options.overrideAuthority } returns ""
    every { options.agentToken } returns ""

    val agent = mockk<Agent>(relaxed = true)
    every { agent.options } returns options
    every { agent.isZipkinEnabled } returns false
    return agent
  }

  // A proxy whose writeChunkedResponsesToProxy reads every chunk, drops it, and answers only when the agent closes the
  // stream, as the real one does.
  private class ChunkReader(
    private val expected: Int,
  ) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
    private val received = AtomicInteger()
    val allReceived = CompletableDeferred<Unit>()

    override suspend fun writeChunkedResponsesToProxy(requests: Flow<ChunkedScrapeResponse>): Empty {
      requests.collect { if (received.incrementAndGet() == expected) allReceived.complete(Unit) }
      return Empty.getDefaultInstance()
    }
  }

  private fun chunk(count: Int) =
    chunkedScrapeResponse {
      chunk = chunkData {
        chunkScrapeId = 1L
        chunkCount = count
        chunkByteCount = CHUNK_BYTES
        chunkBytes = ByteString.copyFrom(ByteArray(CHUNK_BYTES))
      }
    }

  // How many of [refs] stay reachable once the collector has had its chances; a weakly held message the stream let go
  // of is cleared by the first full collection.
  private fun reachableAfterGc(refs: List<WeakReference<ChunkedScrapeResponse>>): Int {
    repeat(GC_ATTEMPTS) {
      if (refs.none { it.get() != null }) return 0
      System.gc()
      Thread.sleep(GC_PAUSE_MILLIS)
    }
    return refs.count { it.get() != null }
  }

  // Sends [CHUNK_COUNT] chunks on the agent's chunked stream to [proxy], and returns how many stay reachable once the
  // proxy has read them all, with the stream still open, as the agent's is for the life of its connection.
  private suspend fun reachableWhileStreamOpen(
    service: AgentGrpcService,
    proxy: ChunkReader,
  ): Int {
    val outbound = Channel<ChunkedScrapeResponse>(UNLIMITED)
    // Only the channel holds a chunk strongly, until the stream takes it.
    val refs =
      List(CHUNK_COUNT) { i ->
        val chunk = chunk(i + 1)
        outbound.trySend(chunk).getOrThrow()
        WeakReference(chunk)
      }
    return coroutineScope {
      val call = launch { service.grpcStub.writeChunkedResponsesToProxy(outbound.consumeAsFlow()) }
      withTimeout(STREAM_TIMEOUT) { proxy.allReceived.await() }
      val reachable = reachableAfterGc(refs)
      outbound.close()
      withTimeout(STREAM_TIMEOUT) { call.join() }
      reachable
    }
  }

  private suspend fun withServer(
    server: Server,
    service: AgentGrpcService,
    block: suspend () -> Unit,
  ) {
    try {
      server.start()
      block()
    } finally {
      service.shutDown()
      server.shutdownNow().awaitTermination()
    }
  }

  init {
    // Fails until the agent's in-process channel stops buffering for retry: every chunk stays reachable.
    "a chunk the in-process proxy has read should not stay reachable while the agent's stream is open" {
      val proxy = ChunkReader(CHUNK_COUNT)
      val server = server(inProcessServerName = IN_PROCESS_NAME) { addService(proxy) }
      val agent = mockAgent("localhost")
      val service = AgentGrpcService(agent, agent.options, IN_PROCESS_NAME)

      withServer(server, service) {
        withClue(REACHABLE_CLUE) { reachableWhileStreamOpen(service, proxy) shouldBeLessThan SLACK }
      }
    }

    // The control: over Netty, the transport the agent uses outside tests, the retry buffer commits once it passes
    // 1 MiB, and the 2 MiB sent here pass it, so the same check finds nothing kept.
    "a chunk the Netty proxy has read should not stay reachable while the agent's stream is open" {
      val proxy = ChunkReader(CHUNK_COUNT)
      val server = server(port = RETENTION_NETTY_PORT, bindAddress = LOOPBACK) { addService(proxy) }
      val agent = mockAgent("$LOOPBACK:$RETENTION_NETTY_PORT")
      val service = AgentGrpcService(agent, agent.options, "")

      withServer(server, service) {
        withClue(REACHABLE_CLUE) { reachableWhileStreamOpen(service, proxy) shouldBeLessThan SLACK }
      }
    }
  }

  companion object {
    private const val IN_PROCESS_NAME = "agent-stream-retention"
    private const val LOOPBACK = "127.0.0.1"

    // 2,000 chunks of 1 KiB: 2 MiB, twice gRPC's default per-RPC retry buffer limit.
    private const val CHUNK_COUNT = 2_000
    private const val CHUNK_BYTES = 1024

    // A chunk or two can still be in a local of the stream's last send when the collector runs.
    private const val SLACK = 10
    private const val GC_ATTEMPTS = 10
    private const val GC_PAUSE_MILLIS = 50L
    private val STREAM_TIMEOUT = 30.seconds
    private val REACHABLE_CLUE = "Chunks, of the $CHUNK_COUNT sent, still reachable once the proxy has read them all"
  }
}
