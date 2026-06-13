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

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY

class AgentTokenClientInterceptorTest : StringSpec() {
  init {
    "interceptCall should return a non-null client call" {
      val interceptor = AgentTokenClientInterceptor("s3cret")
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      call.shouldNotBeNull()
    }

    "interceptCall should delegate to the next channel" {
      val interceptor = AgentTokenClientInterceptor("s3cret")
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      verify { mockChannel.newCall(mockMethod, CallOptions.DEFAULT) }
    }

    "start should attach the token header to the outgoing metadata" {
      val interceptor = AgentTokenClientInterceptor("s3cret")
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)

      val metadataSlot = slot<Metadata>()
      val mockUnderlyingCall = mockk<ClientCall<Any, Any>>(relaxed = true)
      every { mockUnderlyingCall.start(any(), capture(metadataSlot)) } answers {}

      val mockNextChannel = mockk<Channel>(relaxed = true)
      every { mockNextChannel.newCall(any<MethodDescriptor<Any, Any>>(), any()) } returns mockUnderlyingCall

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)
      call.start(mockk(relaxed = true), Metadata())

      metadataSlot.captured.get(META_AGENT_TOKEN_KEY) shouldBe "s3cret"
    }
  }
}
