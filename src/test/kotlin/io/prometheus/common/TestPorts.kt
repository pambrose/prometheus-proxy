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
 * that assert those defaults or run inside a container; TestPortsTest's ephemeral-range and demo-port checks skip
 * them, by name (NEVER_BOUND), though they must still be distinct. A spec that binds a port on this machine uses a
 * dedicated one from the groups below instead: a proxy, agent or Prometheus already running here holds the defaults.
 * They live in a neutral test-support object so any test package can reference them without depending on the
 * Testcontainers support harness.
 */
object TestPorts {
  const val PROXY_HTTP_PORT = 8080
  const val PROXY_METRICS_PORT = 8082
  const val PROXY_ADMIN_PORT = 8092
  const val PROXY_DASHBOARD_PORT = 8094
  const val PROXY_AGENT_PORT = 50051

  const val AGENT_METRICS_PORT = 8083
  const val AGENT_ADMIN_PORT = 8093
  const val AGENT_DASHBOARD_PORT = 8095

  const val PROMETHEUS_PORT = 9090
  const val NGINX_PORT = 80

  // Dedicated ports for the harness specs. Each spec owns its own, so no two specs -- or two cases within one --
  // bind the same port; TestPortsTest fails the build when two of these collide, when one is 32768 or above, in
  // the range the OS hands out to outgoing connections, or when one is a host port a demo publishes (demos/).

  // Admin endpoints on non-default ports (harness/Admin*PathTest)
  const val ADMIN_EMPTY_PATH_ADMIN_PORT = 9755
  const val ADMIN_CUSTOM_PATH_ADMIN_PORT = 9756

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
  const val HARNESS_HELPERS_HTTP_PORT = 9757

  // HarnessHelpersTest: the port awaitPortFree finds free. A port taken from ServerSocket(0) and closed is in the
  // ephemeral range, where another process can bind it before the check does.
  const val HARNESS_HELPERS_FREE_PORT = 9758

  // AgentHttpServiceTest: a scrape target nothing listens on, so its scrapes are refused and counted as failures. A
  // port taken from ServerSocket(0) and closed could be bound by another process first. A socket holding the port
  // without listening can't stand in: macOS drops a connection to it, so the scrape times out instead.
  const val AGENT_HTTP_REFUSED_PORT = 9759

  // The scrape target HarnessTests.timeoutTest serves, which every standard harness suite runs
  const val HARNESS_TIMEOUT_TARGET_PORT = 9528

  // The scrape target HarnessTests.chunkedScrapeTest serves, which every standard harness suite and
  // InProcessReconnectTest run (November #41)
  const val HARNESS_CHUNKED_TARGET_PORT = 11300

  // AgentGrpcStreamRetentionTest: the Netty proxy stand-in its control case streams to
  const val RETENTION_NETTY_PORT = 11301

  // InProcessIdleShutdownTest, InProcessHeartbeatDisabledTest, and AgentMetricFilterTest
  const val IDLE_SHUTDOWN_HTTP_PORT = 9525
  const val HEARTBEAT_DISABLED_HTTP_PORT = 9526
  const val METRIC_FILTER_HTTP_PORT = 9527

  // TlsMutualAuthRejectionTest
  const val TLS_REJECTION_HTTP_PORT = 9512
  const val TLS_REJECTION_AGENT_PORT = 9517

  // TlsNoMutualAuthTest and TlsWithMutualAuthTest: the proxy's gRPC port each runs its TLS channel on
  const val TLS_NO_MUTUAL_AUTH_AGENT_PORT = 9518
  const val TLS_MUTUAL_AUTH_AGENT_PORT = 9519

  // TlsPemTest: a proxy for its one-way TLS case, and another for its mutual-TLS case
  const val TLS_PEM_HTTP_PORT = 9536
  const val TLS_PEM_AGENT_PORT = 9537
  const val TLS_PEM_MUTUAL_HTTP_PORT = 9538
  const val TLS_PEM_MUTUAL_AGENT_PORT = 9539

  // AgentTokenAuthTest
  const val TOKEN_AUTH_HTTP_PORT_OK = 9513
  const val TOKEN_AUTH_AGENT_PORT_OK = 9520
  const val TOKEN_AUTH_HTTP_PORT_BAD = 9514
  const val TOKEN_AUTH_AGENT_PORT_BAD = 9521

  // AgentPathAuthTest
  const val PATH_AUTH_HTTP_PORT = 9515
  const val PATH_AUTH_AGENT_PORT = 9522

  // AgentDiscoveryTest
  const val DISCOVERY_HTTP_PORT = 9516
  const val DISCOVERY_AGENT_PORT = 9523

  // AgentRejectedPathRetryTest
  const val REJECTED_PATH_RETRY_HTTP_PORT = 9594
  const val REJECTED_PATH_RETRY_AGENT_PORT = 9524

