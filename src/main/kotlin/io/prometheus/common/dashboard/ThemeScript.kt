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

package io.prometheus.common.dashboard

import io.prometheus.common.dashboard.DashboardPage.ANNOUNCER_ID
import io.prometheus.common.dashboard.DashboardPage.THEME_BUTTON_ID
import io.prometheus.common.dashboard.DashboardPage.THEME_KEY

/**
 * The bar's theme button's part of a page's script, which the dashboards' ([DashboardScript]) and the proxy's screens
 * page's include, so the button works the same on every page that has it. It defines `say`, which speaks into the
 * page's live region ([DashboardPage.announcer]), and `showTheme` and `cycleTheme`, and names the button now; the
 * including script calls `cycleTheme` on a click of the button.
 */
internal object ThemeScript {
  /**
   * The theme: light, dark, or auto, which follows the system's, from the bar's button, and kept in this browser. The
   * head applies a stored one before the page draws ([DashboardPage.THEME_SCRIPT]), so the theme in force is the page's
   * own, whose icon the stylesheet draws on the button. This keeps the button's name and tooltip in step with it,
   * naming the theme in force, auto's with the system's theme as that changes, and the next; and on a press moves to
   * the next, in the order light, dark, auto, wrapping, applies it, stores it, and says it. Auto is stored as nothing.
   */
  val JS =
    """
    function say(text) {
      var region = document.getElementById('$ANNOUNCER_ID');
      if (region) region.textContent = text;
    }
    var THEMES = ['light', 'dark', 'auto'];
    var systemDark = window.matchMedia('(prefers-color-scheme: dark)');
    function currentTheme() { return document.documentElement.getAttribute('data-theme') || 'auto'; }
    function nextTheme(t) { return THEMES[(THEMES.indexOf(t) + 1) % THEMES.length]; }
    function showTheme() {
      var btn = document.getElementById('$THEME_BUTTON_ID');
      if (!btn) return;
      var current = currentTheme();
      var label = 'Theme: ' + current;
      if (current === 'auto') label += ', following your system, now ' + (systemDark.matches ? 'dark' : 'light');
      label += '. Press for ' + nextTheme(current) + '.';
      btn.setAttribute('aria-label', label);
      btn.title = label;
    }
    function cycleTheme() {
      var t = nextTheme(currentTheme());
      if (t === 'auto') document.documentElement.removeAttribute('data-theme');
      else document.documentElement.setAttribute('data-theme', t);
      try {
        if (t === 'auto') localStorage.removeItem('$THEME_KEY'); else localStorage.setItem('$THEME_KEY', t);
      } catch (e) {}
      showTheme();
      say('Theme: ' + t + '.');
    }
    showTheme();
    systemDark.addEventListener('change', showTheme);
    """.trimIndent()

  /** [JS] with each line after the first indented by [indent], for a script whose own lines are indented so. */
  fun indented(indent: String): String = JS.prependIndent(indent).trimStart()
}
