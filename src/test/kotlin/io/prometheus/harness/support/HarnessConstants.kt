/*
 * Copyright © 2025 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.harness.support

import java.io.File

object HarnessConstants {
  const val REPS = 1000
  const val PROXY_PORT = 9505
  const val DEFAULT_TIMEOUT = 3
  const val DEFAULT_CHUNK_SIZE = 5

  const val HTTP_SERVER_COUNT = 5
  const val PATH_COUNT = 50
  const val SEQUENTIAL_QUERY_COUNT = 1000
  const val PARALLEL_QUERY_COUNT = 10
  const val CONCURRENT_SCRAPES = 100

  const val MIN_DELAY_MILLIS = 400
  const val MAX_DELAY_MILLIS = 600

  private const val TRAVIS_FILE = "etc/test-configs/travis.conf"
  private const val JUNIT_FILE = "etc/test-configs/junit-test.conf"
  private const val GH_PREFIX = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/"

  val CONFIG_ARG = listOf("--config", "${if (File(TRAVIS_FILE).exists()) "" else GH_PREFIX}$TRAVIS_FILE")

  val OPTIONS_CONFIG = "${if (File(JUNIT_FILE).exists()) GH_PREFIX else ""}$JUNIT_FILE"
}
