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
import io.prometheus.common.dashboard.DashboardPage.themeToggle
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

    "the theme button should start at auto, the default, which the page's script then shows as stored" {
      createHTML().div { themeToggle() }.apply {
        this shouldContain
          """<div class="theme"><button type="button" id="${DashboardPage.THEME_TOGGLE_ID}""""
        this shouldContain ">Theme: auto</button>"
        this shouldNotContain "data-theme"
      }
    }
  }
}
