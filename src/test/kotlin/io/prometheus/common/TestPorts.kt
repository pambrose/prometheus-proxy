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

package io.prometheus.common

/**
 * Canonical port numbers shared across the test suite (unit, harness, and Testcontainers specs).
 *
 * The first group mirrors the proxy/agent config defaults and the fixed ports used by the container suite, for tests
 * that assert those defaults or run inside a container. A spec that binds a port on this machine uses a dedicated one
 * from the groups below instead: a proxy, agent or Prometheus already running here holds the defaults. They live in a
 * neutral test-support object so any test package can reference them without depending on the Testcontainers support
 * harness.
 */
object TestPorts {
  const val PROXY_HTTP_PORT = 8080
  const val PROXY_METRICS_PORT = 8082
  const val PROXY_ADMIN_PORT = 8092
  const val PROXY_DASHBOARD_PORT = 8094
  const val PROXY_AGENT_PORT = 50051

  const val AGENT_METRICS_PORT = 8083
  const val AGENT_ADMIN_PORT = 8093

  const val PROMETHEUS_PORT = 9090
  const val NGINX_PORT = 80

  // Dedicated ports for the harness specs. Each spec owns its own, so no two specs -- or two cases within one --
  // bind the same port; TestPortsTest fails the build when two of these collide.

  // Admin endpoints on non-default ports (misc/Admin*PathTest)
  const val ADMIN_EMPTY_PATH_ADMIN_PORT = 8098
  const val ADMIN_CUSTOM_PATH_ADMIN_PORT = 8099

  // The standard harness suite (HarnessSetup subclasses)
  const val HARNESS_PROXY_PORT = 9505

  // What TestUtils.startProxy and startAgent bind unless a spec passes its own port: the proxy's gRPC listener (which
  // the agent dials), and each side's admin and metrics servers when a spec enables them.
  const val HARNESS_PROXY_AGENT_PORT = 9506
  const val HARNESS_PROXY_ADMIN_PORT = 9507
  const val HARNESS_PROXY_METRICS_PORT = 9508
  const val HARNESS_AGENT_ADMIN_PORT = 9509
  const val HARNESS_AGENT_METRICS_PORT = 9510

  // HarnessHelpersTest
  const val HARNESS_HELPERS_HTTP_PORT = 9511

  // TlsMutualAuthRejectionTest
  const val TLS_REJECTION_HTTP_PORT = 9512
  const val TLS_REJECTION_AGENT_PORT = 50460

  // TlsNoMutualAuthTest and TlsWithMutualAuthTest: the proxy's gRPC port each runs its TLS channel on
  const val TLS_NO_MUTUAL_AUTH_AGENT_PORT = 50440
  const val TLS_MUTUAL_AUTH_AGENT_PORT = 50441

  // AgentTokenAuthTest
  const val TOKEN_AUTH_HTTP_PORT_OK = 9513
  const val TOKEN_AUTH_AGENT_PORT_OK = 50461
  const val TOKEN_AUTH_HTTP_PORT_BAD = 9514
  const val TOKEN_AUTH_AGENT_PORT_BAD = 50462

  // AgentPathAuthTest
  const val PATH_AUTH_HTTP_PORT = 9515
  const val PATH_AUTH_AGENT_PORT = 50463

  // AgentDiscoveryTest
  const val DISCOVERY_HTTP_PORT = 9516
  const val DISCOVERY_AGENT_PORT = 50464

  // AgentRejectedPathRetryTest
  const val REJECTED_PATH_RETRY_HTTP_PORT = 9594
  const val REJECTED_PATH_RETRY_AGENT_PORT = 50465

  // AgentProxyFailoverTest
  const val FAILOVER_PROXY_A_HTTP_PORT = 9530
  const val FAILOVER_PROXY_A_GRPC_PORT = 9531
  const val FAILOVER_PROXY_B_HTTP_PORT = 9532
  const val FAILOVER_PROXY_B_GRPC_PORT = 9533
  const val FAILOVER_STUB_PORT = 9534

  // EmbeddedAgentApiTest
  const val EMBEDDED_API_HTTP_PORT = 9560
  const val EMBEDDED_API_GRPC_PORT = 9561

  // InProcessTransportFilterDisabledTest
  const val TF_DISABLED_HTTP_PORT = 9562

  // InProcessStaleAgentCleanupTest
  const val STALE_CLEANUP_OFF_HTTP_PORT = 9563
  const val STALE_CLEANUP_FORCED_HTTP_PORT = 9564

