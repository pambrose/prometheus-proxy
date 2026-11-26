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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * A screen of the proxy's `/screens` page, [Screen]: its id, taken from its name, and each refusal at startup, which
 * names the entry and the problem, so a list the page can't use never reaches it.
 */
class ScreenTest : StringSpec() {
  private val grafana = Screen.Entry(name = "Grafana", url = "http://g:3000", start = "/d/x?kiosk")

  // The message a list is refused with.
  private fun refusal(vararg entries: Screen.Entry): String =
    shouldThrow<IllegalArgumentException> { Screen.parseAll(entries.toList()) }.message.orEmpty()

  init {
    "a screen's id should be its name in lowercase, each run of other characters a dash" {
      Screen.idOf("agent-east") shouldBe "agent-east"
      Screen.idOf("Prometheus") shouldBe "prometheus"
      Screen.idOf("  Ops / On-Call!  ") shouldBe "ops-on-call"
    }

    "start should default to empty, and a listed screen keep its order" {
      Screen
        .parseAll([grafana, Screen.Entry(name = "agent-east", url = "http://a:8095/dashboard")])
        .map { it.id to it.start } shouldBe ["grafana" to "/d/x?kiosk", "agent-east" to ""]
    }

    // One join for the page, the Open in a tab link, and the startup log, so none doubles a slash.
    "a screen should start at its url and start joined with one slash, and be logged so" {
      Screen.parseAll([grafana.copy(url = "http://g:3000/", start = "/alerts")]).single().apply {
        startUrl shouldBe "http://g:3000/alerts"
        logged shouldBe "Grafana (http://g:3000/alerts)"
      }
      Screen.parseAll([grafana.copy(url = "http://g:3000/", start = "?kiosk")]).single().startUrl shouldBe
        "http://g:3000/?kiosk"
      Screen.parseAll([grafana.copy(start = "")]).single().startUrl shouldBe "http://g:3000"
    }

    "a parsed screen should keep its name and url as listed" {
      Screen.parseAll([grafana]).single() shouldBe
        Screen(id = "grafana", name = "Grafana", url = "http://g:3000", start = "/d/x?kiosk")
    }

    "no screens should parse to an empty list" {
      Screen.parseAll(emptyList()) shouldBe emptyList()
    }

    // ==================== Names and ids ====================

    "a blank name should be refused, naming the entry by its position" {
      refusal(grafana, grafana.copy(name = "  ")) shouldBe "proxy.dashboard.screens entry 2 has a blank name"
    }

    "a name with no letter or digit should be refused, since it gives no id" {
      refusal(grafana.copy(name = "!!")) shouldBe
        "proxy.dashboard.screens screen '!!' has no letter or digit for its id"
    }

    "a name longer than 64 characters should be refused" {
      val name = "a".repeat(Screen.MAX_NAME_LENGTH + 1)
      refusal(grafana.copy(name = name)) shouldBe
        "proxy.dashboard.screens screen '$name' has a name longer than 64 characters"
      Screen.parseAll([grafana.copy(name = "a".repeat(Screen.MAX_NAME_LENGTH))]) shouldHaveSize 1
    }

    // Two tabs with one id would make a link into the page open whichever came first.
    "two names with the same id should be refused, naming both" {
      refusal(grafana, grafana.copy(name = "grafana!")) shouldBe
        "proxy.dashboard.screens screen 'grafana!' has the id 'grafana', as 'Grafana' does"
    }

    "the ids the page keeps for itself should be refused" {
      refusal(grafana.copy(name = "Proxy")) shouldBe
        "proxy.dashboard.screens screen 'Proxy' has the id 'proxy', which the page keeps for its own tab"
      refusal(grafana.copy(name = "All")) shouldBe
        "proxy.dashboard.screens screen 'All' has the id 'all', which the page keeps for its grid"
    }

    // ==================== URLs ====================

    // An unset ${?NAME} leaves the url empty, which the schema allows so the refusal can name the entry.
    "an empty url should be refused, asking whether the variable it reads is set" {
      refusal(grafana.copy(url = "")) shouldBe
        "proxy.dashboard.screens screen 'Grafana' has no url; is the variable it reads set?"
    }

    "a url that isn't an absolute http or https URL should be refused" {
      ["ftp://x", "localhost:3000", "javascript:alert(1)", "/grafana", "http://", "http://g g:3000", "http://:8080"]
        .forEach { url ->
        refusal(grafana.copy(url = url)) shouldBe
          "proxy.dashboard.screens screen 'Grafana' has a url that isn't an absolute http or https URL: $url"
        }
      // A host the URI class can't parse as a server, such as one with an underscore, is still a host.
      Screen.parseAll(
        [grafana.copy(url = "HTTPS://g.example.com"), grafana.copy(name = "e", url = "http://agent_east:8095")],
      )
        .shouldHaveSize(2)
    }

    // The page's HTML carries each url, for anyone who can read the dashboard.
    "a url with a username or password should be refused" {
      ["http://u:p@g:3000", "http://u@g:3000", "http://u:p@g_x:3000"].forEach { url ->
        refusal(grafana.copy(url = url)) shouldBe
          "proxy.dashboard.screens screen 'Grafana' has a username or password in its url, which the page would " +
          "show to anyone who can read the dashboard"
      }
    }

    "a start that doesn't begin with / or ? should be refused" {
      refusal(grafana.copy(start = "d/x")) shouldBe
        "proxy.dashboard.screens screen 'Grafana' has a start that doesn't begin with / or ?: d/x"
      Screen.parseAll([grafana.copy(start = "?orgId=1")]).single().start shouldBe "?orgId=1"
    }

    // ==================== The list ====================

    "more than 32 screens should be refused" {
      val screens = (1..Screen.MAX_SCREENS + 1).map { grafana.copy(name = "screen $it") }
      refusal(*screens.toTypedArray()) shouldBe "proxy.dashboard.screens lists 33 screens; at most 32"
      Screen.parseAll(screens.take(Screen.MAX_SCREENS)) shouldHaveSize Screen.MAX_SCREENS
    }
  }
}
