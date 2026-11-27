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

import io.prometheus.proxy.ProxyPathManager.AgentContextInfo

/**
 * The proxy's path map: each path's [AgentContextInfo], by its key, and the paths each agent waits on, by agentId,
 * which are the paths whose entry's waiting list names it.
 *
 * [store] is the only way to change an entry, and it keeps the index in step with the waiting lists, so taking an agent
 * off the lists it is on ([forgetWaiter]) visits only those paths. Without the index, every disconnect read every entry
 * under the path map's lock, a port scanner's and a bad token's included, which stalled every scrape's lookup on a
 * proxy with many paths.
 *
 * Not thread-safe: [ProxyPathManager] reads and changes it only while it holds it as its monitor.
 */
internal class PathEntries {
  private val entries = HashMap<String, AgentContextInfo>()

  // The paths each agent waits on, by agentId; an agent waiting on none has no set.
  private val waited = HashMap<String, MutableSet<String>>()

  val size: Int
    get() = entries.size

  val keys: Set<String>
    get() = entries.keys

  operator fun get(path: String): AgentContextInfo? = entries[path]

  operator fun contains(path: String): Boolean = path in entries

  /** A copy of the map; the entries are immutable, so they can be shared. */
  fun toMap(): Map<String, AgentContextInfo> = entries.toMap()

  /** The entries of the paths [agentId] serves. */
  fun servedBy(agentId: String): Map<String, AgentContextInfo> =
    entries.filterValues { info -> info.agentContexts.any { it.agentId == agentId } }

  /** The paths [agentId] waits on. */
  fun waitedOnBy(agentId: String): Set<String> = waited[agentId].orEmpty().toSet()

  /** Stores [info] as [path]'s entry, or removes the entry when [info] is null, and indexes its waiting list. */
  fun store(
    path: String,
    info: AgentContextInfo?,
  ) {
    val old = if (info == null) entries.remove(path) else entries.put(path, info)
    val before = old?.waiting.orEmpty()
    val after = info?.waiting.orEmpty()
    // A copy of an entry keeps its list, so most changes leave the index as it is.
    if (before === after)
      return
    before.forEach { waiter ->
      waited[waiter.agentId]?.also { paths ->
        paths.remove(path)
        if (paths.isEmpty())
          waited.remove(waiter.agentId)
      }
    }
    after.forEach { waiter -> waited.getOrPut(waiter.agentId) { HashSet() }.add(path) }
  }

  /** Takes [agentId] off every waiting list it is on, visiting only those paths. */
  fun forgetWaiter(agentId: String) {
    waited.remove(agentId)?.forEach { path ->
      entries[path]?.also { store(path, it.withoutWaiter(agentId)) }
    }
  }
}
