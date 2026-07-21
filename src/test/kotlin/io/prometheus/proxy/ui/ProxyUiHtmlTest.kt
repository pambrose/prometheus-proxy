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

package io.prometheus.proxy.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.proxy.ScrapeRecord
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ProxyUiHtmlTest : StringSpec() {
  private fun path(
    name: String,
    agents: List<String> = ["1"],
  ) = PathView(name, agents, labels = "{}")

  private fun agent(
    id: String = "1",
    name: String = "team-a-01",
    paths: List<PathView> = [path("app_metrics")],
  ) = AgentView(
    agentId = id,
    agentName = name,
    hostName = "worker-3",
    remoteAddr = "10.0.1.14:5555",
    launchId = "launch-abc",
    consolidated = false,
    isValid = true,
    connectTime = Instant.now().minusSeconds(120),
    inactivity = 2.seconds,
    backlogSize = 0,
    paths = paths,
  )

  private fun snapshot(
    agents: List<AgentView> = [agent()],
    scrapes: List<ScrapeRecord> = emptyList(),
  ) = ProxySnapshot(
    agents = agents,
    paths = agents.flatMap { it.paths }.distinct(),
    scrapes = scrapes,
    health = HealthView(agents.size, 1, 0, 25, 1, 25),
    maxAgentInactivitySecs = 60,
  )

  init {
    "the push fragment should carry out-of-band swaps for every live region" {
      val html = ProxyUiHtml.pushFragment(snapshot(), selectedId = "1", uiPath = "/ui")

      // Each region is swapped independently by id, which is what lets one frame update the list and
      // the detail pane without re-rendering the page.
      html shouldContain """id="${ProxyUiHtml.AGENT_LIST_ID}""""
      html shouldContain """id="${ProxyUiHtml.DETAIL_ID}""""
      html shouldContain """id="${ProxyUiHtml.STATUS_ID}""""
      html shouldContain """hx-swap-oob="true""""
    }

    "an agent row should carry the htmx attributes that drive selection" {
      val html = ProxyUiHtml.pushFragment(snapshot(), selectedId = null, uiPath = "/ui")

      // Selection lives in the URL via hx-push-url, so it survives reload and is bookmarkable.
      html shouldContain """hx-get="/ui/agents/1""""
      html shouldContain """hx-target="#${ProxyUiHtml.DETAIL_ID}""""
      html shouldContain """hx-push-url="true""""
    }

    "the selected agent should be marked current" {
      ProxyUiHtml.pushFragment(snapshot(), "1", "/ui") shouldContain """aria-current="true""""
      ProxyUiHtml.pushFragment(snapshot(), null, "/ui") shouldNotContain "aria-current"
    }

    // The case the whole UI exists for: an operator is watching an agent when it drops. A stale or
    // blank pane would read as a frozen dashboard, which is worse than an explicit message.
    "a selected agent that disconnected should render an explicit gone state" {
      val html = ProxyUiHtml.detailFragment(snapshot(agents = emptyList()), selectedId = "1")

      html shouldContain "no longer connected"
      html shouldContain "gone"
    }

    "no selection should render a prompt rather than an empty pane" {
      ProxyUiHtml.detailFragment(snapshot(), selectedId = null) shouldContain "Select an agent"
    }

    // Identity arrives at registerAgent, strictly after the transport filter creates the context, so a
    // just-connected agent legitimately has none. Showing the raw sentinel would read as corruption.
    "an agent without identity yet should not show the raw placeholder" {
      val html = ProxyUiHtml.pushFragment(snapshot(agents = [agent(name = "Unassigned")]), null, "/ui")

      html shouldNotContain "Unassigned"
      html shouldContain "registering"
    }

    "the detail pane should show only the selected agent's scrapes" {
      val scrapes =
        [
          ScrapeRecord("1", "app_metrics", 200, "success", 41, 1800),
          ScrapeRecord("2", "other_metrics", 503, "agent_disconnected", 0, 0),
        ]
      val html = ProxyUiHtml.detailFragment(snapshot(scrapes = scrapes), selectedId = "1")

      html shouldContain "app_metrics"
      html shouldNotContain "other_metrics"
    }

    "an empty proxy should say so rather than render an empty list" {
      ProxyUiHtml.pushFragment(snapshot(agents = emptyList()), null, "/ui") shouldContain "No agents connected"
    }

    // Browser-supplied input on a port with no auth: malformed content must degrade to "no selection"
    // rather than throw and drop the WebSocket session.
    "selection parsing should tolerate anything a browser might send" {
      ProxyUiService.parseSelection("""{"select":"agent-7"}""") shouldBe "agent-7"
      ProxyUiService.parseSelection("""{"select":null}""").shouldBeNull()
      ProxyUiService.parseSelection("""{"select":""}""").shouldBeNull()
      ProxyUiService.parseSelection("""{"other":"x"}""").shouldBeNull()
      ProxyUiService.parseSelection("not json at all").shouldBeNull()
      ProxyUiService.parseSelection("").shouldBeNull()
      ProxyUiService.parseSelection("[1,2,3]").shouldBeNull()
    }
  }
}
