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

package io.prometheus.proxy

import io.prometheus.common.AgentValues.truncate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What happened, as the dashboard's activity feed names it.
 *
 * @param label how the feed's Event column reads
 * @param isProblem whether "Problems only" keeps it: something an operator has to act on, where the rest is the
 *   ordinary coming and going of agents and paths
 * @param isNotice whether it is news but not a fault, which the feed marks amber
 */
internal enum class ActivityKind(
  val label: String,
  val isProblem: Boolean = false,
  val isNotice: Boolean = false,
) {
  AGENT_CONNECTED("connected"),
  AGENT_DISCONNECTED("disconnected"),
  AGENT_EVICTED("evicted", isProblem = true),
  AGENT_REFUSED("refused", isProblem = true),
  BAD_TOKEN("bad token", isProblem = true),
  PATH_REGISTERED("registered"),
  PATH_UNREGISTERED("unregistered"),

  // A redeploy takes over every path its old agent held.
  PATH_TAKEN_OVER("taken over", isNotice = true),
  PATH_REJECTED("rejected", isProblem = true),

  // A scrape made from the dashboard, named so a scrape no one at Prometheus asked for can be traced to a browser.
  SCRAPED_NOW("scraped now"),
}

/**
 * One line of the activity feed: an event, or a burst of like ones from one agent folded into one (see [ActivityLog]).
 *
 * @param at when it happened; for a burst, when the burst began
 * @param agentName the agent's name, or empty for an event that has no agent, such as a bad token
 * @param identity the auth identity it acted as, or empty with agent auth off or none known
 * @param paths the paths it concerns, at most [ActivityLog.MAX_SAMPLE_PATHS] of a burst's, each named once
 * @param count how many events the line stands for
 * @param detail what an operator needs beyond the rest, such as why a path was refused, or where a bad token came from
 * @param group what a later event must share, beside its kind, agent, and identity, to join this line: a rejection's
 *   cause, so two causes stay two lines, or a bad token's source
 * @param pathCount how many paths the line concerns, more than [paths] holds once a burst outgrows them; a path that
 *   comes back within the burst, as Scrape now clicked again on one path does, counts once
 */
internal data class ActivityEntry(
  val at: Instant,
  val kind: ActivityKind,
  val agentId: String = "",
  val agentName: String = "",
  val identity: String = "",
  val paths: List<String> = emptyList(),
  val count: Int = 1,
  val detail: String = "",
  val group: String = "",
  val pathCount: Int = paths.size,
) {
  // Whether [next] is one more of this line's burst.
  fun isJoinedBy(
    next: ActivityEntry,
    window: Duration,
  ): Boolean =
    kind == next.kind && agentId == next.agentId && identity == next.identity && group == next.group &&
      next.at - at < window

  // This line with one more event folded in: counted, and [newPaths], those of its paths the line hadn't counted,
  // counted too and named while the line names fewer than the sample's. Folding in every event's paths named a path
  // once per event, "/orders, /orders, /orders", and counted each repeat in the line's "and N more".
  fun joinedBy(newPaths: List<String>): ActivityEntry =
    copy(
      count = count + 1,
      paths = (paths + newPaths).take(ActivityLog.MAX_SAMPLE_PATHS),
      pathCount = pathCount + newPaths.size,
    )
}

