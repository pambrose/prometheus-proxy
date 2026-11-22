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
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import io.prometheus.proxy.dashboard.DashboardFixtures.snapshot

/**
 * The page's script, [DashboardScript], and the htmx it runs on: the frames it drops, the arrow keys between views,
 * the column guide's button, close button, and Escape, the scrape-now request, the connection indicator (both its
 * labels, the WebSocket lifecycle and its reconnect backoff, and the stylesheet that switches them), the copy buttons,
 * scrape times in the viewer's zone, and htmx's config and assets.
 */
class DashboardScriptTest : StringSpec() {
  init {
    // ==================== Frames, views, and scrape-now ====================

    "the page script should count its messages and drop frames stamped below the count" {
      val page = renderFullPage(snapshot())
      page shouldContain "seq: seq"
      page shouldContain "addEventListener('htmx:ws:before:message:incoming'"
      page shouldContain "evt.preventDefault()"
      // Anchored at the start of the frame, on the attribute the server writes.
      page shouldContain """/^<span[^>]*\s${DashboardPage.VIEW_ATTR}="""
    }

    // Left and Right open the neighbouring view from anywhere on the page, not only from a focused tab: a click on a
    // tab loads a page whose focus is on <body>, and Safari never focuses a link on a click or a Tab. Typing is left
    // alone, so the arrows still move the caret in the search, as is a modified key: Alt+Left is the browser's Back.
    // A tab that had focus gets it back on the page that opens, so a keyboard user's next arrow keeps going from it.
    "the page script should switch views with the arrow keys from anywhere but a text field" {
      val page = renderFullPage(snapshot(), DashboardLayout.PATH)

      page shouldContain """<nav class="nav" id="${DashboardPage.NAV_ID}">"""
      page shouldContain "evt.key !== 'ArrowLeft' && evt.key !== 'ArrowRight'"
      page shouldContain "evt.altKey || evt.ctrlKey || evt.metaKey || evt.shiftKey || evt.repeat"
      page shouldContain "if (typingIn(evt.target)) return;"
      page shouldContain
        "var from = focusedTab || document.querySelector('#${DashboardPage.NAV_ID} a[aria-current=\"page\"]');"
      page shouldContain "(tabs.indexOf(from) + step + tabs.length) % tabs.length"
      // The gate that left the arrows working only from a focused tab.
      page shouldNotContain
        "var tab = evt.target.closest('#${DashboardPage.NAV_ID} a');\n        if (!tab) return;"
      page shouldContain "window.location.assign(next.href);"
      page shouldContain "arrowedTo === window.location.pathname"
    }

    // From the listeners on <body>, as the other buttons are. Closing it hands focus back to its button. Escape closes
    // it from anywhere but a field being typed in, so it works after a mouse click in Safari, which leaves focus on
    // <body>, and Escape in the search still clears the search.
    "the page script should open and close the column guide from its button, its close button, and Escape" {
      val page = renderFullPage(snapshot(), DashboardLayout.PATH)
      val toggle = DashboardPage.COLUMN_GUIDE_TOGGLE_ID

      page shouldContain "else if (evt.target.closest('#$toggle')) showGuide(!guideOpen());"
      page shouldContain "toggle.setAttribute('aria-expanded', open ? 'true' : 'false');"
      page shouldContain "else if (guide && evt.target.closest('button.close')) closeGuide();"
      page shouldContain "if (guideOpen() && !typingIn(evt.target)) closeGuide();"
      page.substringAfter("function closeGuide() {").substringBefore("}") shouldContain "toggle.focus();"
    }

    // A request of the script's own, carrying the header the route requires, so another site's page can't send it.
    "the page script should post a scrape-now with htmx's header and put the result in the panel" {
      val page = renderFullPage(snapshot())

      page shouldContain "closest('button.scrape-now')"
      page shouldContain "method: 'POST', headers: { '${DashboardServer.HX_REQUEST_HEADER}': 'true' }"
      page shouldContain "panel.innerHTML = html;"
      // Closing the panel hands focus back to the button that filled it.
      page shouldContain "function closeScrapeResult(panel)"
    }

    // The script tells the server which layout the page shows, from its URL, on every announce. The map comes from
    // DashboardLayout, so a new layout can't be left out of it.
    // Light, dark, or auto, from the bar's picker, kept in this browser; the head applies it before the page draws.
    "the page should apply a stored theme in its head, and its script should set the theme a picker button names" {
      val page = renderFullPage(snapshot())
      page.substringBefore("</head>") shouldContain DashboardPage.THEME_SCRIPT
      page shouldContain """<div class="theme" id="${DashboardPage.THEME_PICKER_ID}" role="group""""
      page shouldContain
        "evt.target.closest('#${DashboardPage.THEME_PICKER_ID} [${DashboardPage.THEME_CHOICE_ATTR}]')"
      page shouldContain "localStorage.setItem('${DashboardPage.THEME_KEY}', t)"
      // The theme in force is the page's own, as the head applied it, so a store that refuses writes can't stick it.
      page shouldContain "return document.documentElement.getAttribute('data-theme') || 'auto';"
      page.substringAfter("function currentTheme") shouldNotContain "localStorage.getItem"
      // Each button's pressed state and tooltip follow the theme in force; auto's names the system's theme, and
      // follows it as it changes.
      page shouldContain "btn.setAttribute('aria-pressed', String(t === current));"
      page shouldContain "matchMedia('(prefers-color-scheme: dark)')"
      page shouldContain "systemDark.addEventListener('change', showTheme)"
      // Outside every pushed region, so no frame resets it.
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard") shouldNotContain DashboardPage.THEME_PICKER_ID
    }

    "the page script should name each layout by its route" {
      renderFullPage(snapshot()) shouldContain
        """var LAYOUTS = {"paths":"PATH","activity":"ACTIVITY","settings":"SETTINGS"};"""
    }

    // ==================== Connection indicator ====================

    // The status region always ships both labels; CSS driven by the body's ws-down class decides which
    // shows. Rendering only "live" and swapping text in JS would leave the disconnected label absent
    // exactly when the server (which does the rendering) is unreachable.
    "the status region should carry both the live and reconnecting labels" {
      val status = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard")
      status shouldContain "live"
      status shouldContain "reconnecting"
    }

    // The whole point: the WebSocket extension reconnects on its own, and these handlers are what makes
    // that effort visible. A close must mark the page down; an open must clear it.
    "the page script should flip the connection state on the WebSocket lifecycle events" {
      val page = renderFullPage(snapshot())
      page shouldContain "addEventListener('htmx:ws:close'"
      page shouldContain "addEventListener('htmx:ws:after:connection'"
      page shouldContain "ws-down"
      // before:connection also fires for the first connect on load; reacting to it would flash red before
      // the initial open, so no listener may be wired for it. ws:error also reports a message that failed
      // to send or swap on an open socket, so it may not mark the page down either. Asserted as the precise
      // listener form rather than the bare event name, which the explaining comment legitimately mentions.
      page shouldNotContain "addEventListener('htmx:ws:before:connection'"
      page shouldNotContain "addEventListener('htmx:ws:error'"
    }

    // htmx 4 renamed every WebSocket event and attribute. A listener left on an htmx 2 name never fires,
    // so the page would keep claiming "live" through an outage; ws-connect still works but logs a
    // deprecation warning on every load; and hx-ext no longer exists.
    // A session that ends while the page is open, by expiring, a Log out elsewhere, or a proxy restart: the socket
    // closes, the page asks whether it may still use the dashboard, and on a 401 reloads, which the server answers with
    // the login page. A scrape refused with a 401 does the same, rather than showing the refusal in the panel.
    "the page script should send an expired session to the login page" {
      val page = renderFullPage(snapshot())
      page shouldContain "function checkSession()"
      page shouldContain "fetch(base + '/session'"
      page shouldContain "if (response.status === 401) location.reload();"
      // Only on a page with a login, which has Log out, and one check at a time: every failed reconnect closes the
      // socket again.
      page shouldContain "if (checkingSession || !document.getElementById('${DashboardPage.LOGOUT_ID}')) return;"
      Regex("""addEventListener\('htmx:ws:close', function \(\) \{[^}]*checkSession\(\)""")
        .containsMatchIn(page) shouldBe true
      page shouldContain "if (response.status === 401) { location.reload(); return null; }"
    }

    "the page should use no htmx 2 WebSocket names" {
      val page = renderFullPage(snapshot())
      page shouldNotContain "htmx:wsOpen"
      page shouldNotContain "htmx:wsClose"
      page shouldNotContain "htmx:wsError"
      page shouldNotContain "htmx:pushedIntoHistory"
      page shouldNotContain " ws-connect="
      page shouldNotContain "hx-ext="
      page shouldNotContain "wsReconnectDelay"
    }

    // htmx 4 dispatches its history events on document, not <body>. A listener on the body would never
    // hear a row click push its URL, so the server would keep pushing the previous agent's detail pane.
    "the page should listen for history pushes on the document" {
      val page = renderFullPage(snapshot())
      page shouldContain "document.addEventListener('htmx:after:history:push', announce)"
      page shouldContain "window.addEventListener('popstate', announce)"
    }

    // The extension closes the socket of a hidden tab and reopens it when the tab is shown. That pause must
    // not read as an outage, or every tab switch would turn the beacon red and announce a lost connection.
    "the page should ignore a close made while the tab is hidden" {
      val page = renderFullPage(snapshot())
      // Nor ask about its session: a hidden tab gives up its socket on purpose.
      page shouldContain "if (!document.hidden) { connected(false); checkSession(); }"
    }

    // Each failed reconnect attempt closes the socket again. Writing the live region on every close would
    // re-announce the outage every couple of seconds, so only the transition into it is announced.
    "the page should announce a lost connection once per outage" {
      val page = renderFullPage(snapshot())
      page shouldContain "if (!up && !wasDown) region.textContent = 'Connection to the proxy lost. Reconnecting.'"
      page shouldContain "else if (up && wasDown) region.textContent = 'Connection to the proxy restored.'"
    }

    // The table is replaced on every push, so a listener attached to a copy button would last one frame.
    "the page should handle copy clicks from one listener on the body" {
      val page = renderFullPage(snapshot())
      page shouldContain "document.body.addEventListener('click'"
      page shouldContain "evt.target.closest('button.copy')"
      page shouldContain "btn.getAttribute('${DashboardPage.COPY_ATTR}')"
    }

    // navigator.clipboard is undefined outside a secure context, and the dashboard is usually plain http on
    // a LAN address. Without the fallback the button would do nothing on exactly the pages that use it most.
    "the page should copy without the clipboard API outside a secure context" {
      val page = renderFullPage(snapshot())
      page shouldContain "if (navigator.clipboard && window.isSecureContext) return"
      page shouldContain "document.execCommand('copy')"
    }

    // The proxy URL's host is the one the viewer opened the dashboard at, which the proxy can't know: a name or address
    // it is reached by from outside, or through a port forward. The scrape port is plain HTTP.
    "the page should build a proxy URL from its own host and the proxy's scrape port" {
      val page = renderFullPage(snapshot())
      page shouldContain "'http://' + window.location.hostname + ':'"
      page shouldContain "document.body.getAttribute('${DashboardPage.SCRAPE_PORT_ATTR}')"
      page shouldContain "btn.getAttribute('${DashboardPage.PROXY_PATH_ATTR}')"
    }

    // A push that lands while the mark is showing replaces the marked button with an unmarked one.
    // After each push: the copy result back on its button, the title's problem count, and a new filter's row count.
    "the page should put a copy result back on the button, and update the title, after each push" {
      val page = renderFullPage(snapshot())
      val handler =
        page
          .substringAfter("addEventListener('htmx:ws:after:message:incoming', function () {")
          .substringBefore("});")

      handler shouldContain "applyMark();"
      handler shouldContain "updateTitle();"
      handler shouldContain "sayCount();"
    }

    // Converted in the fragment before the swap rather than in the page after it, so no frame shows the proxy's zone
    // between the two, and once for the times the page was served with.
    "the page should show scrape times in the viewer's zone before each swap" {
      val page = renderFullPage(snapshot())
      page shouldContain "localTimes(document);"
      page shouldContain "document.addEventListener('htmx:before:swap'"
      page shouldContain "if (task.fragment) localTimes(task.fragment);"
    }

    // The mark is an icon and a colour, neither of which reaches a screen reader.
    "the page should announce the result of a copy" {
      val page = renderFullPage(snapshot())
      page shouldContain "say(what + ' copied.')"
      page shouldContain "say('Could not copy the ' + what.toLowerCase() + '.')"
    }

    // htmx 4 refetches the page on every back or forward and runs the inline scripts of what it swaps in.
    // Without the guard each navigation would stack another set of listeners. The swap brings a new theme picker,
    // served with nothing pressed, so a second run marks it, and does nothing else.
    "the page script should run only once per document, a second run marking the theme picker swapped in" {
      val page = renderFullPage(snapshot())
      page shouldContain "if (window.__proxyUiInit) { window.__proxyUiInit(); return; }"
      page shouldContain "window.__proxyUiInit = showTheme;"
    }

    // The extension's default backoff doubles up to a minute and only resets on a successful open, so a
    // brief outage can leave the page idle for tens of seconds after the proxy is already back. The page
    // caps it in htmx's config; this guards that cap against regressing to the default, which would
    // silently reintroduce the long lag.
    "the page should cap the WebSocket reconnect backoff" {
      val page = renderFullPage(snapshot())
      // kotlinx.html escapes the attribute's quotes, so match the names and values, not the JSON.
      page shouldContain "reconnectMaxDelay&quot;:2000"
      page shouldContain "reconnectDelay&quot;:500"
    }

    // htmx 4 swaps a 4xx or 5xx response in. A row click that failed would replace #detail with the error
    // body, and the pushes, which address #detail, would have nothing left to update.
    "the page should keep error responses from being swapped in" {
      val page = renderFullPage(snapshot())
      page shouldContain "noSwap&quot;:[204,304,&quot;4xx&quot;,&quot;5xx&quot;]"
    }

    // htmx reads its config once, when htmx.min.js loads, so a meta tag after the script is ignored.
    "the htmx config should precede the htmx script" {
      val page = renderFullPage(snapshot())
      val config = page.indexOf("""name="htmx-config"""")
      val script = page.indexOf("htmx.min.js")
      (config in 0..<script) shouldBe true
    }

    // The assets are cached for a year, so their URLs must change when htmx does; a fixed URL would leave a
    // browser running its cached htmx against the next version's markup.
    "the page should load its scripts from the versioned asset directory" {
      val page = renderFullPage(snapshot())
      DashboardServer.ASSET_DIR shouldContain DashboardServer.HTMX_VERSION
      page shouldContain """src="/dashboard/${DashboardServer.ASSET_DIR}/htmx.min.js""""
      page shouldContain """src="/dashboard/${DashboardServer.ASSET_DIR}/hx-ws.min.js""""
    }

    "the stylesheet should define the disconnected treatment and hide it by default" {
      val page = renderFullPage(snapshot())
      // Hidden until the body carries ws-down.
      page shouldContain ".conn .conn-down { display:none; }"
      page shouldContain "body.ws-down .conn .conn-down { display:inline; }"
      page shouldContain "body.ws-down .beacon"
    }
  }
}
