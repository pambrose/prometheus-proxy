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

package io.prometheus.common.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.common.TlsSource
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.SettingsHtml.settingsInner
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.time.Instant

/**
 * The Settings view's region both dashboards render ([SettingsHtml]), and the wording both use for a value
 * ([SettingText]). Each side's own settings are covered in `ProxySettingsTest` and `AgentSettingsTest`.
 */
class SettingsViewTest : StringSpec() {
  private fun render(
    groups: List<SettingsGroup>,
    oob: Boolean = false,
  ) = createHTML(prettyPrint = false).div { settingsInner(groups, oob) }

  init {
    "each group should be a section header followed by a row for each of its settings" {
      render(
        [
          SettingsGroup("Scrapes", [Setting("Scrape timeout", "90s"), Setting("Max in flight", "1000 scrapes")]),
          SettingsGroup("Logging", [Setting("Log level", "info")]),
        ],
      ).apply {
        this shouldStartWith """<div id="${SettingsHtml.SETTINGS_ID}">"""
        this shouldContain
          """<div class="section"><span>Scrapes</span></div>""" +
          """<div class="kv"><span class="k">Scrape timeout</span><span class="v">90s</span></div>""" +
          """<div class="kv"><span class="k">Max in flight</span><span class="v">1000 scrapes</span></div>""" +
          """<div class="section"><span>Logging</span></div>"""
      }
    }

    // A value comes from a config file, an env var, or a flag, so it's text, never markup.
    "a setting's name and value should be escaped" {
      render([SettingsGroup("<b>Group</b>", [Setting("Identity <i>a</i>", "paths <script>x</script>")])]).apply {
        this shouldContain "&lt;b&gt;Group&lt;/b&gt;"
        this shouldContain "Identity &lt;i&gt;a&lt;/i&gt;"
        this shouldContain "paths &lt;script&gt;x&lt;/script&gt;"
        this shouldNotContain "<script>"
      }
    }

    "a setting with a moment should show it as a time, before any value" {
      val at = Instant.parse("2026-11-21T12:34:56Z")
      render([SettingsGroup("About", [Setting("Started", "", at), Setting("Checked", "ok", at)])]).apply {
        this shouldContain """<span class="k">Started</span><span class="v"><time datetime="2026-11-21T12:34:56Z">"""
        this shouldContain """</time> ok</span>"""
      }
    }

    "a push should carry the region out of band, and no groups should say so" {
      render([SettingsGroup("Logging", [Setting("Log level", "info")])], oob = true) shouldStartWith
        """<div id="${SettingsHtml.SETTINGS_ID}" hx-swap-oob="true">"""
      render(emptyList()) shouldContain "No settings to show"
    }

    "each value should be worded alike on both sides" {
      SettingText.onOff(true) shouldBe "on"
      SettingText.onOff(true) { "port 8092" } shouldBe "on, port 8092"
      SettingText.onOff(false) { "port 8092" } shouldBe "off"
      SettingText.yesNo(true) shouldBe "yes"
      SettingText.yesNo(false) shouldBe "no"
      SettingText.secs(45) shouldBe "45s"
      SettingText.grpcDefault(-1L) shouldBe "gRPC default"
      SettingText.grpcDefault(-1L, "120s") shouldBe "gRPC default (120s)"
      SettingText.grpcDefault(30L, "120s") shouldBe "30s"
      SettingText.limit(0, "bytes") shouldBe "unlimited"
      SettingText.limit(512) shouldBe "512"
      SettingText.limit(512, "characters") shouldBe "512 characters"
      SettingText.names(["a", "b"]) shouldBe "a, b"
      SettingText.names(emptyList()) shouldBe DASH
      SettingText.names(emptyList(), empty = "none") shouldBe "none"
    }

    // A token or a password shows only whether it's set, and TLS material only where it comes from: a file's path,
    // which is no secret, or that a PEM was given, never the PEM.
    "a secret should show only whether it is set, and TLS material only its source" {
      SettingText.secret("") shouldBe "not set"
      SettingText.secret("tok-3n7") shouldBe "set"
      SettingText.tlsMaterial(TlsSource("certChain", " /etc/tls/server.pem ", "")) shouldBe "file /etc/tls/server.pem"
      SettingText.tlsMaterial(TlsSource("privateKey", "", "-----BEGIN PRIVATE KEY-----\nMIIsecret")) shouldBe "PEM, set"
      SettingText.tlsMaterial(TlsSource("trustCertCollection", "", "")) shouldBe "not set"
    }
  }
}
