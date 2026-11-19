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

package io.prometheus.common

import ch.qos.logback.classic.Level
import com.pambrose.common.utils.TlsContext
import com.pambrose.common.utils.TlsUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.Base64

// TLS material given as PEM text, the form a platform that passes secrets as environment variables needs, in place of
// the files the Proxy and Agent otherwise read: how a value is read, how a file and a PEM for the same material
// conflict, and the gRPC TLS contexts built from either.
class TlsMaterialTest : StringSpec() {
  init {
    // ==================== pemText ====================

    "a blank value is no PEM" {
      TlsMaterial.pemText("certChainPem", "  \n ") shouldBe ""
    }

    "PEM text is taken as it is, trimmed" {
      TlsMaterial.pemText("certChainPem", "\n$CA_PEM\n  ") shouldBe CA_PEM
    }

    "the base64 of PEM text is decoded" {
      TlsMaterial.pemText("certChainPem", base64(CA_PEM)) shouldBe CA_PEM
    }

    // `base64` without -w0 (GNU) or a MIME encoder wraps its output in lines.
    "base64 wrapped in lines is decoded" {
      val wrapped = Base64.getMimeEncoder().encodeToString(CA_PEM.toByteArray())
      ('\n' in wrapped).shouldBeTrue()
      TlsMaterial.pemText("certChainPem", wrapped) shouldBe CA_PEM
    }

    // Some platforms' variable fields keep a PEM pasted on one line, with literal \n escapes.
    "PEM text on one line with literal \\n escapes gets its line breaks back" {
      TlsMaterial.pemText("certChainPem", CA_PEM.replace("\n", "\\n")) shouldBe CA_PEM
    }

    // The value may be a private key, so the message names the setting and never the value.
    "a value that is neither PEM nor base64 of PEM is refused without the value in the message" {
      listOf("not a certificate at all", base64("not PEM either"), "%%%").forEach { value ->
        val e = shouldThrow<IllegalArgumentException> { TlsMaterial.pemText("privateKeyPem", value) }
        e.message shouldBe "privateKeyPem is neither PEM text nor the base64 of PEM text"
        e.message.orEmpty() shouldNotContain value
      }
    }

    // ==================== TlsSource ====================

    "a source names both of its settings" {
      val source = TlsSource("certChain", "", "")
      source.pathSetting shouldBe "certChainFilePath"
      source.pemSetting shouldBe "certChainPem"
      source.settings shouldBe "certChainFilePath or certChainPem"
      source.isGiven.shouldBeFalse()
    }

    "a source is given by a path or by a PEM" {
      TlsSource("certChain", CA_FILE, "").isGiven.shouldBeTrue()
      TlsSource("certChain", "", CA_PEM).isGiven.shouldBeTrue()
    }

    "a file and a PEM for the same material are refused" {
      val e = shouldThrow<IllegalArgumentException> { TlsSource("certChain", CA_FILE, CA_PEM).requireOneSource() }
      e.message shouldBe "Set certChainFilePath or certChainPem, not both"
    }

    "a source's stream reads the PEM, or the file, or is absent" {
      TlsSource("x", "", CA_PEM).withStream { it.readBytes().decodeToString() } shouldBe CA_PEM
      TlsSource("x", CA_FILE, "").withStream { it.readBytes().decodeToString() } shouldBe File(CA_FILE).readText()
      TlsSource("x", "", "").withStream { it.readBytes() }.shouldBeNull()
    }

    // As common-utils' file-based builders do, a path is trimmed before it is opened.
    "a source's path is trimmed" {
      val source = TlsSource("x", "  $CA_FILE \n", "")
      source.withStream { it.readBytes().decodeToString() } shouldBe File(CA_FILE).readText()
    }

    "a source whose file doesn't exist is refused" {
      val e = shouldThrow<IllegalArgumentException> { TlsSource("x", MISSING_FILE, "").withStream { it.read() } }
      e.message shouldBe "File \"$MISSING_FILE\" does not exist"
    }

    // ==================== TLS contexts ====================

    "a server context from PEM is server-side, with mutual auth only when a trust collection is given" {
      val certChain = pemSource("certChain", SERVER_CERT)
      val privateKey = pemSource("privateKey", SERVER_KEY)
      val oneWay = TlsMaterial.serverContext(certChain, privateKey, none("trustCertCollection"))
      oneWay.sslContext.shouldNotBeNull().isServer.shouldBeTrue()
      oneWay.mutualAuth.shouldBeFalse()

      val mutual = TlsMaterial.serverContext(certChain, privateKey, pemSource("trustCertCollection", CA_FILE))
      mutual.sslContext.shouldNotBeNull().isServer.shouldBeTrue()
      mutual.mutualAuth.shouldBeTrue()
    }

    "a client context from PEM is client-side, with mutual auth only when a certificate and key are given" {
      val trust = pemSource("trustCertCollection", CA_FILE)
      val oneWay = TlsMaterial.clientContext(none("certChain"), none("privateKey"), trust)
      oneWay.sslContext.shouldNotBeNull().isClient.shouldBeTrue()
      oneWay.mutualAuth.shouldBeFalse()

      val mutual =
        TlsMaterial.clientContext(pemSource("certChain", CLIENT_CERT), pemSource("privateKey", CLIENT_KEY), trust)
      mutual.sslContext.shouldNotBeNull().isClient.shouldBeTrue()
      mutual.mutualAuth.shouldBeTrue()
    }

    // A PEM for one piece and a file for another is allowed: each piece is read the way it is given.
    "a context mixes PEM and file material" {
      val context =
        TlsMaterial.serverContext(
          pemSource("certChain", SERVER_CERT),
          TlsSource("privateKey", SERVER_KEY, ""),
          TlsSource("trustCertCollection", CA_FILE, ""),
        )
      context.sslContext.shouldNotBeNull().isServer.shouldBeTrue()
      context.mutualAuth.shouldBeTrue()
    }

    // Material given only as files keeps the path it always took, through common-utils.
    "with no PEM, the contexts come from common-utils' file-based builders" {
      val serverContext = mockk<TlsContext>()
      val clientContext = mockk<TlsContext>()
      mockkObject(TlsUtils)
      try {
        every { TlsUtils.buildServerTlsContext(SERVER_CERT, SERVER_KEY, "") } returns serverContext
        every { TlsUtils.buildClientTlsContext("", "", CA_FILE) } returns clientContext

        TlsMaterial.serverContext(
          TlsSource("certChain", SERVER_CERT, ""),
          TlsSource("privateKey", SERVER_KEY, ""),
          none("trustCertCollection"),
        ) shouldBe serverContext
        TlsMaterial.clientContext(
          none("certChain"),
          none("privateKey"),
          TlsSource("trustCertCollection", CA_FILE, ""),
        ) shouldBe clientContext

        verify(exactly = 1) { TlsUtils.buildServerTlsContext(SERVER_CERT, SERVER_KEY, "") }
        verify(exactly = 1) { TlsUtils.buildClientTlsContext("", "", CA_FILE) }
      } finally {
        unmockkObject(TlsUtils)
      }
    }

    "a server context needs both a certificate chain and a private key" {
      val noKey =
        shouldThrow<IllegalArgumentException> {
          TlsMaterial.serverContext(
            pemSource("certChain", SERVER_CERT),
            none("privateKey"),
            none("trustCertCollection"),
          )
        }
      noKey.message shouldBe "Server privateKeyFilePath or privateKeyPem is required for TLS"
    }

    // PEM armor around content that isn't a certificate fails as a malformed file does, without the content.
    "PEM armor around something that isn't a certificate is refused without the content in the message" {
      val bogus = "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----"
      val e =
        shouldThrow<IllegalArgumentException> {
          TlsMaterial.clientContext(none("certChain"), none("privateKey"), TlsSource("trustCertCollection", "", bogus))
        }
      e.message.orEmpty() shouldNotContain "bm90IGEgY2VydGlmaWNhdGU="
    }

    // ==================== Options ====================

    // -D, the config, and the env vars all reach the same resolution; -D is the one a test can set.
    "the proxy takes its certificate and key as base64 PEM, and turns TLS on" {
      val options =
        proxyOptions(
          [
            "-Dproxy.tls.certChainPem=${base64(read(SERVER_CERT))}",
            "-Dproxy.tls.privateKeyPem=${base64(read(SERVER_KEY))}",
          ],
        )
      options.isTlsEnabled.shouldBeTrue()
      options.hasTrustCertCollection.shouldBeFalse()
      options.certChainPem shouldBe read(SERVER_CERT).trim()
      options.certChainFilePath shouldBe ""
    }

    "the agent takes its trust collection as PEM, which turns TLS on without a certificate of its own" {
      val trustArg = "-Dagent.tls.trustCertCollectionPem=${base64(read(CA_FILE))}"
      val options = agentOptions(["--name", "a", "--proxy", "host", trustArg], false)
      options.isTlsEnabled.shouldBeFalse()
      options.hasTrustCertCollection.shouldBeTrue()
      options.trustCertCollectionSource.withStream { it.readBytes().decodeToString() } shouldBe read(CA_FILE).trim()
    }

    // A private key in the log would be a leak; only whether each PEM is set is logged.
    "a PEM setting is logged as set, never by its value" {
      val keyBase64 = base64(read(SERVER_KEY))
      val keyBody = read(SERVER_KEY).lines()[1]
      val events =
        captureLogs("io.prometheus", Level.INFO) {
          val certArg = "-Dproxy.tls.certChainPem=${base64(read(SERVER_CERT))}"
          proxyOptions([certArg, "-Dproxy.tls.privateKeyPem=$keyBase64"])
        }
      val lines = events.map { it.formattedMessage }
      lines.any { it == "privateKeyPem: (set)" }.shouldBeTrue()
      lines.any { it == "trustCertCollectionPem: (none)" }.shouldBeTrue()
      lines.forEach {
        it shouldNotContain keyBase64
        it shouldNotContain keyBody
      }
    }

    "a file and a PEM for the same material are refused by the proxy's options" {
      val e =
        shouldThrow<IllegalArgumentException> {
          proxyOptions(
            [
              "--cert",
              SERVER_CERT,
              "--key",
              SERVER_KEY,
              "-Dproxy.tls.certChainPem=${base64(read(SERVER_CERT))}",
            ],
          )
        }
      e.message shouldBe "Set certChainFilePath or certChainPem, not both"
    }

    "a PEM certificate without a key is incomplete TLS" {
      val certArg = "-Dproxy.tls.certChainPem=${base64(read(SERVER_CERT))}"
      val e = shouldThrow<IllegalArgumentException> { proxyOptions([certArg]) }
      e.message shouldContain "private key file is missing"
      e.message shouldContain "privateKeyPem"
    }

    // Embedded, a PEM the agent can't read is a ConfigLoadException for the host to catch, without the value.
    "an embedded agent with an unreadable PEM throws ConfigLoadException without the value" {
      val e =
        shouldThrow<ConfigLoadException> {
          agentOptions(["--name", "a", "--proxy", "host", "-Dagent.tls.trustCertCollectionPem=$GARBAGE"], false)
        }
      e.message shouldContain "trustCertCollectionPem is neither PEM text nor the base64 of PEM text"
      generateSequence<Throwable>(e) { it.cause }.forEach { it.message.orEmpty() shouldNotContain GARBAGE }
    }

    "an embedded agent with a file and a PEM for the same material throws ConfigLoadException" {
      val e =
        shouldThrow<ConfigLoadException> {
          val trustArg = "-Dagent.tls.trustCertCollectionPem=${base64(read(CA_FILE))}"
          agentOptions(["--name", "a", "--proxy", "host", "--trust", CA_FILE, trustArg], false)
        }
      e.message shouldContain "Set trustCertCollectionFilePath or trustCertCollectionPem, not both"
      e.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }
  }

  companion object {
    private const val CA_FILE = "testing/certs/ca.pem"
    private const val SERVER_CERT = "testing/certs/server1.pem"
    private const val SERVER_KEY = "testing/certs/server1.key"
    private const val CLIENT_CERT = "testing/certs/client.pem"
    private const val CLIENT_KEY = "testing/certs/client.key"
    private const val MISSING_FILE = "/no/such/dir/missing.pem"
    private const val GARBAGE = "definitely-not-a-certificate"

    private val CA_PEM = read(CA_FILE).trim()

    private fun read(path: String) = File(path).readText()

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    // The material in [file], given as PEM text.
    private fun pemSource(
      name: String,
      file: String,
    ) = TlsSource(name, "", TlsMaterial.pemText("${name}Pem", read(file)))

    private fun none(name: String) = TlsSource(name, "", "")
  }
}
