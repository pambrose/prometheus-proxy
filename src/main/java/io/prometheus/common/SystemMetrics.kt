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

package io.prometheus.common

import io.prometheus.client.Collector
import io.prometheus.client.hotspot.ClassLoadingExports
import io.prometheus.client.hotspot.GarbageCollectorExports
import io.prometheus.client.hotspot.MemoryPoolsExports
import io.prometheus.client.hotspot.StandardExports
import io.prometheus.client.hotspot.ThreadExports
import io.prometheus.client.hotspot.VersionInfoExports

object SystemMetrics {
    private var initialized = false

    @Synchronized
    fun initialize(enableStandardExports: Boolean = false,
                   enableMemoryPoolsExports: Boolean = false,
                   enableGarbageCollectorExports: Boolean = false,
                   enableThreadExports: Boolean = false,
                   enableClassLoadingExports: Boolean = false,
                   enableVersionInfoExports: Boolean = false) {
        if (!initialized) {
            if (enableStandardExports)
                StandardExports().register<Collector>()
            if (enableMemoryPoolsExports)
                MemoryPoolsExports().register<Collector>()
            if (enableGarbageCollectorExports)
                GarbageCollectorExports().register<Collector>()
            if (enableThreadExports)
                ThreadExports().register<Collector>()
            if (enableClassLoadingExports)
                ClassLoadingExports().register<Collector>()
            if (enableVersionInfoExports)
                VersionInfoExports().register<Collector>()
            initialized = true
        }
    }
}
