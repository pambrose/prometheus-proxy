@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.google.protobuf.Empty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import org.junit.jupiter.api.Test

class ConstantsTest {
  @Test
  fun `EMPTY_AGENT_ID_MSG should have expected value`() {
    EMPTY_AGENT_ID_MSG shouldBe "Empty agentId"
  }

  @Test
  fun `EMPTY_PATH_MSG should have expected value`() {
    EMPTY_PATH_MSG shouldBe "Empty path"
  }

  @Test
  fun `EMPTY_INSTANCE should be a protobuf Empty instance`() {
    EMPTY_INSTANCE.shouldBeInstanceOf<Empty>()
    EMPTY_INSTANCE shouldBe Empty.getDefaultInstance()
  }
}
