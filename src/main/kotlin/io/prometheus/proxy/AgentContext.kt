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
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.RegisterAgentRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.minusAssign
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
 * @param authIdentityName the per-agent auth identity the agent connected as, or empty when unbound
 * @see AgentContextManager
 * @see ProxyPathManager
 */
internal class AgentContext(
  // Readable: the operational dashboard shows which machine an agent is actually on, which a self-reported
  // agentName cannot establish.
  val remoteAddr: String,
  // The per-agent auth identity the agent connected as. Recorded only when the transport filter is disabled,
  // where there is no transport-assigned agentId to bind later calls to. Empty when unbound -- auth disabled,
  // or a context created by the transport filter. Identity names are never empty, so "" can't collide.
  val authIdentityName: String = "",
  // Where activity and request times are measured; injectable so tests advance time instead of sleeping, as
  // HttpClientCache and AgentPathManager do.
  private val clock: TimeSource = Monotonic,
) {
  val agentId = AGENT_ID_GENERATOR.incrementAndFetch().toString()

  private val scrapeRequestQueue = ConcurrentLinkedQueue<ScrapeRequestWrapper>()

  // Admission counter for the per-agent backlog cap. Tracks the queue's contents, but is claimed atomically
  // before a request is queued, so concurrent writers cannot overshoot the cap.
  private val queuedCount = AtomicInt(0)
  private val scrapeRequestNotifier = Channel<Unit>(UNLIMITED)

  // The rejection cause this connection was last told for each path; see ProxyPathManager.rejectPath. Dies with
  // the connection, so a reconnect is told afresh.
  private val loggedPathRejections = ConcurrentHashMap<String, PathRejectionCause>()

  /**
   * Wall-clock instant this context was created, i.e. when the agent connected.
   *
   * Every other timing field is a [Monotonic] [TimeMark], which measures elapsed time correctly but
   * cannot be rendered as a time of day. Kept separate rather than replacing them: monotonic marks are
   * immune to clock adjustments and remain the right basis for eviction.
   */
  val connectTime: Instant = Instant.now()
  private var lastActivityTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var lastRequestTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var valid by atomicBoolean(true)

  // Whether this context has been announced as a connected agent; see AgentContextManager.announceAgentContext.
  private val announcedFlag = AtomicBoolean(false)

  /** True once the agent made its first authenticated call; until then the context is a pending connection. */
  val announced: Boolean
    get() = announcedFlag.load()

  // Marks the context announced, returning true only for the call that did it.
  internal fun markAnnounced(): Boolean = announcedFlag.compareAndSet(expectedValue = false, newValue = true)

  // Readable rather than private: the dashboard displays it to distinguish two runs of the same agent name.
  var launchId: String by nonNullableReference("Unassigned")
    private set
  var hostName: String by nonNullableReference("Unassigned")
    private set
  var agentName: String by nonNullableReference("Unassigned")
    private set
  var consolidated: Boolean by atomicBoolean(false)
    private set

  /**
   * The agent's configured failover endpoints and which one this connection uses.
   *
   * Reported at registration, which is exactly when it changes: a failover is a reconnect, so an agent
   * appearing here on its secondary endpoint has, by definition, just failed over to this proxy. Empty
   * for an agent predating the fields, or one with no failover configured.
   */
  var proxyEndpoints: List<String> by nonNullableReference(emptyList())
    private set

  var currentEndpointIndex: Int by nonNullableReference(0)
    private set

  internal val desc: String
    get() = if (consolidated) "consolidated " else ""

  internal val lastRequestDuration
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
    proxyEndpoints = request.proxyEndpointsList.toList()
    currentEndpointIndex = request.currentEndpointIndex
  }

  /**
   * Queues [scrapeRequest] for this agent unless [maxBacklog] requests are already queued.
   *
   * Returns false, without queueing, when the cap is reached, so one slow agent cannot pile up requests without
   * bound.
   */
  suspend fun writeScrapeRequest(
    scrapeRequest: ScrapeRequestWrapper,
    maxBacklog: Int = Int.MAX_VALUE,
  ): Boolean {
    if (queuedCount.incrementAndFetch() > maxBacklog) {
      queuedCount -= 1
      return false
    }
    scrapeRequestQueue.add(scrapeRequest)
    try {
      scrapeRequestNotifier.send(Unit)
    } catch (e: Exception) {
      // Release the slot only if this request was still queued; invalidate() may have drained it already.
      if (scrapeRequestQueue.remove(scrapeRequest))
        queuedCount -= 1
      throw e
    }
    return true
  }

  suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
    scrapeRequestNotifier.receiveCatching().getOrNull()?.let {
      scrapeRequestQueue.poll()?.also { queuedCount -= 1 }
    }

  fun isValid() = valid && !scrapeRequestNotifier.isClosedForReceive

  fun isNotValid() = !isValid()

  // Records that this connection was told [cause] for [path], returning true when that is news: the first
  // rejection of the path, or a different cause than last time.
  fun recordRejection(
    path: String,
    cause: PathRejectionCause,
  ): Boolean {
    val previous = loggedPathRejections.put(path, cause)
    return previous != cause
  }

  // Forgets [path]'s rejection once it registers, so a conflict that re-forms later is reported again.
  fun forgetRejection(path: String) {
    loggedPathRejections.remove(path)
  }

  fun invalidate() {
    valid = false
    scrapeRequestNotifier.close()
    // Drain any buffered scrape requests and FAIL them with an agent-disconnected result (not a bare
    // channel close) so a waiting HTTP handler's awaitCompleted() sees a truthful agent_disconnected instead of a
    // null result that submitScrapeRequest would mislabel as timed_out (finding 15).
    val failure = ProxyFailure.AGENT_DISCONNECTED
    generateSequence { scrapeRequestQueue.poll()?.also { queuedCount -= 1 } }.forEach { wrapper ->
      wrapper.complete(
        ScrapeResults(
          srAgentId = agentId,
          srScrapeId = wrapper.scrapeId,
          srStatusCode = failure.statusCode.value,
          srFailureReason = "Agent disconnected",
        ),
        failure,
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
