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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.SettingsShown

/**
 * The value a push compares to leave out an Activity table, or the settings, the page already shows. The rules every
 * dashboard shares are covered in `DashboardServerTest` and `SnapshotCacheTest`, and the live routes in
 * `ProxyWebDashboardTest`.
 */
class ProxyDashboardServiceTest : StringSpec() {
  // The table an Activity push renders from a snapshot whose activity log had taken [changeCount] writes, for the page
  // message [seq] with [filter].
  private fun activityTable(
    changeCount: Long,
    seq: Long,
    filter: RowFilter,
  ) = ProxyDashboardService.shownRegion(
      DashboardFixtures.snapshot(activityChangeCount = changeCount),
      DashboardLayout.ACTIVITY,
      seq,
      filter,
    )

  // What a Settings push for the page message [seq] keeps, from a snapshot whose activity log had taken [changeCount]
  // writes, which shouldn't matter.
  private fun settingsShown(
    seq: Long,
    changeCount: Long = 0L,
  ) = ProxyDashboardService.shownRegion(
    DashboardFixtures.snapshot(activityChangeCount = changeCount),
    DashboardLayout.SETTINGS,
    seq,
    RowFilter.NONE,
  )

  init {
    // ==================== The Activity table a push sends ====================

    // A push leaves the table out when its ActivityTable equals the one in the last frame the page was sent, so equal
    // values must mean equal tables, and anything that changes the table must change the value.
    "an Activity push of an unchanged feed, for the same message, should name the table the page already shows" {
      val filter = RowFilter.of("east", problemsOnly = true)

      // Two snapshots, as two refreshes collect them, with the feed unchanged in between.
      activityTable(changeCount = 4, seq = 3, filter) shouldBe activityTable(changeCount = 4, seq = 3, filter)
    }

    "a change to the feed, the filter, or the page's message should name another table" {
      val shown = activityTable(changeCount = 4, seq = 3, RowFilter.NONE)

      // The log recorded something, a burst's join included.
      activityTable(changeCount = 5, seq = 3, RowFilter.NONE) shouldNotBe shown
      activityTable(changeCount = 4, seq = 3, RowFilter.of("east", problemsOnly = false)) shouldNotBe shown
      activityTable(changeCount = 4, seq = 3, RowFilter.of("", problemsOnly = true)) shouldNotBe shown
      // A new message, though its filter is the same: the page may have dropped the frame that carried the last table.
      activityTable(changeCount = 4, seq = 4, RowFilter.NONE) shouldNotBe shown
    }

    // A frame for another layout carries no Activity table, so it leaves nothing to compare with, and a page that comes
    // back to the Activity view, as a back or forward navigation does on the same socket, is sent the table again.
    "a push for another layout, or before the page has said which it shows, should have no Activity table" {
      val snapshot = DashboardFixtures.snapshot(activityChangeCount = 4)

      listOf(DashboardLayout.AGENT, DashboardLayout.PATH, null).forEach { layout ->
        ProxyDashboardService.shownRegion(snapshot, layout, seq = 3, RowFilter.NONE).shouldBeNull()
      }
    }

    // ==================== The settings a push sends ====================

    // The settings don't change while the proxy runs, so a page is sent them once per message: a later push for the
    // same message names what the page was sent and leaves them out, and a new message, which may follow a back or
    // forward navigation on the same socket, names something else and sends them again.
    "a Settings push should name the settings it sent, by the page's message, whatever the feed" {
      settingsShown(seq = 3) shouldBe SettingsShown(3)
      settingsShown(seq = 3, changeCount = 9) shouldBe settingsShown(seq = 3)
      settingsShown(seq = 4) shouldNotBe settingsShown(seq = 3)
    }

    // The same message's Activity table must not pass for its settings, or a page switched between the two on one
    // socket would be sent neither.
    "a Settings push and an Activity push for the same message should name different things" {
      settingsShown(seq = 3) shouldNotBe activityTable(changeCount = 0, seq = 3, RowFilter.NONE)
    }
  }
}
