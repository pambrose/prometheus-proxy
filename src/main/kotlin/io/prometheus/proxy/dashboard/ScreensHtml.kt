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

package io.prometheus.proxy.dashboard

import io.prometheus.common.dashboard.DashboardLogo.brand
import io.prometheus.common.dashboard.DashboardPage.announcer
import io.prometheus.common.dashboard.DashboardPage.barEnd
import io.prometheus.common.dashboard.DashboardPage.dashboardHead
import io.prometheus.common.dashboard.LoginHtml
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.iframe
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.ul
import kotlinx.html.unsafe

/**
 * The proxy's screens page, `/screens`: the proxy's own dashboard and every screen `proxy.dashboard.screens` lists, a
 * tab and a pane each, in One, Split, or All. A page of its own, not a view under the dashboard's bar, which nothing
 * pushes to: its script ([ScreensScript]) runs it in the browser from the markup here, and its styles ([ScreensCss])
 * follow the dashboards' stylesheet, whose head, tabs, and bar's end it reuses.
 */
internal object ScreensHtml {
  /** The tabs' `<nav>`, which the script steps through with Left and Right. */
  const val NAV_ID = "screen-nav"

  /** The panes' stage, whose class is the layout in force. */
  const val STAGE_ID = "stage"

  /** The notice a copy shows when its link went to the copy already open and it couldn't close itself. */
  const val HANDOFF_ID = "handoff"

  /** The More button at the tabs' end, shown while a tab doesn't fit the row, which opens [MENU_ID]. */
  const val MORE_ID = "screens-more"

  /** The More menu, a popover listing every screen, with a search. */
  const val MENU_ID = "screens-menu"

  /** The More menu's search field. */
  const val SEARCH_ID = "screens-search"

  /** The page's title, before the proxy's name; its bar names the product, as the login pages do. */
  private const val TITLE = "Screens"

  /**
   * What the page shows.
   *
   * @param proxyName the proxy's name, as its dashboard's bar names it, the proxy's tab's title
   * @param routeBase the dashboard's base, normalized, which the proxy's tab opens at (`/` at a root mount)
   * @param screens the screens after the proxy's, in the config's order
   * @param loginEnabled whether the bar ends in Log out
   */
  data class Page(
    val proxyName: String,
    val routeBase: String,
    val screens: List<Screen>,
    val loginEnabled: Boolean,
  )

  /** The page [page] describes. */
  fun HTML.renderScreens(page: Page) {
    // The proxy's own dashboard first, always, at its base, with no start.
    val proxy = Screen(id = Screen.PROXY_ID, name = page.proxyName, url = page.routeBase.ifEmpty { "/" }, start = "")
    val all = listOf(proxy) + page.screens
    attributes["lang"] = "en"
    head {
      dashboardHead("$TITLE · ${page.proxyName}", page.routeBase, htmx = false) {
        style { unsafe { raw(ScreensCss.CSS) } }
      }
    }
    body("screens") {
      div("bar") {
        brand(LoginHtml.PRODUCT)
        tabs(all)
        layoutButtons()
        barEnd(page.loginEnabled, page.routeBase)
      }
      moreMenu(all)
      announcer()
      handoffNotice()
      main("stage one") {
        attributes["id"] = STAGE_ID
        all.forEach { screen -> pane(screen, all, probeOf(screen, page.routeBase)) }
      }
      script { unsafe { raw(ScreensScript.JS) } }
    }
  }

  // A tab per screen, the dashboards' own (DashboardPage.tabs), each a link to its screen's id in the page, with its
  // status dot where a view's icon would be, which a visually hidden word says too. The proxy's is current until the
  // script reads the link the page was opened with. A long name is cut, and the tooltip gives it whole.
  private fun FlowContent.tabs(screens: List<Screen>) {
    nav("nav") {
      attributes["id"] = NAV_ID
      attributes["aria-label"] = "Screens"
      screens.forEach { screen ->
        screenLink(screen, "screen", "page") { attributes["title"] = screen.name }
      }
      moreButton()
    }
  }

