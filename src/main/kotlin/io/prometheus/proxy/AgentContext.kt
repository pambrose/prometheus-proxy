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

package io.prometheus.proxy

import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ClockMark
import kotlin.time.MonoClock

class AgentContext(private val remoteAddr: String) {

  val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()

  private val scrapeRequestChannel = Channel<ScrapeRequestWrapper>(Channel.UNLIMITED)
  private val channelBacklogSize = AtomicInteger(0)

  private val clock = MonoClock
  private var lastActivityTimeMark: ClockMark by nonNullableReference(clock.markNow())
  private var valid = AtomicBoolean(true)

  var hostName: String by nonNullableReference()
  var agentName: String by nonNullableReference()

  val inactivityTime
    get() = lastActivityTimeMark.elapsedNow()

  val scrapeRequestBacklogSize: Int
    get() = channelBacklogSize.get()

  init {
    hostName = "Unassigned"
    agentName = "Unassigned"
    markActivity()
  }

  suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
    scrapeRequestChannel.send(scrapeRequest)
    channelBacklogSize.incrementAndGet()
  }

  suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
    scrapeRequestChannel.receiveOrNull()
      ?.also {
        channelBacklogSize.decrementAndGet()
      }

  fun isValid() = valid.get() && !scrapeRequestChannel.isClosedForReceive

  fun isNotValid() = !isValid()

  fun invalidate() {
    valid.set(false)
    scrapeRequestChannel.close()
  }

  fun markActivity() {
    lastActivityTimeMark = clock.markNow()
  }

  override fun toString() =
    toStringElements {
      add("agentId", agentId)
      add("valid", valid.get())
      add("remoteAddr", remoteAddr)
      add("agentName", agentName)
      add("hostName", hostName)
      add("inactivityTime", inactivityTime)
    }

  companion object {
    private val AGENT_ID_GENERATOR = AtomicLong(0)
  }
}