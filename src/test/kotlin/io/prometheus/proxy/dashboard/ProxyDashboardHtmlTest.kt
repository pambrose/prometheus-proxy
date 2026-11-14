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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.proxy.ScrapeRecord
import kotlinx.html.html
import kotlinx.html.stream.createHTML

class ProxyDashboardHtmlTest : StringSpec() {
  private fun path(
    name: String,
    agents: List<String> = ["1"],
  ) = DashboardFixtures.pathView(path = name, agentIds = agents)

  private fun agent(
    id: String = "1",
    name: String = "team-a-01",
    paths: List<PathView> = [path("app_metrics")],
    endpoints: List<String> = emptyList(),
    endpointIndex: Int = 0,
  ) = DashboardFixtures.agentView(
    agentId = id,
    agentName = name,
    paths = paths,
    proxyEndpoints = endpoints,
    currentEndpointIndex = endpointIndex,
  )

  private fun snapshot(
    agents: List<AgentView> = [agent()],
    scrapes: List<ScrapeRecord> = emptyList(),
    paths: List<PathView> = agents.flatMap { it.paths }.distinct(),
  ) = DashboardFixtures.snapshot(agents = agents, scrapes = scrapes, paths = paths)

  private fun renderFullPage(layout: DashboardLayout = DashboardLayout.AGENT) =
    createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard", layout) } }

  init {
    "the push fragment should carry out-of-band swaps for every live region" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(), selectedId = "1", routeBase = "/dashboard")

