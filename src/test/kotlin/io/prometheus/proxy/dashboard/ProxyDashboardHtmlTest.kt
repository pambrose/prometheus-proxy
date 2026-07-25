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

  init {
    "the push fragment should carry out-of-band swaps for every live region" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(), selectedId = "1", dashboardPath = "/dashboard")

      // Each region is swapped independently by id, which is what lets one frame update the list and
      // the detail pane without re-rendering the page.
      html shouldContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
      html shouldContain """id="${ProxyDashboardHtml.DETAIL_ID}""""
      html shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""
      html shouldContain """hx-swap-oob="true""""
    }

    "an agent row should carry the htmx attributes that drive selection" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(), selectedId = null, dashboardPath = "/dashboard")

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
          ScrapeRecord("1", "app_metrics", 200, "success", 41, 1800),
          ScrapeRecord("2", "other_metrics", 503, "agent_disconnected", 0, 0),
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
          lastScrape = DashboardFixtures.scrapeRecord(agentId = "agent-9", path = "redis_metrics", statusCode = 503),
          isDeparted = true,
        )
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [departed]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "/redis_metrics"
      html shouldContain "gone"
      html shouldContain "agent-9"
      html shouldContain "503"
    }

    "a consolidated path should collapse its extra agents into a count" {
      val shared = DashboardFixtures.pathView(path = "shared_svc", agentIds = ["a1", "a2", "a3"])
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [shared]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "a1"
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

    // The bug that shipped: the OOB regions were wrapped in an outer <div>. The htmx WebSocket extension
    // iterates only the immediate children of the parsed message, so the wrapper was the sole child it
    // saw -- with no id to match -- and every region nested inside it was silently dropped. The page
    // then updated only on a manual reload. The regions must be top-level siblings.
    "a push must place the OOB regions at the top level, not inside a wrapper" {
      val frame = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.AGENT).trimStart()
      // The very first element is a real region carrying hx-swap-oob, not a bare <div> wrapper.
      frame shouldStartWith """<div class="md-list""""
      frame shouldNotStartWith "<div><"
      // The first tag itself is the OOB target -- hx-swap-oob appears before the region's closing markup.
      frame.substringBefore(">") shouldContain "hx-swap-oob"
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

    // Layout is derived from the URL on both sides -- the server for the initial render, the browser for
    // what it announces over the socket -- so the two must agree on what counts as the path view.
    "layout should be derived from the request path" {
      DashboardLayout.of("/dashboard/paths") shouldBe DashboardLayout.PATH
      DashboardLayout.of("/dashboard/paths/") shouldBe DashboardLayout.PATH
      DashboardLayout.of("/dashboard") shouldBe DashboardLayout.AGENT
      DashboardLayout.of("/dashboard/agents/7") shouldBe DashboardLayout.AGENT
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

    // The whole point: htmx-ext-ws reconnects on its own, and these handlers are what makes that effort
    // visible. wsClose/wsError must mark the page down; wsOpen must clear it.
    "the page script should flip the connection state on the WebSocket lifecycle events" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "htmx:wsClose"
      page shouldContain "htmx:wsError"
      page shouldContain "htmx:wsOpen"
      page shouldContain "ws-down"
      // wsConnecting also fires for the first connect on load; reacting to it would flash red before the
      // initial open, so no listener may be wired for it. Asserted as the precise listener form rather
      // than the bare event name, which the explaining comment legitimately mentions.
      page shouldNotContain "addEventListener('htmx:wsConnecting'"
    }

    // The extension's default 'full-jitter' backoff grows the retry window to 64s and only resets on a
    // successful open, so a brief outage can leave the page idle for tens of seconds after the proxy is
    // already back. The page overrides it with a low cap; this guards that override against regressing to
    // the default, which would silently reintroduce the long lag.
    "the page should cap the WebSocket reconnect backoff" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }
      page shouldContain "wsReconnectDelay"
      page shouldContain "2000"
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
