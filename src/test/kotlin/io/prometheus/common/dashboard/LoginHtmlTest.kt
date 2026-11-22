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

package io.prometheus.common.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.dashboard.LoginHtml.renderLogin
import kotlinx.html.html
import kotlinx.html.stream.createHTML

/** [LoginHtml]: a dashboard's login page. The routes that serve it are covered in `ProxyWebDashboardLoginTest`. */
class LoginHtmlTest : StringSpec() {
  private fun page(
    heading: String = HEADING,
    routeBase: String = "/dashboard",
    next: String = "/dashboard",
    username: String = "",
    alert: String? = null,
  ) = createHTML().html { renderLogin(LoginHtml.Form(heading, routeBase, next, username, alert)) }

  init {
    "the page has the heading, a form posting to the login route, and labeled fields" {
      page().apply {
        this shouldContain "<h1>$HEADING</h1>"
        this shouldContain "<title>Log in · $HEADING</title>"
        this shouldContain """action="/dashboard/login""""
        this shouldContain """method="post""""
        this shouldContain """name="${LoginHtml.USERNAME_FIELD}""""
        this shouldContain """autocomplete="username""""
        this shouldContain """name="${LoginHtml.PASSWORD_FIELD}""""
        this shouldContain """type="password""""
        this shouldContain """autocomplete="current-password""""
        this shouldContain """<label for="${LoginHtml.USERNAME_FIELD}">Username</label>"""
        this shouldContain """<label for="${LoginHtml.PASSWORD_FIELD}">Password</label>"""
        this shouldContain """name="${LoginHtml.NEXT_FIELD}" value="/dashboard""""
        this shouldContain """maxlength="${LoginHtml.MAX_FIELD_LENGTH}""""
        Regex("""<button[^>]*type="submit"[^>]*>Log in</button>""").findAll(this).count() shouldBe 1
      }
    }

    // Each dashboard names itself: the proxy's and the agent's share the page.
    "the page should carry the heading it is given, escaped" {
      page(heading = "Prometheus <Agent> Dashboard").apply {
        this shouldContain "<h1>Prometheus &lt;Agent&gt; Dashboard</h1>"
        this shouldContain "<title>Log in · Prometheus &lt;Agent&gt; Dashboard</title>"
        this shouldNotContain HEADING
      }
    }

    "a failed login shows the alert and keeps the username, with the password focused" {
      page(username = "ops", alert = LoginHtml.FAILED).apply {
        this shouldContain """role="alert""""
        this shouldContain LoginHtml.FAILED
        this shouldContain """value="ops""""
        Regex("""<input[^>]*name="password"[^>]*autofocus""").containsMatchIn(this) shouldBe true
        Regex("""<input[^>]*name="username"[^>]*autofocus""").containsMatchIn(this) shouldBe false
      }
    }

    // Past the limit on wrong logins, the page says so, and why the right password won't do for now.
    "too many wrong logins should show the alert that says so" {
      page(username = "ops", alert = LoginHtml.LIMITED).apply {
        this shouldContain """role="alert""""
        this shouldContain LoginHtml.LIMITED
        this shouldNotContain LoginHtml.FAILED
      }
    }

    "without a failure there is no alert, and the username is focused" {
      page().apply {
        this shouldNotContain """role="alert""""
        this shouldNotContain LoginHtml.FAILED
        Regex("""<input[^>]*name="username"[^>]*autofocus""").containsMatchIn(this) shouldBe true
      }
    }

    "the next path and the username are escaped" {
      page(next = "/dashboard/paths?q=\"><script>x</script>", username = "\"><b>").apply {
        this shouldNotContain "<script>x"
        this shouldNotContain "<b>"
        this shouldContain "&quot;&gt;&lt;script&gt;"
      }
    }

    "at a root mount the form posts to /login" {
      page(routeBase = "", next = "/").also { it shouldContain """action="/login"""" }
    }

    // Its one script is the theme's, inline, which loads nothing.
    "the page loads nothing from anywhere" {
      page().apply {
        Regex("<script").findAll(this).count() shouldBe 1
        this shouldContain "<script>${DashboardPage.THEME_SCRIPT}</script>"
        this shouldNotContain "src="
        this shouldNotContain "http://"
        this shouldNotContain "https://"
      }
    }

    // Above the middle, as Grafana's sits: the space left over goes 40% above the form and 60% below.
    "the form should sit 40% of the way down the page, not in the middle" {
      page().apply {
        this shouldContain "body.login::before { content:\"\"; flex:2; }"
        this shouldContain "body.login::after { content:\"\"; flex:3; }"
        this shouldContain "flex-direction:column;"
      }
    }

    "its colors are the dashboard's tokens, in both themes" {
      page().apply {
        this shouldContain DashboardCss.TOKENS
        this shouldContain "prefers-color-scheme: dark"
        this shouldContain "var(--accent)"
      }
    }

    // The dashboard's theme, as its button left it, so the login page doesn't flash the other one before the dashboard.
    "it should apply the theme the dashboard was set to, in its head" {
      page().apply {
        substringBefore("</head>") shouldContain DashboardPage.THEME_SCRIPT
        this shouldContain DashboardCss.darkRule(":root", "--on-accent:var(--bg);")
      }
    }
  }

  companion object {
    private const val HEADING = "Prometheus Test Dashboard"
  }
}
