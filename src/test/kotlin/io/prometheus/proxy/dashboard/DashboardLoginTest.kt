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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64

/**
 * [DashboardLogin]: what it reads from a request's `Authorization` header, and which headers carry its login. The
 * dashboard's routes requiring it are covered in `ProxyWebDashboardTest`.
 */
class DashboardLoginTest : StringSpec() {
  private val login = DashboardLogin(USERNAME, PASSWORD)

  // A Basic header carrying [credentials] as its UTF-8 bytes, under [scheme].
  private fun basic(
    credentials: String,
    scheme: String = "Basic",
  ) = "$scheme ${Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))}"

  init {
    "the right username and password should be accepted" {
      login.accepts(basic("$USERNAME:$PASSWORD")).shouldBeTrue()
    }

    "a request with no Authorization header should be refused" {
      login.accepts(null).shouldBeFalse()
      login.accepts("").shouldBeFalse()
    }

    "a wrong username, a wrong password, or both should be refused" {
      login.accepts(basic("root:$PASSWORD")).shouldBeFalse()
      login.accepts(basic("$USERNAME:wrong")).shouldBeFalse()
      login.accepts(basic("root:wrong")).shouldBeFalse()
      // A prefix of either, or either with more after it, is as wrong as anything else.
      login.accepts(basic("$USERNAME:${PASSWORD.dropLast(1)}")).shouldBeFalse()
      login.accepts(basic("$USERNAME:${PASSWORD}x")).shouldBeFalse()
      login.accepts(basic("${USERNAME}x:$PASSWORD")).shouldBeFalse()
      login.accepts(basic(":")).shouldBeFalse()
    }

    "the scheme's name should match in any case, and nothing but Basic should be read" {
      login.accepts(basic("$USERNAME:$PASSWORD", scheme = "basic")).shouldBeTrue()
      login.accepts(basic("$USERNAME:$PASSWORD", scheme = "BASIC")).shouldBeTrue()
      login.accepts(basic("$USERNAME:$PASSWORD", scheme = "Bearer")).shouldBeFalse()
      login.accepts(basic("$USERNAME:$PASSWORD", scheme = "Basicx")).shouldBeFalse()
      login.accepts("Basic").shouldBeFalse()
    }

    "a value that isn't base64, or that has no colon, should be refused, not thrown on" {
      login.accepts("Basic not*base64!").shouldBeFalse()
      login.accepts(basic("${USERNAME}$PASSWORD")).shouldBeFalse()
      DashboardLogin.credentials("Basic not*base64!").shouldBeNull()
      DashboardLogin.credentials(basic("no colon")).shouldBeNull()
    }

    // Basic Auth splits at the first colon, so a password may contain one; ProxyOptions refuses a username with one.
    "the credentials should split at the first colon, so the password may contain colons" {
      val credentials = DashboardLogin.credentials(basic("admin:a:b:c")).shouldNotBeNull()
      String(credentials.first, Charsets.UTF_8) shouldBe "admin"
      String(credentials.second, Charsets.UTF_8) shouldBe "a:b:c"
      DashboardLogin("admin", "a:b:c").accepts(basic("admin:a:b:c")).shouldBeTrue()
    }

    // A browser sends a non-ASCII Basic password as its UTF-8 bytes.
    "a non-ASCII username and password should be compared as UTF-8" {
      val unicode = DashboardLogin("opérateur", "pässwörd-日本")
      unicode.accepts(basic("opérateur:pässwörd-日本")).shouldBeTrue()
      unicode.accepts(basic("operateur:passwort-日本")).shouldBeFalse()
    }

    // The login page's form, which the browser sends as text.
    "a form's username and password should be accepted only when both match" {
      login.accepts(USERNAME, PASSWORD).shouldBeTrue()
      login.accepts("root", PASSWORD).shouldBeFalse()
      login.accepts(USERNAME, "wrong").shouldBeFalse()
      login.accepts(USERNAME, "").shouldBeFalse()
      login.accepts("", "").shouldBeFalse()
      login.accepts(USERNAME, PASSWORD.dropLast(1)).shouldBeFalse()
      DashboardLogin("opérateur", "pässwörd-日本").accepts("opérateur", "pässwörd-日本").shouldBeTrue()
    }

    "whitespace around the header's value should be ignored" {
      login.accepts("  ${basic("$USERNAME:$PASSWORD")}  ").shouldBeTrue()
      login.accepts("Basic   ${basic("$USERNAME:$PASSWORD").removePrefix("Basic ")}").shouldBeTrue()
    }

    "toString should show neither the username nor the password" {
      login.toString().apply {
        this shouldNotContain USERNAME
        this shouldNotContain PASSWORD
      }
    }
  }

  private companion object {
    const val USERNAME = "operator"
    const val PASSWORD = "s3cret-Passw0rd"
  }
}
