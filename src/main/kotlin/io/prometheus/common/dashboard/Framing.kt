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

/**
 * Which pages may show a dashboard in a frame, from `<role>.dashboard.framingEnabled` and whether the proxy's
 * `/screens` page needs to: the headers [DashboardServer] adds to every response, or none.
 *
 * @param frameAncestors the `Content-Security-Policy` it sends, or null for none
 * @param xFrameOptions the `X-Frame-Options` it sends, the same rule for a browser that predates `frame-ancestors`, or
 *   null for none
 */
internal enum class Framing(
  val frameAncestors: String?,
  val xFrameOptions: String?,
) {
  /** Any page may frame the dashboard: `framingEnabled`, the default. No header. */
  ANY(null, null),

  /**
   * Only a page of the dashboard's own origin may frame it: `framingEnabled = false` on a proxy that lists screens, so
   * its `/screens` page can still show its own dashboard, and no other site can.
   */
  SAME_ORIGIN("frame-ancestors 'self'", "SAMEORIGIN"),

  /** No page may frame the dashboard: `framingEnabled = false` with no screens listed, as on every agent. */
  NONE("frame-ancestors 'none'", "DENY"),
  ;

  companion object {
    /**
     * The framing for a dashboard with [framingEnabled], on a proxy that lists screens when [hasScreens]; an agent's
     * dashboard, which has no screens page, leaves it out.
     */
    fun of(
      framingEnabled: Boolean,
      hasScreens: Boolean = false,
    ): Framing =
      when {
        framingEnabled -> ANY
        hasScreens -> SAME_ORIGIN
        else -> NONE
      }
  }
}
