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

package io.prometheus.containers

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.containers.support.ContainerTestSupport.agentImage
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.proxyImage
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import java.util.jar.JarFile

// What the images and fat JARs are built with, rather than how they run: the user the images run as, which the
// charts' securityContext names by number, and the NOTICE and LICENSE files the JARs redistribute.
class ContainersPackagingTest : StringSpec() {
  private val images = listOf("proxy" to proxyImage, "agent" to agentImage)
  private val fatJars = listOf("build/libs/prometheus-proxy.jar", "build/libs/prometheus-agent.jar")

  private fun entryText(
    jar: String,
    name: String,
  ): String? =
    JarFile(jar).use { file -> file.getJarEntry(name)?.let { file.getInputStream(it).bufferedReader().readText() } }

  init {
    if (!containerTestsEnabled()) {
      "Image user and fat-JAR notices (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      // The charts set runAsUser, runAsGroup, and fsGroup to 1001, but the images' user got 1001 only because the
      // base image already had a user at 1000, and USER named it, so runAsNonRoot alone couldn't be checked by the
      // kubelet (October #39).
      "each image should run as UID and GID 1001, given by number, and own /app" {
        images.forEach { (name, image) ->
          withClue(name) {
            DockerClientFactory.instance().client().inspectImageCmd(image.get()).exec().config.shouldNotBeNull()
              .user shouldBe "1001:1001"

            val ids = "echo $(id -u):$(id -g) $(stat -c %u:%g /app)"
            val container =
              GenericContainer(image)
                .withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", ids) }
                .withStartupCheckStrategy(OneShotStartupCheckStrategy())
            try {
              container.start()
              container.logs.trim() shouldBe "1001:1001 1001:1001"
            } finally {
              stopQuietly(container)
            }
          }
        }
      }

      // Under the fat JARs' first-wins duplicates strategy, the only META-INF/NOTICE left was the Prometheus
      // library's, and the Apache License's section 4(d) requires every one (October #40).
      "each fat JAR's NOTICE should carry every dependency's notice" {
        fatJars.forEach { jar ->
          withClue(jar) {
            val notice = entryText(jar, "META-INF/NOTICE").shouldNotBeNull()
            listOf(
              "Prometheus instrumentation library for JVM applications",
              "Jackson is a high-performance",
              "The OpenZipkin Authors",
              "The Netty Project",
              "Eclipse Project for Servlet",
              "JCommander",
            ).forEach { notice shouldContain it }
          }
        }
      }

      // The same strategy kept one dependency's META-INF/LICENSE and one META-INF/LICENSE.txt, dropping, among
      // others, slf4j's MIT license, which must go with every copy.
      "each fat JAR's LICENSE should be the project's, followed by every dependency's" {
        fatJars.forEach { jar ->
          withClue(jar) {
            val license = entryText(jar, "META-INF/LICENSE").shouldNotBeNull()
            license shouldStartWith "SPDX-License-Identifier: Apache-2.0"
            listOf(
              "Apache License",
              "Permission is hereby granted, free  of charge",
              "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL)",
              "Eclipse Public License - v 2.0",
            ).forEach { license shouldContain it }
          }
        }
      }
    }
  }
}
