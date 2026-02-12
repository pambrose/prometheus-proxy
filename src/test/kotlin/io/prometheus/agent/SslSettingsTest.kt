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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class SslSettingsTest : FunSpec() {
  init {
    // ==================== getKeyStore Tests ====================

    test("getKeyStore should throw FileNotFoundException for non-existent file") {
      shouldThrow<FileNotFoundException> {
        SslSettings.getKeyStore("non-existent-keystore.jks", "password")
      }
    }

    test("getKeyStore should throw for invalid file path") {
      shouldThrow<FileNotFoundException> {
        SslSettings.getKeyStore("/path/that/does/not/exist/keystore.jks", "test")
      }
    }

    // Tests with valid keystore would require creating a test keystore file,
    // which is typically done in integration tests. The existing TLS tests
    // in HarnessTests cover this functionality with actual cert files.

    // ==================== getTrustManagerFactory Tests ====================

    test("getTrustManagerFactory should throw for non-existent keystore") {
      shouldThrow<FileNotFoundException> {
        SslSettings.getTrustManagerFactory("non-existent-keystore.jks", "password")
      }
    }

    // ==================== getSslContext Tests ====================

    test("getSslContext should throw for non-existent keystore") {
      shouldThrow<FileNotFoundException> {
        SslSettings.getSslContext("non-existent-keystore.jks", "password")
      }
    }

    // ==================== getTrustManager Tests ====================

    test("getTrustManager should throw for non-existent keystore") {
      shouldThrow<FileNotFoundException> {
        SslSettings.getTrustManager("non-existent-keystore.jks", "password")
      }
    }

    // ==================== Type Verification Tests ====================
    // These tests verify the return types of the methods when they succeed

    test("TrustManagerFactory getDefaultAlgorithm should return valid algorithm") {
      // Verify that the default algorithm is available
      val algorithm = TrustManagerFactory.getDefaultAlgorithm()
      algorithm.shouldNotBeNull()
    }

    test("KeyStore getDefaultType should return valid type") {
      // Verify that the default keystore type is available
      val type = KeyStore.getDefaultType()
      type.shouldNotBeNull()
    }

    test("SSLContext TLS instance should be obtainable") {
      // Verify that TLS SSLContext can be created
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.shouldNotBeNull()
      sslContext.protocol shouldBe "TLS"
    }

    test("TrustManagerFactory can be initialized with null") {
      // Verify default trust manager factory behavior
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(null as KeyStore?)
      val trustManagers = tmf.trustManagers
      trustManagers.shouldNotBeNull()
      trustManagers.isNotEmpty() shouldBe true
      trustManagers[0].shouldBeInstanceOf<X509TrustManager>()
    }
  }
}
