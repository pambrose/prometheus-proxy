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

import java.util.concurrent.Executors.newCachedThreadPool

object TestConstants {
    internal val EXECUTOR_SERVICE = newCachedThreadPool()
    internal const val REPS = 1000
    internal const val PROXY_PORT = 9505

    private const val CI_TEST = false

    internal val args =
        listOf(
            "--config",
            (if (CI_TEST) "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/" else "") +
                    "etc/test-configs/travis.conf"
        )

    internal val OPTIONS_CONFIG =
        (if (CI_TEST) "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/" else "") +
                "etc/test-configs/junit-test.conf"
}