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
 * What every dashboard's stylesheet shares: the color tokens, light and dark, of the design system `docs/DESIGN.md`
 * records, with the contrast each pairing was measured at. The proxy's dashboard
 * ([io.prometheus.proxy.dashboard.ProxyDashboardCss]) and the login page ([LoginHtml]) both start from them.
 */
internal object DashboardCss {
  /** The color tokens, light and dark. */
  val TOKENS =
    """
    /* Every quiet colour is set so its *worst* pairing still clears 4.5:1, not just its pairing with
       --surface. --ink-3 is the binding case: it lands on --surface-2 under the table and section
       headers, which is a full step closer than the reading plane. */
    :root {
      --bg:#f2f5f8; --surface:#fff; --surface-2:#e9edf2; --surface-3:#dde3ea;
      --line:#cfd7e0; --line-soft:#e3e9ef; --ink:#131820; --ink-2:#48525f; --ink-3:#626b77;
      --accent:#c8461f; --ok:#1a7a42; --ok-soft:#e3f4ea; --warn:#8a5d08; --warn-soft:#f9f0da;
      --crit:#c02a20; --crit-soft:#fbe6e4;
      --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg:#0d1015; --surface:#151a21; --surface-2:#1c222b; --surface-3:#242c37;
        --line:#2b333f; --line-soft:#212934; --ink:#e7ebf1; --ink-2:#a4aebc; --ink-3:#858f9d;
        --accent:#ff7043; --ok:#3fb950; --ok-soft:#12251a; --warn:#d29922; --warn-soft:#2a2110;
        --crit:#f85149; --crit-soft:#2d1512;
      }
    }
    """.trimIndent()
}
