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

package io.prometheus.misc

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.proxy.dashboard.Screen
import java.io.File

// Each demo whose tour runs in its proxy's screens page links into it, /screens#<link>, from its demo.sh, README, and
// docs page, and the page shows the screens the demo's proxy config lists. A link the page can't follow opens the
// proxy's tab instead, without a word, so a screen renamed in the config, or a link mistyped, would send the reader to
// the wrong screen. Each link must parse as the page parses it (the spec's Links section) and name only the proxy's
// tab, the grid, or a screen the config lists; and each screen must follow its port and allow framing.
class DemoScreensLinksTest : StringSpec() {
  // A demo whose tour runs in a screens page: its directory, the proxy config (in the directory) that lists the
  // screens, its docs page, the page's address at the default port, the compose service of the proxy that serves it,
  // and, for a demo that stops that proxy, where the tour points while it's down.
  private data class ScreensDemo(
    val dir: String,
    val config: String,
    val page: String,
    val pageUrl: String,
    val proxyService: String,
    val proxyDown: ProxyDown?,
  ) {
    // A link in the README or docs page, which give the page's address at the default port.
    val docsLink = Regex(Regex.escape(pageUrl) + LINK_TAIL)
  }

  // While the page's proxy is stopped: demo.sh's scenario case, or its text after [branch] where only that part of the
  // case stops the page's proxy, and the docs page's paragraph that starts with [pageSection], if the page tells it,
  // and the screen's own URL each gives instead, as the script writes it and as the page does.
  private data class ProxyDown(
    val scenario: String,
    val branch: String? = null,
    val directUrl: String,
    val pageSection: String? = null,
    val directUrlOnPage: String? = null,
  )

  // A demo's screen entries, from its config, read without the environment, as each url is a default overridden by
  // ${?…}, unless [values] sets a variable.
  private fun ScreensDemo.entries(values: Map<String, String> = emptyMap()): List<Screen.Entry> {
    val config =
      ConfigFactory
        .parseFile(File(dir, config))
        .withFallback(ConfigFactory.parseMap(values))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    return config.getConfigList("proxy.dashboard.screens").map {
      Screen.Entry(
        name = it.getString("name"),
        url = if (it.hasPath("url")) it.getString("url") else "",
        start = if (it.hasPath("start")) it.getString("start") else "",
      )
    }
  }

  // Each demo's screens' ids, checked as the proxy checks them at startup, so a url, start, or id the page can't use
  // fails here too.
  private val screenIds: Map<ScreensDemo, Set<String>> by lazy {
    DEMOS.associateWith { demo -> Screen.parseAll(demo.entries()).map { it.id }.toSet() }
  }

  private fun ScreensDemo.isScreen(id: String) = id == Screen.PROXY_ID || id in screenIds.getValue(this)

  // A link's panes, split as the page splits them (ScreensScript's splitPanes): at the first + that a screen's id
  // follows, alone or before its path, since a + elsewhere is part of a pane's query.
  private fun ScreensDemo.panes(link: String): List<String> {
    var at = link.indexOf('+')
    while (at >= 0) {
      val rest = link.substring(at + 1)
      if (isScreen(rest.substringBefore('/'))) return listOf(link.substring(0, at), rest)
      at = link.indexOf('+', at + 1)
    }
    return listOf(link)
  }

  // Each of a demo's files' links, the text after /screens#: demo.sh names the page $SCREENS_URL, and the docs its
  // address at the default port.
  private fun ScreensDemo.links(): Map<String, List<String>> =
    mapOf(
      "$dir/demo.sh" to SCRIPT_LINK.findAll(File(dir, "demo.sh").readText()),
      "$dir/README.md" to docsLink.findAll(File(dir, "README.md").readText()),
      page to docsLink.findAll(File(page).readText()),
    ).mapValues { (_, matches) -> matches.map { it.groupValues[1] }.toList() }

  // Why the page can't follow [link], or null when it can: all, <id>, <id>/<path>, or two of those joined by +.
  private fun ScreensDemo.problem(link: String): String? {
    if (link == Screen.ALL_ID) return null
    val unknown = panes(link).map { it.substringBefore('/') }.filterNot { isScreen(it) }
    return if (unknown.isEmpty()) null else "no screen ${unknown.joinToString()} in $config"
  }

