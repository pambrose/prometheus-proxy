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

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

// November #7: the Zipkin tracing interceptors are installed while the agent's channel and the proxy's server are
// built, before the reporter they need exists, so each is wrapped in one of these, which builds it on the first call.
class DeferredInterceptorsTest : StringSpec() {
  init {
    "a deferred client interceptor should not build its delegate before a call" {
      var builds = 0
      DeferredClientInterceptor {
        builds++
        mockk<ClientInterceptor>()
      }
      builds shouldBe 0
    }

    "a deferred client interceptor should build its delegate once and pass every call to it" {
      val method = mockk<MethodDescriptor<String, String>>()
      val next = mockk<Channel>()
      val call = mockk<ClientCall<String, String>>()
      val delegate = mockk<ClientInterceptor>()
      every { delegate.interceptCall(method, CallOptions.DEFAULT, next) } returns call
      var builds = 0
      val interceptor =
        DeferredClientInterceptor {
          builds++
          delegate
        }

      repeat(3) { interceptor.interceptCall(method, CallOptions.DEFAULT, next) shouldBeSameInstanceAs call }

      builds shouldBe 1
      verify(exactly = 3) { delegate.interceptCall(method, CallOptions.DEFAULT, next) }
    }

    // A call made before the delegate can be built fails on its own; the next call tries again.
    "a deferred client interceptor whose delegate can't be built yet should build it on a later call" {
      val method = mockk<MethodDescriptor<String, String>>()
      val next = mockk<Channel>()
      val call = mockk<ClientCall<String, String>>()
      val delegate = mockk<ClientInterceptor>()
      every { delegate.interceptCall(method, CallOptions.DEFAULT, next) } returns call
      var ready = false
      val interceptor = DeferredClientInterceptor { if (ready) delegate else error("not ready") }

      shouldThrow<IllegalStateException> { interceptor.interceptCall(method, CallOptions.DEFAULT, next) }
      ready = true
      interceptor.interceptCall(method, CallOptions.DEFAULT, next) shouldBeSameInstanceAs call
    }

    "a deferred server interceptor should not build its delegate before a call" {
      var builds = 0
      DeferredServerInterceptor {
        builds++
        mockk<ServerInterceptor>()
      }
      builds shouldBe 0
    }

    "a deferred server interceptor should build its delegate once and pass every call to it" {
      val call = mockk<ServerCall<String, String>>()
      val headers = Metadata()
      val next = mockk<ServerCallHandler<String, String>>()
      val listener = mockk<ServerCall.Listener<String>>()
      val delegate = mockk<ServerInterceptor>()
      every { delegate.interceptCall(call, headers, next) } returns listener
      var builds = 0
      val interceptor =
        DeferredServerInterceptor {
          builds++
          delegate
        }

      repeat(3) { interceptor.interceptCall(call, headers, next) shouldBeSameInstanceAs listener }

      builds shouldBe 1
      verify(exactly = 3) { delegate.interceptCall(call, headers, next) }
    }

    "a deferred server interceptor whose delegate can't be built yet should build it on a later call" {
      val call = mockk<ServerCall<String, String>>()
      val headers = Metadata()
      val next = mockk<ServerCallHandler<String, String>>()
      val listener = mockk<ServerCall.Listener<String>>()
      val delegate = mockk<ServerInterceptor>()
      every { delegate.interceptCall(call, headers, next) } returns listener
      var ready = false
      val interceptor = DeferredServerInterceptor { if (ready) delegate else error("not ready") }

      shouldThrow<IllegalStateException> { interceptor.interceptCall(call, headers, next) }
      ready = true
      interceptor.interceptCall(call, headers, next) shouldBeSameInstanceAs listener
    }
  }
}