/**
 * The proxy's recent comings and goings, for the dashboard's activity feed: agents connecting, leaving, and being
 * evicted or refused; paths registered, removed, taken over, and rejected; and calls made with a bad token.
 *
 * It exists for the failures that otherwise reach only the proxy's log. An agent whose paths were refused, or whose
 * token was wrong, looks on the dashboard just like an agent that never started, and those failures are the usual
 * reason a team's metrics don't appear. The event bus can't carry them: it holds no times, drops the oldest event when
 * busy, and several of these failures never emit one.
 *
 * Bounded on every write: at most [capacity] lines, each field cut, oldest dropped first. Two things keep a flood from
 * pushing everything else out:
 * - A burst of like events from one agent within [COALESCE_WINDOW] is one line, counting them and naming the first
 *   [MAX_SAMPLE_PATHS] paths, however other agents' events interleave with it: an agent registering two thousand paths
 *   is one line, not the whole log, and so is each agent of a fleet reconnecting at once. The line counts each of its
 *   paths once, those past the sample's too, while a burst can join it.
 * - [recordFromSource] is for what anyone who can reach the agent port can cause, as often as they like: a bad token,
 *   by the host it came from. Every event from one source within [SOURCE_WINDOW] is counted on one line, and at most
 *   [MAX_SOURCES_PER_WINDOW] sources a window get a line of their own; the rest of the window's are counted on one, so
 *   a caller with many source addresses can't push everything else out either.
 *
 * Kept in memory only, so a restart empties it. Written only with the dashboard on, since nothing else reads it.
 *
 * Every write, a burst's join included, adds one to a change count, which [snapshot] reads with the lines, so the
 * dashboard can tell a feed that changed from one that didn't and send a page the table only when it did.
 *
 * Thread-safe. Every access takes the log's own monitor, which is a leaf: nothing else is called while it is held, so a
 * caller holding the path map's lock may record.
 *
 * @param capacity the most lines kept, `proxy.dashboard.activityLogSize`
 * @param clock where an event's time comes from; injectable so tests move time instead of sleeping
 */
