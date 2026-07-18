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
 * @param filePath path to the discovery file (from `agent.discovery.file.path`)
 */
internal class FileDiscoverySource(
  private val filePath: String,
) : PathDiscoverySource {
  init {
    require(filePath.isNotEmpty()) { "Discovery file path is empty" }
  }

  override fun read(): List<DiscoveredPath> {
    // setAllowMissing(false): a missing file throws instead of yielding an empty config, so it is
    // never mistaken for a valid-but-empty file (which would tear down every discovered path).
    val config = ConfigFactory.parseFile(File(filePath), PARSE_OPTIONS)
    val elements = if (config.hasPath(PATHS_KEY)) config.getConfigList(PATHS_KEY) else []
    return elements.map { element ->
      val path = element.getString("path") // required; a missing field throws (malformed)
      DiscoveredPath(
        name = if (element.hasPath("name")) element.getString("name") else path,
        path = path,
        url = element.getString("url"), // required
        labels = if (element.hasPath("labels")) element.getString("labels") else "{}",
      )
    }
  }

  companion object {
    private const val PATHS_KEY = "paths"
    private val PARSE_OPTIONS: ConfigParseOptions = ConfigParseOptions.defaults().setAllowMissing(false)
  }
}
