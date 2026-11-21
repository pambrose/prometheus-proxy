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

package io.prometheus.agent.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.agent.AgentPathManager.RegistrationFailure
import io.prometheus.agent.PathSource
import io.prometheus.agent.ShownFailure
import io.prometheus.agent.dashboard.AgentDashboardHtml.renderPage
import io.prometheus.common.dashboard.DashboardPage
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/**
 * [AgentDashboardHtml]: the agent dashboard's markup, from snapshots built here. Every value a path, a proxy, or a
 * target supplies escaped; an en dash for a value never given; the empty and gone states; the regions a push carries
 * for each view; the title's count of problems; and when a refused path is tried again. The routes are covered live
 * in `AgentWebDashboardTest`.
 */
class AgentDashboardHtmlTest : StringSpec() {
  private val at = Instant.parse("2026-11-21T12:00:00Z")

  private fun snapshot(
    paths: List<AgentPathView> = emptyList(),
    connection: ConnectionView = ConnectionView(ConnectionState.CONNECTED, [EndpointView("proxy:50051", true)]),
  ) = AgentSnapshot(
    agentName = "agent-7",
    agentId = "12",
    launchId = "launch-1",
    version = "Version: test",
    uptime = 5.minutes,
    backlog = 0,
    connection = connection,
    paths = paths,
    discovery = null,
    settings = ["Scrape timeout" to "15s"],
  )

  private fun page(
    snapshot: AgentSnapshot,
    selected: String? = null,
    layout: AgentLayout = AgentLayout.PATHS,
  ) = createHTML().html { renderPage(snapshot, AgentView(layout, selected), "/dashboard") }

  private fun refusal(
    reason: String = "Agent identity 'team_a' is not authorized to register path /x",
    retrying: Boolean = false,
  ) = RegistrationFailure(ShownFailure(reason, at), refused = true, retrying = retrying, retryAt = null)

