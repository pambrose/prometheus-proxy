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
`app/metrics`) is rejected at registration, as is whitespace, a control character, `?`, or `#`.
See [Troubleshooting](troubleshooting.md).

### pathConfig

An entry in the agent's `pathConfigs` list that maps a proxy `path` to the actual `url` the
agent fetches from, with a `name`, and optionally `labels`. See [Example Configs](examples.md).

### Scrape Request / Response

The gRPC messages exchanged over the agent stream: the proxy sends a **ScrapeRequest** for a
path, and the agent returns a **ScrapeResponse** with the fetched body, status code, and content type.

### Consolidated Mode

A mode (`agent.consolidated = true`) in which multiple agents may register the **same** path,
for redundancy or to put several instances behind one path, instead of the later registration
displacing the earlier one. A scrape of the path goes to every agent serving it, and their responses
are merged, so each agent should scrape its own instance of the service: agents that scrape the same
endpoint send each series twice. See [Advanced Topics](advanced.md#consolidated-mode).

### Chunking

Splitting a large scrape response into multiple `ChunkedScrapeResponse` messages to stay
within gRPC message limits, once its gzipped size reaches `agent.chunkContentSizeKbs` (default 32 KB). See
[Architecture](architecture.md#chunking).

### Heartbeat

A keepalive message the agent sends during periods of inactivity so the proxy knows the
connection is still alive. See [Architecture](architecture.md#heartbeat).

### Service Discovery

The proxy's HTTP endpoint that returns the list of registered targets in Prometheus
`http_sd_config` format, so Prometheus can discover proxied paths dynamically. See
[Service Discovery](service-discovery.md). Distinct from **dynamic target discovery** below,
which is the agent-side mechanism.

### Dynamic Target Discovery

The agent-side reconcile loop (`agent.discovery`) that keeps registered paths in sync with a
watched HOCON/JSON file at runtime, so targets can be added and removed without an agent
restart. Discovered paths are tagged `disc` on the dashboard; static `pathConfigs` entries
(`cfg`) are never touched by it. See
[Dynamic Target Discovery](configuration/agent.md#dynamic-target-discovery).

### Proxy Failover

The agent's high-availability mechanism: an ordered `agent.proxy.endpoints` list. A failed connect,
or a proxy that rejects the agent's registration, moves the agent to the next endpoint. When a
connection that registered drops, the agent starts again from the head of the list, so it returns to
a recovered primary at its next reconnect. One connection is active at a time. See
[High availability](production.md#high-availability).

### Agent Identity

A named entry in the proxy's `proxy.auth` list — a token plus allowed path glob patterns — that
lets the proxy tell agents apart and reject a `registerPath` outside the identity's scope. The
shared [agent token](#agent-token) is the identity-less predecessor, honored during migration as
an allow-all identity. See
[Per-Agent Identities](security/index.md#per-agent-identities-and-path-authorization).

### Metric Filter

An optional per-path allow/deny rule set (`agent.filters`) applied at the agent, dropping whole
metric families before a payload is compressed and sent to the proxy. Regexes are fully
anchored, and a family's `_bucket`/`_sum`/`_count` series are kept or dropped together. See
[Metric Filtering](configuration/agent.md#metric-filtering).

### Dashboard

The proxy's live-updating web page (`proxy.dashboard`, default port `8094`) showing
connected agents, registered paths — including paths whose agent has gone — and recent scrape
results. Off by default, and unauthenticated until a password is set. See [Dashboard](web-dashboard.md).

### Agent Dashboard

The agent's own live-updating web page (`agent.dashboard`, default port `8095`) showing its connection to the proxy,
each proxy endpoint's last connect error, every path with the proxy's reason for refusing one and its recent scrapes as
the agent saw them, its filters, discovery, and settings, and an activity feed of what happened to them: what the
proxy's dashboard can't see. A Scrape now button fetches a path's target from the agent. Off by default, and unauthenticated until a password is
set. See [Agent Dashboard](agent-dashboard.md).

### Stale Agent Cleanup

The proxy's periodic eviction of agents that have been inactive longer than
[`proxy.internal.maxAgentInactivitySecs`](configuration/proxy.md#internal-settings) (default 60s). See
[Architecture](architecture.md#stale-agent-cleanup).

### Embedded Agent

An agent run inside another JVM application rather than as a standalone process, started with
`embeddedAgent { }` or `startAsyncAgent()`, or by the [Spring Boot Starter](spring-boot-starter.md) or the
[Ktor Plugin](ktor-plugin.md). See [Embedded Agent](embedded-agent.md).

### Agent Token

An optional pre-shared secret (`--agent_token` / `AGENT_TOKEN`) the agent attaches to every
gRPC call so the proxy can authenticate agents at the application layer. See
[Security](security/index.md#agent-authentication-pre-shared-token).

### Transport Filter

A gRPC server filter the proxy uses to detect agent disconnects promptly. Disabling it
(`transportFilterDisabled`) is required when fronting the gRPC port with an L7 proxy such as
Nginx. Without it, an agent is removed when its request stream ends, and the inactivity timeout
covers only an agent that dies before opening its stream, or whose connection stays half-open. See
[Advanced Topics](advanced.md#nginx-reverse-proxy).

### `launch_id`

An identifier each agent instance gets when it starts, attached as a label to agent metrics, so a
Prometheus target can tell an agent's series from those of its previous run, and from those of other
agents embedded in the same JVM. See [Monitoring](monitoring.md#agent-metrics).
