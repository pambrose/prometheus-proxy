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

package io.prometheus.harness

import io.kotest.core.spec.style.StringSpec
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.CLIENT_CERT
import io.prometheus.common.TestCerts.CLIENT_KEY
import io.prometheus.common.TestCerts.SERVER_AUTHORITY
import io.prometheus.common.TestCerts.SERVER_CERT
import io.prometheus.common.TestCerts.SERVER_KEY
import io.prometheus.common.TestCerts.base64
import io.prometheus.common.TestCerts.read
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.withEmbeddedAgentScraped

// TLS with every certificate and key given as PEM text, none as a file: the form a platform that passes secrets as
// environment variables needs, which lets the published distroless proxy image run TLS with no wrapper. Over Netty, so
// each scrape crosses a real TLS channel. The proxy takes its material as base64 PEM, as a one-line variable field
// holds it; the agent, embedded, takes its own as PEM text in a config built in memory, so neither side reads a file.
class TlsPemTest : StringSpec() {
  init {
    "scrapes flow over TLS when the proxy's certificate and key, and the agent's trust, are PEM" {
      withEmbeddedAgentScraped(
        httpPort = TestPorts.TLS_PEM_HTTP_PORT,
        agentPort = TestPorts.TLS_PEM_AGENT_PORT,
        agentName = AGENT_NAME,
        path = PATH,
        targetMetrics = TARGET_METRICS,
        proxyArgs =
          [
            "-Dproxy.tls.certChainPem=${base64(SERVER_CERT)}",
            "-Dproxy.tls.privateKeyPem=${base64(SERVER_KEY)}",
          ],
        configure = { trustCertificatePem(read(CA_CERT), SERVER_AUTHORITY) },
      )
    }

    "scrapes flow over mutual TLS when both sides' certificates, keys and trust are PEM" {
      withEmbeddedAgentScraped(
        httpPort = TestPorts.TLS_PEM_MUTUAL_HTTP_PORT,
        agentPort = TestPorts.TLS_PEM_MUTUAL_AGENT_PORT,
        agentName = AGENT_NAME,
        path = PATH,
        targetMetrics = TARGET_METRICS,
        proxyArgs =
          [
            "-Dproxy.tls.certChainPem=${base64(SERVER_CERT)}",
            "-Dproxy.tls.privateKeyPem=${base64(SERVER_KEY)}",
            "-Dproxy.tls.trustCertCollectionPem=${base64(CA_CERT)}",
          ],
        configure = {
          trustCertificatePem(read(CA_CERT), SERVER_AUTHORITY)
          clientCertificatePem(read(CLIENT_CERT), read(CLIENT_KEY))
        },
      )
    }
  }

  companion object {
    private const val AGENT_NAME = "tls-pem-agent"
    private const val PATH = "tls_pem_metrics"
    private const val TARGET_METRICS = "tls_pem_target_metric 1.0"
  }
}
