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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.concurrent.TimeUnit

// Each demo's make up runs the release the docs give only once it's later than the demo's NEEDS_RELEASE_AFTER and its
// images are published, and builds from the checkout otherwise, so master's make up works from a version bump until
// the release is out, with no edit on release day (November 23 #25). This runs each demo.sh's cmd_up with a stubbed
// docker and start_demo, and checks what it chose. The Kubernetes demo's clusters pull the images themselves, so only
// Docker Hub counts for it, and it stops when Docker Hub can't be reached rather than leave a pod waiting.
class DemoReleaseGateTest : StringSpec() {
  private class Demo(
    val dir: File,
  ) {
    val name: String get() = dir.name
    val script: File get() = File(dir, "demo.sh")
    val text: String by lazy { script.readText() }
    val isKubernetes: Boolean get() = name == "kubernetes"

    // What start_demo is given to build from this checkout.
    val built: String get() = if (isKubernetes) "start_demo:local" else "start_demo:"
  }

  // What cmd_up printed and chose, and how it ended.
  private class Outcome(
    val exitCode: Int,
    val output: String,
  )

  private val demos by lazy {
    File(DEMOS_DIR)
      .listFiles { file -> File(file, "demo.sh").isFile }
      .orEmpty()
      .sortedBy { it.name }
      .map { Demo(it) }
  }

  init {
    "every demo should name the newest release that lacks what it shows" {
      demos.shouldNotBeEmpty()
      val siteVersion = siteVersion()
      demos.forEach { demo ->
        withClue(demo.name) {
          val value = NEEDS_RELEASE_AFTER.find(demo.text)?.groupValues?.get(1)
          (value != null && atMost(value, siteVersion)).shouldBeTrue()
        }
      }
    }

    "make up should build from the checkout while the release the docs give predates the demo" {
      demos.forEach { demo ->
        withClue(demo.name) {
          val outcome = up(demo, release = OLD, local = false, hub = "published")
          outcome.exitCode shouldBe 0
          outcome.output shouldContain "The latest release, $OLD, predates this demo"
          outcome.output.lines() shouldContain demo.built
        }
      }
    }

    "make up should build from the checkout while Docker Hub has no image of the release" {
      demos.forEach { demo ->
        withClue(demo.name) {
          val outcome = up(demo, release = NEW, local = false, hub = "missing")
          outcome.exitCode shouldBe 0
          outcome.output shouldContain "Release $NEW, which the docs give, isn't"
          outcome.output shouldNotContain "predates"
          outcome.output.lines() shouldContain demo.built
        }
      }
    }

    "make up should run the release once Docker Hub has it" {
      demos.forEach { demo ->
        withClue(demo.name) {
          val outcome = up(demo, release = NEW, local = false, hub = "published")
          outcome.exitCode shouldBe 0
          if (demo.isKubernetes) {
            outcome.output.lines() shouldContain "start_demo:"
            outcome.output.lines() shouldContain "values:--set-string image.tag=$NEW"
          } else {
            outcome.output.lines() shouldContain "start_demo:$NEW"
          }
        }
      }
    }

    // A release's images don't change, so a demo started again without a network connection runs the ones it has.
    "make up should run a release whose images Docker has, without asking Docker Hub" {
      demos.filterNot { it.isKubernetes }.forEach { demo ->
        withClue(demo.name) {
          up(demo, release = NEW, local = true, hub = "unreachable").output.lines() shouldContain "start_demo:$NEW"
        }
      }
    }

    // Only Docker Hub's answer that it has no such image means the release isn't out; for anything else the pull says
    // what went wrong, or, in the Kubernetes demo, cmd_up does.
    "make up should leave a Docker Hub it can't reach to the pull" {
      demos.forEach { demo ->
        withClue(demo.name) {
          val outcome = up(demo, release = NEW, local = false, hub = "unreachable")
          if (demo.isKubernetes) {
            outcome.exitCode shouldBe DIED
            outcome.output shouldContain "check the network"
          } else {
            outcome.exitCode shouldBe 0
            outcome.output.lines() shouldContain "start_demo:$NEW"
          }
        }
      }
    }
  }

  // Sources the demo's script, which defines its functions and runs its help, then stubs what cmd_up reaches outside
  // the script and runs it. docker answers image inspect from local, and manifest inspect from hub: published, missing
  // (Docker Hub's answer for a tag it doesn't have), or unreachable.
  private fun up(
    demo: Demo,
    release: String,
    local: Boolean,
    hub: String,
  ): Outcome {
    val process =
      ProcessBuilder("bash", "-c", HARNESS)
        .redirectErrorStream(true)
        .apply {
          environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS"))
          environment()["DEMO_SH"] = demo.script.path
          environment()["GATE_RELEASE"] = release
          environment()["GATE_LOCAL"] = if (local) "1" else ""
          environment()["GATE_HUB"] = hub
          environment()["GATE_NEEDS"] = NEEDS
        }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return Outcome(process.exitValue(), output)
  }

  private fun siteVersion(): String {
    val process =
      ProcessBuilder("make", "-s", "--no-print-directory", "site-version")
        .apply { environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS")) }
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor(TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    process.exitValue() shouldBe 0
    return output
  }

  // Whether version a (x.y.z) is at most b: NEEDS_RELEASE_AFTER names a release that's out, which the docs give, or one
  // before it.
  private fun atMost(
    a: String,
    b: String,
  ): Boolean {
    val have = a.split('.').map { it.toInt() }
    val want = b.split('.').map { it.toInt() }
    return have.zip(want).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x < y } ?: true
  }

  companion object {
    private const val DEMOS_DIR = "demos"
    private const val TIMEOUT_SECS = 60L
    private const val DIED = 3

    // The stubs' NEEDS_RELEASE_AFTER, a release the docs give that's no later (OLD), and one that is (NEW).
    private const val NEEDS = "1.0.0"
    private const val OLD = "1.0.0"
    private const val NEW = "1.1.0"
    private val NEEDS_RELEASE_AFTER = Regex("""(?m)^NEEDS_RELEASE_AFTER=([0-9]+\.[0-9]+\.[0-9]+)$""")

    private val HARNESS =
      """
      set -euo pipefail
      docker() {
        case "${'$'}1 ${'$'}{2:-}" in
        "image inspect") [[ -n "${'$'}GATE_LOCAL" ]] ;;
        "manifest inspect")
          case "${'$'}GATE_HUB" in
          published) return 0 ;;
          missing) echo "no such manifest: docker.io/${'$'}3" >&2; return 1 ;;
          *) echo "failed to configure transport: dial tcp: lookup registry-1.docker.io: no such host" >&2; return 1 ;;
          esac
          ;;
        *) return 0 ;;
        esac
      }
      source "${'$'}DEMO_SH" help >/dev/null
      NEEDS_RELEASE_AFTER="${'$'}GATE_NEEDS"
      latest_release() { echo "${'$'}GATE_RELEASE"; }
      check_tools() { :; }
      check_up() { :; }
      wrap() { shift 2; echo "${'$'}*"; }
      bold() { echo "${'$'}*"; }
      start_demo() { echo "start_demo:${'$'}*"; }
      die() { echo "die: ${'$'}*"; exit $DIED; }
      cmd_up
      echo "values:${'$'}{PROXY_IMAGE_VALUES[*]:-}"
      """.trimIndent()
  }
}
