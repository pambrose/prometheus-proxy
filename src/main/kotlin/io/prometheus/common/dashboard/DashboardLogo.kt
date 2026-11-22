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

package io.prometheus.common.dashboard

import java.util.Base64
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.span

/**
 * The project's logo on the dashboards: the full mark, which the login page shows, and its small-size cut, every page's
 * tab icon and the mark beside the dashboard's name in its bar. Each is the website's file, `images/logo.svg` or
 * `images/favicon.svg`, which LogoTest keeps the same, and each goes out as a data URL, so every page, the login page's
 * included, has it with no route of its own: a route would have to be open before login and carry a versioned path, as
 * the other assets do.
 */
internal object DashboardLogo {
  /** The full mark, for 20px and up. */
  const val LOGO_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">""" +
      """<g fill="none" stroke="#E6522C" stroke-linecap="round" stroke-linejoin="round">""" +
      """<path stroke-width="6" d="M2 42H62"/><path stroke-width="6" d="M18 7V58M46 7V58"/>""" +
      """<path stroke-width="3.5" d="M18 9Q32 59 46 9M4 41Q13 36 18 9M60 41Q51 36 46 9"/>""" +
      """<path stroke-width="2.5" d="M25 28V42M32 34V42M39 28V42"/></g></svg>"""

  /** The small-size cut, below 20px, whose heavier strokes stay legible at the 16px a tab draws its icon. */
  const val SMALL_LOGO_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">""" +
      """<g fill="none" stroke="#E6522C" stroke-linecap="round" stroke-linejoin="round">""" +
      """<path stroke-width="8" d="M3 43H61"/><path stroke-width="8" d="M18 7V59M46 7V59"/>""" +
      """<path stroke-width="6" d="M18 9Q32 63 46 9"/></g></svg>"""

  /** [LOGO_SVG] as a data URL. */
  val LOGO_URL = svgDataUrl(LOGO_SVG)

  /** [SMALL_LOGO_SVG] as a data URL. */
  val SMALL_LOGO_URL = svgDataUrl(SMALL_LOGO_SVG)

  /** The mark's width and height in the bar, in CSS pixels: under the bar's 18.75px line, so the bar's height holds. */
  const val BRAND_LOGO_SIZE = 18

  /**
   * The most of the dashboard's name the bar shows, in characters of its monospaced type, which a `ch` measures, so a
   * long agent name can't push the tabs off the bar; one longer is cut with an ellipsis and shown whole in its tooltip.
   */
  const val BRAND_MAX_CHARS = 48

  /** The page's tab icon, [SMALL_LOGO_URL], in its head. */
  fun HEAD.favicon() = link(href = SMALL_LOGO_URL, rel = "icon", type = "image/svg+xml")

  /**
   * The bar's brand: the logo's small-size cut beside the dashboard's [name], which says which proxy or agent the page
   * is, cut past [BRAND_MAX_CHARS]. The mark is decoration beside the name, so it has no text of its own to read.
   */
  fun FlowContent.brand(name: String) =
    span("brand") {
      img(src = SMALL_LOGO_URL, alt = "") {
        width = "$BRAND_LOGO_SIZE"
        height = "$BRAND_LOGO_SIZE"
      }
      span("brand-name") {
        if (name.length > BRAND_MAX_CHARS) attributes["title"] = name
        +name
      }
    }

  private fun svgDataUrl(svg: String) =
    "data:image/svg+xml;base64,${Base64.getEncoder().encodeToString(svg.encodeToByteArray())}"
}
