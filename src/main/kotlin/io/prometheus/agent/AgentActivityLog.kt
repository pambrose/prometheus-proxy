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

package io.prometheus.agent

import io.prometheus.common.AgentValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What happened, as the agent dashboard's activity feed names it.
 *
 * @param label how the feed's Event column reads
 * @param isProblem whether "Problems only" keeps it: something an operator has to act on
 * @param isNotice whether it is news but not a fault, which the feed marks amber, as the proxy's marks a take-over
 * @param isBoundary whether it starts a new connection's run of events, which no later event folds back past
 */
internal enum class AgentActivityKind(
  val label: String,
  val isProblem: Boolean = false,
  val isNotice: Boolean = false,
  val isBoundary: Boolean = false,
) {
  CONNECTED("connected", isBoundary = true),
  DISCONNECTED("disconnected", isNotice = true, isBoundary = true),
  CONNECT_FAILED("connect failed", isProblem = true),
  FAILED_OVER("failed over", isNotice = true),
  FAILED_BACK("back to primary"),
  PATH_REGISTERED("registered"),
  PATH_REFUSED("refused", isProblem = true),
  PATH_TAKEN_OVER("taken over", isNotice = true),
  PATH_UNREGISTERED("unregistered"),
  DISCOVERY_FAILED("discovery failed", isProblem = true),
  DISCOVERY_RECOVERED("discovery recovered"),
  DISCOVERY_SKIPPED("entries skipped", isProblem = true),
  FILTER_FAILED_OPEN("not filtered", isProblem = true),
}

/**
 * One line of the agent's activity feed: an event, or a run of like ones folded into one (see [AgentActivityLog]).
 *
 * @param at when it happened; for a run, when the run began
 * @param paths the paths it concerns, at most [AgentActivityLog.MAX_SAMPLE_PATHS] of a run's
 * @param count how many events the line stands for
 * @param detail what an operator needs beyond the rest, such as the endpoint, or why a path was refused
 * @param group what a later event must share, beside its kind, to join this line in a burst: a refusal's cause, so two
 *   causes stay two lines, or an endpoint
 * @param pathCount how many paths the line concerns, more than [paths] holds once a burst outgrows them; a repeat's
 *   path counts once however often it repeats
 */
internal data class AgentActivityEntry(
  val at: Instant,
  val kind: AgentActivityKind,
  val paths: List<String> = emptyList(),
  val count: Int = 1,
  val detail: String = "",
  val group: String = "",
  val pathCount: Int = paths.size,
) {
  // Whether [next] is the same kind of event, in the same group, which a burst's events share.
  fun isLike(next: AgentActivityEntry): Boolean = kind == next.kind && group == next.group

  // Whether [next] is this line's event again, which joins it however long since.
  fun isRepeatedBy(next: AgentActivityEntry): Boolean = isLike(next) && detail == next.detail && paths == next.paths

  // This line with one more event folded in: counted, and [newPaths], those of its paths the line hadn't counted,
  // counted too and named while the line names fewer than the sample's.
  fun joinedBy(newPaths: List<String>): AgentActivityEntry =
    copy(
      count = count + 1,
      paths = (paths + newPaths).take(AgentActivityLog.MAX_SAMPLE_PATHS),
      pathCount = pathCount + newPaths.size,
    )
}

/**
 * The agent's recent comings and goings, for its dashboard's activity feed: its connection made, lost, failing, and
 * failed over; its paths registered, refused, and removed; its discovery file failing to read, reading again, or
 * skipping entries; and a metric filter letting a scrape through unfiltered.
 *
 * Each of these reaches the agent's log too, but the log is behind the firewall with the agent, and its lines scroll
 * away: the feed keeps the order they came in, so an operator can see that a path was refused just after the agent
 * failed over, say.
 *
 * Bounded on every write: at most [capacity] lines, each value cut, oldest dropped first. Three things keep a run of
 * like events from pushing everything else out:
 * - A burst of like events within [COALESCE_WINDOW] is one line, counting them and naming the first [MAX_SAMPLE_PATHS]
 *   paths, however other events interleave with it: the agent registering two thousand paths as it connects is one
 *   line, not the whole feed. A connect or a disconnect starts a new run, which no later event folds back past, so a
 *   connection that drops and comes back within the window keeps its lines in order. The line counts each of its paths
 *   once, those past the sample's too, while a burst can join it.
 * - The same event again, with nothing between, is the newest line counted once more, however long since: an agent
 *   cycling through its endpoints through a long outage fails over the same way every few seconds.
 * - A connect attempt's own lines, its connect and its static paths' registrations and refusals ([ATTEMPT_KINDS]),
 *   wait in a [Hold] until the attempt knows whether they're news: an attempt the proxy refuses again as it refused the
 *   last one would add them on every retry.
 *
 * Kept in memory only, so a restart empties it, and only with the dashboard on, since nothing else reads it.
 *
 * Every write, a fold and a hold's release included, adds one to a change count, which [snapshot] reads with the lines
 * and [changes] carries, so the dashboard wakes on a new line and sends a page the feed only when it changed. A line
 * held, or dropped with its hold, changes nothing the feed shows, so it adds nothing.
 *
 * Thread-safe. Every access takes the log's own monitor, which is a leaf: nothing else is called while it is held, so a
 * caller holding a lock of its own, such as the path manager's, may record. [changes] is set after the monitor is let
 * go.
 *
 * @param capacity the most lines kept, `agent.dashboard.activityLogSize`
 * @param clock where an event's time comes from; injectable so tests move time instead of sleeping
 */
