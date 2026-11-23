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

package io.prometheus.common

import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration

/**
 * The next element of this channel, or its closing, as [ReceiveChannel.receiveCatching] gives it, or null once
 * [timeout] passes with neither. A select, so an element that comes as the wait ends is either taken or left in the
 * channel: a receive inside withTimeoutOrNull can take an element as the timeout cancels it, and lose it.
 */
internal suspend fun <E> ReceiveChannel<E>.receiveCatchingWithin(timeout: Duration): ChannelResult<E>? =
  select {
    onReceiveCatching { it }
    if (timeout.isFinite())
      onTimeout(timeout) { null }
  }