  init {
    "a path, a reason, and a URL from outside should be escaped, never markup" {
      val hostile = "<script>alert(1)</script>"
      val snapshot =
        snapshot(
          [
            AgentPathView(
              path = hostile,
              source = PathSource.DISCOVERED,
              url = "http://t/$hostile",
              state = PathState.REFUSED,
              failure = refusal(reason = hostile),
            ),
          ],
        )
      listOf(page(snapshot), page(snapshot, selected = hostile)).forEach { html ->
        html shouldNotContain hostile
        html shouldContain "&lt;script&gt;"
      }
    }

    "a path refused for a cause that can't clear should say so, with the proxy's reason and when it's tried again" {
      val path =
        AgentPathView("orders", PathSource.STATIC, "http://orders/metrics", PathState.REFUSED, failure = refusal())
      page(snapshot([path]), selected = "orders").apply {
        this shouldContain "Refused by the proxy"
        this shouldContain "is not authorized to register path /x"
        this shouldContain "not until the agent reconnects"
        this shouldContain """<span class="led crit"></span>/orders<span class="tag crit">refused</span>"""
      }
    }

    "a retry's wait should read as a countdown, and one already due as the next retry" {
      val clock = TestTimeSource()
      val due = clock.markNow() + 90.seconds
      AgentDashboardHtml.nextTry(refusal(retrying = true).copy(retryAt = due)) shouldBe "in 1m"
      clock += 2.minutes
      AgentDashboardHtml.nextTry(refusal(retrying = true).copy(retryAt = due)) shouldBe "at the next retry"
      AgentDashboardHtml.nextTry(refusal(retrying = true)) shouldBe "at the next retry"
    }

    "a path with no labels should show an en dash, not an empty cell" {
      val path = AgentPathView("orders", PathSource.STATIC, "http://orders/metrics", PathState.REGISTERED)
      page(snapshot([path]), selected = "orders") shouldContain
        """<span class="k">Labels</span><span class="v">${DashboardPage.DASH}</span>"""
    }

    "with nothing selected, or a path no longer known, the detail pane should say so" {
      page(snapshot()) shouldContain "Select a path to see where it stands with the proxy"
      page(snapshot(), selected = "gone_metrics") shouldContain
        "/gone_metrics is no longer configured, discovered, or registered"
      page(snapshot()) shouldContain "No paths configured or discovered"
    }

    "a failing connection should show each endpoint's last error, and the endpoint being tried" {
      val connection =
        ConnectionView(
          ConnectionState.FAILING,
          [
            EndpointView(
              "proxy-a:50051",
              current = false,
              failure = ShownFailure("StatusException: UNAVAILABLE", at),
            ),
            EndpointView("proxy-b:50051", current = true),
          ],
        )
      page(snapshot(connection = connection), layout = AgentLayout.CONNECTION).apply {
        this shouldContain "failing · proxy-b:50051"
        this shouldContain """<span class="tag warn">failed over</span>"""
        this shouldContain "StatusException: UNAVAILABLE"
        this shouldContain """proxy-b:50051<span class="tag">trying</span>"""
      }
    }

    // The discovery file's last read, what it skipped, and its conflicts, on the Connection view.
    "with discovery on, the Connection view should show its file, its last read's failure, and its conflicts" {
      val discovery =
        DiscoveryView(
          file = "/etc/agent/targets.conf",
          intervalSecs = 30,
          failure = ShownFailure("ConfigException: line 3: expecting a value", at),
          entries = 2,
          skipped = ["no_url" to ""],
          collisions = ["orders_metrics"],
          duplicates = ["billing_metrics"],
        )
      createHTML().html {
        renderPage(snapshot().copy(discovery = discovery), AgentView(AgentLayout.CONNECTION), "/dashboard")
      }
        .apply {
          this shouldContain "/etc/agent/targets.conf"
          this shouldContain "failed, keeping the paths last read: ConfigException: line 3: expecting a value"
          this shouldContain "'no_url' ''"
          this shouldContain "/orders_metrics"
          this shouldContain "/billing_metrics"
        }
      createHTML().html {
        renderPage(
          snapshot().copy(discovery = DiscoveryView("/f", 30)),
          AgentView(AgentLayout.CONNECTION),
          "/dashboard",
        )
      } shouldContain """<span class="k">Last read</span><span class="v">ok</span>"""
    }

    "the title should count the paths refused or retrying, and a failing connection" {
      val refused = AgentPathView("a", PathSource.STATIC, "", PathState.REFUSED)
      val retrying = AgentPathView("b", PathSource.STATIC, "", PathState.RETRYING)
      val pending = AgentPathView("c", PathSource.STATIC, "", PathState.PENDING)
      AgentDashboardHtml.pageTitle(snapshot()) shouldBe "prometheus-agent"
      AgentDashboardHtml.pageTitle(snapshot([refused, pending])) shouldBe "(1 problem) prometheus-agent"
      AgentDashboardHtml.pageTitle(
        snapshot([refused, retrying], ConnectionView(ConnectionState.FAILING, [EndpointView("p:1", true)])),
      ) shouldBe "(3 problems) prometheus-agent"
    }

    "a push should carry the status bar first, stamped, and the regions of the view the page shows" {
      val snapshot = snapshot([AgentPathView("orders", PathSource.STATIC, "", PathState.REGISTERED)])
      AgentDashboardHtml.pushFragment(snapshot, null, "/dashboard").apply {
        this shouldStartWith
          """<span class="bar-right" id="${DashboardPage.STATUS_ID}" hx-swap-oob="true" ${DashboardPage.VIEW_ATTR}="0""""
        this shouldNotContain AgentDashboardHtml.PATH_LIST_ID
      }
      AgentDashboardHtml.pushFragment(snapshot, AgentView(AgentLayout.PATHS, "orders", seq = 4), "/dashboard").apply {
        this shouldContain """${DashboardPage.VIEW_ATTR}="4""""
        this shouldContain """id="${AgentDashboardHtml.PATH_LIST_ID}" hx-swap-oob="true""""
        this shouldContain """id="${AgentDashboardHtml.DETAIL_ID}" hx-swap-oob="true""""
        this shouldNotContain AgentDashboardHtml.CONNECTION_ID
      }
      AgentDashboardHtml.pushFragment(snapshot, AgentView(AgentLayout.CONNECTION), "/dashboard").apply {
        this shouldContain """id="${AgentDashboardHtml.CONNECTION_ID}" hx-swap-oob="true""""
        this shouldNotContain AgentDashboardHtml.PATH_LIST_ID
      }
    }

    "a path row should select it by its URL, encoded, and the page's script should know the agent's views" {
      val snapshot = snapshot([AgentPathView("a b", PathSource.STATIC, "", PathState.PENDING)])
      page(snapshot).apply {
        this shouldContain """hx-get="/dashboard/paths/a%20b""""
        this shouldContain """var LAYOUTS = {"connection":"CONNECTION"};"""
        this shouldContain "Connection to the agent lost. Reconnecting."
        this shouldContain "if (window.__agentUiInit) return;"
      }
    }
  }
}
