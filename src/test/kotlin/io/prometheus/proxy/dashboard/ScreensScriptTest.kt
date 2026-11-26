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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.ThemeScript

/**
 * The screens page's script, [ScreensScript], checked as `DashboardScriptTest` checks the dashboards': the links into
 * the page, the rule that a link with a path loads it and a bare id never reloads, the handoff of a link to a page
 * already open, the dots, and the theme button, which it shares with the dashboards ([ThemeScript]).
 */
class ScreensScriptTest : StringSpec() {
  private val js = ScreensScript.JS

  // The body of the script's function [name], up to the next function at the script's top level, two spaces in.
  private fun body(name: String) = js.substringAfter("function $name(").substringBefore("\n  function ")

  init {
    // ==================== Links ====================

    // #all, #<id>, #<id>/<path>, and #<a>[/<path>]+<b>[/<path>]: the spec's Links section.
    "a link should parse as the grid, a screen, a screen's path, or two screens side by side" {
      body("parseLink").apply {
        this shouldContain "if (text === '${Screen.ALL_ID}') return { layout: 'all', panes: [] };"
        this shouldContain "var parts = splitPanes(text).map(function (part) {"
        this shouldContain "var slash = part.indexOf('/');"
        this shouldContain "path: slash < 0 ? null : part.slice(slash + 1)"
        this shouldContain "layout: parts.length > 1 ? 'split' : 'one'"
      }
    }

    // The dashboards' own search writes a space in its query as +, so #proxy/activity?q=agent+rogue is one pane: a +
    // splits the link only where a screen's id follows it, alone or before its path.
    "a + in a pane's query should stay in its path, and only a + before a screen's id split the link" {
      body("splitPanes").apply {
        this shouldContain "var at = text.indexOf('+');"
        this shouldContain "if (panes[rest.split('/')[0]]) return [text.slice(0, at), rest];"
        this shouldContain "at = text.indexOf('+', at + 1);"
        this shouldContain "return [text];"
      }
    }

    // #nope or #nope+grafana: the page still opens, on the proxy's tab, with no script error (Review Focus 5).
    "a link to a screen the page doesn't have should fall back to the proxy's tab" {
      body("paneOf") shouldContain "if (!panes[id]) return { id: '${Screen.PROXY_ID}', path: null };"
    }

    // The hash never reaches the server, so the script reads it, and keeps it in step with what the page shows, as a
    // replacement, so a tab or layout change adds no history entry.
    "the page should read its link from the hash, and keep the hash in step without adding history" {
      js shouldContain "location.hash.slice(1)"
      js shouldContain "history.replaceState(null, '', '#' + hash);"
      js shouldContain "window.addEventListener('hashchange'"
    }

    // ==================== The load rule ====================

    // A link with a path loads it, reloading a screen already loaded; a bare id brings a screen up and keeps the
    // reader's place in it, loading its start only the first time (the spec's Anti-Goals).
    "a link with a path should load it, and a bare id never reload a loaded screen" {
      body("follow").apply {
        this shouldContain "if (pane.path !== null) load(pane.id, pane.path);"
      }
      body("show").apply {
        this shouldContain "if (!frame.getAttribute('src')) load(id, null);"
      }
      body("load") shouldContain "setSrc(frame, path === null ? startUrl(frame) : pathUrl(frame, path));"
    }

    // Moving an iframe in the page reloads it, so layouts only set classes and sizes, and every src is set in one
    // place.
    "no frame should be moved in the page, and every src set by the load rule" {
      Regex("""\.src\s*=""").findAll(js).count() shouldBe 1
      body("setSrc") shouldContain "frame.src = url;"
      listOf("appendChild", "insertBefore", ".append(", ".prepend(", "replaceChild", ".before(", ".after(")
        .filter { it in js } shouldBe emptyList()
    }

    // A url at the root, as the proxy's own at a root mount, mustn't double its slash before a path.
    "a path should join its screen's url with one slash" {
      body("join") shouldContain "return rest.charAt(0) === '/' ? url.replace(/\\/+$/, '') + rest : url + rest;"
    }

    // At a root mount the proxy's url is /, so #proxy//evil.example would join to //evil.example, which a browser
    // reads as another site, and load it in the proxy's frame; a backslash reads as a slash there too.
    "a link's path should lose its leading slashes and backslashes, so it can't name another site" {
      body("pathUrl") shouldContain "var rest = path.replace(/^[\\/\\\\]+/, '');"
    }

    // ==================== The handoff ====================

    // A link opened while the page is open hands itself to that page and closes the new tab, or says it couldn't.
    "a page opened with a link should hand it to a copy already open, and close itself" {
      js shouldContain "new BroadcastChannel('prometheus-proxy-screens')"
      js shouldContain "HANDOFF_WAIT_MILLIS = ${ScreensScript.HANDOFF_WAIT_MILLIS}"
      js shouldContain "channel.postMessage({ type: 'link', link: link, id: ticket });"
      js shouldContain "if (data.type === 'link' && isThePage) {"
      js shouldContain "channel.postMessage({ type: 'taken', id: data.id });"
      js shouldContain "window.close();"
      // The notice the page renders hidden, for a browser that won't let the script close its tab.
      js shouldContain "document.getElementById('${ScreensHtml.HANDOFF_ID}').hidden = false;"
      js shouldContain "if (evt.target.closest('#${ScreensHtml.HANDOFF_ID} button')) stayHere();"
    }

    "a page opened without a link should be the page at once, with no handoff" {
      js shouldContain "if (!link || !channel) { becomeThePage(link); return; }"
    }

    // ==================== The dots ====================

    // A request the page can't read, which succeeds whenever the screen answers, and none while the page is hidden.
    "each screen should be probed without reading its answer, and not while the page is hidden" {
      // Without the session cookie, so a page left open doesn't keep its login from going idle.
      js shouldContain
        "fetch(frame.getAttribute('data-probe'), { mode: 'no-cors', cache: 'no-store', credentials: 'omit' })"
      js shouldContain "PROBE_INTERVAL_MILLIS = ${ScreensScript.PROBE_INTERVAL_MILLIS}"
      js shouldContain "document.addEventListener('visibilitychange'"
      js shouldContain "if (document.hidden) return;"
      // A screen that comes back reloads its frame, now if it's shown, else when it next is.
      body("mark") shouldContain "if (up && wasDown && frame.getAttribute('src'))"
    }

    "each dot should say its state in words, for a screen reader" {
      body("mark") shouldContain "state.textContent = up ? 'up' : 'not answering';"
    }

    // ==================== Tabs, layouts, and the theme ====================

    "the tabs should step with Left and Right, and shift-click fill the right pane" {
      js shouldContain "evt.key !== 'ArrowLeft' && evt.key !== 'ArrowRight'"
      js shouldContain "if (evt.shiftKey) fillRight(id); else select(id);"
      js shouldContain "evt.metaKey || evt.ctrlKey"
    }

    "the All layout should scale each frame to its tile, laid out at 1280 by 800" {
      js shouldContain "new ResizeObserver("
      js shouldContain "view.style.setProperty('--s', Math.min(1, view.clientWidth / ${ScreensScript.FRAME_WIDTH}))"
      ScreensCss.CSS shouldContain "width:${ScreensScript.FRAME_WIDTH}px; height:${ScreensScript.FRAME_HEIGHT}px;"
    }

    "the theme button should work as it does on the dashboards" {
      ThemeScript.JS.lines().map { it.trim() }.filter { it.isNotEmpty() }.filterNot { it in js } shouldBe emptyList()
      js shouldContain "if (evt.target.closest('#${DashboardPage.THEME_BUTTON_ID}')) cycleTheme();"
      js shouldNotContain "localStorage.getItem"
    }
  }
}
