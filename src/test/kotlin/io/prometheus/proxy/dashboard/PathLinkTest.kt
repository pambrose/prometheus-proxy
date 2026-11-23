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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.common.dashboard.DashboardCss
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.pathView
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord
import kotlin.math.pow

/**
 * A dashboard link's template, [PathLink]: each placeholder filled from the path and encoded, a path's labels read
 * tolerantly ([PathView.labelValues]), no link for a path lacking a value, and the refusals at startup. How the Paths table shows the links is in [PathTableHtmlTest].
 * Also a link's brand icon, [LinkIcon]: read from the config, its mark on the classpath, and its colours' contrast.
 */
class PathLinkTest : StringSpec() {
  private fun link(
    url: String,
    name: String = "Grafana",
  ) = PathLink.parseAll([PathLink.Entry(name, url)]).single()

  private val orders =
    pathView(
      path = "orders_metrics",
      agentNames = ["agent-east"],
      labels = """{"job": "orders", "team": "shop"}""",
      identity = "east",
    )

  private fun urlFor(
    url: String,
    path: PathView = orders,
  ) = link(url).urlFor(path)

  // The URL a job-label link gives the orders path registered with this job.
  private fun jobUrl(job: String) = urlFor("https://p.example.com/graph?job={label.job}", orders.withJob(job))

  private fun PathView.withJob(job: String) = copy(labels = """{"job": "$job"}""")

  // The message a template is refused with.
  private fun refusal(url: String): String = shouldThrow<IllegalArgumentException> { link(url) }.message.orEmpty()

  // Encoding keeps a value to one component, but in the scheme or host that component is where the link goes.
  private val placeholdersBeforeTheHostEnds =
    [
      "https://{label.host}/d",
      "https://grafana.{label.domain}/d",
      "https://g.example.com{path}",
      "https://g.example.com:{label.port}/d",
      "https://{agent}@g.example.com/d",
    ]

