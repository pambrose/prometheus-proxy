@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.google.protobuf.Empty
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG

class ConstantsTest : FunSpec() {
  init {
    test("EMPTY_AGENT_ID_MSG should have expected value") {
      EMPTY_AGENT_ID_MSG shouldBe "Empty agentId"
    }

    test("EMPTY_PATH_MSG should have expected value") {
      EMPTY_PATH_MSG shouldBe "Empty path"
    }

    test("EMPTY_INSTANCE should be a protobuf Empty instance") {
      EMPTY_INSTANCE.shouldBeInstanceOf<Empty>()
      EMPTY_INSTANCE shouldBe Empty.getDefaultInstance()
    }
  }
}
