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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.dashboard.DashboardLogo
import java.io.File
import java.util.Base64

// The logo lives in the website's images: the full mark, which the site's header, the README's title, and the
// dashboards' login pages show, and its small-size cut, the icon of the website's tabs and of the dashboards'. The
// dashboards carry both inline rather than read them.
class LogoTest : StringSpec() {
  init {
    "the login pages' logo should be the website's" {
      File(LOGO).readText().trim() shouldBe DashboardLogo.LOGO_SVG
      val encoded = DashboardLogo.LOGO_URL.substringAfter("data:image/svg+xml;base64,")
      Base64.getDecoder().decode(encoded).decodeToString() shouldBe DashboardLogo.LOGO_SVG
    }

    "the dashboards' icon should be the website's, the logo's small-size cut" {
      File(FAVICON).readText().trim() shouldBe DashboardLogo.SMALL_LOGO_SVG
      val encoded = DashboardLogo.SMALL_LOGO_URL.substringAfter("data:image/svg+xml;base64,")
      Base64.getDecoder().decode(encoded).decodeToString() shouldBe DashboardLogo.SMALL_LOGO_SVG
    }

    "the website should show the logo in its header and the small-size cut in its tabs" {
      File(LOGO).isFile.shouldBeTrue()
      File(SITE_CONFIG).readText().apply {
        this shouldContain """logo = "${LOGO.substringAfter("$DOCS_DIR/")}""""
        this shouldContain """favicon = "${FAVICON.substringAfter("$DOCS_DIR/")}""""
      }
    }

    "the README's title should show the logo" {
      File("README.md").readLines().first() shouldContain """# <img src="$LOGO""""
    }
  }

  private companion object {
    const val DOCS_DIR = "website/prometheus-proxy/docs"
    const val LOGO = "$DOCS_DIR/images/logo.svg"
    const val FAVICON = "$DOCS_DIR/images/favicon.svg"
    const val SITE_CONFIG = "website/prometheus-proxy/zensical.toml"
  }
}
