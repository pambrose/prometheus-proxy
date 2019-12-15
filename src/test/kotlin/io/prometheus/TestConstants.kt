/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import java.io.File

object TestConstants {
  const val REPS = 1000
  const val PROXY_PORT = 9505

  private const val travisFile = "etc/test-configs/travis.conf"
  private const val junitFile = "etc/test-configs/junit-test.conf"
  private const val ghPrefix = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/"

  val CONFIG_ARG = listOf("--config", "${if (File(travisFile).exists()) "" else ghPrefix}$travisFile")

  val OPTIONS_CONFIG = "${if (File(junitFile).exists()) ghPrefix else ""}$junitFile"
}
