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

package io.prometheus.proxy

import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import io.prometheus.grpc.RegisterAgentRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

internal class AgentContext(private val remoteAddr: String) {

  val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()

  private val scrapeRequestChannel = Channel<ScrapeRequestWrapper>(Channel.UNLIMITED)
  private val channelBacklogSize = AtomicInteger(0)

  private val clock = Monotonic
  private var lastActivityTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var lastRequestTimeMark: TimeMark by nonNullableReference(clock.markNow())
  private var valid by atomicBoolean(true)

  var launchId: String by nonNullableReference("Unassigned")
    private set
  var hostName: String by nonNullableReference("Unassigned")
    private set
  var agentName: String by nonNullableReference("Unassigned")
    private set
  var consolidated: Boolean by nonNullableReference(false)
    private set

  internal val desc: String
    get() = if (consolidated) "consolidated " else ""

  private val lastRequestDuration
    get() = lastRequestTimeMark.elapsedNow()

  val inactivityDuration
    get() = lastActivityTimeMark.elapsedNow()

  val scrapeRequestBacklogSize: Int
    get() = channelBacklogSize.get()

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
    scrapeRequestChannel.send(scrapeRequest)
    channelBacklogSize.incrementAndGet()
  }

  suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
    scrapeRequestChannel.receiveOrNull()
      ?.apply {
        channelBacklogSize.decrementAndGet()
      }

  fun isValid() = valid && !scrapeRequestChannel.isClosedForReceive

  fun isNotValid() = !isValid()

  fun invalidate() {
    valid = false
    scrapeRequestChannel.close()
  }

  fun markActivityTime(isRequest: Boolean) {
    lastActivityTimeMark = clock.markNow()

    if (isRequest)
      lastRequestTimeMark = clock.markNow()
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
      //add("inactivityDuration", inactivityDuration)
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