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

@file:Suppress("TooGenericExceptionCaught")

package io.prometheus.proxy

import com.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.RegisterAgentRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/**
 * Represents a single connected agent on the proxy side.
 *
 * Each agent connection creates an [AgentContext] that holds the agent's identity (ID, name,
 * hostname), validity state, activity timestamps, and a queue of pending scrape requests.
 * The proxy writes scrape requests into the queue and the gRPC streaming RPC drains them.
 * When the agent disconnects or is evicted, the context is invalidated and all pending
 * requests are drained and closed.
 *
 * @param remoteAddr the remote address of the connected agent
 * @see AgentContextManager
 * @see ProxyPathManager
 */
internal class AgentContext(
  private val remoteAddr: String,
) {
  val agentId = AGENT_ID_GENERATOR.incrementAndFetch().toString()

  private val scrapeRequestQueue = ConcurrentLinkedQueue<ScrapeRequestWrapper>()
  private val scrapeRequestNotifier = Channel<Unit>(UNLIMITED)

  private val clock = Monotonic
  private var lastActivityTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var lastRequestTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var valid by atomicBoolean(true)

  private var launchId: String by nonNullableReference("Unassigned")
  var hostName: String by nonNullableReference("Unassigned")
    private set
  var agentName: String by nonNullableReference("Unassigned")
    private set
  var consolidated: Boolean by atomicBoolean(false)
    private set

  internal val desc: String
    get() = if (consolidated) "consolidated " else ""

  private val lastRequestDuration
    get() = lastRequestTimeMark.elapsedNow()

  val inactivityDuration
    get() = lastActivityTimeMark.elapsedNow()

  val scrapeRequestBacklogSize: Int
    get() = scrapeRequestQueue.size

  init {
    markActivityTime(true)
  }

  fun assignProperties(request: RegisterAgentRequest) {
    launchId = request.launchId
    agentName = request.agentName
    hostName = request.hostName
    consolidated = request.consolidated
  }

  suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
    scrapeRequestQueue.add(scrapeRequest)
    try {
      scrapeRequestNotifier.send(Unit)
    } catch (e: Exception) {
      scrapeRequestQueue.remove(scrapeRequest)
      throw e
    }
  }

  suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
    scrapeRequestNotifier.receiveCatching().getOrNull()?.let {
      scrapeRequestQueue.poll()
    }

  fun isValid() = valid && !scrapeRequestNotifier.isClosedForReceive

  fun isNotValid() = !isValid()

  fun invalidate() {
    valid = false
    scrapeRequestNotifier.close()
    // Drain any buffered scrape requests and FAIL them with an agent-disconnected result (not a bare
    // channel close) so a waiting HTTP handler's awaitCompleted() sees a truthful 502 instead of a null
    // result that submitScrapeRequest would mislabel as timed_out (finding 15).
    generateSequence { scrapeRequestQueue.poll() }.forEach { wrapper ->
      wrapper.complete(
        ScrapeResults(
          srAgentId = agentId,
          srScrapeId = wrapper.scrapeId,
          srStatusCode = HttpStatusCode.BadGateway.value,
          srFailureReason = "Agent disconnected",
        ),
      )
    }
  }

  fun markActivityTime(isRequest: Boolean) {
    val now = clock.markNow()
    lastActivityTimeMark = now

    if (isRequest)
      lastRequestTimeMark = now
  }

  override fun toString() =
    toStringElements {
      add("agentId", agentId)
      add("launchId", launchId)
      add("consolidated", consolidated)
      add("valid", valid)
      add("agentName", agentName)
      add("hostName", hostName)
      add("remoteAddr", remoteAddr)
      add("lastRequestDuration", lastRequestDuration)
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as AgentContext
    return agentId == other.agentId
  }

  override fun hashCode() = agentId.hashCode()

  companion object {
    private val AGENT_ID_GENERATOR = AtomicLong(0L)
  }
}
