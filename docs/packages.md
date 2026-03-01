# Module Prometheus Proxy

A Prometheus Proxy system that enables Prometheus to scrape metrics from endpoints behind firewalls.
An **Agent** runs inside the firewall alongside monitored services, while a **Proxy** runs outside
alongside Prometheus. The two communicate over a gRPC stream, allowing Prometheus to reach
otherwise-inaccessible targets without opening inbound firewall ports.

## Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

## Configuration

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file.
See [EnvVars][io.prometheus.common.EnvVars] for environment variable mappings and
[BaseOptions][io.prometheus.common.BaseOptions] for CLI argument handling.

# Package io.prometheus

Top-level entry points for the system.

[Agent][io.prometheus.Agent] is the in-firewall component that connects outbound to the proxy,
registers metrics endpoints, and responds to scrape requests by fetching from local HTTP targets.
It extends `GenericService` and supports synchronous startup via `startSyncAgent()` or
asynchronous embedded usage via `startAsyncAgent()`, which returns an
[EmbeddedAgentInfo][io.prometheus.agent.EmbeddedAgentInfo].

[Proxy][io.prometheus.Proxy] is the outside-firewall component that receives Prometheus scrape
requests over HTTP, routes them to connected agents via gRPC streaming, and returns the collected
metrics. It manages the full lifecycle of agent connections, path registrations, and scrape
request/response dispatch.

Both classes support programmatic configuration, embedded usage inside other JVM applications,
and HOCON-based config files.

# Package io.prometheus.proxy

Proxy-side services and managers that handle agent connections, HTTP scrape routing, and
scrape request lifecycle.

## HTTP Layer

- [ProxyHttpService][io.prometheus.proxy.ProxyHttpService] manages the lifecycle of the Ktor
  CIO embedded HTTP server that Prometheus scrapes.
- [ProxyHttpRoutes][io.prometheus.proxy.ProxyHttpRoutes] defines the Ktor routing tree, resolving
  paths to agent contexts, dispatching scrape requests, and merging consolidated multi-agent
  responses (including OpenMetrics `# EOF` deduplication).
- [ProxyHttpConfig][io.prometheus.proxy.ProxyHttpConfig] configures the Ktor application with
  `DefaultHeaders`, `CallLogging`, `Compression`, and `StatusPages` plugins.

## gRPC Layer

- [ProxyGrpcService][io.prometheus.proxy.ProxyGrpcService] is the lifecycle wrapper around the
  gRPC server that agents connect to. Configures TLS, interceptors, transport filters, keepalive
  settings, and optional proto reflection.
- [ProxyServiceImpl][io.prometheus.proxy.ProxyServiceImpl] implements all gRPC RPCs defined in
  `proxy_service.proto`, including agent registration, path management, heartbeats, and the
  scrape request/response streaming protocol.
- [ProxyServerInterceptor][io.prometheus.proxy.ProxyServerInterceptor] injects the agent ID into
  gRPC response headers.
- [ProxyServerTransportFilter][io.prometheus.proxy.ProxyServerTransportFilter] creates an
  [AgentContext][io.prometheus.proxy.AgentContext] on transport connect and triggers cleanup
  on disconnect.

## State Management

- [AgentContext][io.prometheus.proxy.AgentContext] is the proxy-side representation of one
  connected agent. Holds identity fields, validity state, activity timestamps, and a queue of
  pending scrape requests.
- [AgentContextManager][io.prometheus.proxy.AgentContextManager] is a dual-map registry of
  agent ID to [AgentContext][io.prometheus.proxy.AgentContext] and scrape ID to
  [ChunkedContext][io.prometheus.proxy.ChunkedContext]. Provides CRUD operations, stale-agent
  detection, and bulk invalidation.
- [ProxyPathManager][io.prometheus.proxy.ProxyPathManager] maps path strings to agent contexts,
  supporting both exclusive (single agent) and consolidated (multiple agent) registration.
- [ScrapeRequestManager][io.prometheus.proxy.ScrapeRequestManager] tracks in-flight scrape
  requests by scrape ID, routes incoming results to waiting callers, and handles failure on
  agent disconnect or proxy shutdown.
- [ScrapeRequestWrapper][io.prometheus.proxy.ScrapeRequestWrapper] wraps a single `ScrapeRequest`
  protobuf with lifecycle state: a completion channel, latency timer, and the eventual results.
- [ChunkedContext][io.prometheus.proxy.ChunkedContext] reassembles chunked scrape responses
  (header + N chunks + summary) into a single result with running CRC32 validation.