  // A tab or a menu entry: a link to its screen's id in the page, the proxy's current, with [current] its
  // aria-current, then the screen's name and its state in a visually hidden word, which the script keeps in step with
  // the dot.
  private fun FlowContent.screenLink(
    screen: Screen,
    cssClass: String,
    current: String,
    extra: A.() -> Unit = {},
  ) {
    val on = screen.id == Screen.PROXY_ID
    a(href = "#${screen.id}", classes = if (on) "$cssClass on" else cssClass) {
      attributes["data-screen"] = screen.id
      if (on) attributes["aria-current"] = current
      extra()
      span("name") { +screen.name }
      +" "
      span("sr-only state") { +"checking" }
    }
  }

  // The tabs' last item, hidden until the script finds a tab that doesn't fit the row: how many screens the row
  // leaves out, and how many of those aren't answering, which the script fills in.
  private fun FlowContent.moreButton() {
    button(type = ButtonType.button, classes = "more") {
      attributes["id"] = MORE_ID
      attributes["popovertarget"] = MENU_ID
      attributes["aria-label"] = "More screens"
      attributes["hidden"] = "hidden"
      +"More "
      span("count") {}
      span("crit") {}
    }
  }

  // The More menu: a search, then an entry per screen, in the tabs' order, the proxy's current until the script reads
  // the link the page was opened with. A popover, which draws in the top layer, so it sits outside the nav, whose
  // tab rules would otherwise style its entries.
  private fun FlowContent.moreMenu(screens: List<Screen>) {
    div("menu") {
      attributes["id"] = MENU_ID
      attributes["popover"] = "auto"
      input(type = InputType.search) {
        attributes["id"] = SEARCH_ID
        attributes["aria-label"] = "Find a screen"
        attributes["placeholder"] = "Find a screen…"
        attributes["autocomplete"] = "off"
        // Focused as the menu shows, so a key pressed at once goes to the search, not the More button.
        attributes["autofocus"] = "autofocus"
      }
      ul("entries") {
        screens.forEach { screen -> li { screenLink(screen, "entry", "true") } }
      }
    }
  }

  // One, Split, and All, the layout in force pressed.
  private fun FlowContent.layoutButtons() {
    div("layouts") {
      attributes["role"] = "group"
      attributes["aria-label"] = "Layout"
      listOf("one" to "One", "split" to "Split", "all" to "All").forEach { (layout, label) ->
        button(type = ButtonType.button) {
          attributes["data-layout"] = layout
          attributes["aria-pressed"] = "${layout == "one"}"
          +label
        }
      }
    }
  }

  // Shown by the script when a link this copy was opened with went to the copy already open, and the browser wouldn't
  // let the script close this one.
  private fun FlowContent.handoffNotice() {
    div("handoff") {
      attributes["id"] = HANDOFF_ID
      attributes["role"] = "status"
      attributes["hidden"] = "hidden"
      span { +"This link opened in your other Screens tab." }
      button(type = ButtonType.button) { +"Stay here instead" }
    }
  }

  // Where the script checks whether a screen answers: the proxy's own at its session route, which answers at once,
  // with a 401 behind a login and a 204 without, where its dashboard's page renders every agent; any other at its url.
  private fun probeOf(
    screen: Screen,
    routeBase: String,
  ) = if (screen.id == Screen.PROXY_ID) "$routeBase/session" else screen.url

  // A screen's pane: its bar, with its name, a picker of every screen for Split, and Open in a tab, and its view,
  // holding its frame, which the script gives a src the first time the pane shows, and what it says while the screen
  // isn't answering.
  private fun FlowContent.pane(
    screen: Screen,
    all: List<Screen>,
    probe: String,
  ) {
    val start = screen.startUrl
    section("pane") {
      attributes["data-screen"] = screen.id
      div("pane-bar") {
        span("name") { +screen.name }
        select {
          attributes["aria-label"] = "Screen in this pane"
          all.forEach { other ->
            option {
              value = other.id
              if (other.id == screen.id) selected = true
              +other.name
            }
          }
        }
        a(classes = "open") {
          href = start
          target = "_blank"
          rel = "noopener"
          +"Open in a tab ↗"
        }
      }
      div("view") {
        iframe(classes = "frame") {
          attributes["title"] = screen.name
          attributes["data-url"] = screen.url
          attributes["data-start"] = start
          attributes["data-probe"] = probe
        }
        div("down") {
          strong { +"${screen.name} isn't answering." }
          span { +"Its pane reloads once it answers." }
        }
      }
    }
  }
}
