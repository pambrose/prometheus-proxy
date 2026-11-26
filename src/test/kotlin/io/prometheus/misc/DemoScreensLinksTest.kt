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

// The basics demo's tour, README, and docs page link into the proxy's screens page, /screens#<link>, which shows the
// screens the demo's proxy.conf lists. A link the page can't follow opens the proxy's tab instead, without a word, so
// a screen renamed in proxy.conf, or a link mistyped, would send the reader to the wrong screen. Each link must parse
// as the page parses it (the spec's Links section) and name only the proxy's tab, the grid, or a screen proxy.conf
// lists.
class DemoScreensLinksTest : StringSpec() {
  // The screens' ids, from proxy.conf, read without the environment, as each url is a default overridden by ${?…}.
  private val screenIds: Set<String> by lazy {
    val config =
      ConfigFactory
        .parseFile(File(DEMO_DIR, "config/proxy.conf"))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    config.getConfigList("proxy.dashboard.screens").map { Screen.idOf(it.getString("name")) }.toSet()
  }

  // Each file's links, the text after /screens#: demo.sh names the page $SCREENS_URL, and the docs its default URL.
  private val links: Map<String, List<String>> by lazy {
    mapOf(
      "demos/basics/demo.sh" to SCRIPT_LINK.findAll(File(DEMO_DIR, "demo.sh").readText()),
      "demos/basics/README.md" to DOCS_LINK.findAll(File(DEMO_DIR, "README.md").readText()),
      "website/prometheus-proxy/docs/basics-demo.md" to DOCS_LINK.findAll(File(PAGE).readText()),
    ).mapValues { (_, matches) -> matches.map { it.groupValues[1] }.toList() }
  }

  // Why the page can't follow [link], or null when it can: all, <id>, <id>/<path>, or two of those joined by +.
  private fun problem(link: String): String? {
    if (link == Screen.ALL_ID) return null
    val panes = link.split('+')
    if (panes.size > 2) return "more than two panes"
    val unknown = panes.map { it.substringBefore('/') }.filterNot { it == Screen.PROXY_ID || it in screenIds }
    return if (unknown.isEmpty()) null else "no screen ${unknown.joinToString()} in proxy.conf"
  }

  init {
    "proxy.conf should list the demo's screens" {
      screenIds.shouldNotBeEmpty()
    }

    "each of the demo's files should link into the screens page" {
      links.forEach { (file, found) -> withClue(file) { found.shouldNotBeEmpty() } }
    }

    "every link into the screens page should name the proxy's tab, the grid, or a screen proxy.conf lists" {
      links
        .flatMap { (file, found) -> found.mapNotNull { link -> problem(link)?.let { "$file: #$link: $it" } } }
        .shouldBeEmpty()
    }

    // The page is the proxy's, so a link into it while the proxy is down opens only the browser's connection error,
    // where the demo means to show that agent-east's own dashboard keeps answering.
    "while the proxy is down, the demo should point at agent-east's own dashboard, not the proxy's page" {
      val script = File(DEMO_DIR, "demo.sh").readText()
      val proxyDown = script.substringAfter("\n  proxy-down)").substringBefore("\n    ;;")
      val stopped = File(PAGE).readText().substringAfter("With the proxy stopped").substringBefore("\n\n")
      listOf(proxyDown, stopped).map { "/screens#" in it || "SCREENS_URL" in it } shouldBe listOf(false, false)
      proxyDown shouldContain "\$EAST_DASHBOARD_URL/connection"
      stopped shouldContain "http://localhost:8095/dashboard/connection"
    }

    // So the demo shows the reader every way a link opens the page.
    "the links should include a screen, a screen's view, and two screens side by side" {
      val all = links.values.flatten()
      listOf(
        all.any { '/' !in it && '+' !in it && it != Screen.ALL_ID },
        all.any { '/' in it && '+' !in it },
        all.any { '+' in it },
      ) shouldBe listOf(true, true, true)
    }
  }

  private companion object {
    const val DEMO_DIR = "demos/basics"
    const val PAGE = "website/prometheus-proxy/docs/basics-demo.md"

    // A link runs to the next space, quote, or bracket, as the demo's URLs end unpunctuated.
    val SCRIPT_LINK = Regex("""\$\{?SCREENS_URL}?#([^\s"'`)\]<>\\]+)""")
    val DOCS_LINK = Regex("""http://localhost:8094/screens#([^\s"'`)\]<>\\]+)""")
  }
}
