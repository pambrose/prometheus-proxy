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

internal object ProxyConstants {
  const val MISSING_PATH_MSG = "Request missing path"
  const val CACHE_CONTROL_VALUE = "must-revalidate,no-store"
  const val FAVICON_FILENAME = "favicon.ico"

  // Why an agent's context was removed, as the log gives it; the activity feed tells an eviction from the rest by it.
  const val EVICTION_REASON = "Eviction"
  const val TERMINATION_REASON = "Termination"
  const val STREAM_TERMINATED_REASON = "Stream terminated (transport filter disabled)"
}
