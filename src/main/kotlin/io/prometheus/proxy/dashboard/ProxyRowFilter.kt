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

package io.prometheus.proxy.dashboard

import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.RowFilter.Companion.pathAsShown
import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel

/**
 * Whether the Paths view shows [path]: its path, its agents' names, its target URL, and its identity are what it
 * shows, and it is a problem when its last scrape failed or its agent has gone ([PathView.isProblem]).
 */
internal fun RowFilter.matches(path: PathView): Boolean = matches(path.isProblem) { path.searchFields() }

/**
 * Whether the Activity view shows [entry]: its event, its agent's name, its identity, its paths, and its detail are
 * what it shows, and it is a problem when an operator has to act on it ([io.prometheus.proxy.ActivityKind.isProblem]).
 */
internal fun RowFilter.matches(entry: ActivityEntry): Boolean = matches(entry.kind.isProblem) { entry.searchFields() }

// What the row shows, as it shows it: a path with its slash, an agent and an identity as their labels read, and the
// agent the row names (servingAgent), the first of agentNames, or for a departed path with none, the one its last
// scrape names.
private fun PathView.searchFields(): List<String> =
  listOfNotNull(pathAsShown(path), targetUrl, servingAgent?.let(::agentLabel), identityLabel(identity)) +
    agentNames.map(::agentLabel)

private fun ActivityEntry.searchFields(): List<String> =
  listOfNotNull(kind.label, agentName.ifEmpty { null }?.let(::agentLabel), identityLabel(identity), detail) +
    paths.map(::pathAsShown)
