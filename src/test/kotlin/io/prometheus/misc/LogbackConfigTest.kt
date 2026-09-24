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

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.status.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File
import java.net.URI
import java.net.URL
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class LogbackConfigTest : StringSpec() {
  // Logback prints its whole internal status list (~30 "|-INFO in ch.qos.logback..." lines) at startup
  // whenever configuring recorded a WARN or ERROR status, so a config that records none starts silently.
  private fun warningsFrom(configUrl: URL): List<String> {
    val context = LoggerContext()
    try {
      JoranConfigurator().apply { this.context = context }.doConfigure(configUrl)
      return context.statusManager.copyOfStatusList
        .filter { it.effectiveLevel >= Status.WARN }
        .map { it.message }
    } finally {
      context.stop()
    }
  }

  init {
    // The fat JARs load src/main/resources/logback.xml as a jar: URL, which logback can't watch, so a
    // scan="true" there records two WARNs and every start of the proxy or agent printed the status list.
    "bundled logback.xml should configure from inside a JAR without warnings" {
      val jar = File.createTempFile("logback-config", ".jar").apply { deleteOnExit() }
      JarOutputStream(jar.outputStream()).use { out ->
        out.putNextEntry(JarEntry("logback.xml"))
        out.write(File("src/main/resources/logback.xml").readBytes())
        out.closeEntry()
      }

      warningsFrom(URI("jar:${jar.toURI()}!/logback.xml").toURL()).shouldBeEmpty()
    }

    // The external example is meant for -Dlogback.configurationFile, where it is a file on disk and its
    // scan="true" can reload edits without the status-list noise.
    "logback/docker-logback.xml should configure from a file on disk without warnings" {
      warningsFrom(File("logback/docker-logback.xml").toURI().toURL()).shouldBeEmpty()
    }
  }
}