internal class ActivityLog(
  private val capacity: Int,
  private val clock: Clock = Clock.System,
) {
  init {
    require(capacity > 0) { "capacity must be positive: $capacity" }
  }

  /**
   * The lines and the change count they were read at, in one read.
   *
   * @param changeCount how many writes the log had taken, a burst's join counting as one: equal counts mean equal lines
   * @param entries the lines, newest first
   */
  data class Snapshot(
    val changeCount: Long = 0L,
    val entries: List<ActivityEntry> = emptyList(),
  )

  // A line, with every path it has counted while a burst can still join it: within COALESCE_WINDOW of its start (see
  // settle). Past that, only a source's event can, which has no path.
  private class Line(
    var entry: ActivityEntry,
  ) {
    var counted: MutableSet<String>? = entry.paths.toHashSet()

    // Folds [next] in, counting each of its paths the line hasn't.
    fun join(next: ActivityEntry) {
      entry = entry.joinedBy(next.paths.filter { path -> counted?.add(path) ?: (path !in entry.paths) })
    }
  }

  // Oldest first, so a new line goes on the end and the oldest comes off the front.
  private val lines = ArrayDeque<Line>()

  // The index in lines of the oldest line that may still keep its counted paths; every line before it has let go of
  // them (see settle). Guarded by lines' monitor.
  private var unsettled = 0

  // When the current source window began, and how many sources have a line of their own in it. Guarded by lines'
  // monitor.
  private var windowStart: Instant? = null
  private var windowSources = 0

  // How many writes the log has taken, each append and each join. Guarded by lines' monitor, so a snapshot reads it at
  // the same moment as the lines.
  private var changeCount = 0L

  /**
   * Records [kind] for [agent], when there is one, about [path], when there is one. [identity] defaults to the one the
   * agent registered with; an event with no agent, such as a refused connect, names its own.
   */
  fun record(
    kind: ActivityKind,
    agent: AgentContext? = null,
    path: String = "",
    detail: String = "",
    group: String = "",
    identity: String = agent?.identityName.orEmpty(),
  ) {
    // Built before taking the monitor, which stays a leaf: the agent's fields are its own atomics.
    val entry = entry(kind, agent, path, detail, group, identity)
    synchronized(lines) {
      settle(entry)
      if (!join(entry, COALESCE_WINDOW)) append(entry)
    }
  }

  /** Records [kind] from the source [key], such as a bad token from a host; see the class's account of the bounds. */
  fun recordFromSource(
    key: String,
    kind: ActivityKind,
    detail: String,
  ) {
    val entry = entry(kind, agent = null, path = "", detail, group = key, identity = "")
    synchronized(lines) {
      if (join(entry, SOURCE_WINDOW)) return
      val start = windowStart
      if (start == null || entry.at - start >= SOURCE_WINDOW) {
        windowStart = entry.at
        windowSources = 0
      }
      windowSources += 1
      if (windowSources <= MAX_SOURCES_PER_WINDOW) {
        append(entry)
      } else {
        val more = entry.copy(detail = MORE_SOURCES_DETAIL, group = MORE_SOURCES_GROUP)
        if (!join(more, SOURCE_WINDOW)) append(more)
      }
    }
  }

  /** A copy of the lines, newest first. */
  fun entries(): List<ActivityEntry> = synchronized(lines) { newestFirst() }

  /**
   * A copy of the lines, newest first, with the change count, read under one acquisition of the monitor. Read apart, a
   * write landing between the two could pair the lines from before it with the count from after it, and a reader
   * comparing counts would never see that write's line.
   */
  fun snapshot(): Snapshot = synchronized(lines) { Snapshot(changeCount, newestFirst()) }

  // Callers MUST hold lines' monitor.
  private fun newestFirst(): List<ActivityEntry> = List(lines.size) { lines[lines.lastIndex - it].entry }

  private fun entry(
    kind: ActivityKind,
    agent: AgentContext?,
    path: String,
    detail: String,
    group: String,
    identity: String,
  ) = ActivityEntry(
    at = clock.now(),
    kind = kind,
    agentId = agent?.agentId.orEmpty(),
    agentName = truncate(agent?.agentName.orEmpty(), MAX_FIELD_CHARS),
    identity = truncate(identity, MAX_FIELD_CHARS),
    paths = if (path.isEmpty()) emptyList() else listOf(truncate(path, MAX_FIELD_CHARS)),
    detail = truncate(detail, MAX_DETAIL_CHARS),
    group = truncate(group, MAX_FIELD_CHARS),
  )

  // Folds [entry] into the line of its burst, the newest line it can join within [window], and returns whether there
  // was one. The line needn't be the newest: when a fleet reconnects at once, every agent's registrations interleave,
  // and joining only the newest line would give each registration its own, the flood this exists to prevent. The search
  // stops at the first line older than the window. Callers MUST hold lines' monitor.
  private fun join(
    entry: ActivityEntry,
    window: Duration,
  ): Boolean {
    var i = lines.lastIndex
    while (i >= 0 && entry.at - lines[i].entry.at < window) {
      if (lines[i].entry.isJoinedBy(entry, window)) {
        lines[i].join(entry)
        // A join changes a line's count, and perhaps its paths, so it is a change like an append.
        changeCount += 1
        return true
      }
      i -= 1
    }
    return false
  }

  // Has each line that no burst can join any more, as of [entry], let go of the paths it counted: lines are in the
  // order their bursts began, so those are the oldest, and each is let go of once. Callers MUST hold lines' monitor.
  private fun settle(entry: ActivityEntry) {
    while (unsettled < lines.size && entry.at - lines[unsettled].entry.at >= COALESCE_WINDOW) {
      lines[unsettled].counted = null
      unsettled += 1
    }
  }

  // Callers MUST hold lines' monitor.
  private fun append(entry: ActivityEntry) {
    if (lines.size >= capacity) {
      lines.removeFirst()
      if (unsettled > 0)
        unsettled -= 1
    }
    lines.addLast(Line(entry))
    changeCount += 1
  }

  companion object {
    // A burst: an agent registering its paths as it starts, or retrying them all, takes about this long.
    val COALESCE_WINDOW = 5.seconds

    // How long one source's events share a line, and the period MAX_SOURCES_PER_WINDOW counts over.
    val SOURCE_WINDOW = 1.minutes

    // Sources a window that get a line each: enough for a few misconfigured agents to show by host.
    const val MAX_SOURCES_PER_WINDOW = 10

    // The line counting the rest. Its group can't be a source: a source is a host, which has no "*".
    const val MORE_SOURCES_DETAIL = "From more addresses than the feed lists one by one in a minute"
    const val MORE_SOURCES_GROUP = "*"

    // Enough to say which paths a burst was about without the line growing with it.
    const val MAX_SAMPLE_PATHS = 3

    // What a line keeps of each value. A path or name past this is cut; the proxy's own bounds are usually tighter.
    const val MAX_FIELD_CHARS = 256
    const val MAX_DETAIL_CHARS = 512
  }
}
