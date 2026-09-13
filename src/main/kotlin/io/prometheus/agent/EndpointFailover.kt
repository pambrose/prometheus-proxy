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

package io.prometheus.agent

/**
 * Chooses which proxy endpoint the agent dials on each connection attempt.
 *
 * [beforeAttempt] runs before every attempt. A previous attempt that fully registered and then dropped
 * fails back to the primary endpoint, which makes the endpoint list a priority order rather than a ring.
 * A previous attempt that never became usable -- it failed to connect, or it connected but the proxy
 * rejected registration -- fails forward to the next endpoint. [registrationSucceeded] marks the current
 * attempt as usable.
 *
 * @param grpcService the service whose endpoint cursor and channel this drives
 */
internal class EndpointFailover(
  private val grpcService: AgentGrpcService,
) {
  // The first attempt uses the channel AgentGrpcService.init already built, so it must not tear that
  // channel down and rebuild it. Every later attempt is a retry, and a retry is where rotation happens.
  private var firstAttempt = true
  private var previousAttemptRegistered = false

  fun beforeAttempt(previousAttemptConnected: Boolean) {
    val registered = previousAttemptRegistered
    previousAttemptRegistered = false
    when {
      // Registered, then dropped. Return to the head of the list so a recovered primary gets re-probed --
      // the entire failback mechanism. The previous connection's channel is spent either way.
      registered -> {
        grpcService.resetEndpoint()
        grpcService.resetGrpcStubs()
      }

      // Never usable, so move to the next endpoint. Keying this on connect alone would send an agent back
      // to a proxy that accepts connections but rejects registration, forever. The rebuild is required: a
      // ManagedChannel is bound to its target address. Guarded on advanceEndpoint() returning true so a
      // single-endpoint agent keeps reusing its channel and leaning on gRPC's own backoff.
      !firstAttempt && grpcService.advanceEndpoint() -> {
        grpcService.resetGrpcStubs()
      }

      // Connected but rejected, with no other endpoint to move to: stay put, but the channel is spent.
      previousAttemptConnected -> {
        grpcService.resetGrpcStubs()
      }
    }
    firstAttempt = false
  }

  fun registrationSucceeded() {
    previousAttemptRegistered = true
  }
}
