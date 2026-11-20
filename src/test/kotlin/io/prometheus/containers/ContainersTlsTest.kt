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
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.CLIENT_CERT
import io.prometheus.common.TestCerts.CLIENT_KEY
import io.prometheus.common.TestCerts.SERVER_AUTHORITY
import io.prometheus.common.TestCerts.SERVER_CERT
import io.prometheus.common.TestCerts.SERVER_KEY
import io.prometheus.common.TestCerts.base64
import io.prometheus.common.TestCerts.read
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.Network
import kotlin.time.Duration.Companion.seconds

/**
 * Stretch: the agent↔proxy gRPC channel runs over TLS through the packaged JARs in containers, using the
 * project's `testing/certs` fixtures (the same ones the Netty harness specs [io.prometheus.harness.TlsNoMutualAuthTest]
 * and [io.prometheus.harness.TlsWithMutualAuthTest] use; regenerate them with `make regen-certs`). The proxy
 * presents `server1.pem`, whose SAN `*.test.google.fr` is matched via the agent's `OVERRIDE_AUTHORITY`, and the
 * agent trusts the signing CA `ca.pem`. The second case adds mutual TLS with the `client.pem` client cert. The third
 * gives the proxy's certificate and key, and the agent's trust, as PEM in environment variables, with no file mounted.
 */
class ContainersTlsTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Agent connects to the proxy over TLS (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "agent scrapes flow over a server-only TLS gRPC channel" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy =
          proxyContainer(
            network,
            env =
              mapOf(
                "CERT_CHAIN_FILE_PATH" to "/certs/server.pem",
                "PRIVATE_KEY_FILE_PATH" to "/certs/server.key",
              ),
            hostFiles =
              mapOf(
                SERVER_CERT to "/certs/server.pem",
                SERVER_KEY to "/certs/server.key",
              ),
          )
        val agent =
          agentContainer(
            network,
            env =
              mapOf(
                "TRUST_CERT_COLLECTION_FILE_PATH" to "/certs/ca.pem",
                "OVERRIDE_AUTHORITY" to SERVER_AUTHORITY,
              ),
            hostFiles = mapOf(CA_CERT to "/certs/ca.pem"),
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()
          eventually(30.seconds) {
            val body = httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics")
            body shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }

      "agent scrapes flow over a mutual TLS gRPC channel" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy =
          proxyContainer(
            network,
            env =
              mapOf(
                "CERT_CHAIN_FILE_PATH" to "/certs/server.pem",
                "PRIVATE_KEY_FILE_PATH" to "/certs/server.key",
                "TRUST_CERT_COLLECTION_FILE_PATH" to "/certs/ca.pem",
              ),
            hostFiles =
              mapOf(
                SERVER_CERT to "/certs/server.pem",
                SERVER_KEY to "/certs/server.key",
                CA_CERT to "/certs/ca.pem",
              ),
          )
        val agent =
          agentContainer(
            network,
            env =
              mapOf(
                "CERT_CHAIN_FILE_PATH" to "/certs/client.pem",
                "PRIVATE_KEY_FILE_PATH" to "/certs/client.key",
                "TRUST_CERT_COLLECTION_FILE_PATH" to "/certs/ca.pem",
                "OVERRIDE_AUTHORITY" to SERVER_AUTHORITY,
              ),
            hostFiles =
              mapOf(
                CLIENT_CERT to "/certs/client.pem",
                CLIENT_KEY to "/certs/client.key",
                CA_CERT to "/certs/ca.pem",
              ),
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()
          eventually(30.seconds) {
            val body = httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics")
            body shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }

      // The published images are distroless, with no shell to write a variable out to a file, so a platform that
      // passes secrets as environment variables gives them as PEM: the certificate as base64, as a one-line variable
      // field holds it, and the key and the trust as PEM text. No file is mounted.
      "agent scrapes flow over TLS with the certificates and key given as environment variables" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy =
          proxyContainer(
            network,
            env =
              mapOf(
                "CERT_CHAIN_PEM" to base64(SERVER_CERT),
                "PRIVATE_KEY_PEM" to read(SERVER_KEY),
              ),
          )
        val agent =
          agentContainer(
            network,
            env =
              mapOf(
                "TRUST_CERT_COLLECTION_PEM" to read(CA_CERT),
                "OVERRIDE_AUTHORITY" to SERVER_AUTHORITY,
              ),
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()
          eventually(30.seconds) {
            val body = httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics")
            body shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }
}
