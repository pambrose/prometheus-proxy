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

package io.prometheus.proxy.dashboard

/**
 * A brand mark a `proxy.dashboard.links` entry can show in place of its name (its `icon` key), painted in the brand's
 * color through the mark as a mask (see [ProxyDashboardCss.linkIconCss]).
 *
 * The marks are Simple Icons' (simpleicons.org, CC0), from its WebJar. The build extracts only the SVGs named here into
 * the proxy's resources (`configureDashboardIcons` in build.gradle.kts, whose list of slugs must match), and
 * [ProxyDashboardService] serves them under [ProxyDashboardService.ICON_DIR]. The marks belong to their owners.
 *
 * Each color clears WCAG 1.4.11's 3:1 for a non-text mark on `--surface-2`, the ground of a hovered row, in both
 * themes: 3:1 rather than text's 4.5:1, since the link's name is also in its tooltip and its `aria-label`. Prometheus's
 * `#E6522C` measures 3.18:1 light and 4.27:1 dark. Grafana's `#F46800` measures 5.20:1 dark but 2.62:1 light, so the
 * light theme takes `#E26000`, a little darker, at 3.02:1.
 *
 * @param slug the mark's Simple Icons name, which the config's `icon` key gives and its file and class are named for
 * @param lightColor its color in the light theme
 * @param darkColor its color in the dark theme
 */
internal enum class LinkIcon(
  val slug: String,
  val lightColor: String,
  val darkColor: String,
) {
  PROMETHEUS("prometheus", "#E6522C", "#E6522C"),
  GRAFANA("grafana", "#E26000", "#F46800"),
  ;

  /** The file the dashboard serves the mark as, under [ProxyDashboardService.ICON_DIR]. */
  val fileName: String get() = "$slug.svg"

  /** Where the build put the mark on the classpath. */
  val resource: String get() = "$RESOURCE_DIR/$fileName"

  companion object {
    private const val RESOURCE_DIR = "io/prometheus/proxy/dashboard/icons"

    /** The icon whose [slug] is [name], ignoring case, or null when there is none. */
    fun of(name: String): LinkIcon? = entries.firstOrNull { it.slug.equals(name, ignoreCase = true) }

    /** The slugs a config may give, for a refusal to list. */
    val slugs: String get() = entries.joinToString(" or ") { it.slug }
  }
}
