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

import io.github.oshai.kotlinlogging.KLogger
import io.prometheus.Proxy

/**
 * How many paths each agent serves, by agentId, and the agents of each auth identity together, for
 * `proxy.internal.maxPathsPerAgent` and `maxPathsPerIdentity`, and the warnings as either count nears its limit.
 *
 * [ProxyPathManager] keeps the counts in step with every change to its path map, so enforcing the limits, and finding
 * an agent left with no paths, needs no scan of the map. An agent's paths count against the identity its first one
 * registered as, so each path it gives up comes off the identity it was counted against, even when a later call
 * presented another identity's token.
 *
 * Not thread-safe: the path manager reads and changes it only under its path map's lock, and changes it only through
 * its `countPaths`, so the counts and the warnings change in the map's order.
 *
 * @param proxy where the limits are read, as each count changes
 * @param logger the path manager's, which the warnings go to
 */
internal class PathCounts(
  private val proxy: Proxy,
  private val logger: KLogger,
) {
  private val agentCounts = HashMap<String, Int>()

  // The identity each agent's paths count against, by agentId, while it serves any.
  private val countedIdentities = HashMap<String, String>()
  private val identityCounts = HashMap<String, Int>()

  // When to warn that an agent nears maxPathsPerAgent, and the agents of an identity maxPathsPerIdentity.
  private val agentsNearLimit = NearLimitWarnings<String>()
  private val identitiesNearLimit = NearLimitWarnings<String>()

  /** The number of paths [agentId] serves. */
  fun of(agentId: String): Int = agentCounts[agentId] ?: 0

  /** The number of paths the agents of [identity] serve together, empty for agents with no auth identity. */
  fun ofIdentity(identity: String): Int = identityCounts[identity] ?: 0

  /** The identity [agentId]'s paths count against, or null while it serves none. */
  fun identityOf(agentId: String): String? = countedIdentities[agentId]

  /**
   * Adds [delta] to the paths [agentId] serves, and to its identity's: [identity], the identity of the registration,
   * when the agent serves none yet. Warns as either count reaches 80% of its limit, once, until it falls below 70%.
   */
  fun add(
    agentId: String,
    delta: Int,
    identity: String,
  ) {
    val counted = countedIdentities[agentId] ?: identity
    val agentCount = agentCounts.addTo(agentId, delta)
    if (agentCount > 0)
      countedIdentities[agentId] = counted
    else
      countedIdentities.remove(agentId)
    val identityCount = identityCounts.addTo(counted, delta)
    if (delta > 0) {
      warnNearLimit(agentsNearLimit, agentId, agentCount, proxy.maxPathsPerAgent) { max ->
        "Agent $agentId serves $agentCount of $max paths allowed by proxy.internal.maxPathsPerAgent"
      }
      warnNearLimit(identitiesNearLimit, counted, identityCount, proxy.maxPathsPerIdentity) { max ->
        "The agents ${identityDesc(counted)} serve $identityCount of $max paths allowed by " +
          "proxy.internal.maxPathsPerIdentity"
      }
    } else {
      agentsNearLimit.fell(agentId, agentCount)
      identitiesNearLimit.fell(counted, identityCount)
    }
  }

  // Warns as [key]'s count, now [count], reaches 80% of [max], so an operator hears about it before the registrations
  // start failing: an agent's for maxPathsPerAgent, an identity's agents' for maxPathsPerIdentity. [warning] is the
  // line, given the limit.
  private fun warnNearLimit(
    warnings: NearLimitWarnings<String>,
    key: String,
    count: Int,
    max: Int,
    warning: (Int) -> String,
  ) {
    if (warnings.rose(key, count, max))
      logger.warn { warning(max) }
  }

  companion object {
    // Adds [delta] to [key]'s count, which goes once it reaches 0, and returns the new count.
    private fun HashMap<String, Int>.addTo(
      key: String,
      delta: Int,
    ): Int = ((this[key] ?: 0) + delta).also { if (it > 0) this[key] = it else remove(key) }

    /**
     * [identity] as a log line or a rejection names the agents of an identity. Identity names are the proxy's own,
     * from proxy.auth, never an agent's.
     */
    internal fun identityDesc(identity: String) =
      if (identity.isEmpty()) "with no auth identity" else "of identity '$identity'"
  }
}