      // Each region is swapped independently by id, which is what lets one frame update the list and
      // the detail pane without re-rendering the page.
      html shouldContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
      html shouldContain """id="${ProxyDashboardHtml.DETAIL_ID}""""
      html shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""
      html shouldContain """hx-swap-oob="true""""
    }

    "an agent row should carry the htmx attributes that drive selection" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(), selectedId = null, routeBase = "/dashboard")

      // Selection lives in the URL via hx-push-url, so it survives reload and is bookmarkable.
      html shouldContain """hx-get="/dashboard/agents/1""""
      html shouldContain """hx-target="#${ProxyDashboardHtml.DETAIL_ID}""""
      html shouldContain """hx-push-url="true""""
    }

    "the selected agent should be marked current" {
      ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard") shouldContain """aria-current="true""""
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard") shouldNotContain "aria-current"
    }

    // The case the whole dashboard exists for: an operator is watching an agent when it drops. A stale or
    // blank pane would read as a frozen dashboard, which is worse than an explicit message.
    "a selected agent that disconnected should render an explicit gone state" {
      val html = ProxyDashboardHtml.detailFragment(snapshot(agents = emptyList()), selectedId = "1")

      html shouldContain "no longer connected"
      html shouldContain "gone"
    }

    "no selection should render a prompt rather than an empty pane" {
      ProxyDashboardHtml.detailFragment(snapshot(), selectedId = null) shouldContain "Select an agent"
    }

    // Identity arrives at registerAgent, strictly after the transport filter creates the context, so a
    // just-connected agent legitimately has none. Showing the raw sentinel would read as corruption.
    "an agent without identity yet should not show the raw placeholder" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(agents = [agent(name = "Unassigned")]), null, "/dashboard")

      html shouldNotContain "Unassigned"
      html shouldContain "registering"
    }

    "the detail pane should show only the selected agent's scrapes" {
      val scrapes =
        [
          DashboardFixtures.scrapeRecord(agentId = "1", path = "app_metrics"),
          DashboardFixtures.scrapeRecord(
            agentId = "2",
            path = "other_metrics",
            statusCode = 503,
            outcome = "agent_disconnected",
            durationMillis = 0,
            contentLength = 0,
          ),
        ]
      val html = ProxyDashboardHtml.detailFragment(snapshot(scrapes = scrapes), selectedId = "1")

      html shouldContain "app_metrics"
      html shouldNotContain "other_metrics"
    }

    "an empty proxy should say so rather than render an empty list" {
      ProxyDashboardHtml.pushFragment(snapshot(agents = emptyList()), null, "/dashboard") shouldContain
        "No agents connected"
    }

    // Browser-supplied input on a port with no auth: malformed content must degrade to "no selection"
    // rather than throw and drop the WebSocket session.
    "selection parsing should tolerate anything a browser might send" {
      ProxyDashboardService.parseSelection("""{"select":"agent-7"}""") shouldBe "agent-7"
      ProxyDashboardService.parseSelection("""{"select":null}""").shouldBeNull()
      ProxyDashboardService.parseSelection("""{"select":""}""").shouldBeNull()
      ProxyDashboardService.parseSelection("""{"other":"x"}""").shouldBeNull()
      ProxyDashboardService.parseSelection("not json at all").shouldBeNull()
      ProxyDashboardService.parseSelection("").shouldBeNull()
      ProxyDashboardService.parseSelection("[1,2,3]").shouldBeNull()
    }

    // "This agent failed over to us" is the thing an operator cannot otherwise learn from one
    // dashboard, so it must be visible without hunting through the meta line.
    "an agent that failed over should be marked in the detail pane" {
      val failedOver = agent(endpoints = ["proxy-a:50051", "proxy-b:50051"], endpointIndex = 1)
      val html = ProxyDashboardHtml.detailFragment(snapshot(agents = [failedOver]), selectedId = "1")

      html shouldContain "via proxy-b:50051 (2 of 2)"
      html shouldContain "failed over"
    }

    "an agent on its primary should show its position but not the failed-over marker" {
      val primary = agent(endpoints = ["proxy-a:50051", "proxy-b:50051"], endpointIndex = 0)
      val html = ProxyDashboardHtml.detailFragment(snapshot(agents = [primary]), selectedId = "1")

      html shouldContain "via proxy-a:50051 (1 of 2)"
      html shouldNotContain "failed over"
    }

    "an agent without failover configured should show no position line" {
      ProxyDashboardHtml.detailFragment(snapshot(), selectedId = "1") shouldNotContain "via "
    }

    // ==================== Path-centric layout ====================

    "the path table should render a row per path with its target and source" {
      val paths =
        [
          DashboardFixtures.pathView(
            path = "app_metrics",
            targetUrl = "app1:9090/metrics",
            pathSource = "STATIC",
            lastScrape = DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 200, durationMillis = 41),
          ),
          DashboardFixtures.pathView(
            path = "node_metrics",
            targetUrl = "node-exporter:9100/metrics",
            pathSource = "DISCOVERED",
            lastScrape = DashboardFixtures.scrapeRecord(path = "node_metrics", statusCode = 200),
          ),
        ]
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = paths), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "/app_metrics"
      html shouldContain "app1:9090/metrics"
      html shouldContain "cfg"
      html shouldContain "node-exporter:9100/metrics"
      html shouldContain "disc"
      html shouldContain "41 ms"
    }

    // The reason this layout exists. The agent-centric view filters by agent, so when the agent goes the
    // path goes with it -- a target that stopped serving becomes invisible at the exact moment it matters.
    "a departed path should still render, marked gone, attributed to its last agent" {
      val departed =
        DashboardFixtures.pathView(
          path = "redis_metrics",
          agentIds = emptyList(),
          agentNames = emptyList(),
          lastScrape =
            DashboardFixtures.scrapeRecord(
              agentId = "9",
              agentName = "cache-tier-01",
              path = "redis_metrics",
              statusCode = 503,
            ),
          isDeparted = true,
        )
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [departed]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "/redis_metrics"
      html shouldContain "gone"
      html shouldContain "cache-tier-01"
      html shouldContain "503"
    }

    // The two layouts are read against each other -- find the failing path here, look the agent up
    // there. Rendering the internal id in one view and the name in the other made that a manual
    // translation step, and on a proxy with forty agents an unusable one.
    "the path table should name its agent the way the agent list does, never by internal id" {
      val served =
        DashboardFixtures.pathView(path = "ledger_metrics", agentIds = ["17"], agentNames = ["edge-eu-west-2"])
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [served]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "edge-eu-west-2"
      // The bare id must not appear as the cell's whole contents.
      html shouldNotContain ">17<"
    }

    // An agent that has connected but not yet registered gets the same treatment in both layouts, so
    // one agent never shows up under two different labels.
    "a path served by a not-yet-registered agent should show the same placeholder as the agent list" {
      val pending = DashboardFixtures.pathView(path = "new_metrics", agentIds = ["3"], agentNames = ["Unassigned"])
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [pending]), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain "Unassigned"
      html shouldContain "registering"
    }

    "a consolidated path should collapse its extra agents into a count" {
      val shared =
        DashboardFixtures.pathView(
          path = "shared_svc",
          agentIds = ["a1", "a2", "a3"],
          agentNames = ["team-a-01", "team-a-02", "team-a-03"],
        )
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [shared]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "team-a-01"
      html shouldContain "+2"
    }

    // An agent predating the proto fields sends neither, and a path may simply not have been scraped
    // yet. Both must render as absent data, never as an empty cell or a literal null.
    "a path missing target, source and scrape should render dashes rather than blanks" {
      val bare = DashboardFixtures.pathView(path = "new_path")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [bare]), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain "null"
      html shouldContain "\u2013"
    }

    // Each layout owns disjoint region ids, so a frame carrying the other layout's regions would be
    // addressing elements that do not exist on that page.
    "a push should carry only the regions belonging to the session's layout" {
      val pathFrame = ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard", DashboardLayout.PATH)
      pathFrame shouldContain """id="${ProxyDashboardHtml.PATH_TABLE_ID}""""
      pathFrame shouldNotContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
      pathFrame shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""

      val agentFrame = ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard", DashboardLayout.AGENT)
      agentFrame shouldContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
      agentFrame shouldNotContain """id="${ProxyDashboardHtml.PATH_TABLE_ID}""""
    }

    // The bug that shipped: the OOB regions were wrapped in an outer <div>. htmx 2's WebSocket extension
    // iterated only the immediate children of the parsed message, so the wrapper was the sole child it
    // saw -- with no id to match -- and every region nested inside it was silently dropped. The page
    // then updated only on a manual reload. Under htmx 4 a wrapper is worse: the regions are pulled out
    // of it, and the empty wrapper left behind is swapped in as the whole of <body>. The regions must be
    // top-level siblings.
    "a push must place the OOB regions at the top level, not inside a wrapper" {
      val frame = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.AGENT).trimStart()
      // The very first element is a real region carrying hx-swap-oob, not a bare <div> wrapper.
      frame shouldStartWith """<div class="md-list""""
      frame shouldNotStartWith "<div><"
      // The first tag itself is the OOB target -- hx-swap-oob appears before the region's closing markup.
      frame.substringBefore(">") shouldContain "hx-swap-oob"
    }

    // A session that has not yet said which page it shows gets only the status bar. A guess at the rest
    // (the agent layout, nothing selected) replaced the page's detail pane with the "select an agent"
    // placeholder until the browser's message arrived, a flash on every connect and reconnect.
    "a push for a session with no known layout should carry only the status bar" {
      val frame = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", layout = null)
      frame shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""
      frame shouldNotContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
      frame shouldNotContain """id="${ProxyDashboardHtml.DETAIL_ID}""""
      frame shouldNotContain """id="${ProxyDashboardHtml.PATH_TABLE_ID}""""
      frame shouldNotContain "Select an agent"
      // Still a frame of top-level OOB regions, like every other.
      frame.trimStart().substringBefore(">") shouldContain "hx-swap-oob"
    }

    "an empty proxy should say so in the path layout too" {
      ProxyDashboardHtml.pushFragment(
        snapshot(agents = emptyList()),
        null,
        "/dashboard",
        DashboardLayout.PATH,
      ) shouldContain
        "No paths registered"
    }

    // ==================== Status bar ====================

    // health.pathCount counts registrations, so departed paths are deliberately absent from it. Left
    // unexplained that reads as the bar contradicting the table under it -- "0 paths" above three rows.
    "the status bar should name departed paths so its count stops contradicting the table" {
      val departed =
        DashboardFixtures.pathView(
          path = "orphan_metrics",
          agentIds = emptyList(),
          agentNames = emptyList(),
          lastScrape = DashboardFixtures.scrapeRecord(path = "orphan_metrics"),
          isDeparted = true,
        )
      val snap = DashboardFixtures.snapshot(agents = emptyList(), paths = [departed])

      ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", DashboardLayout.PATH) shouldContain "1 departed"
    }

    "the status bar should stay quiet about departed paths when there are none" {
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard") shouldNotContain "departed"
    }

    // ==================== Accessibility ====================

    // The beacon reports connection state with colour and a change of rhythm, neither of which reaches a
    // screen reader. Without a live region the page just goes silently stale.
    "the page should carry a polite live region for connection state" {
      val html = renderFullPage()

      html shouldContain """id="${ProxyDashboardHtml.ANNOUNCER_ID}""""
      html shouldContain """aria-live="polite""""
      html shouldContain """role="status""""
      html shouldContain "Connection to the proxy lost"
      html shouldContain "Connection to the proxy restored"
    }

    // A region the push loop rewrites would re-announce itself on every frame; this one is written only
    // by the socket lifecycle handlers, so it must stay out of every out-of-band fragment.
    "the live region must not be part of any pushed fragment" {
      ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard") shouldNotContain ProxyDashboardHtml.ANNOUNCER_ID
      ProxyDashboardHtml.pushFragment(
        snapshot(),
        null,
        "/dashboard",
        DashboardLayout.PATH,
      ) shouldNotContain ProxyDashboardHtml.ANNOUNCER_ID
    }

    "the page should declare its language and mark its main region" {
      val html = renderFullPage()

      html shouldContain """lang="en""""
      html shouldContain "<main"
    }

    "the path table should be captioned and its headers scoped" {
      val html = renderFullPage(DashboardLayout.PATH)

      html shouldContain """scope="col""""
      html shouldContain "<caption"
      html shouldContain "Registered paths"
    }

    // ProxyDashboardService hands the renderer a base with no trailing slash, which is empty at a root
    // mount. Every sub-route must still join with exactly one separator: "//events" is a
    // protocol-relative URL, which sends the browser to a host named "events".
    "a root-mounted dashboard should not emit protocol-relative links" {
      val page =
        createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "", DashboardLayout.AGENT) } }
      page shouldContain """hx-ws:connect="/events""""
      page shouldContain """src="/${ProxyDashboardService.ASSET_DIR}/htmx.min.js""""
      page shouldContain """href="/paths""""
      page shouldContain """hx-get="/agents/1""""
      page shouldNotContain "\"//"
      // The Agents link is the root itself, which must stay "/" rather than collapse to an empty href.
      page shouldContain """href="/" class="on""""
    }

    "layout parsing should tolerate anything a browser might send" {
      ProxyDashboardService.parseLayout("""{"layout":"PATH"}""") shouldBe DashboardLayout.PATH
      ProxyDashboardService.parseLayout("""{"layout":"AGENT"}""") shouldBe DashboardLayout.AGENT
      // Everything unexpected falls back to the layout the page served before layouts existed.
      ProxyDashboardService.parseLayout("""{"layout":"SIDEWAYS"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("""{"select":"a1"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("not json at all") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("") shouldBe DashboardLayout.AGENT
    }

    "the nav should mark the current layout" {
      val agentPage = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.AGENT)
      // The nav lives on the page shell rather than in a pushed region, so assert it where it renders.
      val page = createHTML().html {
        with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard", DashboardLayout.PATH) }
      }
      page shouldContain """href="/dashboard/paths""""
      page shouldContain """aria-current="page""""
      agentPage shouldNotContain "aria-current=\"page\""
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
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
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
    "the page should use no htmx 2 WebSocket names" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
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
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "document.addEventListener('htmx:after:history:push', announce)"
      page shouldContain "window.addEventListener('popstate', announce)"
    }

    // The extension closes the socket of a hidden tab and reopens it when the tab is shown. That pause must
    // not read as an outage, or every tab switch would turn the beacon red and announce a lost connection.
    "the page should ignore a close made while the tab is hidden" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "if (!document.hidden) connected(false)"
    }

    // Each failed reconnect attempt closes the socket again. Writing the live region on every close would
    // re-announce the outage every couple of seconds, so only the transition into it is announced.
    "the page should announce a lost connection once per outage" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "if (!up && !wasDown) region.textContent = 'Connection to the proxy lost. Reconnecting.'"
      page shouldContain "else if (up && wasDown) region.textContent = 'Connection to the proxy restored.'"
    }

    // htmx 4 refetches the page on every back or forward and runs the inline scripts of what it swaps in.
    // Without the guard each navigation would stack another set of listeners.
    "the page script should run only once per document" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "if (window.__proxyUiInit) return;"
    }

    // The extension's default backoff doubles up to a minute and only resets on a successful open, so a
    // brief outage can leave the page idle for tens of seconds after the proxy is already back. The page
    // caps it in htmx's config; this guards that cap against regressing to the default, which would
    // silently reintroduce the long lag.
    "the page should cap the WebSocket reconnect backoff" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      // kotlinx.html escapes the attribute's quotes, so match the names and values, not the JSON.
      page shouldContain "reconnectMaxDelay&quot;:2000"
      page shouldContain "reconnectDelay&quot;:500"
    }

    // htmx 4 swaps a 4xx or 5xx response in. A row click that failed would replace #detail with the error
    // body, and the pushes, which address #detail, would have nothing left to update.
    "the page should keep error responses from being swapped in" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "noSwap&quot;:[204,304,&quot;4xx&quot;,&quot;5xx&quot;]"
    }

    // htmx reads its config once, when htmx.min.js loads, so a meta tag after the script is ignored.
    "the htmx config should precede the htmx script" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      val config = page.indexOf("""name="htmx-config"""")
      val script = page.indexOf("htmx.min.js")
      (config in 0..<script) shouldBe true
    }

    // The assets are cached for a year, so their URLs must change when htmx does; a fixed URL would leave a
    // browser running its cached htmx against the next version's markup.
    "the page should load its scripts from the versioned asset directory" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      ProxyDashboardService.ASSET_DIR shouldContain ProxyDashboardService.HTMX_VERSION
      page shouldContain """src="/dashboard/${ProxyDashboardService.ASSET_DIR}/htmx.min.js""""
      page shouldContain """src="/dashboard/${ProxyDashboardService.ASSET_DIR}/hx-ws.min.js""""
    }

    "the stylesheet should define the disconnected treatment and hide it by default" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      // Hidden until the body carries ws-down.
      page shouldContain ".conn .conn-down { display:none; }"
      page shouldContain "body.ws-down .conn .conn-down { display:inline; }"
      page shouldContain "body.ws-down .beacon"
    }
  }
}
