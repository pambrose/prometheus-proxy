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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.dashboard.DashboardCss
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.proxy.dashboard.DashboardFixtures.PROXY_NAME
import io.prometheus.proxy.dashboard.ScreensHtml.renderScreens
import kotlinx.html.html
import kotlinx.html.stream.createHTML

/**
 * The proxy's screens page, [ScreensHtml]: a tab and a pane per screen, the proxy's first, the markup its script
 * ([ScreensScript]) reads, every name escaped, and the dashboards' head, tabs, and bar. Its route and login are
 * `ProxyScreensTest`'s, and its script's behavior `ScreensScriptTest`'s.
 */
class ScreensHtmlTest : StringSpec() {
  private val screens =
    Screen.parseAll(
      [
        Screen.Entry(name = "agent-east", url = "http://a:8095/dashboard"),
        Screen.Entry(name = "Grafana", url = "http://g:3000", start = "/d/x?kiosk"),
      ],
    )

  private fun page(
    screens: List<Screen> = this.screens,
    routeBase: String = "/dashboard",
    loginEnabled: Boolean = false,
  ) = createHTML().html { renderScreens(ScreensHtml.Page(PROXY_NAME, routeBase, screens, loginEnabled)) }

  // The values of [attribute] on the elements of [tag] with [cssClass], in the page's order.
  private fun String.attributeValues(
    tag: String,
    cssClass: String,
    attribute: String,
  ): List<String> =
    Regex("""<$tag\b[^>]*class="$cssClass\b[^"]*"[^>]*>""")
      .findAll(this)
      .map { Regex("""\s$attribute="([^"]*)"""").find(it.value)?.groupValues?.get(1).orEmpty() }
      .toList()

  init {
    // ==================== Tabs and panes ====================

    "the tabs and panes should come in the config's order, after the proxy's" {
      page().apply {
        attributeValues("a", "screen", "data-screen") shouldBe ["proxy", "agent-east", "grafana"]
        attributeValues("section", "pane", "data-screen") shouldBe ["proxy", "agent-east", "grafana"]
        attributeValues("a", "screen", "href") shouldBe ["#proxy", "#agent-east", "#grafana"]
      }
    }

    "the proxy's tab should be named for the proxy, and be the current one" {
      page().apply {
        this shouldContain """<nav class="nav" id="${ScreensHtml.NAV_ID}" aria-label="Screens">"""
        Regex("""<a href="#proxy" class="screen on" data-screen="proxy" aria-current="page">""")
          .containsMatchIn(this) shouldBe true
        this shouldContain """>$PROXY_NAME <span class="sr-only state">checking</span></a>"""
        Regex("""aria-current="page"""").findAll(this).count() shouldBe 1
      }
    }

    // The dot is the state's colour, which a screen reader can't see, so each tab says it in words too.
    "each tab's dot should have a text equivalent" {
      page().apply {
        Regex("""<span class="sr-only state">checking</span>""").findAll(this).count() shouldBe 3
        this shouldContain """<a href="#agent-east" class="screen" data-screen="agent-east">"""
      }
    }

    "the layout buttons should say which layout is on" {
      page().apply {
        this shouldContain """<div class="layouts" role="group" aria-label="Layout">"""
        this shouldContain """<button type="button" data-layout="one" aria-pressed="true">One</button>"""
        this shouldContain """<button type="button" data-layout="split" aria-pressed="false">Split</button>"""
        this shouldContain """<button type="button" data-layout="all" aria-pressed="false">All</button>"""
        this shouldContain """<main class="stage one" id="${ScreensHtml.STAGE_ID}">"""
      }
    }

    // A frame loads the first time its pane shows, so a page holding many screens loads only the one it opens on. Its
    // start is its url and start joined, which the script loads and Open in a tab opens.
    "each pane should hold a frame with no src, its url and start in data attributes, and its down notice" {
      page().apply {
        attributeValues("iframe", "frame", "data-url") shouldBe
          ["/dashboard", "http://a:8095/dashboard", "http://g:3000"]
        attributeValues("iframe", "frame", "data-start") shouldBe
          ["/dashboard", "http://a:8095/dashboard", "http://g:3000/d/x?kiosk"]
        attributeValues("iframe", "frame", "title") shouldBe [PROXY_NAME, "agent-east", "Grafana"]
        Regex("""<iframe\b[^>]*\ssrc=""").containsMatchIn(this) shouldBe false
        this shouldContain """<div class="down"><strong>Grafana isn't answering.</strong>"""
      }
    }

    // The proxy's own screen is checked at its session route, which answers at once, a 401 with a login and a 204
    // without, rather than at the dashboard's page, which renders every agent; the others at their url.
    "each pane should be checked where it answers cheaply: the proxy's at its session route" {
      page().attributeValues("iframe", "frame", "data-probe") shouldBe
        ["/dashboard/session", "http://a:8095/dashboard", "http://g:3000"]
      page(routeBase = "").attributeValues("iframe", "frame", "data-probe").first() shouldBe "/session"
    }

    "each pane's bar should name it, offer every screen, and open it in a tab" {
      page().apply {
        attributeValues("a", "open", "href") shouldBe
          ["/dashboard", "http://a:8095/dashboard", "http://g:3000/d/x?kiosk"]
        this shouldContain """<a class="open" href="http://g:3000/d/x?kiosk" target="_blank" rel="noopener">"""
        this shouldContain "Open in a tab ↗</a>"
        Regex("""<select aria-label="Screen in this pane">""").findAll(this).count() shouldBe 3
        Regex("""<option value="grafana">Grafana</option>""").findAll(this).count() shouldBe 2
        Regex("""<option value="grafana" selected="selected">Grafana</option>""").findAll(this).count() shouldBe 1
      }
    }

    // Shown by the script only when a link went to the copy already open and the browser won't let it close this one.
    "the page should carry the handoff's notice, hidden, with a way to stay" {
      page() shouldContain
        """<div class="handoff" id="${ScreensHtml.HANDOFF_ID}" role="status" hidden="hidden">""" +
        """<span>This link opened in your other Screens tab.</span>""" +
        """<button type="button">Stay here instead</button></div>"""
    }

    // ==================== Escaping and the root mount ====================

    "a name with HTML or quote characters should be escaped everywhere it appears" {
      val text = page(Screen.parseAll([Screen.Entry(name = "<b>Ops \"A\"</b>", url = "http://ops:9000")]))
      text shouldNotContain "<b>Ops"
      text shouldContain """&lt;b&gt;Ops &quot;A&quot;&lt;/b&gt; <span class="sr-only state">"""
      text shouldContain """title="&lt;b&gt;Ops &quot;A&quot;&lt;/b&gt;""""
      text shouldContain """<span class="name">&lt;b&gt;Ops &quot;A&quot;&lt;/b&gt;</span>"""
      text shouldContain """data-screen="b-ops-a-b""""
    }

    "at a root mount, the proxy's pane should open at /, not //" {
      page(routeBase = "").apply {
        attributeValues("iframe", "frame", "data-url").first() shouldBe "/"
        attributeValues("a", "open", "href").first() shouldBe "/"
        this shouldNotContain "\"//"
      }
    }

    // ==================== The bar and the head ====================

    "the bar should have the theme button, and Log out only with a login" {
      page().apply {
        this shouldContain """<button type="button" class="theme" id="${DashboardPage.THEME_BUTTON_ID}""""
        this shouldNotContain """id="${DashboardPage.LOGOUT_ID}""""
      }
      page(loginEnabled = true) shouldContain """<form action="/dashboard/logout" method="post" class="logout""""
    }

    "the head should be the dashboards', with the page's own styles after them" {
      page().apply {
        this shouldContain "<title>Screens · $PROXY_NAME</title>"
        this shouldContain "<script>${DashboardPage.THEME_SCRIPT}</script>"
        this shouldContain DashboardCss.CSS
        this shouldContain ScreensCss.CSS
        indexOf(ScreensCss.CSS) shouldBeGreaterThan indexOf(DashboardCss.CSS)
        this shouldContain "<script>${ScreensScript.JS}</script>"
        // A page of its own: no socket, which the dashboard's pages open.
        this shouldNotContain "hx-ws:connect"
      }
    }

    // A bare prefers-color-scheme block would ignore the theme button (DashboardCss.darkRule).
    "every dark rule of the page's styles should go through darkRule" {
      ScreensCss.CSS.lines().filter { "prefers-color-scheme" in it }.shouldBeEmpty()
    }

    // A tab's state class is down while its screen isn't answering, so a bare .down rule for the pane's notice would
    // give the tab the notice's ground.
    "the pane's down notice should be styled only inside its view, apart from a tab's state class" {
      val css = ScreensCss.CSS
      Regex("""\.down\b""")
        .findAll(css)
        .map { css.substring(0, it.range.first) }
        .filterNot { it.endsWith(".view ") || it.endsWith("a.screen") }
        .count() shouldBe 0
      css shouldContain ".view .down {"
    }

    // The dot takes the icon's place in the dashboards' own tabs, which are reused, not restyled.
    "a tab's dot should be 7px and coloured by its state" {
      ScreensCss.CSS shouldContain ".nav a.screen::before"
      ScreensCss.CSS shouldContain "width:7px; height:7px;"
      ScreensCss.CSS shouldContain ".nav a.screen.up::before { background:var(--ok); }"
      ScreensCss.CSS shouldContain ".nav a.screen.down::before { background:var(--crit); }"
    }
  }
}