  // A service's block in the demo's compose file, from its line to the next at its indent or less, read by indent as
  // the other specs read compose files.
  private fun ScreensDemo.composeService(service: String): String {
    val lines = File(dir, COMPOSE).readLines()
    val start = lines.indexOf("  $service:")
    if (start < 0) return ""
    val rest = lines.drop(start + 1)
    val end =
      rest.indexOfFirst { line ->
        val text = line.trimStart()
        text.isNotEmpty() && !text.startsWith("#") && line.length - text.length <= 2
      }
    return (if (end < 0) rest else rest.take(end)).joinToString("\n")
  }

  init {
    "each demo's proxy config should list its screens" {
      DEMOS.forEach { demo -> withClue(demo.dir) { screenIds.getValue(demo).shouldNotBeEmpty() } }
    }

    // As the page splits a link, so a + in a pane's query, as a search for "agent rogue" writes it, isn't a second
    // pane.
    "a link should split into panes only at a + that a screen's id follows" {
      BASICS.panes("proxy/paths?problems=1+grafana") shouldBe ["proxy/paths?problems=1", "grafana"]
      BASICS.panes("proxy/activity?q=agent+rogue") shouldBe ["proxy/activity?q=agent+rogue"]
      BASICS.panes("agent-east+grafana/d/x?var=a+b") shouldBe ["agent-east", "grafana/d/x?var=a+b"]
    }

    "each of a demo's files should link into the screens page" {
      DEMOS.forEach { demo ->
        demo.links().forEach { (file, found) -> withClue(file) { found.shouldNotBeEmpty() } }
      }
    }

    "every link into the screens page should name the proxy's tab, the grid, or a screen the config lists" {
      DEMOS
        .flatMap { demo ->
          demo.links().flatMap { (file, found) ->
            found.mapNotNull { link -> demo.problem(link)?.let { "$file: #$link: $it" } }
          }
        }.shouldBeEmpty()
    }

    // make open, and ./demo.sh open, open the screens page, so ./demo.sh help says so, as the Makefile's help does.
    "each demo's help should say open opens the screens page" {
      DEMOS.forEach { demo ->
        withClue(demo.dir) {
          val open = File(demo.dir, "demo.sh").readLines().firstOrNull { it.trimStart().startsWith("open ") }.orEmpty()
          open shouldContain "screens page"
        }
      }
    }

    // A terminal takes a URL to the next space, and a step's text is one paragraph, so a comma or period after a link
    // becomes part of the address the reader opens: a link ends its sentence's text, unpunctuated. A comma after a
    // link that names one screen, as #a+b, got by, since the page read it as part of the second pane's id and showed
    // the first pane alone.
    "no link into the screens page should end in punctuation" {
      DEMOS
        .flatMap { demo ->
          demo.links().flatMap { (file, found) -> found.filter { it.last() in ",.;:" }.map { "$file: #$it" } }
        }.shouldBeEmpty()
    }

    // The page is the proxy's, so a link into it while the proxy is down opens only the browser's connection error,
    // where the demo means to show that a screen's own dashboard keeps answering.
    "while the page's proxy is down, the demo should point at a screen's own URL, not the page" {
      DEMOS.forEach { demo ->
        val down = demo.proxyDown ?: return@forEach
        withClue(demo.dir) {
          val script = File(demo.dir, "demo.sh").readText()
          val case = script.substringAfter("\n  ${down.scenario})").substringBefore("\n    ;;")
          val proxyDown = down.branch?.let { case.substringAfter(it, missingDelimiterValue = "") } ?: case
          ("SCREENS_URL" in proxyDown) shouldBe false
          proxyDown shouldContain down.directUrl
          if (down.pageSection != null && down.directUrlOnPage != null) {
            val stopped = File(demo.page).readText().substringAfter(down.pageSection).substringBefore("\n\n")
            ("/screens#" in stopped) shouldBe false
            stopped shouldContain down.directUrlOnPage
          }
        }
      }
    }

    // So each demo shows the reader every way a link opens the page.
    "each demo's links should include a screen, a screen's view, and two screens side by side" {
      DEMOS.forEach { demo ->
        withClue(demo.dir) {
          val all = demo.links().values.flatten()
          listOf(
            all.any { '/' !in it && '+' !in it && it != Screen.ALL_ID },
            all.any { '/' in it && '+' !in it },
            all.any { '+' in it },
          ) shouldBe listOf(true, true, true)
        }
      }
    }

    // A screen's url is its default port's, and compose gives the page's proxy the url a moved port makes, as ${?NAME},
    // so a port moved with a *_PORT variable moves its tab too. Each ${?NAME} in the config is set to a marker, so each
    // url says which variable it reads. Compose's value, its ports at their defaults, must be the screen's url, and
    // each port variable in it one that publishes a port: a screen with no override, a variable compose doesn't set,
    // or one that names another screen's port, as a slip among the near-identical lines would, leaves the tab behind.
    "every screen should follow its port: compose gives the page's proxy each url, from the port it publishes" {
      DEMOS.forEach { demo ->
        withClue(demo.dir) {
          val names = OPTIONAL.findAll(File(demo.dir, demo.config).readText()).map { it.groupValues[1] }.toSet()
          val urls = demo.entries(names.associateWith { "$MARKER$it" }).associate { it.name to it.url }
          urls.filterValues { !it.startsWith(MARKER) }.keys.shouldBeEmpty()
          val defaults = demo.entries().associate { it.name to it.url }
          val service = demo.composeService(demo.proxyService)
          val compose = File(demo.dir, COMPOSE).readText()
          urls
            .mapNotNull { (screen, marked) ->
              val name = marked.removePrefix(MARKER)
              val value =
                Regex("""(?m)^\s+${Regex.escape(name)}:\s*(\S+)""").find(service)?.groupValues?.get(1)
                  ?: return@mapNotNull "$screen: compose doesn't give ${demo.proxyService} $name"
              val ports = PORT_DEFAULT.findAll(value).map { it.groupValues[1] }.toList()
              val atDefaults = PORT_DEFAULT.replace(value) { it.groupValues[2] }
              when {
                atDefaults != defaults[screen] -> {
                  "$screen: $name is $atDefaults at its defaults, not ${defaults[screen]}"
                }

                ports.isEmpty() -> {
                  "$screen: $name names no port variable"
                }

                ports.any { !published(it).containsMatchIn(compose) } -> {
                  "$screen: $name's port isn't one compose publishes"
                }

                else -> {
                  null
                }
              }
            }.shouldBeEmpty()
        }
      }
    }

    // Grafana refuses framing unless told, and the page can't tell a frame it refused from an empty one.
    "a demo whose page lists Grafana should let Grafana be framed" {
      DEMOS.filter { "grafana" in screenIds.getValue(it) }.forEach { demo ->
        withClue(demo.dir) { demo.composeService("grafana") shouldContain """GF_SECURITY_ALLOW_EMBEDDING: "true"""" }
      }
    }
  }

  private companion object {
    const val COMPOSE = "docker/compose.yml"

    // What each ${?NAME} is set to while checking which variable a screen's url reads.
    const val MARKER = "marker:"

    // A link runs to the next space, quote, or bracket, as the demo's URLs end unpunctuated.
    const val LINK_TAIL = """#([^\s"'`)\]<>\\]+)"""
    val SCRIPT_LINK = Regex("""\$\{?SCREENS_URL}?$LINK_TAIL""")
    val OPTIONAL = Regex("""\$\{\?([A-Za-z0-9_]+)}""")

    // A compose variable with its default, ${NAME:-default}.
    val PORT_DEFAULT = Regex("""\$\{([A-Z0-9_]+):-([^}]*)}""")

    // A port compose publishes on the host from [variable]: "127.0.0.1:${variable:-<port>}:<port in the container>".
    fun published(variable: String) = Regex("""127\.0\.0\.1:\$\{${Regex.escape(variable)}:-\d+}:""")

    val BASICS =
      ScreensDemo(
        dir = "demos/basics",
        config = "config/proxy.conf",
        page = "website/prometheus-proxy/docs/basics-demo.md",
        pageUrl = "http://localhost:8094/screens",
        proxyService = "proxy",
        proxyDown =
          ProxyDown(
            scenario = "proxy-down",
            directUrl = "\$EAST_DASHBOARD_URL/connection",
            pageSection = "With the proxy stopped",
            directUrlOnPage = "http://localhost:8095/dashboard/connection",
          ),
      )

    // The network demo's page is the standby's, which no part of its tour stops; proxy-down stops it only when it's
    // given the standby, in its else branch, which points at the primary's own dashboard.
    val NETWORK =
      ScreensDemo(
        dir = "demos/network",
        config = "config/proxy-standby.conf",
        page = "website/prometheus-proxy/docs/network-demo.md",
        pageUrl = "http://localhost:8195/screens",
        proxyService = "proxy-standby",
        proxyDown = ProxyDown(scenario = "proxy-down", branch = "\n    else\n", directUrl = "\$DASHBOARD_URL"),
      )

    val DEMOS = listOf(BASICS, NETWORK)
  }
}
