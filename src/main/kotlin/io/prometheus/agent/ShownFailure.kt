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

import com.pambrose.common.util.simpleClassName
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.Utils.sanitizeUrlsInText
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A failure as the agent's dashboard shows it: what it was, and when. The dashboard may have no login, so every
 * failure is kept through [of], which redacts any URL's credentials and query values (a discovery file's parse error
 * can quote a target's URL), escapes control characters, and cuts it to a sentence or two.
 */
internal data class ShownFailure(
  val message: String,
  val at: Instant,
) {
  companion object {
    // How much of a failure the dashboard shows: the proxy's reasons run to a sentence or two.
    private const val SHOWN_FAILURE_CHARS = 512

    /** [text], redacted, escaped, and cut, failing now. */
    fun of(text: String): ShownFailure =
      ShownFailure(shownValue(sanitizeUrlsInText(text), SHOWN_FAILURE_CHARS), Clock.System.now())

    /** [e], as its class and message, redacted, escaped, and cut, failing now. */
    fun of(e: Throwable): ShownFailure = of("${e.simpleClassName}: ${e.message}")
  }
}
