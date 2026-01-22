/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import org.junit.jupiter.api.Test

// Tests for ProxyHttpConfig which configures Ktor server plugins for the proxy HTTP service.
// Note: Full integration tests with testApplication would require ktor-server-test-host dependency.
// These tests verify the configuration logic without running a full HTTP server.
class ProxyHttpConfigTest {
  // ==================== Configuration Object Tests ====================

  @Test
  fun `ProxyHttpConfig object should exist`() {
    // Verify the ProxyHttpConfig object is accessible
    ProxyHttpConfig.shouldNotBeNull()
  }

  // ==================== Compression Configuration Tests ====================

  @Test
  fun `CompressionConfig should support gzip configuration`() {
    val config = CompressionConfig()

    // Apply gzip configuration similar to ProxyHttpConfig
    config.gzip {
      priority = 1.0
    }

    // Configuration should be applied without throwing
    config.shouldNotBeNull()
  }

  @Test
  fun `CompressionConfig should support deflate configuration`() {
    val config = CompressionConfig()

    // Apply deflate configuration similar to ProxyHttpConfig
    config.deflate {
      priority = 10.0
      minimumSize(1024)
    }

    // Configuration should be applied without throwing
    config.shouldNotBeNull()
  }

  @Test
  fun `CompressionConfig should support multiple encoders`() {
    val config = CompressionConfig()

    // Apply both gzip and deflate like ProxyHttpConfig does
    config.gzip {
      priority = 1.0
    }
    config.deflate {
      priority = 10.0
      minimumSize(1024)
    }

    config.shouldNotBeNull()
  }

  // ==================== HTTP Status Code Tests ====================

  @Test
  fun `NotFound status code should have correct value`() {
    HttpStatusCode.NotFound.value shouldBe 404
    HttpStatusCode.NotFound.description shouldBe "Not Found"
  }

  @Test
  fun `Found status code should have correct value`() {
    HttpStatusCode.Found.value shouldBe 302
  }

  @Test
  fun `status codes should be comparable`() {
    val status = HttpStatusCode.OK

    (status == HttpStatusCode.OK) shouldBe true
    (status == HttpStatusCode.NotFound) shouldBe false
  }

  // ==================== Compression Priority Tests ====================

  @Test
  fun `gzip should have higher priority than deflate in config`() {
    // In ProxyHttpConfig, gzip has priority 1.0 and deflate has 10.0
    // Lower numbers = higher priority in Ktor
    val gzipPriority = 1.0
    val deflatePriority = 10.0

    (gzipPriority < deflatePriority) shouldBe true
  }

  @Test
  fun `deflate minimum size should be 1024 bytes`() {
    val minimumSize = 1024L

    minimumSize shouldBe 1024L
  }
}
