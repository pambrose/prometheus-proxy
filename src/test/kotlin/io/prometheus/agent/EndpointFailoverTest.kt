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

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class EndpointFailoverTest : StringSpec() {
  // advanceEndpoint() reports whether there was another endpoint to move to, so a single-endpoint agent
  // returns false -- which is what tells the caller not to rebuild the channel.
  private fun grpcServiceWithEndpoints(count: Int): AgentGrpcService =
    mockk<AgentGrpcService>(relaxed = true).also { every { it.advanceEndpoint() } returns (count > 1) }

  init {
    "the first attempt should use the existing channel without rotating or rebuilding" {
      val grpc = grpcServiceWithEndpoints(2)

      EndpointFailover(grpc).beforeAttempt(previousAttemptConnected = false)

      verify(exactly = 0) { grpc.advanceEndpoint() }
      verify(exactly = 0) { grpc.resetEndpoint() }
      verify(exactly = 0) { grpc.resetGrpcStubs() }
    }

    "a failed connect should move to the next endpoint and rebuild the channel" {
      val grpc = grpcServiceWithEndpoints(2)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      failover.beforeAttempt(previousAttemptConnected = false)

      verify(exactly = 1) { grpc.advanceEndpoint() }
      verify(exactly = 1) { grpc.resetGrpcStubs() }
      verify(exactly = 0) { grpc.resetEndpoint() }
    }

    "a failed connect with a single endpoint should keep the existing channel" {
      val grpc = grpcServiceWithEndpoints(1)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      failover.beforeAttempt(previousAttemptConnected = false)

      verify(exactly = 0) { grpc.resetGrpcStubs() }
    }

    "a registered connection that later drops should return to the primary endpoint" {
      val grpc = grpcServiceWithEndpoints(2)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      failover.registrationSucceeded()
      failover.beforeAttempt(previousAttemptConnected = true)

      verify(exactly = 1) { grpc.resetEndpoint() }
      verify(exactly = 1) { grpc.resetGrpcStubs() }
      verify(exactly = 0) { grpc.advanceEndpoint() }
    }

    // A proxy that accepts the connection but rejects registration (for example, a different auth config)
    // never became usable. Returning to the primary would retry that same proxy forever and never reach a
    // healthy endpoint later in the list.
    "a connection whose registration was rejected should move to the next endpoint" {
      val grpc = grpcServiceWithEndpoints(2)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      // Connected, but registration threw, so registrationSucceeded() was never called.
      failover.beforeAttempt(previousAttemptConnected = true)

      verify(exactly = 1) { grpc.advanceEndpoint() }
      verify(exactly = 0) { grpc.resetEndpoint() }
      verify(exactly = 1) { grpc.resetGrpcStubs() }
    }

    "a rejected registration on a single endpoint should still rebuild the spent channel" {
      val grpc = grpcServiceWithEndpoints(1)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      failover.beforeAttempt(previousAttemptConnected = true)

      verify(exactly = 1) { grpc.resetGrpcStubs() }
    }

    "a successful registration should count only for the attempt that registered" {
      val grpc = grpcServiceWithEndpoints(2)
      val failover = EndpointFailover(grpc)

      failover.beforeAttempt(previousAttemptConnected = false)
      failover.registrationSucceeded()
      failover.beforeAttempt(previousAttemptConnected = true)
      clearMocks(grpc, answers = false)

      // This attempt connected but did not register.
      failover.beforeAttempt(previousAttemptConnected = true)

      verify(exactly = 1) { grpc.advanceEndpoint() }
      verify(exactly = 0) { grpc.resetEndpoint() }
    }
  }
}
