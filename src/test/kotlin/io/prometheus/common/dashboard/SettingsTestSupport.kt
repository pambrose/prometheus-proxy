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

@file:Suppress("UndocumentedPublicFunction")

package io.prometheus.common.dashboard

// Lookups into a Settings view's groups, shared by the specs of the groups both sides share and of each side's own.

/** The group titled [title]. */
internal fun List<SettingsGroup>.group(title: String): SettingsGroup = single { it.title == title }

/** The value of the setting named [name]. */
internal fun SettingsGroup.value(name: String): String = rows.single { it.name == name }.value

/** The value of the setting named [name] in the group titled [title]. */
internal fun List<SettingsGroup>.value(
  title: String,
  name: String,
): String = group(title).value(name)

/** Every title, name, and value the view shows, one per line, to search for a secret. */
internal fun List<SettingsGroup>.text(): String =
  joinToString("\n") { group -> group.title + "\n" + group.rows.joinToString("\n") { "${it.name}: ${it.value}" } }
