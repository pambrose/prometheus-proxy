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
import io.prometheus.common.dashboard.DashboardPage.barEnd
import io.prometheus.common.dashboard.DashboardPage.logoutForm
import io.prometheus.common.dashboard.DashboardPage.themePicker
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * The dashboards' theme: auto, which follows the system's, light, or dark, chosen with the bar's button. Every dark
 * rule applies under the system's dark preference unless the page is set to light, and whenever it is set to dark;
 * the page's head applies a stored choice before it draws.
 */
class DashboardThemeTest : StringSpec() {
  init {
    "a dark rule should apply under the system's dark preference unless set to light, and whenever set to dark" {
      DashboardCss.darkRule(":root", "--ink:#fff;") shouldBe
        """
        @media (prefers-color-scheme: dark) {
          :root:not([data-theme="light"]) { --ink:#fff; }
        }
        :root[data-theme="dark"] { --ink:#fff; }
        """.trimIndent()
      DashboardCss.darkRule(".links a.icon-x", "--brand:#000;") shouldBe
        """
        @media (prefers-color-scheme: dark) {
          :root:not([data-theme="light"]) .links a.icon-x { --brand:#000; }
        }
        :root[data-theme="dark"] .links a.icon-x { --brand:#000; }
        """.trimIndent()
    }

    // The tokens are the one place each colour is defined, so both dark selectors carry the same values.
    "the tokens should carry the dark colours for both, and the browser's own widgets should follow the choice" {
      DashboardCss.TOKENS.apply {
        this shouldContain """:root:not([data-theme="light"]) { --bg:#0d1015;"""
        this shouldContain """:root[data-theme="dark"] { --bg:#0d1015;"""
        this shouldContain """:root[data-theme="light"] { color-scheme:light; }"""
        this shouldContain ":root { color-scheme:light dark;"
      }
    }

    // In the head, so a stored choice applies before the page draws, and only a choice it knows.
    "the head's script should apply a stored light or dark choice, and nothing else" {
      DashboardPage.THEME_SCRIPT.apply {
        this shouldContain "localStorage.getItem('${DashboardPage.THEME_KEY}')"
        this shouldContain "t === 'light' || t === 'dark'"
        this shouldContain "setAttribute('data-theme', t)"
      }
    }

    // Light, dark, and auto, each a press away, in the order the system's own appearance settings list them. Each is
    // named for its theme; which is pressed, and the tooltips, are the page's script's, which reads the theme the head
    // applied (see DashboardScriptTest), since the server can't know it.
    "the theme picker should offer light, dark, and auto, each a button named for its theme" {
      createHTML().div { themePicker() }.apply {
        this shouldContain
          """<div class="theme" id="${DashboardPage.THEME_PICKER_ID}" role="group" aria-label="Theme">"""
        Regex("<button[^>]*>").findAll(this).map { it.value }.toList() shouldBe
          listOf("light" to "Light", "dark" to "Dark", "auto" to "Auto").map { (theme, name) ->
            val choice = """${DashboardPage.THEME_CHOICE_ATTR}="$theme""""
            """<button type="button" class="theme-$theme" $choice aria-label="$name">"""
          }
        this shouldNotContain "aria-pressed"
        this shouldNotContain "title="
      }
    }

    // One box for the bar's end, which sizes its controls alike, so Log out is as tall as the picker however tall a
    // coarse pointer makes it.
    "the bar's end should hold the theme picker, then Log out with a login, in one box that sizes them alike" {
      createHTML(prettyPrint = false).div { barEnd(loginEnabled = true, routeBase = "/dashboard") }.apply {
        this shouldContain """<div class="bar-end"><div class="theme" id="${DashboardPage.THEME_PICKER_ID}""""
        substringAfter(DashboardPage.THEME_PICKER_ID) shouldContain """action="/dashboard/logout""""
      }
      createHTML().div { barEnd(loginEnabled = false, routeBase = "/dashboard") } shouldNotContain "logout"
      DashboardCss.CSS.apply {
        this shouldContain ".bar-end { display:flex; align-items:stretch;"
        this shouldNotContain "min-height:26px"
      }
    }

    // An exit icon rather than words, named for assistive tech and in its tooltip, its glyph a mask as the picker's
    // are, which keeps its paint in forced colours as theirs does.
    "Log out should be an exit icon, named Log out" {
      createHTML(prettyPrint = false).div { logoutForm(routeBase = "/dashboard") }.apply {
        val label = DashboardPage.LOGOUT_LABEL
        this shouldContain """<button type="submit" aria-label="$label" title="$label"></button>"""
      }
      DashboardCss.CSS.apply {
        this shouldContain Regex("""\.logout button \{[^}]*--icon:url\("data:image/svg\+xml,""")
        this shouldContain Regex("""\.logout button::before,\s*\.theme button::before \{\s*content:"";""")
        val forced = """\{\s*forced-color-adjust:none; background:ButtonText;"""
        this shouldContain Regex("""\.logout button::before,\s*\.theme button::before $forced""")
      }
    }

    // From <html data-theme>, which the head sets before the page draws, so the mark is right before the page's script
    // runs; and the current tab's mark, an accent line along the bottom, so a tonal step isn't the only sign.
    "the picker should mark the theme in force from the page's own data-theme, as the current tab is marked" {
      DashboardCss.CSS.apply {
        this shouldContain """:root:not([data-theme]) .theme-auto, :root[data-theme="light"] .theme-light,"""
        this shouldContain
          """:root[data-theme="dark"] .theme-dark { color:var(--ink); background-color:var(--surface-2);"""
        this shouldContain "border-bottom-color:var(--accent); }"
        // Its glyphs are the icon buttons', and keep their paint in forced colours as theirs do.
        this shouldContain Regex("""\.theme button::before \{\s*content:"";""")
        this shouldContain Regex("""\.theme button::before \{\s*forced-color-adjust:none; background:ButtonText;""")
      }
    }
  }
}
