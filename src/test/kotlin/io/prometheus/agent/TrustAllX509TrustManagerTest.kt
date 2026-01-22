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

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

// Tests for TrustAllX509TrustManager which is used for development/testing environments
// to bypass SSL certificate validation. This should NEVER be used in production.
class TrustAllX509TrustManagerTest {
  // ==================== Interface Implementation Tests ====================

  @Test
  fun `TrustAllX509TrustManager should implement X509TrustManager interface`() {
    TrustAllX509TrustManager.shouldBeInstanceOf<X509TrustManager>()
  }

  // ==================== getAcceptedIssuers Tests ====================

  @Test
  fun `getAcceptedIssuers should return empty array`() {
    val issuers = TrustAllX509TrustManager.getAcceptedIssuers()

    issuers.shouldBeEmpty()
  }

  @Test
  fun `getAcceptedIssuers should return same instance on multiple calls`() {
    // The implementation returns a static empty array
    val issuers1 = TrustAllX509TrustManager.getAcceptedIssuers()
    val issuers2 = TrustAllX509TrustManager.getAcceptedIssuers()

    issuers1 shouldBe issuers2
  }

  @Test
  fun `getAcceptedIssuers should return array of size 0`() {
    val issuers = TrustAllX509TrustManager.getAcceptedIssuers()

    issuers.size shouldBe 0
  }

  // ==================== checkClientTrusted Tests ====================

  @Test
  fun `checkClientTrusted should accept null certificates`() {
    // Should not throw any exception
    TrustAllX509TrustManager.checkClientTrusted(null, null)
  }

  @Test
  fun `checkClientTrusted should accept empty certificate array`() {
    val emptyCerts = arrayOfNulls<X509Certificate>(0)

    // Should not throw any exception
    TrustAllX509TrustManager.checkClientTrusted(emptyCerts, "RSA")
  }

  @Test
  fun `checkClientTrusted should accept any certificate array`() {
    val mockCert = mockk<X509Certificate>(relaxed = true)
    val certs = arrayOf<X509Certificate?>(mockCert)

    // Should not throw any exception - trusts all certificates
    TrustAllX509TrustManager.checkClientTrusted(certs, "RSA")
  }

  @Test
  fun `checkClientTrusted should accept null authType`() {
    val mockCert = mockk<X509Certificate>(relaxed = true)
    val certs = arrayOf<X509Certificate?>(mockCert)

    // Should not throw any exception
    TrustAllX509TrustManager.checkClientTrusted(certs, null)
  }

  @Test
  fun `checkClientTrusted should accept any authType string`() {
    val mockCert = mockk<X509Certificate>(relaxed = true)
    val certs = arrayOf<X509Certificate?>(mockCert)

    // Should not throw any exception for any auth type
    TrustAllX509TrustManager.checkClientTrusted(certs, "RSA")
    TrustAllX509TrustManager.checkClientTrusted(certs, "DSA")
    TrustAllX509TrustManager.checkClientTrusted(certs, "EC")
    TrustAllX509TrustManager.checkClientTrusted(certs, "UNKNOWN")
  }

  // ==================== checkServerTrusted Tests ====================

  @Test
  fun `checkServerTrusted should accept null certificates`() {
    // Should not throw any exception
    TrustAllX509TrustManager.checkServerTrusted(null, null)
  }

  @Test
  fun `checkServerTrusted should accept empty certificate array`() {
    val emptyCerts = arrayOfNulls<X509Certificate>(0)

    // Should not throw any exception
    TrustAllX509TrustManager.checkServerTrusted(emptyCerts, "RSA")
  }

  @Test
  fun `checkServerTrusted should accept any certificate array`() {
    val mockCert = mockk<X509Certificate>(relaxed = true)
    val certs = arrayOf<X509Certificate?>(mockCert)

    // Should not throw any exception - trusts all certificates
    TrustAllX509TrustManager.checkServerTrusted(certs, "RSA")
  }

  @Test
  fun `checkServerTrusted should accept null authType`() {
    val mockCert = mockk<X509Certificate>(relaxed = true)
    val certs = arrayOf<X509Certificate?>(mockCert)

    // Should not throw any exception
    TrustAllX509TrustManager.checkServerTrusted(certs, null)
  }

  @Test
  fun `checkServerTrusted should accept multiple certificates in chain`() {
    val mockCert1 = mockk<X509Certificate>(relaxed = true)
    val mockCert2 = mockk<X509Certificate>(relaxed = true)
    val mockCert3 = mockk<X509Certificate>(relaxed = true)
    val certChain = arrayOf<X509Certificate?>(mockCert1, mockCert2, mockCert3)

    // Should not throw any exception - trusts all certificate chains
    TrustAllX509TrustManager.checkServerTrusted(certChain, "RSA")
  }

  // ==================== Object Singleton Tests ====================

  @Test
  fun `TrustAllX509TrustManager should be a singleton object`() {
    // The object keyword in Kotlin creates a singleton
    val instance1 = TrustAllX509TrustManager
    val instance2 = TrustAllX509TrustManager

    instance1 shouldBe instance2
  }
}