internal class AgentActivityLog(
  private val capacity: Int,
  private val clock: Clock = Clock.System,
) {
  init {
    require(capacity > 0) { "capacity must be positive: $capacity" }
  }

  /**
   * The lines and the change count they were read at, in one read.
   *
   * @param changeCount how many writes the log had taken, a fold counting as one: equal counts mean equal lines
   * @param entries the lines, newest first
   */
  data class Snapshot(
    val changeCount: Long = 0L,
    val entries: List<AgentActivityEntry> = emptyList(),
  )

  /**
   * A connect attempt's own lines, held from [holdAttempt] until the attempt knows whether they're news (see
   * `AgentGrpcService.registrationFailed`). [release] adds them to the feed, as for an attempt that registered, or one
   * whose failure is new; [discard] drops them, as for one that failed as the last attempt to its endpoint did. Either
   * ends the hold, and a second call, or a call once another hold began, does nothing.
   */
  inner class Hold internal constructor() {
    /** Adds the held lines to the feed, in the order they came, and ends the hold. */
    fun release() = endHold(this, keep = true)

    /** Drops the held lines, and ends the hold. */
    fun discard() = endHold(this, keep = false)
  }

  // A line, with every path it has counted while a burst can still join it: within COALESCE_WINDOW of its start (see
  // settle). Past that, only a repeat can, whose paths are the line's own, so the sample alone tells them apart.
  private class Line(
    var entry: AgentActivityEntry,
  ) {
    var counted: MutableSet<String>? = entry.paths.toHashSet()

    // Folds [next] in, counting each of its paths the line hasn't. Against the sample alone, a path past it was
    // counted again each time it came back.
    fun join(next: AgentActivityEntry) {
      entry = entry.joinedBy(next.paths.filter { path -> counted?.add(path) ?: (path !in entry.paths) })
    }
  }

  // Oldest first, so a new line goes on the end and the oldest comes off the front.
  private val lines = ArrayDeque<Line>()

  // The index in lines of the oldest line that may still keep its counted paths; every line before it has let go of
  // them (see settle). Guarded by lines' monitor.
  private var unsettled = 0

  // The hold an attempt has open, and the lines it holds, folded as the feed folds them. Guarded by lines' monitor.
  private var hold: Hold? = null
  private var held = ArrayDeque<Line>()

  // How many writes the log has taken, each append and each fold. Guarded by lines' monitor, so a snapshot reads it at
  // the same moment as the lines.
  private var changeCount = 0L

  private val changeFlow = MutableStateFlow(0L)

  /** The change count, for the dashboard's push loop to wake on. */
  val changes: StateFlow<Long> = changeFlow.asStateFlow()

  /**
   * Records [kind] about [path], when there is one, with [detail], folding it into a line it repeats or a burst it
   * joins (see the class's account), or into the open [Hold]'s lines, when it's one of [ATTEMPT_KINDS]; [group] is what
   * a burst's events must share beside their kind.
   */
  fun record(
    kind: AgentActivityKind,
    path: String = "",
    detail: String = "",
    group: String = "",
  ) {
    val entry =
      AgentActivityEntry(
        at = clock.now(),
        kind = kind,
        paths = if (path.isEmpty()) emptyList() else listOf(AgentValues.shownValue(path, MAX_FIELD_CHARS)),
        detail = AgentValues.shownValue(detail, MAX_DETAIL_CHARS),
        group = AgentValues.truncate(group, MAX_FIELD_CHARS),
      )
    val count =
      synchronized(lines) {
        if (hold != null && kind in ATTEMPT_KINDS) {
          fold(held, entry)
          return
        }
        settle(entry)
        fold(lines, entry)
        changeCount += 1
        changeCount
      }
    // Never back to a lower count, though two recorders let go of the monitor in the other order.
    changeFlow.update { maxOf(it, count) }
  }

  /**
   * Holds the lines of [ATTEMPT_KINDS] recorded from now until the hold is released or discarded, for a connect attempt
   * to decide whether they're news. One attempt runs at a time; a hold begun while another is open ends that one,
   * releasing its lines.
   */
  fun holdAttempt(): Hold {
    val earlier = synchronized(lines) { hold }
    earlier?.release()
    return synchronized(lines) { Hold().also { hold = it } }
  }

  /**
   * A copy of the lines, newest first, with the change count, read under one acquisition of the monitor, so a write
   * landing between the two can't pair the lines from before it with the count from after it.
   */
  fun snapshot(): Snapshot =
    synchronized(lines) { Snapshot(changeCount, List(lines.size) { lines[lines.lastIndex - it].entry }) }

  // Ends [ending], adding its lines to the feed if [keep], unless it ended already.
  private fun endHold(
    ending: Hold,
    keep: Boolean,
  ) {
    val count =
      synchronized(lines) {
        if (hold !== ending)
          return
        val released = held
        hold = null
        held = ArrayDeque()
        if (!keep || released.isEmpty())
          return
        // Appended as they are: an attempt's lines begin with its connect, which starts a run no line folds back past.
        released.forEach { append(lines, it) }
        changeCount += 1
        changeCount
      }
    changeFlow.update { maxOf(it, count) }
  }

  // Folds [entry] into the line of [into] it joins (see [joinable]), or adds it as a line of its own. Callers MUST hold
  // lines' monitor.
  private fun fold(
    into: ArrayDeque<Line>,
    entry: AgentActivityEntry,
  ) {
    val at = joinable(into, entry)
    if (at >= 0)
      into[at].join(entry)
    else
      append(into, Line(entry))
  }

  // The index of the line of [into] that [entry] joins, or -1 for none: the newest line, when [entry] repeats it, or
  // else the line of its burst. A connect or a disconnect joins only a line it repeats. Callers MUST hold lines'
  // monitor.
  private fun joinable(
    into: ArrayDeque<Line>,
    entry: AgentActivityEntry,
  ): Int =
    when {
      into.lastOrNull()?.entry?.isRepeatedBy(entry) == true -> into.lastIndex
      entry.kind.isBoundary -> -1
      else -> burstLine(into, entry)
    }

  // The newest line of [into] like [entry] within the window of its burst's start, or -1 for none. Lines are in the
  // order their bursts began, so the search ends at the first line outside the window, and at a connect or a
  // disconnect, which starts a run no later event folds back past. Callers MUST hold lines' monitor.
  private fun burstLine(
    into: ArrayDeque<Line>,
    entry: AgentActivityEntry,
  ): Int {
    for (i in into.indices.reversed()) {
      val line = into[i].entry
      if (line.kind.isBoundary || entry.at - line.at >= COALESCE_WINDOW)
        break
      if (line.isLike(entry))
        return i
    }
    return -1
  }

  // Has each line that no burst can join any more, as of [entry], let go of the paths it counted: lines are in the
  // order their bursts began, so those are the oldest, and each is let go of once. Callers MUST hold lines' monitor.
  private fun settle(entry: AgentActivityEntry) {
    while (unsettled < lines.size && entry.at - lines[unsettled].entry.at >= COALESCE_WINDOW) {
      lines[unsettled].counted = null
      unsettled += 1
    }
  }

  // Callers MUST hold lines' monitor.
  private fun append(
    into: ArrayDeque<Line>,
    line: Line,
  ) {
    if (into.size >= capacity) {
      into.removeFirst()
      if (into === lines && unsettled > 0)
        unsettled -= 1
    }
    into.addLast(line)
  }

  companion object {
    // A burst: the agent registering its paths as it connects, or discovery adding a file's worth, takes about this
    // long.
    private val COALESCE_WINDOW = 5.seconds

    // Enough to say which paths a burst was about without the line growing with it.
    const val MAX_SAMPLE_PATHS = 3

    // What a connect attempt records before it knows whether it's news (see holdAttempt): its connect, and its static
    // paths' registrations and refusals.
    private val ATTEMPT_KINDS: Set<AgentActivityKind> =
      setOf(AgentActivityKind.CONNECTED, AgentActivityKind.PATH_REGISTERED, AgentActivityKind.PATH_REFUSED)

    // What a line keeps of each value.
    private const val MAX_FIELD_CHARS = 256
    private const val MAX_DETAIL_CHARS = 512
  }
}