  // EmbeddedAgentMetricsTest
  const val EMBEDDED_METRICS_HTTP_PORT = 9595
  const val EMBEDDED_METRICS_GRPC_PORT = 9596
  const val EMBEDDED_METRICS_AGENT_1_PORT = 9597
  const val EMBEDDED_METRICS_AGENT_2_PORT = 9598

  // AgentPathTakeoverTest
  const val PATH_TAKEOVER_HTTP_PORT = 9754

  // AgentProxyFailoverTest
  const val FAILOVER_PROXY_A_HTTP_PORT = 9530
  const val FAILOVER_PROXY_A_GRPC_PORT = 9531
  const val FAILOVER_PROXY_B_HTTP_PORT = 9532
  const val FAILOVER_PROXY_B_GRPC_PORT = 9533
  const val FAILOVER_STUB_PORT = 9534

  // AgentPathManagerLincheckTest: gRPC runs in-process, so only the proxy's HTTP port binds
  const val PATH_MANAGER_LINCHECK_HTTP_PORT = 9535

  // EmbeddedAgentApiTest
  const val EMBEDDED_API_HTTP_PORT = 9560
  const val EMBEDDED_API_GRPC_PORT = 9561

  // InProcessTransportFilterDisabledTest
  const val TF_DISABLED_HTTP_PORT = 9562

  // InProcessTransportFilterDisabledTest: the proxy whose maxAgentsPerIdentity refuses a second agent (November #17).
  const val TF_DISABLED_LIMIT_HTTP_PORT = 11000

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
  const val CLIENT_CANCELLED_HTTP_PORT = 9599
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

  // ProxyWebDashboardTest: the page routes serving the cached snapshot (November #18).
  const val DASHBOARD_UI_PAGE_CACHE_HTTP_PORT = 9720
  const val DASHBOARD_UI_PAGE_CACHE_GRPC_PORT = 9721
  const val DASHBOARD_UI_PAGE_CACHE_DASHBOARD_PORT = 9722
  const val DASHBOARD_UI_PAGE_FRESH_HTTP_PORT = 9723
  const val DASHBOARD_UI_PAGE_FRESH_GRPC_PORT = 9724
  const val DASHBOARD_UI_PAGE_FRESH_DASHBOARD_PORT = 9725

  // ProxyWebDashboardTest: a gone path outliving the recent-scrapes queue.
  const val DASHBOARD_UI_HISTORY_HTTP_PORT = 9726
  const val DASHBOARD_UI_HISTORY_GRPC_PORT = 9727
  const val DASHBOARD_UI_HISTORY_DASHBOARD_PORT = 9728

  // ProxyWebDashboardTest: identities and the path search.
  const val DASHBOARD_UI_IDENTITY_HTTP_PORT = 9729
  const val DASHBOARD_UI_IDENTITY_GRPC_PORT = 9730
  const val DASHBOARD_UI_IDENTITY_DASHBOARD_PORT = 9731

  // ProxyWebDashboardTest: a push that fails to render.
  const val DASHBOARD_UI_RENDER_FAILURE_HTTP_PORT = 9732
  const val DASHBOARD_UI_RENDER_FAILURE_GRPC_PORT = 9733
  const val DASHBOARD_UI_RENDER_FAILURE_DASHBOARD_PORT = 9734

  // ProxyWebDashboardTest: scrape-now, against a stub target, with the proxy's and the agent's metrics on.
  const val DASHBOARD_UI_SCRAPE_HTTP_PORT = 9735
  const val DASHBOARD_UI_SCRAPE_GRPC_PORT = 9736
  const val DASHBOARD_UI_SCRAPE_DASHBOARD_PORT = 9737
  const val DASHBOARD_UI_SCRAPE_METRICS_PORT = 9738
  const val DASHBOARD_UI_SCRAPE_AGENT_METRICS_PORT = 9739

  // ProxyWebDashboardTest: an Activity page sent its table only when the feed changes or the page sends a message.
  const val DASHBOARD_UI_ACTIVITY_PUSH_HTTP_PORT = 9740
  const val DASHBOARD_UI_ACTIVITY_PUSH_GRPC_PORT = 9741
  const val DASHBOARD_UI_ACTIVITY_PUSH_DASHBOARD_PORT = 9742

  // ProxyWebDashboardLoginTest: a dashboard that requires a login.
  const val DASHBOARD_UI_LOGIN_HTTP_PORT = 9743
  const val DASHBOARD_UI_LOGIN_GRPC_PORT = 9744
  const val DASHBOARD_UI_LOGIN_DASHBOARD_PORT = 9745
  const val DASHBOARD_UI_LOGIN_METRICS_PORT = 9752

  // AgentWebDashboardTest: the agent's dashboard, with its proxy up and with it down (nothing listens on the down
  // case's gRPC port), and with a login.
  const val AGENT_DASHBOARD_UI_HTTP_PORT = 9746
  const val AGENT_DASHBOARD_UI_GRPC_PORT = 9747
  const val AGENT_DASHBOARD_UI_DASHBOARD_PORT = 9748
  const val AGENT_DASHBOARD_UI_DOWN_GRPC_PORT = 9749
  const val AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT = 9750
  const val AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT = 9751
  const val AGENT_DASHBOARD_UI_METRICS_PORT = 9753

