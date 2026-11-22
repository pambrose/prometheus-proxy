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
 * What a push's Activity table is rendered from, the proxy's or the agent's. A frame for an Activity view carries the
 * table only when this differs from what the last frame sent to the page carried; otherwise it carries the status bar
 * alone.
 *
 * The table went to every Activity page on every refresh, though the feed changes only when something is recorded, so
 * a page rewrote the table someone was reading every couple of seconds, undoing a text selection, to show the same
 * rows. Nothing in the table depends on when it is rendered: each time is the event's own, which the page converts to
 * the viewer's zone as the frame arrives, and the rest follows settings fixed at startup. So a table that isn't sent
 * again shows what one sent again would.
 *
 * Equal values render equal tables: the change count covers every line and its count, and the rest is what the page's
 * view contributes. A frame for another layout has no value, so a page that comes back to the Activity view, as a back
 * or forward navigation does without a new socket, is sent the table again.
 *
 * @param changeCount the activity log's change count, as the snapshot read it with the lines
 * @param seq the count of the message the page's view came from. A new message, even one asking for the same filter,
 *   is sent the table again: the page drops a frame stamped below the count it has sent, so the frame that carried the
 *   last table may never have been shown, and a back or forward navigation replaces the page's table with the one its
 *   GET rendered before the page says so
 * @param filter the search and "Problems only" the table is filtered by
 */
internal data class ActivityTable(
  val changeCount: Long,
  val seq: Long,
  val filter: RowFilter,
) {
  companion object
}
