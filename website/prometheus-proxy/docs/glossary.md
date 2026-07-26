---
icon: lucide/book-a
---

# Glossary

Core terms used throughout the Prometheus Proxy documentation.

### Proxy

The component that runs **outside** the firewall, alongside Prometheus. It accepts agent
connections on the gRPC port (`50051`) and serves proxied metrics to Prometheus on the HTTP
port (`8080`). See [Architecture](architecture.md).

### Agent

The component that runs **inside** the firewall, next to the services being monitored. It
opens an outbound gRPC connection to the proxy, receives scrape requests, fetches the target
endpoints, and streams the responses back. See [Agent Configuration](configuration/agent.md).

### Path

A single URL path segment registered by an agent (for example `app1_metrics`). Prometheus
scrapes `http://proxy-host:8080/<path>`, and the proxy routes that request to the agent that
registered the path. Paths must be a **single segment** — an embedded `/` (e.g.
`app/metrics`) is rejected at registration. See [Troubleshooting](troubleshooting.md).

### pathConfig

An entry in the agent's `pathConfigs` list that maps a proxy `path` to the actual `url` the
agent fetches from, optionally with `name` and `labels`. See [Example Configs](examples.md).

### Scrape request / response

The gRPC messages exchanged over the agent stream: the proxy sends a **ScrapeRequest** for a
path, and the agent returns a **ScrapeResponse** with the fetched body, status, and headers.

### Consolidated mode

A mode (`agent.consolidated = true`) in which multiple agents may register the **same** path
for redundancy or load distribution, instead of the later registration displacing the earlier
one. See [Advanced Topics](advanced.md#consolidated-mode).

### Chunking

Splitting a large scrape response into multiple `ChunkedScrapeResponse` messages to stay
within gRPC message limits. The threshold is `agent.chunkContentSizeKbs` (default 32 KB). See
[Architecture](architecture.md#chunking).

### Heartbeat

A keepalive message the agent sends during periods of inactivity so the proxy knows the
connection is still alive. See [Architecture](architecture.md#heartbeat).

### Service discovery

The proxy's HTTP endpoint that returns the list of registered targets in Prometheus
`http_sd_config` format, so Prometheus can discover proxied paths dynamically. See
[Service Discovery](service-discovery.md). Distinct from **dynamic target discovery** below,
which is the agent-side mechanism.

### Dynamic target discovery

The agent-side reconcile loop (`agent.discovery`) that keeps registered paths in sync with a
watched HOCON/JSON file at runtime, so targets can be added and removed without an agent
restart. Discovered paths are tagged `disc` on the dashboard; static `pathConfigs` entries
(`cfg`) are never touched by it. See
[Dynamic Target Discovery](configuration/agent.md#dynamic-target-discovery).

### Proxy failover

The agent's high-availability mechanism: an ordered `agent.proxy.endpoints` list tried head-first
on every reconnect, so the agent moves to a standby when a connect fails and returns to the
primary once it recovers. One connection is active at a time. See
[High availability](production.md#high-availability).

### Agent identity

A named entry in the proxy's `proxy.auth` list — a token plus allowed path glob patterns — that
lets the proxy tell agents apart and reject a `registerPath` outside the identity's scope. The
shared [agent token](#agent-token) is the identity-less predecessor, honored during migration as
an allow-all identity. See
[Per-Agent Identities](security/index.md#per-agent-identities-and-path-authorization).

### Metric filter

An optional per-path allow/deny rule set (`agent.filters`) applied at the agent, dropping whole
metric families before a payload is compressed and sent to the proxy. Regexes are fully
anchored, and a family's `_bucket`/`_sum`/`_count` series are kept or dropped together. See
[Metric Filtering](configuration/agent.md#metric-filtering).

### Dashboard

The proxy's read-only, live-updating web page (`proxy.dashboard`, default port `8094`) showing
connected agents, registered paths — including paths whose agent has gone — and recent scrape
results. Off by default and unauthenticated. See [Dashboard](web-dashboard.md).

### Stale agent cleanup

The proxy's periodic eviction of agents that have been inactive longer than
`proxy.internal.maxAgentInactivitySecs` (default 60s). See
[Architecture](architecture.md#stale-agent-cleanup).

### Embedded agent

An agent run inside another JVM application via `startAsyncAgent()` rather than as a
standalone process. See [Embedded Agent](embedded-agent.md).

### Agent token

An optional pre-shared secret (`--agent_token` / `AGENT_TOKEN`) the agent attaches to every
gRPC call so the proxy can authenticate agents at the application layer. See
[Security](security/index.md#agent-authentication-pre-shared-token).

### Transport filter

A gRPC server filter the proxy uses to detect agent disconnects promptly. Disabling it
(`transportFilterDisabled`) is required when fronting the gRPC port with an L7 proxy such as
Nginx, at the cost of relying on the inactivity timeout for cleanup. See
[Advanced Topics](advanced.md#nginx-reverse-proxy).

### launch_id

A per-process identifier attached as a label to agent metrics, so a Prometheus target can
distinguish one agent process lifetime from the next across restarts. See
[Monitoring](monitoring.md#agent-metrics).
