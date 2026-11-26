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

import io.prometheus.common.dashboard.DashboardCss
import io.prometheus.common.dashboard.DashboardServer

/**
 * The proxy dashboard's own styles, beyond the stylesheet every dashboard shares ([DashboardCss]): the Links column's
 * brand marks, whose URLs are under the route base.
 */
internal object ProxyDashboardCss {
  /**
   * The bar's link to the screens page, before the bar's end, in a second `<style>` in the page's head while the proxy
   * serves the page: quiet as Log out is, in its ink and border, and as tall as the theme button.
   */
  const val SCREENS_LINK_CSS =
    ".screens-link { display:inline-flex; align-items:center; flex:none; height:26px; margin-bottom:6px; " +
      "padding:0 10px; color:var(--ink-2); text-decoration:none; border:1px solid var(--line); border-radius:3px; }\n" +
      ".screens-link:hover { color:var(--ink); background-color:var(--surface-2); }\n" +
      ".screens-link:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }\n" +
      "@media (pointer: coarse) { .screens-link { height:46px; } }"

  /**
   * The rules that give each of [icons] its mark and its colour in each theme, for a second `<style>` in the page's
   * head, which [PathTableHtml.linkIconStyle] renders only when a link shows an icon.
   *
   * Apart from [DashboardCss.CSS] because the mark's URL is under [routeBase], which the static sheet can't know, and
   * in the head rather than on each link, so a push carries only the link's class. [routeBase] arrives normalized, as
   * every route does, and joins by plain interpolation.
   */
  fun linkIconCss(
    routeBase: String,
    icons: Collection<LinkIcon>,
  ): String =
    buildString {
      for (icon in icons) {
        val url = "$routeBase/${DashboardServer.ICON_DIR}/${icon.fileName}"
        appendLine(".links a.icon-${icon.slug} { --icon:url(\"$url\"); --brand:${icon.lightColor}; }")
      }
      // Only the icons whose colour changes with the theme need a rule in the dark one.
      icons
        .filter { it.darkColor != it.lightColor }
        .forEach { appendLine(DashboardCss.darkRule(".links a.icon-${it.slug}", "--brand:${it.darkColor};")) }
    }.trimEnd()
}
