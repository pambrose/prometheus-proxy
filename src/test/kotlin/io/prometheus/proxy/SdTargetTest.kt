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

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SdTargetTest : StringSpec() {
  init {
    "parse should take host:port as the target, and leave the scheme to the scrape config" {
      SdTarget.parse("proxy.example.com:8080") shouldBe SdTarget("proxy.example.com:8080", null, "")
    }

    // Prometheus takes a target as __address__, which can't contain "/", so a URL is split into what it does take.
    "parse should split a URL into its host and port, its scheme, and its path prefix" {
      SdTarget.parse("http://proxy.example.com:8080/") shouldBe SdTarget("proxy.example.com:8080", "http", "")
      SdTarget.parse("HTTPS://proxy.example.com/prom/") shouldBe SdTarget("proxy.example.com", "https", "/prom")
      SdTarget.parse(" http://[::1]:9090 ") shouldBe SdTarget("[::1]:9090", "http", "")
    }

    "parse should reject a value Prometheus couldn't scrape, naming the setting" {
      listOf(
        "",
        "  ",
        "ftp://proxy:1",
        "http://",
        "http://user:pw@proxy:1",
        "http://proxy:1/#f",
        "proxy/x",
        "proxy:1/x",
      )
        .forEach { prefix ->
          shouldThrow<IllegalArgumentException> { SdTarget.parse(prefix) }.message shouldContain "sdTargetPrefix"
        }
    }
  }
}
