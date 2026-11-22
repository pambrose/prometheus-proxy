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

package io.prometheus.containers.support

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode

/**
 * The Kotest config of a run of only the container specs, which build.gradle.kts selects with
 * `kotest.framework.config.fqn`: their specs run [CONCURRENT_SPECS] at a time. Each spec starts its own containers on a
 * network of its own, with every host port random, and shares nothing in the JVM but the two images, which build once
 * (see [ContainerTestSupport.proxyImage]). So a spec spends most of its time waiting on its containers to start, which
 * specs can do side by side. A run with any other spec keeps Kotest's default, one spec at a time, since the other
 * specs share the default registry and log capture.
 */
class ContainerTestsConfig : AbstractProjectConfig() {
  override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(CONCURRENT_SPECS)

  companion object {
    // Three keeps a 4-vCPU CI runner busy without starving the containers' JVMs as they start.
    const val CONCURRENT_SPECS = 3
  }
}
