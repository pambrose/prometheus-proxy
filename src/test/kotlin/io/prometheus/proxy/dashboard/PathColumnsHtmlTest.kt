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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.TestPorts
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import io.prometheus.proxy.dashboard.DashboardFixtures.snapshot
import kotlin.time.Instant

/**
 * The Paths view's long-standing columns, [PathTableHtml]: a row's path, agent, target, source, and last scrape, with
 * dashes for what a path lacks; a departed path's row; the buttons that copy a path's proxy URL and its target; and a
 * scrape's time, which the Agents view's detail shows too. Its newer columns and its search are in
 * [PathTableHtmlTest].
 */
class PathColumnsHtmlTest : StringSpec() {
  private fun agent(paths: List<PathView>) = DashboardFixtures.agentView(paths = paths)

  init {
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
  }
}
