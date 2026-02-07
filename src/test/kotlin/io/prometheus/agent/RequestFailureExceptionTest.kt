@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RequestFailureExceptionTest {
  @Test
  fun `should store message`() {
    val exception = RequestFailureException("test error message")

    exception.message shouldBe "test error message"
  }

  @Test
  fun `should be throwable as Exception`() {
    val exception = RequestFailureException("failure")

    exception.shouldBeInstanceOf<Exception>()
  }

  @Test
  fun `should be catchable`() {
    val caught = assertThrows<RequestFailureException> {
      throw RequestFailureException("expected failure")
    }

    caught.message shouldBe "expected failure"
  }

  @Test
  fun `should have null cause by default`() {
    val exception = RequestFailureException("no cause")

    exception.cause.shouldBeNull()
  }

  @Test
  fun `should handle empty message`() {
    val exception = RequestFailureException("")

    exception.message shouldBe ""
  }

  @Test
  fun `should handle message with special characters`() {
    val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
    val exception = RequestFailureException(msg)

    exception.message shouldBe msg
  }
}
