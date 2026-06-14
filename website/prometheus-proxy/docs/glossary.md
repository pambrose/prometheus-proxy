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
[Service Discovery](service-discovery.md).

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
