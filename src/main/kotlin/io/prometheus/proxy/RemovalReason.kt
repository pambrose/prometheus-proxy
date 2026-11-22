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

/**
 * Why the proxy removed an agent's context, which the removal's log lines, the failures of the agent's scrapes, and the
 * dashboard's activity feed each report.
 *
 * Until this existed the reason was a string, and the activity feed told an eviction from the rest by comparing it with
 * a constant, so a reason spelled differently was silently reported as a disconnect. An enum rather than a sealed type:
 * the reasons are a fixed set that carry nothing of their own, so each is one value that a `when` covers exhaustively
 * and a MockK verify or a Lincheck spec names. The feed's line for an eviction names the inactivity limit, which is the
 * proxy's setting rather than the removal's, so [activityCause] is handed it instead of the reason carrying it.
 *
 * @param logText the reason as the log gives it, in parentheses after the agentId, and as a failed scrape's reason
 *   gives it after "Agent disconnected: "
 * @param activityKind how the activity feed names the departure: an eviction is the agent going quiet, which is a
 *   problem; the rest are an agent stopping or losing its connection, which the proxy can't tell apart
 */
internal enum class RemovalReason(
  val logText: String,
  val activityKind: ActivityKind,
) {
  /** The cleanup service found the agent inactive for longer than `proxy.internal.maxAgentInactivitySecs`. */
  EVICTION("Eviction", ActivityKind.AGENT_EVICTED),

  /** The agent's connection closed, which the transport filter saw. */
  TERMINATION("Termination", ActivityKind.AGENT_DISCONNECTED),

  /** With the transport filter disabled, the agent's scrape-request stream ended. */
  STREAM_TERMINATED("Stream terminated (transport filter disabled)", ActivityKind.AGENT_DISCONNECTED),
  ;

  /** What the activity feed says caused the departure, given `proxy.internal.maxAgentInactivitySecs`. */
  fun activityCause(maxInactivitySecs: Int): String =
    when (this) {
      EVICTION -> "No contact within proxy.internal.maxAgentInactivitySecs (${maxInactivitySecs}s)"
      TERMINATION -> "Connection closed"
      STREAM_TERMINATED -> logText
    }
}
