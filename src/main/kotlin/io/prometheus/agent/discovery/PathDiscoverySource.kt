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

package io.prometheus.agent.discovery

/**
 * A single target path discovered from a dynamic source, mirroring the shape of a static
 * `agent.pathConfigs` entry.
 */
internal data class DiscoveredPath(
  val name: String,
  val path: String,
  val url: String,
  val labels: String,
) {
  // An entry missing either could never register -- AgentPathManager.doRegisterPath requires both -- so a source
  // drops it rather than letting it fail on every reconcile.
  val usable: Boolean get() = path.isNotBlank() && url.isNotBlank()
}

/**
 * A source of dynamically discovered target paths for the agent to reconcile against.
 *
 * The MVP implementation is [FileDiscoverySource]; a Kubernetes or Docker source would implement
 * this same interface without any other change.
 *
 * @see io.prometheus.agent.discovery.PathDiscoveryService
 */
internal fun interface PathDiscoverySource {
  /**
   * Returns the current desired set of discovered paths.
   *
   * A valid source with no entries returns an empty list (which the reconciler treats as "remove all
   * discovered paths"). A read or parse **failure** must **throw** rather than return empty, so the
   * caller can tell failure apart from emptiness and preserve the last-known-good set.
   */
  fun read(): List<DiscoveredPath>

  /**
   * The entries the last [read] dropped as unusable, for the agent's debug page.
   *
   * Empty by default: a source that drops nothing, or does not track what it dropped, need not implement this.
   */
  val skippedEntries: List<DiscoveredPath> get() = emptyList()
}