  // AgentMetricsProxyPathTest: a standalone agent's own metrics, served through the proxy with no metrics port.
  const val METRICS_PROXY_PATH_HTTP_PORT = 9760
  const val METRICS_PROXY_PATH_GRPC_PORT = 9761

  // ProxyTest and AgentTest: what a real proxy or agent binds in the garbage-collection and failed-startup cases.
  // These came from ServerSocket(0), released and bound again later, so another connection could take one in between
  // (October #46). The port each failed-startup case holds open as taken still does.
  const val PROXY_GC_HTTP_PORT = 9700
  const val PROXY_FAILED_START_ADMIN_PORT = 9701
  const val PROXY_FAILED_START_METRICS_PORT = 9702
  const val PROXY_FAILED_SUB_SERVICE_AGENT_PORT = 9703
  const val PROXY_FAILED_SUB_SERVICE_HTTP_PORT = 9704
  const val PROXY_FAILED_SUB_SERVICE_ADMIN_PORT = 9705
  const val AGENT_FAILED_START_METRICS_PORT = 9706

  // AgentTest: the admin and metrics servers an agent whose gRPC shutdown fails must still stop (October #20).
  const val AGENT_FAILED_SHUTDOWN_ADMIN_PORT = 9707
  const val AGENT_FAILED_SHUTDOWN_METRICS_PORT = 9708

  // AgentStartupTest: a dashboard port the spec holds open, and the admin and metrics servers the agent's failed start
  // must stop, through startSync() and through the embedded entry point; and the admin server of an agent whose start
  // times out, and its dashboard's port, which the case's mock keeps unbound (November 23 review, #5 and #12).
  const val AGENT_FAILED_DASHBOARD_TAKEN_PORT = 9715
  const val AGENT_FAILED_DASHBOARD_ADMIN_PORT = 9716
  const val AGENT_FAILED_DASHBOARD_METRICS_PORT = 9717
  const val EMBEDDED_FAILED_DASHBOARD_TAKEN_PORT = 9718
  const val EMBEDDED_FAILED_DASHBOARD_ADMIN_PORT = 9719
  const val AGENT_START_TIMEOUT_ADMIN_PORT = 9709
  const val AGENT_START_TIMEOUT_DASHBOARD_PORT = 9762
  // DashboardPushTest: a DashboardServer per case, serving a site the spec stands in for, with no proxy or agent.
  const val DASHBOARD_PUSH_CHANGE_PORT = 11410
  const val DASHBOARD_PUSH_COLLECT_FAILURE_PORT = 11411
  const val DASHBOARD_PUSH_LOGOUT_PORT = 11412
  const val DASHBOARD_PUSH_STALLED_LOGOUT_PORT = 11413
  const val DASHBOARD_PUSH_STALLED_EXPIRY_PORT = 11414
  const val DASHBOARD_PUSH_OVERFLOW_PORT = 11415

  // InProcessZipkinTest: the HTTP port of each case's proxy, and a Zipkin port nothing listens on, which the reporter
  // fails to send to without holding up startup, a scrape, or shutdown.
  const val ZIPKIN_UNREACHABLE_PORT = 9710
  const val ZIPKIN_PROXY_HTTP_PORT = 9711
  const val ZIPKIN_AGENT_HTTP_PORT = 9712
  const val ZIPKIN_BOTH_HTTP_PORT = 9713
  const val ZIPKIN_UNSAMPLED_HTTP_PORT = 9714

  // The Spring Boot starter's PrometheusProxyAgentIntegrationTest (spring-boot-starter/, in Java, which can't import
  // this): its proxy's scrape and agent ports, bound while the root project's tests run beside it in `build`. Listed
  // here so TestPortsTest keeps every root spec off them; the starter's test repeats the numbers.
  const val STARTER_PROXY_HTTP_PORT = 11400
  const val STARTER_PROXY_AGENT_PORT = 11401

  // The Ktor plugin's PrometheusProxyAgentIntegrationTest (ktor-plugin/, a subproject whose tests can't import the
  // root's test sources): its proxy's scrape and agent ports, listed here for the same reason; its test repeats the
  // numbers.
  const val KTOR_PLUGIN_PROXY_HTTP_PORT = 11402
  const val KTOR_PLUGIN_PROXY_AGENT_PORT = 11403

  // First port of the block each standard harness suite registers scrape targets on (startPort + i)
  const val HARNESS_DEFAULT_START_PORT = 9600
  const val IN_PROCESS_NO_ADMIN_START_PORT = 10100
  const val TLS_NO_MUTUAL_AUTH_START_PORT = 10200
  const val NETTY_WITH_ADMIN_START_PORT = 10300
  const val IN_PROCESS_WITH_ADMIN_START_PORT = 10700
  const val TLS_MUTUAL_AUTH_START_PORT = 10800
  const val NETTY_NO_ADMIN_START_PORT = 10900
}
