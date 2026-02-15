# Module Prometheus Proxy

A Prometheus Proxy system that enables Prometheus to scrape metrics from endpoints behind firewalls.
An **Agent** runs inside the firewall alongside monitored services, while a **Proxy** runs outside
alongside Prometheus. The two communicate over a gRPC stream, allowing Prometheus to reach
otherwise-inaccessible targets without opening inbound firewall ports.

# Package io.prometheus

Top-level entry points for the system. Contains the [Agent][io.prometheus.Agent] and
[Proxy][io.prometheus.Proxy] classes, which are the main classes users instantiate
to start each component. Both support programmatic configuration, embedded usage
inside other JVM applications, and HOCON-based config files.

# Package io.prometheus.proxy

Proxy-side services and managers. Handles inbound HTTP scrape requests from Prometheus
via [ProxyHttpService][io.prometheus.proxy.ProxyHttpService], manages agent connections
through [ProxyGrpcService][io.prometheus.proxy.ProxyGrpcService], and routes requests
to the correct agent using [ProxyPathManager][io.prometheus.proxy.ProxyPathManager].
Includes [AgentContextManager][io.prometheus.proxy.AgentContextManager] for tracking
connected agents, [ScrapeRequestManager][io.prometheus.proxy.ScrapeRequestManager]
for in-flight request lifecycle, and [AgentContextCleanupService][io.prometheus.proxy.AgentContextCleanupService]
for evicting stale agents.

# Package io.prometheus.agent

Agent-side services for gRPC communication and HTTP scraping. The
[AgentGrpcService][io.prometheus.agent.AgentGrpcService] maintains the bidirectional
gRPC connection to the proxy, while [AgentHttpService][io.prometheus.agent.AgentHttpService]
scrapes actual metrics endpoints via Ktor HTTP client. Path registrations are managed by
[AgentPathManager][io.prometheus.agent.AgentPathManager], and HTTP clients are cached
per credential set in [HttpClientCache][io.prometheus.agent.HttpClientCache].

# Package io.prometheus.common

Shared utilities, configuration, and data models used by both proxy and agent.
Includes [BaseOptions][io.prometheus.common.BaseOptions] for CLI argument parsing,
[ConfigVals][io.prometheus.common.ConfigVals] for type-safe HOCON configuration
(auto-generated via tscfg), [ScrapeResults][io.prometheus.common.ScrapeResults] for
scrape response data, and [EnvVars][io.prometheus.common.EnvVars] for environment
variable mappings.
