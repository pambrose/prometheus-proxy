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

package io.prometheus.proxy

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A discrete change in proxy topology.
 *
 * Deliberately covers only transitions that happen at an identifiable *moment*. Values that drift
 * rather than change — backlog depth, map sizes, eviction countdowns — have no such moment and are
 * sampled on a timer by whoever needs them, not emitted here.
 */
internal sealed interface ProxyEvent {
  /** An agent completed registration and is now serving. */
  data class AgentConnected(
    val agentId: String,
  ) : ProxyEvent

  /** An agent's context was removed, whether it disconnected or was evicted as stale. */
  data class AgentDisconnected(
    val agentId: String,
    val reason: String,
  ) : ProxyEvent

  /** A path became servable by an agent. */
  data class PathRegistered(
    val path: String,
    val agentId: String,
  ) : ProxyEvent

  /** A path stopped being servable by an agent. */
  data class PathUnregistered(
    val path: String,
    val agentId: String,
  ) : ProxyEvent

  /** A scrape finished, successfully or not. */
  data class ScrapeCompleted(
    val agentId: String,
    val path: String,
    val isSuccess: Boolean,
  ) : ProxyEvent
}

/**
 * Fan-out bus for [ProxyEvent]s, so observers can react to topology changes without polling.
 *
 * Before this existed, nothing in the proxy was observable: every collection was a plain
 * `ConcurrentHashMap` or a `synchronized` `HashMap` with no listeners, callbacks, or flows. The
 * operational web UI is the first consumer.
 *
 * ### Why emitting is always safe
 *
 * [emit] is a `tryEmit` on a [MutableSharedFlow] configured with [BufferOverflow.DROP_OLDEST], which is
 * **non-suspending and never blocks**. That is what makes it safe to call from the places these
 * transitions actually happen: inside `synchronized(pathMap)`, and on gRPC transport threads. A slow or
 * absent subscriber can never stall a registration or a disconnect — it just misses events.
 *
 * Dropping is the right trade here. Every consumer re-reads a full snapshot when woken, so a missed
 * event costs at most a slightly later refresh, never a wrong render. The bus is a wake-up signal, not
 * a ledger.
 */
internal class ProxyEventBus {
  private val events =
    MutableSharedFlow<ProxyEvent>(
      replay = 0,
      extraBufferCapacity = BUFFER_CAPACITY,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  /** Observable stream of topology changes. Cold subscribers see only events emitted after subscribing. */
  val flow: SharedFlow<ProxyEvent> = events.asSharedFlow()

  /** Publishes [event]. Never suspends, never blocks, and never throws. */
  fun emit(event: ProxyEvent) {
    events.tryEmit(event)
  }

  companion object {
    // Deep enough to absorb a burst (a fleet reconnecting at once), small enough that a stalled
    // subscriber cannot retain meaningful memory.
    private const val BUFFER_CAPACITY = 128
  }
}
