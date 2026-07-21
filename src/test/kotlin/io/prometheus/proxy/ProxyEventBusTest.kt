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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class ProxyEventBusTest : StringSpec() {
  // Collects exactly [count] events, guaranteeing the subscriber is registered before [emit] runs.
  // onSubscription fires after registration, so this is deterministic -- no sleeps, no yield races.
  private suspend fun collect(
    bus: ProxyEventBus,
    count: Int,
    emit: () -> Unit,
  ): List<ProxyEvent> =
    coroutineScope {
      val subscribed = CompletableDeferred<Unit>()
      val collected = async { bus.flow.onSubscription { subscribed.complete(Unit) }.take(count).toList() }
      subscribed.await()
      emit()
      collected.await()
    }

  init {
    "a subscriber should receive emitted events in order" {
      val bus = ProxyEventBus()
      val seen =
        collect(bus, 5) {
          bus.emit(ProxyEvent.AgentConnected("agent-1"))
          bus.emit(ProxyEvent.PathRegistered("app_metrics", "agent-1"))
          bus.emit(ProxyEvent.ScrapeCompleted("agent-1", "app_metrics", true))
          bus.emit(ProxyEvent.PathUnregistered("app_metrics", "agent-1"))
          bus.emit(ProxyEvent.AgentDisconnected("agent-1", "stale"))
        }

      seen shouldContainExactly
        [
          ProxyEvent.AgentConnected("agent-1"),
          ProxyEvent.PathRegistered("app_metrics", "agent-1"),
          ProxyEvent.ScrapeCompleted("agent-1", "app_metrics", true),
          ProxyEvent.PathUnregistered("app_metrics", "agent-1"),
          ProxyEvent.AgentDisconnected("agent-1", "stale"),
        ]
    }

    // The whole point of tryEmit + DROP_OLDEST: these calls happen inside synchronized(pathMap) and on
    // gRPC transport threads, so a missing subscriber must never stall a registration or a disconnect.
    "emitting with no subscriber should neither block nor throw" {
      val bus = ProxyEventBus()
      repeat(1_000) { bus.emit(ProxyEvent.AgentConnected("agent-$it")) }
    }

    // A slow subscriber loses the oldest events rather than applying backpressure to the emitter. That
    // is the deliberate trade: every consumer re-reads a full snapshot when woken, so a dropped event
    // costs a slightly later refresh, never a wrong render.
    "a backlog with no subscriber should not be replayed to one that joins later" {
      val bus = ProxyEventBus()
      // Far more than the buffer holds, with nobody collecting: these overflow and are dropped.
      repeat(5_000) { bus.emit(ProxyEvent.PathRegistered("path-$it", "agent-1")) }

      val seen = collect(bus, 1) { bus.emit(ProxyEvent.AgentConnected("late")) }

      // replay = 0, so a late subscriber sees only what was emitted after it subscribed.
      seen shouldHaveSize 1
      seen.first() shouldBe ProxyEvent.AgentConnected("late")
    }

    "multiple subscribers should each receive every event" {
      val bus = ProxyEventBus()
      coroutineScope {
        val readyA = CompletableDeferred<Unit>()
        val readyB = CompletableDeferred<Unit>()
        val a = async { bus.flow.onSubscription { readyA.complete(Unit) }.take(1).toList() }
        val b = async { bus.flow.onSubscription { readyB.complete(Unit) }.take(1).toList() }
        readyA.await()
        readyB.await()

        bus.emit(ProxyEvent.AgentConnected("agent-1"))

        a.await() shouldContainExactly [ProxyEvent.AgentConnected("agent-1")]
        b.await() shouldContainExactly [ProxyEvent.AgentConnected("agent-1")]
      }
    }

    // AgentConnected fires when the transport is established, which is BEFORE registerAgent supplies
    // identity -- so it deliberately carries only the id. AgentDisconnected is NOT emitted here: it
    // moved to Proxy.removeAgentContext so its happens-before edge covers the path sweep too.
    "AgentContextManager should emit on connect only" {
      val bus = ProxyEventBus()
      val manager = AgentContextManager(isTestMode = true, eventBus = bus)
      val context = AgentContext("10.0.1.14:1234")

      val seen =
        collect(bus, 1) {
          manager.removeFromContextManager(context.agentId, "not registered yet")
          manager.addAgentContext(context)
        }

      seen shouldContainExactly [ProxyEvent.AgentConnected(context.agentId)]
    }

    // AgentConnected and AgentRegistered are distinct on purpose. The transport filter runs before
    // per-call auth and before registerAgent, so at AgentConnected time the identity fields are still
    // "Unassigned" and the peer may still be rejected -- anything completing an HTTP/2 handshake gets
    // that far, including a health probe. Only AgentRegistered means a named, serving agent.
    "AgentConnected should precede identity assignment" {
      val context = AgentContext("10.0.1.14:1234")
      context.agentName shouldBe "Unassigned"
      context.hostName shouldBe "Unassigned"
      context.launchId shouldBe "Unassigned"
    }
  }
}
