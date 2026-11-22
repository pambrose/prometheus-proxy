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

import io.prometheus.common.TlsSource
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.span
import kotlin.time.Instant

/**
 * One row of a dashboard's Settings view: a setting's [name] and its [value] as the process resolved it, from its
 * flags, env vars, config, and defaults. Never a secret: a token, a password, or a PEM shows only whether it is set.
 *
 * @param at a moment the row shows before its value, in the browser's time zone, as every time on the page is
 */
internal data class Setting(
  val name: String,
  val value: String,
  val at: Instant? = null,
)

/** A titled group of a Settings view's rows, under one section header. */
internal data class SettingsGroup(
  val title: String,
  val rows: List<Setting>,
)

/**
 * What a push to a Settings page keeps (see [DashboardFrame.sent]): that the page was sent its settings, at the count
 * of messages [seq] it had sent. The settings are fixed for the life of the process, so a page is sent them once,
 * in the first frame after it connects, and every later frame carries the status bar alone. A page left open across a
 * restart reconnects to the new process as a new session, which sends the new values; a value selected on the page
 * stays selected, as a push replacing the region every tick would clear it.
 */
internal data class SettingsShown(
  val seq: Long,
)

/** The Settings view's region, which both dashboards render from their own [SettingsGroup]s. */
internal object SettingsHtml {
  const val SETTINGS_ID = "settings"

  /** The region: a section header for each group, then a row for each of its settings. */
  fun DIV.settingsInner(
    groups: List<SettingsGroup>,
    oob: Boolean,
  ) {
    attributes["id"] = SETTINGS_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    if (groups.isEmpty()) {
      div("empty pad") { +"No settings to show" }
      return
    }
    groups.forEach { group ->
      div("section") { span { +group.title } }
      group.rows.forEach { setting ->
        div("kv") {
          span("k") { +setting.name }
          span("v") {
            setting.at?.also {
              timeOfDay(it)
              if (setting.value.isNotEmpty()) +" "
            }
            +setting.value
          }
        }
      }
    }
  }
}

/** How both dashboards' Settings views word a value, so the proxy's and the agent's read alike. */
internal object SettingText {
  /** "on", with [detail] after it when there is one, or "off". */
  fun onOff(
    enabled: Boolean,
    detail: () -> String = { "" },
  ): String =
    if (enabled)
      detail().let { if (it.isEmpty()) "on" else "on, $it" }
    else
      "off"

  fun yesNo(value: Boolean): String = if (value) "yes" else "no"

  fun secs(value: Number): String = "${value}s"

  /** A gRPC duration in seconds, where -1 leaves it to gRPC: its [default], when there's one to name. */
  fun grpcDefault(
    secs: Long,
    default: String = "",
  ): String =
    when {
      secs != -1L -> secs(secs)
      default.isEmpty() -> "gRPC default"
      else -> "gRPC default ($default)"
    }

  /** A limit where 0 turns it off, with its [unit] after it. */
  fun limit(
    value: Int,
    unit: String = "",
  ): String =
    when {
      value == 0 -> "unlimited"
      unit.isEmpty() -> "$value"
      else -> "$value $unit"
    }

  /** Only whether a secret, a token or a password, is set: never the secret. */
  fun secret(value: String): String = if (value.isEmpty()) "not set" else "set"

  /** Where TLS material comes from: its file, whose path is no secret, or a PEM, of which only that it's set. */
  fun tlsMaterial(source: TlsSource): String =
    when {
      source.path.isNotEmpty() -> "file ${source.path}"
      source.pem.isNotEmpty() -> "PEM, set"
      else -> "not set"
    }

  /** [values] as a list, or [empty] for none. */
  fun names(
    values: Collection<String>,
    empty: String = DASH,
  ): String = values.joinToString(", ").ifEmpty { empty }
}
