@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RequestFailureExceptionTest : FunSpec() {
  init {
    test("should store message") {
      val exception = RequestFailureException("test error message")

      exception.message shouldBe "test error message"
    }

    test("should be throwable as Exception") {
      val exception = RequestFailureException("failure")

      exception.shouldBeInstanceOf<Exception>()
    }

    test("should be catchable") {
      val caught = shouldThrow<RequestFailureException> {
        throw RequestFailureException("expected failure")
      }

      caught.message shouldBe "expected failure"
    }

    test("should have null cause by default") {
      val exception = RequestFailureException("no cause")

      exception.cause.shouldBeNull()
    }

    test("should handle empty message") {
      val exception = RequestFailureException("")

      exception.message shouldBe ""
    }

    test("should handle message with special characters") {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      val exception = RequestFailureException(msg)

      exception.message shouldBe msg
    }
  }
}
