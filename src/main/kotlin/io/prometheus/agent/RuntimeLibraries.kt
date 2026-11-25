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

package io.prometheus.agent

import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.Job
import java.util.Properties

// The libraries an app's dependency management can hold below the versions the agent runs on, as Spring Boot's does.
// kotlinx-coroutines: Spring Boot 4.0 and 4.1 set it to 1.10.2, which lacks the runBlocking that the agent and Ktor are
// compiled to call. The Prometheus Java client: Spring Boot 4.0 and 4.1.0 set it to 1.4.3 and 1.5.1, which lack the
// metric family descriptors the agent's metrics are named by, and refuse a gauge named agent_build_info. Either fails
// the agent once it runs, with a NoSuchMethodError or an ExceptionInInitializerError, and leaves the app running with
// an agent that never serves, so an embedded agent checks them before it starts. A version it can't read, from a
// library packaged without it, passes.
internal object RuntimeLibraries {
  internal const val MIN_COROUTINES = "1.11.0"
  internal const val MIN_PROMETHEUS_CLIENT = "1.7.0"

  // A library, the version the agent needs, the Spring Boot property that sets it and the releases that set it lower,
  // and the version this JVM has, or null when it can't be read.
  internal class Library(
    val name: String,
    val minimum: String,
    val springBootProperty: String,
    val springBootReleases: String,
    val version: String?,
  ) {
    // Why the agent can't run with this version, or null when it can, or the version can't be read
    fun problem(): String? =
      version
        ?.takeUnless { isAtLeast(it, minimum) }
        ?.let { found ->
          "The agent needs $name $minimum or later, and finds $found, which the app's dependency management " +
            "sets, as Spring Boot $springBootReleases do: with Spring Boot, set its $springBootProperty property to " +
            "$minimum or later."
        }
  }

  // The libraries the agent checks, with the versions this JVM has
  internal fun installed(): List<Library> =
    listOf(
      Library(
        name = "kotlinx-coroutines",
        minimum = MIN_COROUTINES,
        springBootProperty = "kotlin-coroutines.version",
        springBootReleases = "4.0 and 4.1",
        version = resourceText(Job::class.java, "META-INF/kotlinx_coroutines_core.version")?.trim(),
      ),
      Library(
        name = "the Prometheus Java client",
        minimum = MIN_PROMETHEUS_CLIENT,
        springBootProperty = "prometheus-client.version",
        springBootReleases = "4.0 and 4.1.0",
        version = pomVersion(PrometheusRegistry::class.java, "io.prometheus", "prometheus-metrics-model"),
      ),
    )

  /** Throws [IllegalStateException], naming each library [libraries] has below the version the agent needs. */
  fun requireSupported(libraries: List<Library> = installed()) {
    val problems = libraries.mapNotNull { it.problem() }
    check(problems.isEmpty()) { problems.joinToString(" ") }
  }

  // Whether [version] is [minimum] or later, by their leading dotted numbers, so 1.11.0-RC2 is 1.11.0. A version with
  // no leading number can't be compared, and passes.
  internal fun isAtLeast(
    version: String,
    minimum: String,
  ): Boolean {
    fun numbers(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() }.takeWhile { it != null }.filterNotNull()
    val found = numbers(version)
    val needed = numbers(minimum)
    val first =
      (0 until maxOf(found.size, needed.size))
        .map { found.getOrElse(it) { 0 }.compareTo(needed.getOrElse(it) { 0 }) }
        .firstOrNull { it != 0 }
    return found.isEmpty() || (first ?: 0) >= 0
  }

  private fun resourceText(
    anchor: Class<*>,
    path: String,
  ): String? = anchor.classLoader?.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

  // The version in the pom.properties a Maven-built jar carries, read through the class loader of a class in it
  private fun pomVersion(
    anchor: Class<*>,
    group: String,
    artifact: String,
  ): String? =
    resourceText(anchor, "META-INF/maven/$group/$artifact/pom.properties")
      ?.let { text -> Properties().apply { load(text.reader()) }.getProperty("version") }
}
