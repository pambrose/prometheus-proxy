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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.dashboard.DashboardPage.barEnd
import io.prometheus.common.dashboard.DashboardPage.logoutForm
import io.prometheus.common.dashboard.DashboardPage.themeButton
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.math.pow

/**
 * The dashboards' theme: auto, which follows the system's, light, or dark, moved through with the bar's button. Every
 * dark rule applies under the system's dark preference unless the page is set to light, and whenever it is set to dark;
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

    // A hovered list row's ground, --surface-3, is a step closer to --ink-3 than --surface-2 is, and under 4.5:1 in
    // both themes, so the row's quiet line steps up to --ink-2 there, which clears it in light, dark, and set to dark.
    "a hovered list row's address line should step up to a colour that clears 4.5:1 on its ground" {
      DashboardCss.CSS shouldContain ".md-row:hover .host { color:var(--ink-2); }"
      val inks = tokenValues("--ink-2")
      val grounds = tokenValues("--surface-3")
      inks.size shouldBe 3
      grounds.size shouldBe inks.size
      inks.zip(grounds).forEach { (ink, ground) -> contrast(ink, ground) shouldBeGreaterThan 4.5 }
    }

    // dashboard-constraints.md: every text colour also lands on --surface-2, under the tables and section headers, the
    // tighter ground where all 29 contrast failures fixed in 4.0.1 were. Light, dark, and set to dark each.
    "every text colour should clear 4.5:1 on --surface-2, in every token block" {
      val grounds = tokenValues("--surface-2")
      grounds.size shouldBe 3
      listOf("--ink", "--ink-2", "--ink-3", "--ok", "--warn", "--crit").forEach { token ->
        val inks = tokenValues(token)
        inks.size shouldBe grounds.size
        inks.zip(grounds).forEach { (ink, ground) ->
          withClue("$token $ink on $ground") { contrast(ink, ground) shouldBeGreaterThan 4.5 }
        }
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

    // One button, which takes the bar's end's room of one, not three. Its name and tooltip, which name the theme in
    // force and the next, are the page's script's, which reads the theme the head applied (see DashboardScriptTest),
    // since the server can't know it; until then it is named for what it sets.
    "the theme button should be one button, named Theme until the page's script names the theme in force" {
      createHTML(prettyPrint = false).div { themeButton() }.apply {
        Regex("<button[^>]*>").findAll(this).map { it.value }.toList() shouldBe
          listOf(
            """<button type="button" class="theme" id="${DashboardPage.THEME_BUTTON_ID}" aria-label="Theme">""",
          )
        this shouldNotContain "aria-pressed"
        this shouldNotContain "title="
        this shouldNotContain "role=\"group\""
      }
    }

    // One box for the bar's end, which sizes its controls alike, so Log out is as tall as the theme button however
    // tall a coarse pointer makes it.
    "the bar's end should hold the theme button, then Log out with a login, in one box that sizes them alike" {
      createHTML(prettyPrint = false).div { barEnd(loginEnabled = true, routeBase = "/dashboard") }.apply {
        val button = """<button type="button" class="theme" id="${DashboardPage.THEME_BUTTON_ID}""""
        this shouldContain """<div class="bar-end">$button"""
        substringAfter(DashboardPage.THEME_BUTTON_ID) shouldContain """action="/dashboard/logout""""
      }
      createHTML().div { barEnd(loginEnabled = false, routeBase = "/dashboard") } shouldNotContain "logout"
      DashboardCss.CSS.apply {
        this shouldContain ".bar-end { display:flex; align-items:stretch;"
        this shouldNotContain "min-height:26px"
      }
    }

    // An exit icon rather than words, named for assistive tech and in its tooltip, its glyph a mask as the theme
    // button's is, which keeps its paint in forced colours as its does.
    "Log out should be an exit icon, named Log out" {
      createHTML(prettyPrint = false).div { logoutForm(routeBase = "/dashboard") }.apply {
        val label = DashboardPage.LOGOUT_LABEL
        this shouldContain """<button type="submit" aria-label="$label" title="$label"></button>"""
      }
      DashboardCss.CSS.apply {
        this shouldContain Regex("""\.logout button \{[^}]*--icon:url\("data:image/svg\+xml,""")
        this shouldContain Regex("""\.logout button::before,\s*\.theme::before \{\s*content:"";""")
        val forced = """\{\s*forced-color-adjust:none; background:ButtonText;"""
        this shouldContain Regex("""\.logout button::before,\s*\.theme::before $forced""")
      }
    }

    // From <html data-theme>, which the head sets before the page draws, so the icon is right before the page's script
    // runs: auto's with no data-theme, then light's and dark's, each a different glyph.
    "the theme button should show the theme in force's icon from the page's own data-theme" {
      DashboardCss.CSS.apply {
        val icon = """\s*--icon:url\("data:image/svg\+xml,([^"]+)"\); \}"""
        val auto = Regex("""\n\s*\.theme \{ height:26px;$icon""").find(this)?.groupValues?.get(1)
        val light = Regex(""":root\[data-theme="light"] \.theme \{$icon""").find(this)?.groupValues?.get(1)
        val dark = Regex(""":root\[data-theme="dark"] \.theme \{$icon""").find(this)?.groupValues?.get(1)
        listOfNotNull(auto, light, dark).toSet().size shouldBe 3
        // The theme in force is the icon itself, so no button is marked as the current tab is.
        this shouldNotContain ".theme-auto"
        this shouldNotContain "border-bottom-color:var(--accent)"
        // Its glyph is the icon buttons', and keeps its paint in forced colours as theirs do; a coarse pointer makes
        // it a 44px target inside its border, as wide as Log out.
        this shouldContain Regex("""\.theme::before \{\s*content:"";""")
        this shouldContain Regex("""\.theme::before \{\s*forced-color-adjust:none; background:ButtonText;""")
        this shouldContain ".theme { width:46px; height:46px; }"
        this shouldContain ".logout button { width:46px; }"
      }
    }
  }
}

// Each value [token] takes in DashboardCss.TOKENS, light first, then the two dark selectors'.
private fun tokenValues(token: String): List<String> =
  Regex("$token:(#[0-9a-f]{6})").findAll(DashboardCss.TOKENS).map { it.groupValues[1] }.toList()

// WCAG 2's contrast ratio of two #rrggbb colours.
private fun contrast(
  first: String,
  second: String,
): Double {
  val (lighter, darker) = listOf(luminance(first), luminance(second)).sortedDescending()
  return (lighter + 0.05) / (darker + 0.05)
}

private fun luminance(hex: String): Double {
  val (red, green, blue) =
    listOf(1, 3, 5)
      .map { hex.substring(it, it + 2).toInt(16) / 255.0 }
      .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}
