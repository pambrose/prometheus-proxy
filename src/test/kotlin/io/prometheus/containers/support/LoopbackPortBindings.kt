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

import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.LOOPBACK_HOST
import org.testcontainers.DockerClientFactory
import org.testcontainers.core.CreateContainerCmdModifier
import java.net.InetAddress

/**
 * Publishes every container port on `127.0.0.1` instead of Docker's default `0.0.0.0` wildcard.
 *
 * ### Why this exists
 *
 * A wildcard bind does not conflict with a loopback-specific listener already holding the same port —
 * they are different addresses, so both binds succeed and Docker reports nothing wrong. Testcontainers
 * then reaches the container through `localhost`, which resolves to `127.0.0.1`, and BSD delivers that
 * connection to the **more specific** listener. A foreign process answers, the container never sees a
 * request, and the failure surfaces as a wait strategy timing out against a status the container could
 * not have produced.
 *
 * That is not hypothetical: a metrics stub once failed its own `/metrics` wait with a 60-second run of
 * HTTP 404s while its access log recorded zero requests, because an IDE held the same ephemeral port on
 * loopback. Any spec can draw a colliding port on any run, which reads as unexplained flakiness, and it
 * reaches real assertions too since they build their URLs from the same mapped port.
 *
 * Binding the interface explicitly makes the OS pick from ports genuinely free **on loopback**, so the
 * collision cannot arise. This is the container-side half of the hazard
 * [io.prometheus.common.LOOPBACK_HOST]'s own comment block documents for in-process Ktor servers.
 *
 * ### Why a ServiceLoader hook rather than a call in each factory
 *
 * Registered through `META-INF/services`, so it applies to every container in the test JVM — including
 * the ones specs build directly rather than through [ContainerTestSupport]. A per-factory call would
 * cover today's containers and silently miss the next one somebody writes.
 *
 * Published ports are only ever used by the test JVM on this host; containers reach each other over the
 * Docker network by alias. Restricting them to loopback therefore costs nothing and additionally keeps
 * test containers off the local network.
 */
class LoopbackPortBindings : CreateContainerCmdModifier {
  override fun modify(cmd: CreateContainerCmd): CreateContainerCmd {
    if (hostIsLoopback) cmd.hostConfig?.rebindToLoopback()
    return cmd
  }

  // ContainerDef.applyTo() has already put a binding on the host config for every exposed port -- an empty
  // Ports.Binding for the randomized ones -- and modifiers run after it, so rewriting what is there covers
  // every published port. Rewriting rather than rebuilding also means a spec that pinned a fixed host port
  // keeps it; only the interface is forced.
  private fun HostConfig.rebindToLoopback() {
    val existing = portBindings?.bindings?.takeIf { it.isNotEmpty() } ?: return
    val rebound = Ports()
    existing.forEach { (exposed, bindings) ->
      bindings.orEmpty().forEach { rebound.bind(exposed, Ports.Binding(LOOPBACK_HOST, it.hostPortSpec)) }
    }
    withPortBindings(rebound)
  }

  companion object {
    private val logger = logger {}

    /**
     * False for a remote Docker daemon, where a loopback-bound port would be unreachable from this JVM.
     * Resolved once; any failure leaves the default wildcard binding untouched.
     */
    private val hostIsLoopback: Boolean by lazy {
      runCatching {
        val host = DockerClientFactory.instance().dockerHostIpAddress()
        InetAddress.getByName(host).isLoopbackAddress
          .also { logger.info { "Docker host $host is loopback: $it" } }
      }.getOrElse { e ->
        logger.warn(e) { "Could not resolve the Docker host; leaving published ports on the wildcard address" }
        false
      }
    }
  }
}