- [AgentContextCleanupService][io.prometheus.proxy.AgentContextCleanupService] is a background
  thread that periodically evicts agents exceeding `maxAgentInactivitySecs`.

## Configuration and Metrics

- [ProxyOptions][io.prometheus.proxy.ProxyOptions] parses CLI arguments and HOCON config for
  proxy-specific settings (HTTP port, gRPC port, service discovery, TLS handshake, keepalive).
- [ProxyMetrics][io.prometheus.proxy.ProxyMetrics] declares Prometheus metrics for scrape
  requests, agent connections, evictions, heartbeats, and latency.

# Package io.prometheus.agent

Agent-side services for gRPC communication, HTTP scraping, and path management.

## Core Services

- [AgentGrpcService][io.prometheus.agent.AgentGrpcService] manages the agent's entire gRPC
  lifecycle: channel creation, TLS setup, stub management, reconnection, and all RPC calls
  to the proxy (connect, register, heartbeat, scrape streaming).
- [AgentHttpService][io.prometheus.agent.AgentHttpService] executes HTTP scrapes against
  registered endpoints using a cached Ktor `HttpClient`, applying size limits and gzip
  compression, and returning results as [ScrapeResults][io.prometheus.common.ScrapeResults].
- [AgentPathManager][io.prometheus.agent.AgentPathManager] maintains the agent-side path
  registry. Loads path configs from HOCON, registers each with the proxy, and supports
  dynamic registration/unregistration at runtime.
- [AgentConnectionContext][io.prometheus.agent.AgentConnectionContext] holds the bidirectional
  channel pair for one agent-to-proxy connection: a bounded inbound channel for scrape request
  actions and an unbounded outbound channel for scrape results.

## HTTP Client Caching

- [HttpClientCache][io.prometheus.agent.HttpClientCache] is an LRU cache for Ktor `HttpClient`
  instances keyed by authentication credentials. Tracks per-entry in-use counts to avoid
  closing a client mid-request. A background coroutine periodically evicts expired entries.

## TLS and Security

- [SslSettings][io.prometheus.agent.SslSettings] builds `KeyStore`, `TrustManagerFactory`,
  `SSLContext`, and `X509TrustManager` from keystore files for mutual TLS configuration.
- [TrustAllX509TrustManager][io.prometheus.agent.TrustAllX509TrustManager] is a no-op trust
  manager that accepts all certificates. Intended for development use only.
- [AgentClientInterceptor][io.prometheus.agent.AgentClientInterceptor] is a gRPC client
  interceptor that reads the `agent-id` header from proxy response metadata.

## Configuration and Metrics

- [AgentOptions][io.prometheus.agent.AgentOptions] parses CLI arguments and HOCON config for
  agent-specific settings (proxy hostname, scrape timeout, chunking threshold, gzip size,
  HTTP client cache, TLS, and keepalive).
- [AgentMetrics][io.prometheus.agent.AgentMetrics] declares Prometheus metrics for scrape
  requests, scrape results (non-gzipped/gzipped/chunked), connections, and latency.
- [EmbeddedAgentInfo][io.prometheus.agent.EmbeddedAgentInfo] is the return value of
  `Agent.startAsyncAgent()`, carrying the `launchId` and `agentName` of the started agent.

# Package io.prometheus.common

Shared utilities, configuration, and data models used by both proxy and agent.

## Configuration

- [BaseOptions][io.prometheus.common.BaseOptions] is the abstract base class for
  [AgentOptions][io.prometheus.agent.AgentOptions] and
  [ProxyOptions][io.prometheus.proxy.ProxyOptions]. Handles JCommander argument parsing,
  HOCON config loading (from file or URL), and provides shared fields for TLS, keepalive,
  admin, metrics, and log-level options with CLI > env > config precedence.
- [ConfigVals][io.prometheus.common.ConfigVals] is the auto-generated type-safe HOCON
  configuration wrapper (generated via tscfg from the reference schema).
- [EnvVars][io.prometheus.common.EnvVars] is a typed enumeration of all environment variable
  names with `getEnv()` overloads for `String`, `Boolean`, `Int`, and `Long`.

## Data Models

- [ScrapeResults][io.prometheus.common.ScrapeResults] is an immutable data holder for one
  completed scrape, carrying status code, content type, content (as text or gzipped bytes),
  failure reason, and URL. Provides conversion to gRPC `ScrapeResponse` and chunked header
  messages.

## Utilities

- [Utils][io.prometheus.common.Utils] provides miscellaneous functions including URL
  sanitization, query parameter encoding, JSON parsing, log level configuration, gRPC
  status formatting, and host:port parsing.
