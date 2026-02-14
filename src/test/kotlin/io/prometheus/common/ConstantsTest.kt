/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.google.protobuf.Empty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG

class ConstantsTest : StringSpec() {
  init {
    "EMPTY_AGENT_ID_MSG should have expected value" {
      EMPTY_AGENT_ID_MSG shouldBe "Empty agentId"
    }

    "EMPTY_PATH_MSG should have expected value" {
      EMPTY_PATH_MSG shouldBe "Empty path"
    }

    "EMPTY_INSTANCE should be a protobuf Empty instance" {
      EMPTY_INSTANCE.shouldBeInstanceOf<Empty>()
      EMPTY_INSTANCE shouldBe Empty.getDefaultInstance()
    }
  }
}
