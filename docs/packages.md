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

## Public API

These are the entry points intended for embedders and integrators:

- [Agent][io.prometheus.Agent] — the in-firewall component. Construct directly or use
  [Agent.startSyncAgent][io.prometheus.Agent.Companion.startSyncAgent] /
  [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent].
- [Proxy][io.prometheus.Proxy] — the outside-firewall component.
- [AgentOptions][io.prometheus.agent.AgentOptions] /
  [ProxyOptions][io.prometheus.proxy.ProxyOptions] —
  CLI argument parsing and config loading; subclasses of
  [BaseOptions][io.prometheus.common.BaseOptions].
- [EmbeddedAgentConfig][io.prometheus.agent.EmbeddedAgentConfig] — an embedded Agent's settings,
  built in code with a typed method for each: [embeddedAgent][io.prometheus.agent.embeddedAgent]
  from Kotlin, [EmbeddedAgentConfig.builder][io.prometheus.agent.EmbeddedAgentConfig.Companion.builder]
  from Java.
- [MetricsSource][io.prometheus.agent.MetricsSource] — an embedded Agent's host's metrics, read
  in-process for a path, in place of a URL, with
  [EmbeddedAgentConfig.Builder.source][io.prometheus.agent.EmbeddedAgentConfig.Builder.source], which
  takes a `PrometheusRegistry` too, as
  [MetricsSource.of][io.prometheus.agent.MetricsSource.Companion.of] does.
- [EmbeddedAgentInfo][io.prometheus.agent.EmbeddedAgentInfo] — lifecycle handle returned by
  [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent] when running an Agent
  inside a host JVM.
- [EnvVars][io.prometheus.common.EnvVars] — typed enum of the environment variables the Proxy and
  Agent read themselves, not the `PROMETHEUS_*` ones
  [fromEnvironment][io.prometheus.agent.EmbeddedAgentConfig.Builder.fromEnvironment] reads for an
  embedding app.
- [ConfigLoadException][io.prometheus.common.ConfigLoadException] — what an embedded Agent throws,
  in place of ending the process, for a config it can't load or a setting it can't use.

Everything else (gRPC service implementations, agent registries, HTTP clients, request
managers, transport filters, interceptors) is `internal` to the module: it is documented in
source but not part of the supported API surface, and is intentionally omitted from these
generated docs.

## Configuration

Uses Typesafe Config (HOCON). Resolution precedence is **CLI args → env vars → config file →
built-in defaults**. See [BaseOptions][io.prometheus.common.BaseOptions] for the shared CLI
flag set and [EnvVars][io.prometheus.common.EnvVars] for the env-var mappings.

# Package io.prometheus

Top-level entry points for the system.

[Agent][io.prometheus.Agent] is the in-firewall component that connects outbound to the proxy,
registers metrics endpoints, and responds to scrape requests by fetching from local HTTP targets.
It extends `GenericService` and supports synchronous startup via
[startSyncAgent][io.prometheus.Agent.Companion.startSyncAgent] or asynchronous embedded usage
via [startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent], which returns an
[EmbeddedAgentInfo][io.prometheus.agent.EmbeddedAgentInfo].

[Proxy][io.prometheus.Proxy] is the outside-firewall component that receives Prometheus scrape
requests over HTTP, routes them to connected agents via gRPC streaming, and returns the collected
metrics. It manages the full lifecycle of agent connections, path registrations, and scrape
request/response dispatch.

Both classes support programmatic configuration, embedded usage inside other JVM applications,
and HOCON-based config files.

# Package io.prometheus.proxy

Proxy-side configuration. The Proxy's HTTP routing, gRPC service implementation, agent registry,
scrape-request manager, chunked-response reassembly, transport filter, and stale-agent cleanup
are all `internal` and not surfaced here.

- [ProxyOptions][io.prometheus.proxy.ProxyOptions] parses CLI arguments and HOCON config for
  proxy-specific settings (HTTP port, gRPC agent port, service-discovery endpoint, gRPC handshake
  and connection-management timeouts).

