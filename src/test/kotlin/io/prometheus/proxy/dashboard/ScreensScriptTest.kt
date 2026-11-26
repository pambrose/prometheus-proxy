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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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

    // A url at the root, as the proxy's own at a root mount, mustn't double its slash before a path. Where a screen
    // starts is joined once, by the page (ScreensHtml), and read from its frame.
    "a path should join its screen's url with one slash, and the start come joined from the page" {
      body("pathUrl") shouldContain "if (rest === '') return url;"
      body("pathUrl") shouldContain "return url.replace(/\\/+$/, '') + '/' + rest;"
      body("startUrl") shouldContain "return frame.getAttribute('data-start');"
    }

    // A query alone follows the url with no slash: the dashboards don't ignore a trailing slash, so
    // #agent-east/?q=orders as /dashboard/?q=orders would be a 404; at a root mount, / and the query make /?q=orders.
    "a link's query alone should follow its screen's url with no slash between" {
      body("pathUrl") shouldContain "if (rest.charAt(0) === '?') return url + rest;"
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
      js shouldContain "var handoff = document.getElementById('${ScreensHtml.HANDOFF_ID}');"
      js shouldContain "setTimeout(function () { handoff.hidden = false; }, 100);"
      js shouldContain "if (evt.target.closest('#${ScreensHtml.HANDOFF_ID} button')) becomeThePage();"
    }

    "a page opened without a link should be the page at once, with no handoff" {
      js shouldContain "if (link && channel) {"
      js.substringAfter("if (link && channel) {").substringAfter("} else {").trimStart() shouldStartWith
        "becomeThePage();"
      body("becomeThePage") shouldContain "follow(parseLink(link || '${Screen.PROXY_ID}'));"
    }

    // ==================== The dots ====================

    // A request the page can't read, which succeeds whenever the screen answers, and none while the page is hidden.
    "each screen should be probed without reading its answer, and not while the page is hidden" {
      // Without the session cookie, so a page left open doesn't keep its login from going idle.
      // A HEAD, so no screen renders a page for it, one at a time for each screen.
      js shouldContain "fetch(frame.getAttribute('data-probe'), {"
      js shouldContain "method: 'HEAD', mode: 'no-cors', cache: 'no-store', credentials: 'omit', signal: signal"
      body("probe") shouldContain "if (probing[id]) return;"
      // A screen whose host drops the request counts as not answering once the interval is up, not when the browser
      // gives up on the connection.
      // Given up before the next round, so that round finds the screen free to check again; and free before it's
      // marked, so a mark that throws can't leave it checked forever.
      body("probe") shouldContain "AbortSignal.timeout(PROBE_TIMEOUT_MILLIS)"
      ScreensScript.PROBE_TIMEOUT_MILLIS shouldBeLessThan ScreensScript.PROBE_INTERVAL_MILLIS
      body("probe") shouldContain "function () { probing[id] = false; mark(id, true); }"
      body("probe") shouldContain "function () { probing[id] = false; mark(id, false); }"
      js shouldContain "PROBE_INTERVAL_MILLIS = ${ScreensScript.PROBE_INTERVAL_MILLIS}"
      js shouldContain "document.addEventListener('visibilitychange'"
      js shouldContain "if (document.hidden) return;"
      // A screen that comes back reloads its frame, now if it's shown, else when it next is.
      body("mark") shouldContain "if (up && wasDown && frame.getAttribute('src'))"
      // Only on a change, so a probe that finds the screen as it was touches nothing.
      body("mark") shouldContain "if (tab.classList.contains(up ? 'up' : 'down')) return;"
    }

    // A copy that hands its link over isn't the page, and shows no screens, so it checks none.
    "only the page should check its screens, starting once it becomes the page" {
      body("becomeThePage") shouldContain "startProbing();"
      body("startProbing").apply {
        this shouldContain "if (probingStarted) return;"
        this shouldContain "setInterval(probeAll, PROBE_INTERVAL_MILLIS);"
        this shouldContain "document.addEventListener('visibilitychange', probeAll);"
      }
      Regex("""setInterval\(""").findAll(js).count() shouldBe 1
    }

    // A copy whose close the browser refused shows its notice; a tab or layout clicked there changes nothing until
    // Stay here makes it the page, so it loads no screens while it isn't.
    "a copy that isn't the page should show no screens until it becomes the page" {
      body("render") shouldContain "if (!isThePage) return;"
      // Nor change what it would show, or move focus, so Stay here finds it as the link left it.
      listOf("select", "fillRight", "setLayout").filterNot { "if (!isThePage) return;" in body(it) } shouldBe
        emptyList()
      // Every one of them: the tabs' Left and Right, the More menu's keys, and the panes' pickers.
      listOf("addEventListener('keydown'", "addEventListener('change'").forEach { listener ->
        val handlers = js.split(listener).drop(1).map { it.substringBefore("});") }
        handlers.size shouldBeGreaterThan 0
        handlers.filterNot { "if (!isThePage) return;" in it } shouldBe emptyList()
      }
    }

    "each dot should say its state in words, for a screen reader" {
      body("mark") shouldContain "state.textContent = up ? 'up' : 'not answering';"
    }

    // ==================== Tabs, layouts, and the theme ====================

    "the tabs should step with Left and Right, and shift-click fill the right pane" {
      js shouldContain "evt.key !== 'ArrowLeft' && evt.key !== 'ArrowRight'"
      js shouldContain "if (evt.shiftKey) fillRight(id); else select(id);"
      js shouldContain "evt.metaKey || evt.ctrlKey"
      // Not a held key, which would load every screen on the way.
      js shouldContain "evt.altKey || evt.ctrlKey || evt.metaKey || evt.shiftKey || evt.repeat"
    }

    // ==================== The row and the More button ====================

    // A tab that doesn't fit is hidden, never moved: the tabs keep the list's order for a screen reader and Left/Right.
    "the tabs should fit one row, the ones that don't fit hidden, never moved" {
      ScreensCss.CSS shouldContain "body.screens .nav { flex:1 1 0; flex-wrap:nowrap; min-width:0; }"
      ScreensCss.CSS shouldContain ".nav a.screen.out { display:none; }"
      body("fitTabs").apply {
        this shouldContain "var room = nav.clientWidth;"
        this shouldContain "tabs[id].classList.toggle('out', row.indexOf(id) < 0);"
        this shouldContain "updateMore();"
      }
      js shouldNotContain "style.order"
    }

    // A link into the page, a tab's click, or Left/Right can select a screen the menu holds (Review Focus 1).
    "a screen a link or a tab selects, and Split's right one, should be kept in the row" {
      body("fitTabs") shouldContain "var kept = layout === 'split' ? [first, second] : layout === 'one' ? [first] : [];"
      body("render") shouldContain "fitTabs();"
    }

    "each tab should be measured once, in bold, and the More button at its widest label" {
      body("measureTabs").apply {
        this shouldContain "tab.classList.remove('out');"
        this shouldContain "tab.classList.add('on');"
        this shouldContain "' · ' + ids.length + ' down'"
      }
    }

    // Review Focus 5: the row is fitted with the More button's widest label, so its count can change alone.
    "a screen going down or up should change the More button's count, never the row" {
      body("updateMore").apply {
        this shouldContain "'More screens: ' + out.length + ' hidden'"
        this shouldContain "', ' + down + ' not answering'"
        this shouldContain "more.hidden = out.length === 0;"
        this shouldNotContain "fitTabs"
      }
      body("mark") shouldContain "updateMore();"
      body("mark") shouldNotContain "fitTabs"
    }

    "the row should refit when the tabs' width changes, not their height" {
      js shouldContain "tabSizer.observe(nav);"
      js shouldContain "if (width === navWidth) return;"
    }

    // ==================== The More menu ====================

    // The menu sits outside the nav (ScreensHtml), so the one handler listens on both.
    "a menu entry should work as a tab does, and close the menu" {
      js shouldContain "evt.target.closest('a.screen, a.entry')"
      js shouldContain "nav.addEventListener('click', onScreenClick);"
      js shouldContain "menu.addEventListener('click', onScreenClick);"
      body("onScreenClick") shouldContain "closeMenu();"
      body("closeMenu") shouldContain "if (menu.matches(':popover-open')) menu.hidePopover();"
    }

    "each entry should mark the current screen, the right pane's, and its screen's state" {
      body("render").apply {
        this shouldContain "var entry = menuItems[id];"
        this shouldContain "entry.classList.toggle('on', current);"
        this shouldContain "if (current) entry.setAttribute('aria-current', 'true');"
        this shouldContain "else entry.removeAttribute('aria-current');"
        this shouldContain "entry.classList.toggle('second', layout === 'split' && id === second);"
      }
      body("mark") shouldContain "menuItems[id].classList.toggle('down', !up);"
      body("mark") shouldContain "menuItems[id].querySelector('.state').textContent = state.textContent;"
    }

    "the search should filter the entries by name, case-insensitively, as a substring" {
      body("filterMenu").apply {
        this shouldContain "var query = text.trim().toLowerCase();"
        this shouldContain "entry.querySelector('.name').textContent.toLowerCase().indexOf(query) < 0"
        this shouldContain "entry.parentNode.hidden ="
      }
      js shouldContain "search.addEventListener('input', function () { filterMenu(search.value); });"
    }

    // A search that matches nothing leaves no entry: Enter and Down then do nothing (Review Focus 3).
    "the menu's keys should move through the entries left, and Enter in the search open the first" {
      body("visibleEntries") shouldContain "!entry.parentNode.hidden"
      // Prevented, or Enter's keypress reaches the More button, which the closing menu hands focus to, and reopens it.
      Regex(
        """if \(evt\.key === 'Enter' && evt\.target === search\) \{\s+evt\.preventDefault\(\);\s+""" +
          """if \(list\.length\) list\[0]\.click\(\);""",
      ).containsMatchIn(js) shouldBe true
      js shouldContain "var next = evt.key === 'ArrowDown' ? list[at + 1] : at <= 0 ? search : list[at - 1];"
      js shouldContain "if (next) next.focus();"
    }

    // Placed and cleared before it shows (beforetoggle, which runs as the menu opens, where toggle runs a task later),
    // so it never draws a frame out of place; its search takes focus as it shows (autofocus, ScreensHtml), so no key
    // pressed at once reaches the More button and steps the tabs behind the menu.
    "opening the menu should place it under More and focus a cleared search; closing it should focus More" {
      body("placeMenu").apply {
        this shouldContain "var rect = more.getBoundingClientRect();"
        this shouldContain "menu.style.top ="
        this shouldContain "menu.style.right ="
      }
      js.substringAfter("menu.addEventListener('beforetoggle', function (evt) {").substringBefore("});").apply {
        this shouldContain "if (evt.newState !== 'open') return;"
        this shouldContain "placeMenu();"
        this shouldContain "search.value = '';"
        this shouldContain "filterMenu('');"
      }
      js.substringAfter("menu.addEventListener('toggle', function (evt) {").substringBefore("});").apply {
        this shouldContain "if (evt.newState === 'open') search.focus();"
        this shouldContain "more.focus();"
      }
    }

    // At a phone's width More sits nearer the left edge than the menu is wide, so aligning their right edges alone put
    // the menu's left, its search and every name's start, off the screen.
    "the menu should stay inside the window on both sides" {
      ScreensCss.CSS shouldContain "width:min(${ScreensScript.MENU_WIDTH}px, calc(100vw - 32px));"
      body("placeMenu").apply {
        this shouldContain "var menuWidth = Math.min(${ScreensScript.MENU_WIDTH}, window.innerWidth - 32);"
        this shouldContain "Math.max(8, Math.min(width - rect.right, width - menuWidth - 8))"
      }
    }

    // The kept tabs and More can be wider than the row, as two long names in Split at half a laptop's width are; the
    // tabs then shrink, their names cut, rather than spill over the layout buttons or scroll the page sideways.
    "kept tabs wider than the row should shrink rather than spill out of it" {
      ScreensCss.CSS shouldContain ".nav.tight a.screen { min-width:0; }"
      body("fitTabs").apply {
        this shouldContain "tight = used > room;"
        this shouldContain "nav.classList.toggle('tight', tight);"
      }
      body("measureTabs") shouldContain "nav.classList.remove('tight');"
    }

    // Light dismiss sees only the page's own clicks, and a click into a screen goes to its frame's document, which
    // moves focus out of the page's window.
    "a click into a screen should close the menu" {
      js shouldContain "window.addEventListener('blur', closeMenu);"
    }

    "Left and Right should not step the tabs from inside the menu" {
      js shouldContain "if (evt.target.closest('#${ScreensHtml.MENU_ID}')) return;"
    }

    // Review Focus 4: an open menu would otherwise float where the button was.
    "a change in the tabs' width should close an open menu" {
      Regex("""navWidth = width;\s+closeMenu\(\);""").containsMatchIn(js) shouldBe true
    }

    "the All layout should scale each frame to its tile, laid out at 1280 by 800" {
      js shouldContain "new ResizeObserver("
      js shouldContain "var s = String(Math.min(1, entry.contentRect.width / ${ScreensScript.FRAME_WIDTH}));"
      js shouldContain "if (view.style.getPropertyValue('--s') !== s) view.style.setProperty('--s', s);"
      ScreensCss.CSS shouldContain "width:${ScreensScript.FRAME_WIDTH}px; height:${ScreensScript.FRAME_HEIGHT}px;"
    }

    // A pane is a scroll container (overflow:hidden), whose automatic minimum height is 0, so the grid's auto rows
    // shrank to share the stage's height, and a tile drew its title bar alone.
    "the All grid should size its rows to their tiles, and scroll" {
      ScreensCss.CSS shouldContain "grid-auto-rows:max-content;"
    }

    // Every screen is a live page, a dashboard with its socket, so All loads the tiles near the view, not 33 at once.
    "the All layout should load a tile's frame only once it comes near the stage's view" {
      body("render") shouldContain "if (at >= 0 && layout !== 'all') show(id);"
      js shouldContain "root: stage, rootMargin: '${ScreensScript.ALL_LOAD_MARGIN}'"
      js shouldContain "if (!isThePage || layout !== 'all') return;"
      js shouldContain "if (entry.isIntersecting) show(entry.target.getAttribute('data-screen'));"
      js shouldContain "loader.observe(panes[id]);"
    }

    "the theme button should work as it does on the dashboards" {
      ThemeScript.JS.lines().map { it.trim() }.filter { it.isNotEmpty() }.filterNot { it in js } shouldBe emptyList()
      js shouldContain "if (evt.target.closest('#${DashboardPage.THEME_BUTTON_ID}')) cycleTheme();"
      js shouldNotContain "localStorage.getItem"
    }
  }
}