  init {
    // ==================== Filling the placeholders ====================

    "each placeholder should take its value from the path" {
      urlFor("https://runbooks.example.com/{path}?agent={agent}&identity={identity}&team={label.team}") shouldBe
        "https://runbooks.example.com/orders_metrics?agent=agent-east&identity=east&team=shop"
      urlFor("https://grafana.example.com/d/proxy") shouldBe "https://grafana.example.com/d/proxy"
    }

    // A value an agent supplies must stay one component: a slash, query, or fragment of its own would point the link
    // somewhere the operator didn't write.
    "a filled value should be percent-encoded as a URL component" {
      jobUrl("a/b?c=d&e#f g<>") shouldBe "https://p.example.com/graph?job=a%2Fb%3Fc%3Dd%26e%23f%20g%3C%3E"
      jobUrl("caf\u00e9") shouldBe "https://p.example.com/graph?job=caf%C3%A9"
      jobUrl("a-b_c.d~e") shouldBe "https://p.example.com/graph?job=a-b_c.d~e"
    }

    // A link like the demo's Prometheus one: PromQL's braces and quotes encoded in the template, the placeholder
    // literal.
    "the template's own encoding should be kept as written" {
      urlFor("http://localhost:9090/graph?g0.expr=up%7Bjob%3D%22{label.job}%22%7D") shouldBe
        "http://localhost:9090/graph?g0.expr=up%7Bjob%3D%22orders%22%7D"
    }

    "a link should keep its configured name, and the links their order" {
      PathLink.parseAll(
        [PathLink.Entry("Prometheus", "https://p.example.com"), PathLink.Entry("Grafana", "https://g.example.com")],
      ).map { it.name } shouldBe ["Prometheus", "Grafana"]
    }

    // ==================== A missing value ====================

    // The legacy shared token is an identity in name only, one token every agent shares.
    "a path lacking a value the template needs should get no link, rather than one with an empty slot" {
      urlFor("https://g.example.com/d?job={label.missing}").shouldBeNull()
      jobUrl("").shouldBeNull()
      urlFor("https://g.example.com/d?identity={identity}", orders.copy(identity = "")).shouldBeNull()
      urlFor("https://g.example.com/d?identity={identity}", orders.copy(identity = LEGACY_IDENTITY_NAME)).shouldBeNull()
      urlFor("https://g.example.com/d?agent={agent}", pathView(agentNames = emptyList())).shouldBeNull()
    }

    // An agent supplied the labels: anything that isn't a JSON object of plain values gives no label rather than an
    // exception during a push.
    "a path's labels should be read tolerantly, keeping the plain values" {
      pathView(labels = """{"job": "orders", "port": 8080, "up": true, "a": null, "b": {}, "c": []}""")
        .labelValues shouldBe mapOf("job" to "orders", "port" to "8080", "up" to "true")
      for (labels in ["", "not json", "[1, 2]", "\"orders\"", "{\"job\": "]) {
        pathView(labels = labels).labelValues shouldBe emptyMap()
      }
    }

    "a departed path should keep only the links that use no label" {
      val departed = departedPathView("orders_metrics", scrapeRecord(path = "orders_metrics", agentName = "agent-east"))

      urlFor("https://g.example.com/d?job={label.job}", departed).shouldBeNull()
      urlFor("https://runbooks.example.com/{path}?agent={agent}", departed) shouldBe
        "https://runbooks.example.com/orders_metrics?agent=agent-east"
    }

    // ==================== Refusals ====================

    "a blank name should be refused, naming the entry's position" {
      shouldThrow<IllegalArgumentException> {
        PathLink.parseAll([PathLink.Entry("Prometheus", "https://p.example.com"), PathLink.Entry(" ", "https://g.ex")])
      }.message shouldBe "proxy.dashboard.links entry 2 has a blank name"
    }

    // Placeholders are case-sensitive, as label names are.
    "an unknown placeholder should be refused, naming the link and the placeholder" {
      refusal("https://g.example.com/d?job={foo}") shouldContain
        "proxy.dashboard.links link 'Grafana' has an unknown placeholder {foo} in its url"
      for (key in ["{label.}", "{}", "{Path}"]) refusal("https://g.example.com/d?job=$key") shouldContain key
    }

    // A browser would run a javascript: link in the dashboard's own page.
    "a URL that isn't an absolute http or https URL should be refused" {
      for (url in ["/d/{path}", "ftp://f.example.com/{path}", "javascript:alert(1)", "https:///d", "{label.s}://g"]) {
        refusal(url) shouldContain "link 'Grafana' has a url that isn't an absolute http or https URL: $url"
      }
    }

    // PromQL's braces have to be percent-encoded: a brace that isn't a placeholder's stays in the URL.
    "a URL that doesn't parse should be refused with the reason" {
      refusal("""http://localhost:9090/graph?g0.expr=up{job="{label.job}"}""") shouldContain
        "link 'Grafana' has a url that isn't a valid URL (Illegal character in query)"
      refusal("https://g.example.com/a b") shouldContain "isn't a valid URL"
    }

    "a placeholder in the URL's scheme or host should be refused, and one just after the host allowed" {
      for (url in placeholdersBeforeTheHostEnds) {
        refusal(url) shouldContain "link 'Grafana' has a placeholder in its url's scheme or host"
      }
      urlFor("HTTPS://g.example.com/{path}") shouldBe "HTTPS://g.example.com/orders_metrics"
      urlFor("https://g.example.com?p={path}") shouldBe "https://g.example.com?p=orders_metrics"
      urlFor("https://g.example.com#{path}") shouldBe "https://g.example.com#orders_metrics"
    }

    // ==================== Icons ====================

    // A config is written by hand, so its case doesn't matter, and an empty icon, the default, shows the name.
    "an icon should be read ignoring case and surrounding spaces, and a blank one show the name" {
      PathLink.parseAll(
        [
          PathLink.Entry("Prometheus", "https://p.example.com", icon = "prometheus"),
          PathLink.Entry("Grafana", "https://g.example.com", icon = " Grafana "),
          PathLink.Entry("Runbook", "https://r.example.com", icon = " "),
          PathLink.Entry("Logs", "https://l.example.com"),
        ],
      ).map { it.icon } shouldBe [LinkIcon.PROMETHEUS, LinkIcon.GRAFANA, null, null]
    }

    // An icon the dashboard doesn't have would leave the link with neither a mark nor a name.
    "an unknown icon should be refused, naming the link and the icons there are" {
      shouldThrow<IllegalArgumentException> {
        PathLink.parseAll([PathLink.Entry("Logs", "https://l.example.com", icon = "loki")])
      }.message shouldBe "proxy.dashboard.links link 'Logs' has an unknown icon 'loki'; use prometheus or grafana"
    }

    "the startup log should name a link with an icon by its name and its icon" {
      PathLink.parseAll(
        [
          PathLink.Entry("Prometheus", "https://p.example.com", icon = "PROMETHEUS"),
          PathLink.Entry("Runbook", "https://r.example.com"),
        ],
      ).map { it.logged } shouldBe ["Prometheus (prometheus icon)", "Runbook"]
    }

    // The build extracts each mark from the Simple Icons WebJar, which drops brands in major versions, and names the
    // marks it extracts itself (configureDashboardIcons in build.gradle.kts), so an icon added here alone has no mark.
    "every icon's mark should be on the classpath, an SVG, under the file name the dashboard serves it as" {
      LinkIcon.entries.forEach { icon ->
        javaClass.classLoader.getResource(icon.resource).shouldNotBeNull().readText() shouldStartWith "<svg"
        icon.fileName shouldBe "${icon.slug}.svg"
      }
    }

    // WCAG 1.4.11's 3:1 for a non-text mark, rather than text's 4.5:1, since the link's name is in its tooltip and its
    // aria-label too; on --surface-2, a hovered row's ground and the tighter of the two a row has (see docs/DESIGN.md).
    "every icon's colour should clear 3:1 against a hovered row in each theme" {
      // The light theme's, then the dark theme's, which the system's dark preference and a page set to dark share.
      val grounds =
        Regex("""--surface-2:(#[0-9a-f]{6})""").findAll(DashboardCss.CSS).map { it.groupValues[1] }.distinct().toList()
      grounds.size shouldBe 2

      LinkIcon.entries.forEach { icon ->
        contrast(icon.lightColor, grounds[0]) shouldBeGreaterThanOrEqual 3.0
        contrast(icon.darkColor, grounds[1]) shouldBeGreaterThanOrEqual 3.0
      }
    }
  }

  private companion object {
    // WCAG's contrast ratio between two #rrggbb colours, from 1 to 21.
    fun contrast(
      first: String,
      second: String,
    ): Double {
      val lighter = maxOf(luminance(first), luminance(second))
      val darker = minOf(luminance(first), luminance(second))
      return (lighter + 0.05) / (darker + 0.05)
    }

    // WCAG's relative luminance of a #rrggbb colour.
    fun luminance(color: String): Double {
      val channels =
        listOf(1, 3, 5)
          .map { color.substring(it, it + 2).toInt(16) / 255.0 }
          .map { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
      return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }
  }
}
