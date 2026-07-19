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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.proxy.AgentAuthManager.AuthEntry
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME

class AgentAuthManagerTest : StringSpec() {
  private fun identity(vararg patterns: String): AgentIdentity =
    AgentIdentity("test", ByteArray(0), patterns.map { AgentAuthManager.globToRegex(it) })

  init {
    // ---- glob / isAuthorized semantics ----

    "an identity with no patterns authorizes every path" {
      val identity = identity()
      identity.isAuthorized("anything").shouldBeTrue()
      identity.isAuthorized("team_a_metrics").shouldBeTrue()
    }

    "a trailing-star pattern matches by prefix" {
      val identity = identity("team_a_*")
      identity.isAuthorized("team_a_metrics").shouldBeTrue()
      identity.isAuthorized("team_a_").shouldBeTrue()
      identity.isAuthorized("team_b_metrics").shouldBeFalse()
    }

    "an exact pattern matches only that path" {
      val identity = identity("metrics")
      identity.isAuthorized("metrics").shouldBeTrue()
      identity.isAuthorized("metrics2").shouldBeFalse()
      identity.isAuthorized("ametrics").shouldBeFalse()
    }

    "isAuthorized normalizes a leading slash before matching" {
      identity("team_a_*").isAuthorized("/team_a_metrics").shouldBeTrue()
    }

    "a question-mark matches exactly one character" {
      val identity = identity("team_?")
      identity.isAuthorized("team_a").shouldBeTrue()
      identity.isAuthorized("team_ab").shouldBeFalse()
      identity.isAuthorized("team_").shouldBeFalse()
    }

    "regex metacharacters in a pattern match literally" {
      val identity = identity("app.v1")
      identity.isAuthorized("app.v1").shouldBeTrue()
      identity.isAuthorized("appxv1").shouldBeFalse()
    }

    "multiple patterns authorize a path matching any of them" {
      val identity = identity("team_a_*", "shared_metrics")
      identity.isAuthorized("team_a_x").shouldBeTrue()
      identity.isAuthorized("shared_metrics").shouldBeTrue()
      identity.isAuthorized("team_b_x").shouldBeFalse()
    }

    // ---- token resolution ----

    "resolveToken returns the matching identity" {
      val manager =
        AgentAuthManager.create(
          authEntries =
            [
              AuthEntry("team_a", "tok_a", ["team_a_*"]),
              AuthEntry("team_b", "tok_b", ["team_b_*"]),
            ],
          legacyToken = "",
        )
      manager.resolveToken("tok_a")?.name shouldBe "team_a"
      manager.resolveToken("tok_b")?.name shouldBe "team_b"
    }

    "resolveToken returns null for an unknown token" {
      val manager = AgentAuthManager.create([AuthEntry("team_a", "tok_a", ["team_a_*"])], "")
      manager.resolveToken("nope").shouldBeNull()
    }

    "resolveToken rejects a same-length but different token" {
      val manager = AgentAuthManager.create([AuthEntry("team_a", "abcdef", ["*"])], "")
      manager.resolveToken("abcxyz").shouldBeNull()
    }

    "the legacy token resolves to an allow-all identity" {
      val manager = AgentAuthManager.create(authEntries = emptyList(), legacyToken = "legacy-secret")
      val identity = manager.resolveToken("legacy-secret")
      identity?.name shouldBe LEGACY_IDENTITY_NAME
      identity?.isAuthorized("any_path")?.shouldBeTrue()
    }

    "the legacy token coexists with per-agent identities" {
      val manager =
        AgentAuthManager.create(
          authEntries = [AuthEntry("team_a", "tok_a", ["team_a_*"])],
          legacyToken = "legacy-secret",
        )
      manager.resolveToken("tok_a")?.name shouldBe "team_a"
      manager.resolveToken("legacy-secret")?.name shouldBe LEGACY_IDENTITY_NAME
      // The per-agent identity is still constrained even though the legacy identity is allow-all.
      manager.resolveToken("tok_a")?.isAuthorized("team_b_x")?.shouldBeFalse()
    }

    // ---- isEnabled ----

    "isEnabled is false when no identities and no legacy token are configured" {
      AgentAuthManager.create(emptyList(), "").isEnabled.shouldBeFalse()
    }

    "isEnabled is true when only the legacy token is configured" {
      AgentAuthManager.create(emptyList(), "legacy-secret").isEnabled.shouldBeTrue()
    }

    "isEnabled is true when per-agent identities are configured" {
      AgentAuthManager.create([AuthEntry("team_a", "tok_a", emptyList())], "").isEnabled.shouldBeTrue()
    }

    // ---- fail-fast construction ----

    "an empty identity name is rejected" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create([AuthEntry("", "tok", emptyList())], "")
      }
    }

    "an empty identity token is rejected" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create([AuthEntry("team_a", "", emptyList())], "")
      }
    }

    "duplicate identity names are rejected" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create(
          [
            AuthEntry("team_a", "tok_a", emptyList()),
            AuthEntry("team_a", "tok_b", emptyList()),
          ],
          "",
        )
      }
    }

    "a per-agent identity named like the legacy identity collides with the legacy token" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create(
          [AuthEntry(LEGACY_IDENTITY_NAME, "tok", emptyList())],
          legacyToken = "legacy-secret",
        )
      }
    }

    // resolveToken() takes the first digest match, so two identities sharing a token silently
    // collapse to whichever comes first — the second is dead config the operator never gets told
    // about.
    "duplicate identity tokens are rejected" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create(
          [
            AuthEntry("team_a", "shared", emptyList()),
            AuthEntry("team_b", "shared", emptyList()),
          ],
          "",
        )
      }
    }

    // The bite: per-agent identities are ordered ahead of the legacy identity, so reusing the legacy
    // token value for a per-agent entry silently strips the legacy allow-all scope down to that
    // entry's patterns — while the "legacy token is active as an allow-all identity" warning still
    // fires, telling the operator the opposite of what is happening.
    "a per-agent identity reusing the legacy token value is rejected" {
      shouldThrow<IllegalArgumentException> {
        AgentAuthManager.create(
          [AuthEntry("team_a", "shared", ["team_a_*"])],
          legacyToken = "shared",
        )
      }
    }
  }
}
