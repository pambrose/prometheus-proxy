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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.common.TestPorts
import io.prometheus.proxy.ScrapeRecord
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
      val lastScrape =
        DashboardFixtures.scrapeRecord(
          agentId = "9",
          agentName = "cache-tier-01",
          path = "redis_metrics",
          statusCode = 503,
        )
      val departed = departedPathView(path = "redis_metrics", lastScrape = lastScrape)
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

    "each target URL should be followed by a button that copies it" {
      val served = DashboardFixtures.pathView(path = "app_metrics", targetUrl = "http://app1:9090/metrics")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [served]), null, "/dashboard", DashboardLayout.PATH)

      // type="button", so a click can never submit anything should the table ever sit inside a form.
      html shouldContain """<button type="button" class="copy""""
      html shouldContain """${ProxyDashboardHtml.COPY_ATTR}="http://app1:9090/metrics""""
      html shouldContain """aria-label="Copy the target URL of /app_metrics""""
      // After the URL it copies, in the same cell.
      val button = html.indexOf("""id="${ProxyDashboardHtml.COPY_TARGET_ID_PREFIX}app_metrics"""")
      (html.indexOf("http://app1:9090/metrics<") in 0..<button) shouldBe true
    }

    // A dash is the absence of a target, not a value to copy.
    "a path without a target should have no target copy button" {
      val bare = DashboardFixtures.pathView(path = "new_path")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [bare]), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain ProxyDashboardHtml.COPY_ATTR
      html shouldNotContain ProxyDashboardHtml.COPY_TARGET_ID_PREFIX
    }

    // The URL to curl to check a path through the proxy. The proxy can't know the name the viewer reaches it by, so the
    // button carries the path and the page builds the URL from its own host and the scrape port (see SCRAPE_PORT_ATTR).
    "each registered path should be followed by a button that copies its proxy URL" {
      val served = DashboardFixtures.pathView(path = "app_metrics", targetUrl = "http://app1:9090/metrics")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [served]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain """id="${ProxyDashboardHtml.COPY_PROXY_ID_PREFIX}app_metrics""""
      html shouldContain """${ProxyDashboardHtml.PROXY_PATH_ATTR}="/app_metrics""""
      html shouldContain """aria-label="Copy the proxy URL of /app_metrics""""
      // After the path it copies, in the same cell, and before the target's button.
      val proxyButton = html.indexOf("""id="${ProxyDashboardHtml.COPY_PROXY_ID_PREFIX}app_metrics"""")
      val targetButton = html.indexOf("""id="${ProxyDashboardHtml.COPY_TARGET_ID_PREFIX}app_metrics"""")
      (html.indexOf(">/app_metrics<") in 0..<proxyButton) shouldBe true
      (proxyButton < targetButton) shouldBe true
    }

    // The proxy no longer serves a departed path, so its URL would only return 404.
    "a departed path should have no proxy URL button" {
      val departed = departedPathView(path = "redis_metrics")
      val html =
        ProxyDashboardHtml.pushFragment(snapshot(paths = [departed]), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain ProxyDashboardHtml.PROXY_PATH_ATTR
    }

    // On the page, not in the pushes: the port is the proxy's and doesn't change, and <body> is never swapped.
    "the page should carry the proxy's scrape port, and the pushes should not" {
      val page = renderFullPage(snapshot())
      page shouldContain """${ProxyDashboardHtml.SCRAPE_PORT_ATTR}="${TestPorts.PROXY_HTTP_PORT}""""
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.PATH) shouldNotContain
        ProxyDashboardHtml.SCRAPE_PORT_ATTR
    }

    // The push loop replaces the whole table every frame. htmx refocuses the element carrying the id the
    // focused one had, and the page puts the copied mark back by id, so the id must be the same in the page
    // and in every frame, and distinct per row.
    "a copy button should keep one id per path across the page and its pushes" {
      val paths =
        [
          DashboardFixtures.pathView(path = "app_metrics", targetUrl = "http://app1:9090/metrics"),
          DashboardFixtures.pathView(path = "node_metrics", targetUrl = "http://app1:9090/metrics"),
        ]
      val page = renderFullPage(snapshot(paths = paths), DashboardLayout.PATH)
      val frame = ProxyDashboardHtml.pushFragment(snapshot(paths = paths), null, "/dashboard", DashboardLayout.PATH)

      listOf(page, frame).forEach { html ->
        for (path in ["app_metrics", "node_metrics"]) {
          html shouldContain """id="${ProxyDashboardHtml.COPY_TARGET_ID_PREFIX}$path""""
          html shouldContain """id="${ProxyDashboardHtml.COPY_PROXY_ID_PREFIX}$path""""
        }
      }
    }

    // The page shows each scrape time in the viewer's zone, the one Grafana uses, from the instant in datetime. The
    // text is in the proxy's zone, so it depends on the JVM running the test and is not asserted.
    "a scrape time should carry its instant, to the second, in both layouts" {
      val at = Instant.parse("2026-11-15T01:24:23.456Z")
      val scraped =
        DashboardFixtures.pathView(
          path = "app_metrics",
          lastScrape = DashboardFixtures.scrapeRecord(path = "app_metrics", at = at),
        )
      val snap = snapshot(paths = [scraped])

      val pathFrame = ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", DashboardLayout.PATH)
      val detail = ProxyDashboardHtml.detailFragment(snap, selectedId = "1")
      listOf(pathFrame, detail).forEach { html ->
        html shouldContain """<time datetime="2026-11-15T01:24:23Z">"""
      }
    }

    // The icon buttons' rule makes an element a 24px inline box with negative margins. When the scrape-now button was
    // given the class "scrape", which each of the detail's Recent scrapes rows already had, every row became such a
    // box, and the list collapsed onto one line.
    "no button should share a class with a row of the detail's recent scrapes" {
      val scraped =
        DashboardFixtures.pathView(
          path = "app_metrics",
          lastScrape = DashboardFixtures.scrapeRecord(path = "app_metrics"),
        )
      val snap = DashboardFixtures.snapshot(agents = [agent(paths = [scraped])], scrapeNowEnabled = true)

      fun classesOf(
        html: String,
        tag: Regex,
      ) = tag.findAll(html).flatMap { it.groupValues[1].split(' ') }.toSet()

      val detail = ProxyDashboardHtml.detailFragment(snap, selectedId = "1")
      val pathFrame = ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", DashboardLayout.PATH)
      val rowClasses = classesOf(detail, Regex("""<div class="([^"]*)"><span class="t">"""))
      val buttonClasses = classesOf(pathFrame, Regex("""<button[^>]* class="([^"]*)""""))

      rowClasses shouldBe setOf("kv", "scrape")
      buttonClasses shouldContain "scrape-now"
      buttonClasses.intersect(rowClasses).shouldBeEmpty()
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

    "a path that was never scraped should show a dash rather than a time" {
      val bare = DashboardFixtures.pathView(path = "new_path")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [bare]), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain "<time"
    }

    // The target URL is agent-supplied, and the button carries it in an attribute.
    "a target URL should be escaped in the copy attribute" {
      val served = DashboardFixtures.pathView(path = "q_metrics", targetUrl = "http://h:9100/m?a=1&b=\"x\"")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [served]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain """${ProxyDashboardHtml.COPY_ATTR}="http://h:9100/m?a=1&amp;b=&quot;x&quot;""""
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

    "the page script should count its messages and drop frames stamped below the count" {
      val page = renderFullPage(snapshot())
      page shouldContain "seq: seq"
      page shouldContain "addEventListener('htmx:ws:before:message:incoming'"
      page shouldContain "evt.preventDefault()"
      // Anchored at the start of the frame, on the attribute the server writes.
      page shouldContain """/^<span[^>]*\s${ProxyDashboardHtml.VIEW_ATTR}="""
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

    // A request of the script's own, carrying the header the route requires, so another site's page can't send it.
    "the page script should post a scrape-now with htmx's header and put the result in the panel" {
      val page = renderFullPage(snapshot())

      page shouldContain "closest('button.scrape-now')"
      page shouldContain "method: 'POST', headers: { '${ProxyDashboardService.HX_REQUEST_HEADER}': 'true' }"
      page shouldContain "panel.innerHTML = html;"
      // Closing the panel hands focus back to the button that filled it.
      page shouldContain "function closeScrapeResult(panel)"
    }

    // The script tells the server which layout the page shows, from its URL, on every announce. The map comes from
    // DashboardLayout, so a new layout can't be left out of it.
    "the page script should name each layout by its route" {
      renderFullPage(snapshot()) shouldContain """var LAYOUTS = {"paths":"PATH","activity":"ACTIVITY"};"""
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
      page shouldContain "if (!document.hidden) connected(false)"
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
      page shouldContain "btn.getAttribute('${ProxyDashboardHtml.COPY_ATTR}')"
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
      page shouldContain "document.body.getAttribute('${ProxyDashboardHtml.SCRAPE_PORT_ATTR}')"
      page shouldContain "btn.getAttribute('${ProxyDashboardHtml.PROXY_PATH_ATTR}')"
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
    // Without the guard each navigation would stack another set of listeners.
    "the page script should run only once per document" {
      val page = renderFullPage(snapshot())
      page shouldContain "if (window.__proxyUiInit) return;"
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
      ProxyDashboardService.ASSET_DIR shouldContain ProxyDashboardService.HTMX_VERSION
      page shouldContain """src="/dashboard/${ProxyDashboardService.ASSET_DIR}/htmx.min.js""""
      page shouldContain """src="/dashboard/${ProxyDashboardService.ASSET_DIR}/hx-ws.min.js""""
    }

    "the stylesheet should define the disconnected treatment and hide it by default" {
      val page = renderFullPage(snapshot())
      // Hidden until the body carries ws-down.
      page shouldContain ".conn .conn-down { display:none; }"
      page shouldContain "body.ws-down .conn .conn-down { display:inline; }"
      page shouldContain "body.ws-down .beacon"
    }
  }

  private companion object {
    // What the page script matches: the stamp inside the frame's first tag.
    val VIEW_STAMP = Regex("""^<span[^>]*\s${ProxyDashboardHtml.VIEW_ATTR}="(\d+)"""")
  }
}
