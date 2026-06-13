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

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.containers.support.ContainerTestSupport.NGINX_METRICS_DEST
import io.prometheus.containers.support.ContainerTestSupport.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.logConsumer
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.statusOf
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import kotlin.time.Duration.Companion.seconds

/**
 * Stretch: the agent scrapes an HTTPS upstream — an nginx-tls stub presenting the project's `testing/certs`
 * `server1.pem`, which is signed by an untrusted CA (`testca`). The stub is aliased `waterzooi.test.google.be`
 * so it matches a SAN entry in the cert (Ktor verifies the hostname regardless of trust-all). With
 * `TRUST_ALL_X509_CERTIFICATES=true` the agent accepts the untrusted chain and the scrape succeeds; without it
 * the agent rejects the chain and the proxy returns 503. Regenerate the fixtures with `make regen-certs`.
 */
class ContainersHttpsTargetTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Agent scrapes a self-signed HTTPS target (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      fun tlsStub(network: Network): GenericContainer<*> =
        GenericContainer<Nothing>("nginx:alpine").apply {
          withNetwork(network)
          withNetworkAliases("waterzooi.test.google.be")
          withClasspathResourceMapping("containers/nginx-tls.conf", "/etc/nginx/conf.d/https.conf", BindMode.READ_ONLY)
          withClasspathResourceMapping("containers/metrics.txt", NGINX_METRICS_DEST, BindMode.READ_ONLY)
          withCopyFileToContainer(MountableFile.forHostPath("testing/certs/server1.pem"), "/etc/nginx/certs/server.pem")
          withCopyFileToContainer(MountableFile.forHostPath("testing/certs/server1.key"), "/etc/nginx/certs/server.key")
          withExposedPorts(443)
          withLogConsumer(logConsumer("metrics-stub-tls"))
          waitingFor(Wait.forListeningPort())
        }

      "agent scrapes a self-signed HTTPS target when trust-all is enabled" {
        val network = Network.newNetwork()
        val metricsStub = tlsStub(network)
        val proxy = proxyContainer(network)
        val agent =
          agentContainer(
            network,
            configResource = "containers/agent-https-target.conf",
            env = mapOf("TRUST_ALL_X509_CERTIFICATES" to "true"),
            waitLogRegex = ".*Registered https://waterzooi.test.google.be/metrics as /https_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()
          eventually(30.seconds) {
            httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/https_metrics") shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }

      "agent refuses the self-signed HTTPS target without trust-all" {
        val network = Network.newNetwork()
        val metricsStub = tlsStub(network)
        val proxy = proxyContainer(network)
        val agent =
          agentContainer(
            network,
            configResource = "containers/agent-https-target.conf",
            waitLogRegex = ".*Registered https://waterzooi.test.google.be/metrics as /https_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()
          // Path is registered, but the agent cannot validate the cert, so the scrape fails with 503.
          eventually(30.seconds) {
            httpClient.statusOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/https_metrics") shouldBe 503
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }
}
