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

package io.prometheus.harness

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.TestPorts
import io.prometheus.harness.HarnessConfig.MEDIUM
import java.io.File

object HarnessConstants {
  val logger = logger {}

  val HARNESS_CONFIG: HarnessConfig

  init {
    val defaultValue = MEDIUM
    val harnessConfigName = System.getenv("HARNESS_CONFIG") ?: defaultValue.name
    HARNESS_CONFIG =
      runCatching {
        HarnessConfig.valueOf(harnessConfigName)
      }.getOrElse {
        logger.warn { "Invalid HARNESS_CONFIG: $harnessConfigName - using $defaultValue" }
        defaultValue
      }
    logger.info { "HarnessConfig: ${HARNESS_CONFIG.name}" }
  }

  const val PROXY_PORT = TestPorts.HARNESS_PROXY_PORT

  const val MIN_DELAY_MILLIS = 400
  const val MAX_DELAY_MILLIS = 600

  const val DEFAULT_SCRAPE_TIMEOUT_SECS = 3
  const val DEFAULT_CHUNK_SIZE_BYTES = 5

  private const val HARNESS_CONFIG_FILE = "config/test-configs/harness.conf"
  private const val JUNIT_FILE = "config/test-configs/junit-test.conf"

  // Tests run from the project root. A missing file used to fall back to a copy on GitHub master, which hid a
  // renamed or deleted config and quietly tested against whatever master held.
  internal fun localConfigFile(path: String): String {
    require(File(path).exists()) { "Test config file not found: $path" }
    return path
  }

  val CONFIG_ARG = ["--config", localConfigFile(HARNESS_CONFIG_FILE)]

  val OPTIONS_CONFIG = localConfigFile(JUNIT_FILE)
}
