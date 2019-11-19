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

package io.prometheus.agent

import io.prometheus.common.ScrapeRequestAction
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

class AgentConnectionContext {
  val disconnected = AtomicBoolean(false)
  val scrapeRequestChannel = Channel<ScrapeRequestAction>(Channel.UNLIMITED)
  val scrapeResultChannel = Channel<ScrapeResponse>(Channel.UNLIMITED)

  fun disconnect() {
    disconnected.set(true)
    scrapeRequestChannel.cancel()
    scrapeResultChannel.cancel()
  }

  val connected get() = !disconnected.get()
}