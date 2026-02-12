@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RequestFailureExceptionTest : StringSpec() {
  init {
    "should store message" {
      val exception = RequestFailureException("test error message")

      exception.message shouldBe "test error message"
    }

    "should be throwable as Exception" {
      val exception = RequestFailureException("failure")

      exception.shouldBeInstanceOf<Exception>()
    }

    "should be catchable" {
      val caught = shouldThrow<RequestFailureException> {
        throw RequestFailureException("expected failure")
      }

      caught.message shouldBe "expected failure"
    }

    "should have null cause by default" {
      val exception = RequestFailureException("no cause")

      exception.cause.shouldBeNull()
    }

    "should handle empty message" {
      val exception = RequestFailureException("")

      exception.message shouldBe ""
    }

    "should handle message with special characters" {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      val exception = RequestFailureException(msg)

      exception.message shouldBe msg
    }
  }
}
