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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import java.util.jar.JarFile

// What the images and fat JARs are built with, rather than how they run: the user the images run as, which the
// charts' securityContext names by number, the JVM's user and encodings, and the NOTICE and LICENSE files the JARs
// redistribute.
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
      "Image user, JVM settings, and fat-JAR notices (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      // The charts set runAsUser, runAsGroup, and fsGroup to 1001, but the images' user got 1001 only because the
      // base image already had a user at 1000, and USER named it, so runAsNonRoot alone couldn't be checked by the
      // kubelet (October #39).
      "each image should run as UID and GID 1001, given by number, and own /app" {
        images.forEach { (name, image) ->
          withClue(name) {
            val client = DockerClientFactory.instance().client()
            client.inspectImageCmd(image.get()).exec().config.shouldNotBeNull().user shouldBe "1001:1001"

            // The images have no shell to run stat in, so /app's owner comes from the archive docker cp reads.
            val id = client.createContainerCmd(image.get()).exec().id
            try {
              val app =
                client.copyArchiveFromContainerCmd(id, "/app").exec().use { TarArchiveInputStream(it).nextEntry }
              app.shouldNotBeNull().name shouldBe "app/"
              "${app.longUserId}:${app.longGroupId}" shouldBe "1001:1001"
            } finally {
              client.removeContainerCmd(id).withForce(true).exec()
            }
          }
        }
      }

      // The images copy Temurin's JRE onto distroless, which has no user at 1001 and no locales: the Dockerfiles add
      // a passwd entry for the user and the C.UTF-8 locale. Without the locale the JVM's native and stdout encodings
      // fall back to ASCII; the locale comes from Ubuntu's C library and has to load under Debian's.
      "each image's JVM should know its user and use UTF-8" {
        images.forEach { (name, image) ->
          withClue(name) {
            val container =
              GenericContainer(image)
                .withCreateContainerCmdModifier { it.withEntrypoint("java", "-XshowSettings:properties", "-version") }
                .withStartupCheckStrategy(OneShotStartupCheckStrategy())
            try {
              container.start()
              val settings = container.logs
              listOf(
                "user.name = prometheus",
                "user.home = /app",
                "native.encoding = UTF-8",
                "stdout.encoding = UTF-8",
                "sun.jnu.encoding = UTF-8",
              ).forEach { settings shouldContain it }
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
