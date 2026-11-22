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

package io.prometheus.common.dashboard

import kotlinx.html.FlowContent
import kotlinx.html.SPAN
import kotlinx.html.div
import kotlinx.html.span

/**
 * A key–value row (docs/DESIGN.md, Key–Value Row), as the agent's detail panes and both dashboards' Settings views
 * show one: [name], then [value], or what [render] puts in its place, such as a time.
 */
internal fun FlowContent.kv(
  name: String,
  value: String = "",
  render: SPAN.() -> Unit = { +value },
) {
  div("kv") {
    span("k") { +name }
    span("v") { render() }
  }
}