# Package io.prometheus.agent

Agent-side configuration and embedded-lifecycle handle. The Agent's gRPC client service, HTTP
scrape executor, path manager, HTTP-client cache, TLS helpers, and gRPC interceptors are all
`internal` and not surfaced here.

- [AgentOptions][io.prometheus.agent.AgentOptions] parses CLI arguments and HOCON config for
  agent-specific settings (proxy hostname, scrape timeout, chunking threshold, gzip threshold,
  HTTP-client cache sizing, TLS, and keepalive).
- [EmbeddedAgentConfig][io.prometheus.agent.EmbeddedAgentConfig] builds an embedded agent's
  settings in code (name, proxy, token, PEM certificates, or those from the app's environment with
  [fromEnvironment][io.prometheus.agent.EmbeddedAgentConfig.Builder.fromEnvironment], the paths it
  scrapes, with Basic Auth credentials percent-encoded into their URLs, the registries it serves
  in-process, and its own metrics, with
  [agentMetricsAt][io.prometheus.agent.EmbeddedAgentConfig.Builder.agentMetricsAt]) and starts it,
  with [start][io.prometheus.agent.EmbeddedAgentConfig.start] or, for an app that runs without it,
  [startIfConfigured][io.prometheus.agent.EmbeddedAgentConfig.startIfConfigured], which starts it
  where [namesProxy][io.prometheus.agent.EmbeddedAgentConfig.namesProxy] says the settings name a
  proxy; its builder is the receiver of [embeddedAgent][io.prometheus.agent.embeddedAgent], the
  Kotlin DSL.
- [EmbeddedAgentInfo][io.prometheus.agent.EmbeddedAgentInfo] is the return value of
  [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent], carrying the
  `launchId` and `agentName` of the started agent, its connection to the proxy (`isConnected`,
  `proxyEndpoint`, read live for a host's health check), and a `shutdown()` method for graceful
  stop, which `close()` calls, as it's `AutoCloseable`.

# Package io.prometheus.common

Shared configuration types used by both Proxy and Agent. The `ScrapeResults` data model, the
auto-generated `ConfigVals` HOCON wrapper, and the assorted internal utility/constant objects are
all `internal` (or, in the case of `ConfigVals`, explicitly suppressed from these docs because
of its size).

- [BaseOptions][io.prometheus.common.BaseOptions] is the abstract base class for
  [AgentOptions][io.prometheus.agent.AgentOptions] and
  [ProxyOptions][io.prometheus.proxy.ProxyOptions]. Handles JCommander argument parsing,
  HOCON config loading from file or URL, and provides shared fields for TLS, keepalive,
  admin-servlet, metrics-endpoint, and log-level options with CLI > env > config precedence.
- [EnvVars][io.prometheus.common.EnvVars] is a typed enumeration of the environment variables the
  Proxy and Agent read themselves, with `getEnv()` overloads for `String`, `Boolean`, `Int`, and
  `Long` that reject malformed values. The `PROMETHEUS_*` variables
  [fromEnvironment][io.prometheus.agent.EmbeddedAgentConfig.Builder.fromEnvironment] reads for an
  embedding app aren't among them: nothing else reads those.
- [ConfigLoadException][io.prometheus.common.ConfigLoadException] is thrown when a configuration
  file or URL cannot be loaded, or a setting is one the agent can't use, by
  [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent] (the form that takes a
  file, like [Agent.startSyncAgent][io.prometheus.Agent.Companion.startSyncAgent], only with
  `exitOnMissingConfig = false`) and [EmbeddedAgentConfig.start][io.prometheus.agent.EmbeddedAgentConfig.start],
  and by the builder's methods as they're called, for a value they can tell is wrong.
  [startIfConfigured][io.prometheus.agent.EmbeddedAgentConfig.startIfConfigured] catches it and
  returns null. Embedders can catch it so a config failure does not terminate the host JVM.
