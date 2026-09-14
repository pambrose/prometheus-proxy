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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import java.io.File

class ShadowServiceFilesTest : StringSpec() {
  private val serviceFiles by lazy {
    File("src/shadow/resources/META-INF/services").listFiles { file -> file.isFile }.orEmpty().toList()
  }

  init {
    // agentJar and proxyJar merge these pinned files into the fat JARs' META-INF/services. Only the Docker
    // container suite runs the fat JARs, so nothing else reads them, and a listed class that no longer exists
    // makes ServiceLoader throw ServiceConfigurationError the first time gRPC loads that service -- an agent
    // then dies building its channel. The test runtime classpath carries the same runtime dependencies as the
    // fat JARs, so resolving each entry here catches a stale name without Docker.
    "the pinned gRPC service files should be present" {
      val names = serviceFiles.map { it.name }
      names shouldContainAll listOf("io.grpc.NameResolverProvider", "io.grpc.LoadBalancerProvider")
    }

    "every provider in a pinned service file should load and implement its service" {
      val loader = javaClass.classLoader
      val problems =
        serviceFiles.flatMap { file ->
          val service =
            runCatching { Class.forName(file.name, false, loader) }.getOrNull()
              ?: return@flatMap listOf("${file.name}: service type not found")
          file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { name ->
              val provider = runCatching { Class.forName(name, false, loader) }.getOrNull()
              when {
                provider == null -> "${file.name}: $name not found"
                !service.isAssignableFrom(provider) -> "${file.name}: $name does not implement ${service.name}"
                else -> null
              }
            }
        }

      problems.shouldBeEmpty()
    }
  }
}
