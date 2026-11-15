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
 */
internal enum class ActivityKind(
  val label: String,
  val isProblem: Boolean = false,
) {
  AGENT_CONNECTED("connected"),
  AGENT_DISCONNECTED("disconnected"),
  AGENT_EVICTED("evicted", isProblem = true),
  AGENT_REFUSED("refused", isProblem = true),
  BAD_TOKEN("bad token", isProblem = true),
  PATH_REGISTERED("registered"),
  PATH_UNREGISTERED("unregistered"),
  PATH_TAKEN_OVER("taken over"),
  PATH_REJECTED("rejected", isProblem = true),
}

/**
 * One line of the activity feed: an event, or a burst of like ones from one agent folded into one (see [ActivityLog]).
 *
 * @param at when it happened; for a burst, when the burst began
 * @param agentName the agent's name, or empty for an event that has no agent, such as a bad token
 * @param identity the auth identity it acted as, or empty with agent auth off or none known
 * @param paths the paths it concerns, at most [ActivityLog.MAX_SAMPLE_PATHS] of a burst's
 * @param count how many events the line stands for, more than [paths] holds once a burst outgrows them
 * @param detail what an operator needs beyond the rest, such as why a path was refused, or where a bad token came from
 * @param group what a later event must share, beside its kind, agent, and identity, to join this line: a rejection's
 *   cause, so two causes stay two lines, or a bad token's source
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
) {
  // Whether [next] is one more of this line's burst.
  fun isJoinedBy(
    next: ActivityEntry,
    window: Duration,
  ): Boolean =
    kind == next.kind && agentId == next.agentId && identity == next.identity && group == next.group &&
      next.at - at < window

  // This line with [next] folded in.
  fun joinedBy(next: ActivityEntry): ActivityEntry =
    copy(
      count = count + 1,
      paths = if (paths.size < ActivityLog.MAX_SAMPLE_PATHS) paths + next.paths else paths,
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
 *   is one line, not the whole log, and so is each agent of a fleet reconnecting at once.
 * - [recordThrottled] takes at most one event per key per [THROTTLE_INTERVAL], for what anyone who can reach the agent
 *   port can cause, a bad token, keyed by its source. The keys it remembers are capped too, the least recent going
 *   first.
 *
 * Kept in memory only, so a restart empties it. Written only with the dashboard on, since nothing else reads it.
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
  // Oldest first, so a new line goes on the end and the oldest comes off the front.
  private val lines = ArrayDeque<ActivityEntry>()

  // When each throttled key last recorded. Access-ordered, so the least recently used key is first, and the eldest goes
  // once there are more than MAX_THROTTLE_KEYS. Guarded by lines' monitor.
  private val throttled =
    object : LinkedHashMap<String, Instant>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>) = size > MAX_THROTTLE_KEYS
    }

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
    synchronized(lines) { add(entry) }
  }

  /** Records [kind] as [record] does, unless [key] recorded within the last [THROTTLE_INTERVAL]. */
  fun recordThrottled(
    key: String,
    kind: ActivityKind,
    detail: String,
  ) {
    val entry = entry(kind, agent = null, path = "", detail, group = key, identity = "")
    synchronized(lines) {
      val last = throttled[entry.group]
      if (last != null && entry.at - last < THROTTLE_INTERVAL)
        return
      throttled[entry.group] = entry.at
      add(entry)
    }
  }

  /** A copy of the lines, newest first. */
  fun entries(): List<ActivityEntry> = synchronized(lines) { lines.reversed() }

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

  // Folds [entry] into the line of its burst, or adds it. The burst's line needn't be the newest: when a fleet
  // reconnects at once, every agent's registrations interleave, and joining only the newest line would give each
  // registration its own, the flood this exists to prevent. Only lines within the window can be joined, so the search
  // stops at the first older one. Callers MUST hold lines' monitor.
  private fun add(entry: ActivityEntry) {
    if (capacity <= 0) return
    val burst =
      lines.indices.reversed().asSequence()
        .takeWhile { entry.at - lines[it].at < COALESCE_WINDOW }
        .firstOrNull { lines[it].isJoinedBy(entry, COALESCE_WINDOW) }
    if (burst != null) {
      lines[burst] = lines[burst].joinedBy(entry)
      return
    }
    if (lines.size >= capacity)
      lines.removeFirst()
    lines.addLast(entry)
  }

  companion object {
    // A burst: an agent registering its paths as it starts, or retrying them all, takes about this long.
    val COALESCE_WINDOW = 5.seconds
    val THROTTLE_INTERVAL = 1.minutes

    // Enough to say which paths a burst was about without the line growing with it.
    const val MAX_SAMPLE_PATHS = 3
    const val MAX_THROTTLE_KEYS = 1000

    // What a line keeps of each value. A path or name past this is cut; the proxy's own bounds are usually tighter.
    const val MAX_FIELD_CHARS = 256
    const val MAX_DETAIL_CHARS = 512

    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
  }
}
