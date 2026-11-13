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

import io.grpc.Status
import io.grpc.StatusException

// A DEADLINE_EXCEEDED failure described as grpc-java 1.85 describes one: the description carries the call's [elapsed]
// seconds and its connection delays, so no two such failures share a message (November #9, #39).
internal fun deadlineExceeded(elapsed: String): StatusException =
  StatusException(
    Status.DEADLINE_EXCEEDED.withDescription(
      "CallOptions deadline exceeded after ${elapsed}s. Name resolution delay 0.000000000 seconds. " +
        "[closed=[], open=[[connecting_and_lb_delay=30001748917ns, was_still_waiting]]]",
    ),
  )
