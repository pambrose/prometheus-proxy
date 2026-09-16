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

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import java.io.File

/**
 * Reads discovered paths from a local HOCON/JSON file (HOCON is a JSON superset, so either parses).
 *
 * The file holds a `paths` list of `{ name, path, url, labels }` objects; `path` and `url` are
 * required, `name` defaults to `path`, and `labels` defaults to `"{}"`. A missing, unreadable, or
 * malformed file **throws** ([setAllowMissing(false)][ConfigParseOptions.setAllowMissing] makes an
 * absent file an error, not an empty config); a valid file with no `paths` entries returns an empty
 * list.
 *
 * An entry whose `path` or `url` is blank parses but could never register, so it is dropped here and
 * the rest of the file is still read. Dropping it is reported when the set of dropped entries changes,
 * not on every read, since the file is re-read on every reconcile.
 *
 * @param filePath path to the discovery file (from `agent.discovery.file.path`)
 */
internal class FileDiscoverySource(
  private val filePath: String,
) : PathDiscoverySource {
  // What the last read dropped, so the same bad file is reported once. Read and written only by the discovery
  // coroutine, which polls this source one read at a time.
  private var reportedUnusable = emptySet<String>()

  init {
    require(filePath.isNotEmpty()) { "Discovery file path is empty" }
  }

  override fun read(): List<DiscoveredPath> {
    // setAllowMissing(false): a missing file throws instead of yielding an empty config, so it is
    // never mistaken for a valid-but-empty file (which would tear down every discovered path).
    // resolve(): parseFile() leaves substitutions unevaluated, so ${...} would throw NotResolved on
    // the first getString() below. A no-op for files that use no substitutions.
    val config = ConfigFactory.parseFile(File(filePath), PARSE_OPTIONS).resolve()
    val elements = if (config.hasPath(PATHS_KEY)) config.getConfigList(PATHS_KEY) else emptyList()
    val entries =
      elements.map { element ->
        val path = element.getString("path") // required; a missing field throws (malformed)
        DiscoveredPath(
          name = if (element.hasPath("name")) element.getString("name") else path,
          path = path,
          url = element.getString("url"), // required
          labels = if (element.hasPath("labels")) element.getString("labels") else "{}",
        )
      }
    val (usable, unusable) = entries.partition { it.path.isNotBlank() && it.url.isNotBlank() }
    reportUnusable(unusable)
    return usable
  }

  // Reports the entries dropped from the last read, but only when that set changes: the file is re-read every
  // reconcile, so a file left unfixed would otherwise repeat the same warning forever.
  private fun reportUnusable(unusable: List<DiscoveredPath>) {
    val descriptions = unusable.map { "${it.name.ifBlank { "unnamed" }} (path='${it.path}', url='${it.url}')" }
    if (descriptions.toSet() == reportedUnusable)
      return
    reportedUnusable = descriptions.toSet()
    if (descriptions.isNotEmpty())
      logger.warn { "Skipping ${descriptions.size} discovery entries needing a path and a url: $descriptions" }
  }

  companion object {
    private val logger = logger {}

    private const val PATHS_KEY = "paths"
    private val PARSE_OPTIONS: ConfigParseOptions = ConfigParseOptions.defaults().setAllowMissing(false)
  }
}
