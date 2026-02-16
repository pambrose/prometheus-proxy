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

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ProxyDynamicConfigTest : StringSpec() {
  init {
    "proxy.internal.maxZippedContentSizeMBytes should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.maxZippedContentSizeMBytes=12"))
      options.configVals.proxy.internal.maxZippedContentSizeMBytes shouldBe 12
    }

    "proxy.internal.maxUnzippedContentSizeMBytes should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.maxUnzippedContentSizeMBytes=25"))
      options.configVals.proxy.internal.maxUnzippedContentSizeMBytes shouldBe 25
    }

    "proxy.internal.staleAgentCheckEnabled should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.staleAgentCheckEnabled=false"))
      options.configVals.proxy.internal.staleAgentCheckEnabled shouldBe false
    }

    "proxy.internal.maxAgentInactivitySecs should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.maxAgentInactivitySecs=120"))
      options.configVals.proxy.internal.maxAgentInactivitySecs shouldBe 120
    }

    "proxy.internal.staleAgentCheckPauseSecs should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.staleAgentCheckPauseSecs=20"))
      options.configVals.proxy.internal.staleAgentCheckPauseSecs shouldBe 20
    }

    "proxy.internal.scrapeRequestTimeoutSecs should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.scrapeRequestTimeoutSecs=45"))
      options.configVals.proxy.internal.scrapeRequestTimeoutSecs shouldBe 45
    }

    "proxy.internal.scrapeRequestCheckMillis should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.scrapeRequestCheckMillis=250"))
      options.configVals.proxy.internal.scrapeRequestCheckMillis shouldBe 250
    }

    "proxy.internal.scrapeRequestBacklogUnhealthySize should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.scrapeRequestBacklogUnhealthySize=50"))
      options.configVals.proxy.internal.scrapeRequestBacklogUnhealthySize shouldBe 50
    }

    "proxy.internal.scrapeRequestMapUnhealthySize should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.scrapeRequestMapUnhealthySize=60"))
      options.configVals.proxy.internal.scrapeRequestMapUnhealthySize shouldBe 60
    }

    "proxy.internal.chunkContextMapUnhealthySize should be settable via -D" {
      val options = ProxyOptions(listOf("-Dproxy.internal.chunkContextMapUnhealthySize=70"))
      options.configVals.proxy.internal.chunkContextMapUnhealthySize shouldBe 70
    }
  }
}
