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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.proxy.ScrapeRecord
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The dashboard's renderer, [ProxyDashboardHtml]: the Agents view's list and detail pane, the pushes' out-of-band
 * regions and view stamp, tolerant parsing of what a browser sends, the status bar, accessibility, an agent's
 * identity, the data the demo's screenshot script reads, and the page's links and nav. The Paths view's long-standing
 * columns are in [PathColumnsHtmlTest], its newer ones and its search in [PathTableHtmlTest], the Activity view in
 * [ActivityHtmlTest], and the page's script in [DashboardScriptTest].
 */
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
    paths: List<PathView> = agents.flatMap { it.paths }.distinct(),
  ) = DashboardFixtures.snapshot(agents = agents, paths = paths)

  // Agent "1" serving a path for each of [scrapes]' paths, each path's history its records, in the order given.
  private fun scraped(scrapes: List<ScrapeRecord>): ProxySnapshot {
    val paths =
      scrapes.groupBy { it.path }.map { (name, records) -> DashboardFixtures.pathView(path = name, history = records) }
    return snapshot(agents = [agent(paths = paths)])
  }

  // An agent row's start tag, which carries every attribute the row has.
  private fun rowTag(
    html: String,
    agentId: String,
  ): String =
    Regex("""<button [^>]*id="${ProxyDashboardHtml.AGENT_ROW_ID_PREFIX}$agentId"[^>]*>""")
      .find(html)
      .shouldNotBeNull()
      .value

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

    // The push loop replaces the agent list every frame, and htmx gives focus back only to an element with the id the
    // focused one had. Without an id on each row, a keyboard user lost their place in the list on every refresh.
    "an agent row should keep one id per agent across the page and its pushes" {
      val agents = [agent(id = "1", name = "team-a-01"), agent(id = "2", name = "team-b-01")]
      val page =
        renderFullPage(snapshot(agents = agents))
      val frame = ProxyDashboardHtml.pushFragment(snapshot(agents = agents), null, "/dashboard")

      listOf(page, frame).forEach { html ->
        html shouldContain """id="${ProxyDashboardHtml.AGENT_ROW_ID_PREFIX}1""""
        html shouldContain """id="${ProxyDashboardHtml.AGENT_ROW_ID_PREFIX}2""""
      }
    }

    // Behind nginx an agent names its own agent id, so a row id must not be able to equal a region's: an agent called
    // "list" would otherwise take #agent-list, which every push addresses.
    "an agent row's id should never equal a region's id" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(agents = [agent(id = "list")]), null, "/dashboard")

      Regex("""id="${ProxyDashboardHtml.AGENT_LIST_ID}"""").findAll(html).count() shouldBe 1
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

    // A consolidated path's history holds every agent's scrapes of it, so the detail picks out the selected agent's.
    "the detail pane should show only the selected agent's scrapes" {
      val ours = DashboardFixtures.scrapeRecord(agentId = "1")
      val theirs = DashboardFixtures.scrapeRecord(agentId = "2", outcome = "agent_disconnected")
      val shared = DashboardFixtures.pathView(agentIds = ["1", "2"], history = [theirs, ours])
      val agents = [agent(paths = [shared]), agent(id = "2", name = "team-a-02", paths = [shared])]
      val html = ProxyDashboardHtml.detailFragment(snapshot(agents = agents), selectedId = "1")

      Regex("""class="kv scrape"""").findAll(html).count() shouldBe 1
      html shouldContain " · success</span>"
      html shouldNotContain "agent_disconnected"
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

    "an agent's uptime should show its two largest units from a day up, then one" {
      ProxyDashboardHtml.humanize(45.seconds) shouldBe "45s"
      ProxyDashboardHtml.humanize(3.minutes + 59.seconds) shouldBe "3m"
      ProxyDashboardHtml.humanize(2.hours + 5.minutes + 30.seconds) shouldBe "2h 5m"
      ProxyDashboardHtml.humanize(1.days + 3.hours + 20.minutes) shouldBe "1d 3h"
    }

    "the agent detail should show how long the agent has been connected" {
      val agent = DashboardFixtures.agentView(agentId = "1")
      ProxyDashboardHtml.detailFragment(snapshot(agents = [agent]), selectedId = "1") shouldContain " · up 2m · "
    }

    // ==================== Pushes ====================

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
      // The very first element is a real region carrying hx-swap-oob -- the status bar, which carries the
      // frame's view stamp -- not a bare <div> wrapper.
      frame shouldStartWith """<span class="bar-right""""
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

    // The page drops a frame rendered from an older message than its latest, which closes the race between
    // a row click and a tick rendered from the previous selection. It reads the stamp from the frame's first
    // tag, so the status region must come first, in every layout.
    "a push should stamp its view count on the frame's first tag" {
      listOf(DashboardLayout.AGENT, DashboardLayout.PATH, null).forEach { layout ->
        val frame = ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard", layout, viewSeq = 7)
        VIEW_STAMP.find(frame)?.groupValues?.get(1) shouldBe "7"
      }
      // A session that has sent nothing yet is stamped 0.
      VIEW_STAMP.find(ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", null))
        ?.groupValues
        ?.get(1) shouldBe "0"
    }

    // The match is anchored at the start of the frame, inside the status region's tag. An agent that names
    // itself after the stamp lands in the agent list, after that tag, so it cannot stand in for the real
    // one -- a forged 0 would otherwise make every page drop every frame and freeze.
    "an agent-supplied value should not be read as the view stamp" {
      val forged = snapshot(agents = [agent(name = """x" data-view="0" """)])
      val frame = ProxyDashboardHtml.pushFragment(forged, "1", "/dashboard", DashboardLayout.AGENT, viewSeq = 9)
      VIEW_STAMP.find(frame)?.groupValues?.get(1) shouldBe "9"
    }

    "view count parsing should tolerate anything a browser might send" {
      ProxyDashboardService.parseSeq("""{"seq":12}""") shouldBe 12L
      ProxyDashboardService.parseSeq("""{"select":"a1","layout":"AGENT","seq":3}""") shouldBe 3L
      // Everything unexpected falls back to 0, which a page predating the field never compares against.
      ProxyDashboardService.parseSeq("""{"seq":"12"}""") shouldBe 0L
      ProxyDashboardService.parseSeq("""{"seq":-1}""") shouldBe 0L
      ProxyDashboardService.parseSeq("""{"seq":1.5}""") shouldBe 0L
      ProxyDashboardService.parseSeq("""{"seq":null}""") shouldBe 0L
      ProxyDashboardService.parseSeq("""{"select":"a1"}""") shouldBe 0L
      ProxyDashboardService.parseSeq("not json at all") shouldBe 0L
    }

    // ==================== Status bar ====================

    // health.pathCount counts registrations, so departed paths are deliberately absent from it. Left
    // unexplained that reads as the bar contradicting the table under it -- "0 paths" above three rows.
    "the status bar should name departed paths so its count stops contradicting the table" {
      val snap = DashboardFixtures.snapshot(agents = emptyList(), paths = [departedPathView()])

      ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", DashboardLayout.PATH) shouldContain "1 departed"
    }

    "the status bar should stay quiet about departed paths when there are none" {
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard") shouldNotContain "departed"
    }

    // ==================== Accessibility ====================

    // The beacon reports connection state with colour and a change of rhythm, neither of which reaches a
    // screen reader. Without a live region the page just goes silently stale.
    "the page should carry a polite live region for connection state" {
      val html = renderFullPage(snapshot())

      html shouldContain """id="${ProxyDashboardHtml.ANNOUNCER_ID}""""
      html shouldContain """aria-live="polite""""
      html shouldContain """role="status""""
      html shouldContain "Connection to the proxy lost"
      html shouldContain "Connection to the proxy restored"
    }

    // A region the push loop rewrites would re-announce itself on every frame; this one is written only
    // by the socket lifecycle handlers, so it must stay out of every out-of-band fragment.
    // Every layout's push, now that the Paths and Activity pages carry a column guide outside their tables too.
    "the live region must not be part of any pushed fragment" {
      (DashboardLayout.entries + null).forEach { layout ->
        ProxyDashboardHtml.pushFragment(snapshot(), "1", "/dashboard", layout) shouldNotContain
          ProxyDashboardHtml.ANNOUNCER_ID
      }
    }

    "the page should declare its language and mark its main region" {
      val html = renderFullPage(snapshot())

      html shouldContain """lang="en""""
      html shouldContain "<main"
    }

    // ==================== Agent identity ====================

    // A tag after the name, on the name's line rather than the address's, which the list's width wraps.
    "the agent list and detail should show the agent's identity" {
      val agent = DashboardFixtures.agentView(agentName = "agent-east", identity = "east")
      val snap = snapshot(agents = [agent])

      ProxyDashboardHtml.pushFragment(snap, null, "/dashboard") shouldContain
        "agent-east<span class=\"tag\">east</span></span><span class=\"host\">10.0.1.14:5555 · 1 paths</span>"
      ProxyDashboardHtml.detailFragment(snap, selectedId = "1") shouldContain " · host worker-3 · identity east · "
    }

    "an agent without an identity should show none" {
      ProxyDashboardHtml.detailFragment(snapshot(), selectedId = "1") shouldNotContain "identity"
    }

    // ==================== The demo's screenshot script ====================

    // demos/dashboard/screenshots.sh finds an agent's row by its name and reads its id and path count from these, not
    // from the row's text, which every change to the row's look broke. The page and every push render the row.
    "an agent row should carry its id, name, and path count as data" {
      val agents = [agent(id = "7", name = "agent-east", paths = [path("a_metrics"), path("b_metrics")])]
      val page = renderFullPage(snapshot(agents = agents))
      val frame = ProxyDashboardHtml.pushFragment(snapshot(agents = agents), null, "/dashboard")

      listOf(page, frame).forEach { html ->
        val row = rowTag(html, "7")
        row shouldContain """${ProxyDashboardHtml.AGENT_ATTR}="7""""
        row shouldContain """${ProxyDashboardHtml.AGENT_NAME_ATTR}="agent-east""""
        row shouldContain """${ProxyDashboardHtml.PATH_COUNT_ATTR}="2""""
      }
    }

    // The duplicate-agent scene waits for agent-east's row to say its copy has taken every path.
    "an agent row with no paths should count 0" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(agents = [agent(paths = emptyList())]), null, "/dashboard")

      rowTag(html, "1") shouldContain """${ProxyDashboardHtml.PATH_COUNT_ATTR}="0""""
    }

    // Through agentLabel, as the row's text names it, so the data and the text never name an agent differently.
    "an agent row's name should be the label the row shows" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(agents = [agent(name = "Unassigned")]), null, "/dashboard")

      rowTag(html, "1") shouldContain
        """${ProxyDashboardHtml.AGENT_NAME_ATTR}="${ProxyDashboardHtml.agentLabel("Unassigned")}""""
    }

    // An agent names itself, so its name reaches the attribute escaped, and the script's match of a row's start tag, up
    // to the first ">", ends at the tag's own end.
    "an agent row's name should be escaped in its attribute" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(agents = [agent(name = "a\"b<c>&d")]), null, "/dashboard")

      rowTag(html, "1") shouldContain """${ProxyDashboardHtml.AGENT_NAME_ATTR}="a&quot;b&lt;c&gt;&amp;d""""
    }

    // The script waits for an agent's detail to count enough recent scrapes to fill a fixed-height screenshot. The
    // data sits on the header whose text gives the same count.
    "the detail's Recent scrapes header should carry their count as data" {
      val scrapes =
        [DashboardFixtures.scrapeRecord(path = "a_metrics"), DashboardFixtures.scrapeRecord(path = "b_metrics")]

      ProxyDashboardHtml.detailFragment(scraped(scrapes), selectedId = "1") shouldContain
        """<div class="section" ${ProxyDashboardHtml.SCRAPE_COUNT_ATTR}="2"><span>Recent scrapes</span><span>2</span>"""
      ProxyDashboardHtml.detailFragment(snapshot(), selectedId = "1") shouldContain
        """${ProxyDashboardHtml.SCRAPE_COUNT_ATTR}="0""""
      // Past the cap the snapshot keeps the agent's latest, so the count is the rows the script can crop.
      val many = (0..ProxySnapshot.MAX_DETAIL_SCRAPES).map { DashboardFixtures.scrapeRecord(path = "p${it % 3}") }
      val html = ProxyDashboardHtml.detailFragment(scraped(many), selectedId = "1")
      html shouldContain """${ProxyDashboardHtml.SCRAPE_COUNT_ATTR}="${ProxySnapshot.MAX_DETAIL_SCRAPES}""""
      Regex("""class="kv scrape"""").findAll(html).count() shouldBe ProxySnapshot.MAX_DETAIL_SCRAPES
    }

    // The script tells a scrape since its wait began from an older one by ordering the Paths view's scrape times as
    // strings, which holds only while every one has the same width: its seconds even at a whole minute, no fraction.
    "a scrape time should keep its seconds at a whole minute" {
      val at = Instant.parse("2026-01-05T00:00:00Z")
      val scraped = DashboardFixtures.pathView(lastScrape = DashboardFixtures.scrapeRecord(at = at))

      ProxyDashboardHtml.pushFragment(
        snapshot(paths = [scraped]),
        null,
        "/dashboard",
        DashboardLayout.PATH,
      ) shouldContain
        """<time datetime="2026-01-05T00:00:00Z">"""
    }

    // ==================== Page and navigation ====================

    // ProxyDashboardService hands the renderer a base with no trailing slash, which is empty at a root
    // mount. Every sub-route must still join with exactly one separator: "//events" is a
    // protocol-relative URL, which sends the browser to a host named "events".
    "a root-mounted dashboard should not emit protocol-relative links" {
      val page = renderFullPage(snapshot(), routeBase = "")
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
      ProxyDashboardService.parseLayout("""{"layout":"ACTIVITY"}""") shouldBe DashboardLayout.ACTIVITY
      // Everything unexpected falls back to the layout the page served before layouts existed.
      ProxyDashboardService.parseLayout("""{"layout":"SIDEWAYS"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("""{"select":"a1"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("not json at all") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("") shouldBe DashboardLayout.AGENT
    }

    "the nav should link every layout and mark the current one" {
      val agentPage = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.AGENT)
      // The nav lives on the page shell rather than in a pushed region, so assert it where it renders.
      val page = renderFullPage(snapshot(), DashboardLayout.PATH)
      page shouldContain """href="/dashboard/paths" class="on" aria-current="page">Paths</a>"""
      page shouldContain """<a href="/dashboard/activity">Activity</a>"""
      renderFullPage(snapshot(), DashboardLayout.ACTIVITY) shouldContain
        """href="/dashboard/activity" class="on" aria-current="page">Activity</a>"""
      agentPage shouldNotContain "aria-current=\"page\""
    }
  }

  private companion object {
    // What the page script matches: the stamp inside the frame's first tag.
    val VIEW_STAMP = Regex("""^<span[^>]*\s${ProxyDashboardHtml.VIEW_ATTR}="(\d+)"""")
  }
}