  // InProcessHealthCheckTest
  const val HEALTH_CHECK_HTTP_PORT = 9565
  const val HEALTH_CHECK_PROXY_ADMIN_PORT = 9566
  const val HEALTH_CHECK_AGENT_ADMIN_PORT = 9567
  const val HEALTH_CHECK_DASHBOARD_PORT = 9568

  // InProcessHeartbeatEvictionTest
  const val HEARTBEAT_EVICTION_HTTP_PORT = 9569

  // InProcessClientCancelledScrapeTest
  const val CLIENT_CANCELLED_HTTP_PORT = 9590
  const val CLIENT_CANCELLED_DASHBOARD_PORT = 9591

  // InProcessScrapeTimeoutHeaderTest
  const val SCRAPE_TIMEOUT_HEADER_HTTP_PORT = 9592

  // ProxyWebDashboardTest
  const val DASHBOARD_UI_PROXY_HTTP_PORT = 9540
  const val DASHBOARD_UI_PROXY_GRPC_PORT = 9541
  const val DASHBOARD_UI_DASHBOARD_PORT = 9542
  const val DASHBOARD_UI_OFF_HTTP_PORT = 9543
  const val DASHBOARD_UI_OFF_GRPC_PORT = 9544
  const val DASHBOARD_UI_OFF_DASHBOARD_PORT = 9545
  const val DASHBOARD_UI_FAILOVER_HTTP_PORT = 9546
  const val DASHBOARD_UI_FAILOVER_GRPC_PORT = 9547
  const val DASHBOARD_UI_FAILOVER_DASHBOARD_PORT = 9548
  const val DASHBOARD_UI_DEAD_PORT = 9549
  const val DASHBOARD_UI_NAV_HTTP_PORT = 9550
  const val DASHBOARD_UI_NAV_GRPC_PORT = 9551
  const val DASHBOARD_UI_NAV_DASHBOARD_PORT = 9552
  const val DASHBOARD_UI_ROOT_HTTP_PORT = 9553
  const val DASHBOARD_UI_ROOT_GRPC_PORT = 9554
  const val DASHBOARD_UI_ROOT_DASHBOARD_PORT = 9558
  const val DASHBOARD_UI_PATHS_HTTP_PORT = 9555
  const val DASHBOARD_UI_PATHS_GRPC_PORT = 9556
  const val DASHBOARD_UI_PATHS_DASHBOARD_PORT = 9557
  const val DASHBOARD_UI_SELECT_HTTP_PORT = 9570
  const val DASHBOARD_UI_SELECT_GRPC_PORT = 9571
  const val DASHBOARD_UI_SELECT_DASHBOARD_PORT = 9572
  const val DASHBOARD_UI_MOUNT_HTTP_PORT = 9573
  const val DASHBOARD_UI_MOUNT_GRPC_PORT = 9574
  const val DASHBOARD_UI_MOUNT_DASHBOARD_PORT = 9575
  const val DASHBOARD_UI_ORIGIN_HTTP_PORT = 9576
  const val DASHBOARD_UI_ORIGIN_GRPC_PORT = 9577
  const val DASHBOARD_UI_ORIGIN_DASHBOARD_PORT = 9578
  const val DASHBOARD_UI_CAP_HTTP_PORT = 9579
  const val DASHBOARD_UI_CAP_GRPC_PORT = 9580
  const val DASHBOARD_UI_CAP_DASHBOARD_PORT = 9581
  const val DASHBOARD_UI_FRAME_HTTP_PORT = 9582
  const val DASHBOARD_UI_FRAME_GRPC_PORT = 9583
  const val DASHBOARD_UI_FRAME_DASHBOARD_PORT = 9584
  const val DASHBOARD_UI_CACHE_HTTP_PORT = 9585
  const val DASHBOARD_UI_CACHE_GRPC_PORT = 9586
  const val DASHBOARD_UI_CACHE_DASHBOARD_PORT = 9587
  const val DASHBOARD_UI_HOST_HTTP_PORT = 9588
  const val DASHBOARD_UI_HOST_GRPC_PORT = 9589
  const val DASHBOARD_UI_HOST_DASHBOARD_PORT = 9593

  // First port of the block each standard harness suite registers scrape targets on (startPort + i)
  const val HARNESS_DEFAULT_START_PORT = 9600
  const val IN_PROCESS_NO_ADMIN_START_PORT = 10100
  const val TLS_NO_MUTUAL_AUTH_START_PORT = 10200
  const val NETTY_WITH_ADMIN_START_PORT = 10300
  const val IN_PROCESS_WITH_ADMIN_START_PORT = 10700
  const val TLS_MUTUAL_AUTH_START_PORT = 10800
  const val NETTY_NO_ADMIN_START_PORT = 10900
}
