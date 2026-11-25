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

package io.prometheus.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// receiveCatchingWithin is every wait that ends in a keepalive, on both sides of the connection: the proxy's wait for
// a scrape request and for a path notice, and the agent's for a result to send. Nothing tested it, so a rewrite in the
// form AGENTS.md rules out, a receive inside withTimeoutOrNull, passed the whole suite (November #46).
class ChannelsTest : StringSpec() {
  init {
    // A receive inside withTimeoutOrNull takes the element and is then cancelled by the timeout, so it returns null and
    // the element is gone: a scrape request left unread, a path notice unread until the next, a result dropped.
    "an element that comes as the wait times out should be returned, and leave the channel empty" {
      val channel = Channel<Int>(UNLIMITED)

      val received = lateArrival(wait = { channel.receiveCatchingWithin(it) }, arrive = { channel.trySend(1) })

      received.shouldNotBeNull().getOrNull() shouldBe 1
      channel.tryReceive().isFailure.shouldBeTrue()
    }

    "a wait should return null once its timeout passes with nothing, and leave the channel to the next one" {
      val channel = Channel<Int>(UNLIMITED)

      channel.receiveCatchingWithin(50.milliseconds).shouldBeNull()
      channel.trySend(2)

      channel.receiveCatchingWithin(1.seconds).shouldNotBeNull().getOrNull() shouldBe 2
    }

    "an element queued before the wait should be returned at once" {
      val channel = Channel<Int>(UNLIMITED)
      channel.trySend(3)

      channel.receiveCatchingWithin(Duration.ZERO).shouldNotBeNull().getOrNull() shouldBe 3
    }

    "a wait should return the channel's closing, with its cause" {
      val channel = Channel<Int>(UNLIMITED)
      channel.close(IllegalStateException("gone"))

      val received = channel.receiveCatchingWithin(1.seconds).shouldNotBeNull()

      received.isClosed.shouldBeTrue()
      received.exceptionOrNull()?.message shouldBe "gone"
    }

    // A keepalive interval of 0 from the proxy, or a proxy predating keepalives, is an infinite wait: no timeout.
    "an infinite timeout should wait for an element with no timeout" {
      val channel = Channel<Int>(UNLIMITED)

      coroutineScope {
        val waiting = async { channel.receiveCatchingWithin(Duration.INFINITE) }
        delay(100.milliseconds)
        waiting.isActive.shouldBeTrue()
        channel.trySend(4)

        waiting.await().shouldNotBeNull().getOrNull() shouldBe 4
      }
    }
  }
}
