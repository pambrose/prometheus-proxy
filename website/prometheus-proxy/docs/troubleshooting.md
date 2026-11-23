---
icon: lucide/life-buoy
---

# Troubleshooting

!!! tip "Try the dashboard first"

    The proxy ships an [operational dashboard](web-dashboard.md) that shows connected agents, the paths each one
    backs, and their recent scrape results on one screen, and can scrape a path on demand. It is off by default; enable it with
    `--dashboard` and it will answer most of the questions below without log-diving.

A symptom-driven guide to the failures Prometheus Proxy most commonly produces, with how to
confirm each cause and how to fix it.

## First steps

Before diving into a specific symptom, gather signal:

1. **Enable admin and metrics endpoints** on both proxy and agent (`--admin --metrics`, or
   `ADMIN_ENABLED=true METRICS_ENABLED=true`). See [Monitoring](monitoring.md).
2. **Check liveness/health**: `curl http://<host>:<admin-port>/ping` (expects `pong`) and
   `curl http://<host>:<admin-port>/healthcheck` (health JSON). Admin ports default to `8092`
   (proxy) and `8093` (agent).
3. **Scrape the path directly**, bypassing Prometheus:
   `curl -i http://<proxy-host>:8080/<path>` — the HTTP status code tells you a lot (see
   below).
4. **Watch the outcome metric**: `proxy_scrape_requests_total` is labeled by `type`
   (`success`, `timed_out`, `upstream_timed_out`, `agent_disconnected`, `path_not_found`, `upstream_error`,
   `content_too_large`, `payload_too_large`, …). Whichever `type` is incrementing names the failure
   mode; see [Monitoring](monitoring.md) for the full table.
5. **Read the logs.** The proxy and agent log the reason for most rejections at WARN/INFO.

---

## The proxy or agent exits at startup

### `java.net.BindException: Address already in use`

Another process already listens on one of the proxy's ports: the HTTP port Prometheus scrapes (default `8080`),
the agent gRPC port (`50051`), or, when they're enabled, the admin (`8092`), metrics (`8082`), or dashboard
(`8094`) port. The proxy logs the exception and exits with status 1, so a supervisor's restart policy retries it;
before 4.3.0 it kept running instead.

- Find what holds the port: `lsof -nP -iTCP:8080 -sTCP:LISTEN` (macOS, Linux) or `ss -ltnp 'sport = :8080'`
  (Linux).
- Stop that process, or move the proxy to a free port with the matching option in the
  [CLI Reference](cli-reference.md).

### `Invalid configuration: …`

A setting failed a check at startup. The line names the setting and why it was refused: fix it and start again. A
standalone proxy or agent logs this one line and exits with status 1; before 4.5.0, some of these settings ended in
an uncaught exception's stack trace instead. An [embedded agent](embedded-agent.md#agentstartasyncagent) throws a
`ConfigLoadException` with the same reason.

Settings that fail startup since 4.5.0:

- `proxy.http.idleTimeoutSecs` of `0`, or a negative value other than `-1`. `0` never turned the timeout off; `-1`
  gives the default, 45 seconds ([Proxy Configuration](configuration/proxy.md)).
- A service discovery `targetPrefix` with a query or fragment ([Service Discovery](service-discovery.md)).
- A `proxy.auth` identity named `legacy-agent-token`, the name the legacy shared token has
  ([Security](security/index.md)).
- An agent `pathConfigs` path with whitespace, a control character, `?`, or `#`, which no scrape can reach.
- A `proxy.dashboard.links` entry the dashboard can't use, or an `icon` it doesn't have
  ([Links](web-dashboard.md#links)).
- A negative `proxy.internal.maxTargetUrlLength` or `proxy.internal.maxAgentsPerIdentity`.

---

## Agent can't connect to the proxy

### Connection refused / timeouts on the gRPC port

- Confirm the agent's `PROXY_HOSTNAME` (and port, if not the default `50051`) points at the
  proxy's reachable address. In Kubernetes this is usually a `LoadBalancer` host — see
  [Kubernetes](kubernetes.md#exposing-grpc-to-remote-agents).
- Confirm a firewall/security group allows the agent **egress** to the proxy gRPC port.
- gRPC is HTTP/2 — if an L7 proxy or ingress sits in front, it must speak HTTP/2/gRPC.

### `UNAUTHENTICATED: Missing or invalid agent token`

The proxy has an agent token configured and the agent's token is missing or doesn't match.

- **Where it shows:** the agent logs it at WARN, with a stack trace, on its first attempt, and again every 10 minutes
  while it lasts; its reconnects every few seconds in between are at DEBUG. The proxy logs
  `Refused an agent from <host> (UNAUTHENTICATED): it sent an unknown agent token` (or `no agent token`) at WARN, at
  most once a minute for each host.

- **Fix:** set the **same** `--agent_token` / `AGENT_TOKEN` on the agent as on the proxy. An
  empty token on the proxy disables the check. See
  [Security](security/index.md#agent-authentication-pre-shared-token).

---

## Prometheus scrape returns an error

Scrape the path directly with `curl -i` and match the status:

### `404 Not Found`

The proxy has no usable mapping for that path.

- **`metrics_path` mismatch** — the Prometheus `metrics_path` must exactly equal the agent's
  `path` (with a single leading slash). `app1_metrics` in the agent → `metrics_path:
  '/app1_metrics'` in Prometheus.
- **Multi-segment path** — a registered `path` containing an embedded `/` is rejected at
  registration with `Multi-segment path not supported (use a single path segment)`, and
  scrapes 404. Use a single segment (e.g. `app_metrics`, not `app/metrics`).
- **Agent not registered** — confirm the agent started cleanly and registered the path
  (check `proxy_path_map_size` and the agent logs).
- **Agent gone** (`invalid_agent_context`) — every agent on the path has disconnected, and the
  path is on its way out; seen briefly as the path's last agent leaves. Check
  `proxy_agent_map_size` and whether the agent is up.

### `503 Service Unavailable`

The path is known but cannot be served right now.

- **Proxy shutting down** (`proxy_stopped`).
- **Agent disconnected** (`agent_disconnected`) — before or during the scrape, including an eviction
  or another agent taking over the path. Transient during agent restarts. Consolidated mode keeps a
  path answering while one of its agents is down, when each agent scrapes its own instance of the
  service; see [Consolidated Mode](advanced.md#consolidated-mode).

### `502 Bad Gateway`

- **Target error** (`upstream_error`) — the target itself returned the status; check the target.
- **Unusable response** (`invalid_response`) — the agent answered, but a chunk or summary failed
  validation. Check `proxy_chunk_validation_failures_total` and the proxy log.

### `413 Payload Too Large`

The scrape body exceeded a configured size limit.

- **Agent side:** raise `agent.http.maxContentLengthMBytes` (default `10`). Outcome label
  `content_too_large`.
- **Proxy side:** raise `proxy.internal.maxUnzippedContentSizeMBytes` (the decompressed-size
  guard; `0` means "reject all"). Outcome label `payload_too_large`.
- The two limits are independent and both surface as HTTP 413 — the outcome label tells you which
  one rejected the scrape.

### Scrape hangs, then times out

Check which timeout fired: `upstream_timed_out` means the agent answered promptly to report that
the *target* was slow, while `timed_out` means the agent never answered the proxy at all. Because
`scrapeTimeoutSecs` (15s) is well below the proxy's `scrapeRequestTimeoutSecs` (90s), a slow target
normally shows up as `upstream_timed_out`.

- **`upstream_timed_out`** — the target endpoint is slow. Raise the agent's `scrapeTimeoutSecs`
  (default `15`) and/or `clientTimeoutSecs` (default `90`), or fix the target. The agent also stops at
  Prometheus's own `scrape_timeout`, which the proxy forwards in `X-Prometheus-Scrape-Timeout-Seconds`, so raise
  that too. The agent passes the timeout it enforces on to the target in the same header, so exporters that
  size their work by it, such as blackbox_exporter, finish within it.
- **`timed_out`** — the agent is unresponsive or the link to it is broken. Check the agent is still
  connected (`proxy_agent_map_size`) and raise `proxy.internal.scrapeRequestTimeoutSecs` only if
  the agent legitimately needs longer than 90s.
- Either can mean the agent is saturated — raise `agent.http.maxConcurrentClients` (default `1`) so
  scrapes run in parallel; watch `agent_scrape_backlog_size` and `proxy_cumulative_agent_backlog_size`.
- See [Performance Tuning](advanced.md#performance-tuning).

---

## Registration is rejected

The proxy logs the reason and returns `valid = false` to the agent:

| Log reason                                                  | Cause / fix                                                                 |
|:------------------------------------------------------------|:----------------------------------------------------------------------------|
| `Multi-segment path not supported (use a single path segment)` | The `path` contains an embedded `/`. Use one segment.                    |
| `Invalid path: /… ('…' can't appear in a scrape's request path)` | The `path` contains whitespace, a control character, `?`, or `#`, which no scrape can reach. Rename it. |
| `Consolidated agent rejected for non-consolidated path`     | A consolidated agent tried to join a path another agent owns non-consolidated. Make **all** agents for the path consolidated, or none. |
| `Non-consolidated agent rejected for consolidated path`     | The reverse of the above — same fix.                                        |
| `Agent identity '…' denied registration of path /…` (the agent logs `… is not authorized to register path /…`) | The path matches none of the `paths` patterns of the agent's [`proxy.auth` identity](security/index.md#per-agent-identities-and-path-authorization). Add a pattern that covers it, or rename the path. |
| `Path /… is served by identity '…'; identity '…' cannot take it over` (or `join it`) | A live agent of another identity serves the path. The agent retries, and registers the path once that agent is gone. To move the path, stop the old agent; for both to serve it, give them the same identity and consolidated mode. |
| `Path /… is served by agentId … (…), of another launch; agentId … takes it back once that agent leaves` (INFO) | Not a fault. Another agent took the path over, and this one, told so, is waiting to take it back; see [An agent is connected but serves no paths](#an-agent-is-connected-but-serves-no-paths). |
| `Path /… is N characters, over proxy.internal.maxPathLength (…)` | Shorten the path, or raise the limit ([Internal Settings](configuration/proxy.md#internal-settings)). |
| `Labels for path /… are N bytes, over proxy.internal.maxLabelsSizeBytes (…)` | Trim the path's `labels`, or raise the limit. |
| `Agent … already serves N paths, the proxy.internal.maxPathsPerAgent limit (…); path /… refused` | Spread the paths across more agents, or raise the limit. |
| `Invalid path: /… is the service discovery path, which the proxy serves itself` | The proxy serves that path itself, as it does `favicon.ico` and, when enabled, the [Blitz verification path](service-discovery.md#labels). Rename it. |

See [Consolidated Mode](advanced.md#consolidated-mode) for the all-or-nothing rule. The agent retries the
consolidation and identity conflicts, and a path another agent took over, which clear once the agents serving the path
leave; it doesn't retry the others while it stays connected.

The proxy logs the first rejection of a path for an agent connection at WARN, and an identical repeat at
DEBUG -- an agent retries a rejection that can clear, and a lasting conflict would otherwise fill the log.
To see every attempt, set `io.prometheus.proxy.ProxyPathManager` to DEBUG. The agent logs its own side of
the same conflict, so its log shows whether it is still retrying.

### An agent is connected but serves no paths

Another agent registered the same paths without consolidated mode and took them over: the later registration
serves a path, and the agent it displaced stays connected without it. The proxy logs it at INFO:

```
agentId 1 keeps its connection without paths after agentId 2, of another launch, took over /orders_metrics; ...
```

The proxy tells the displaced agent, which logs a WARN naming the agent that took the path, and its
[dashboard](agent-dashboard.md) shows the path `retrying`, taken over by that agent. It takes the path back once no
agent serves it, within seconds, without reconnecting, and never while the other agent does, even after its own
connection drops and it reconnects.

If both agents should serve the paths, set `agent.consolidated = true` on each. If only one should, remove the
paths from the other's config, or stop it. Earlier versions disconnected the displaced agent, so the two took the
paths from each other in turn, each reconnecting every few seconds. Agents and proxies predating the notice show a
taken-over path as registered on the displaced agent, which takes it back only when it reconnects.

---

## TLS handshake failures

- **Untrusted certificate** — the side validating the cert needs the signing CA in its
  `trustCertCollectionFilePath`.
- **Hostname / SAN mismatch** — the cert's SAN must match the hostname the agent dials, or set
  `tls.overrideAuthority` (the test fixtures use `foo.test.google.fr`).
- **Mutual auth** — with mutual TLS the **agent** must also present `certChainFilePath` /
  `privateKeyFilePath`, and the proxy must trust the client CA. A server-only config will be
  rejected.
- For HTTPS **scrape targets** signed by a private CA, point the agent at
  `--https_truststore` rather than disabling validation with `--trust_all_x509`.

See [TLS Setup](security/tls.md) and the [example configs](examples.md).

---

## Metrics or admin endpoints don't answer

The endpoints are **disabled by default**, so their ports refuse connections until you enable them with
`--admin` / `--metrics` (or `ADMIN_ENABLED=true` / `METRICS_ENABLED=true`). A 404 means the port is up but serves
no such path: scrape `/metrics` on the metrics port (`8082` proxy, `8083` agent), and `/ping`, `/healthcheck`,
`/version`, and `/threaddump` on the admin port (`8092` proxy, `8093` agent).

---

## Disconnects aren't detected behind Nginx

With `transportFilterDisabled` (required when fronting gRPC with Nginx), the proxy notices an
agent leave when the agent's request stream ends, which a disconnect normally does at once. An
agent that dies before opening that stream, or whose connection stays half-open, stays listed
until `proxy.internal.maxAgentInactivitySecs` (default 60s) evicts it. This is expected; see
[Nginx Reverse Proxy](advanced.md#nginx-reverse-proxy).

### Agents reconnect every minute behind Nginx

The agent logs `RST_STREAM closed stream` on `writeChunkedResponsesToProxy()` or `writeResponsesToProxy()`, or
`HTTP status code 504` on `readRequestsFromProxy()`, then `Disconnected from proxy`, about a minute after it connects,
and again every minute, scraped or not. Its paths are gone from the proxy for a moment each time, so a scrape can fail.

- **Cause:** Nginx closes a gRPC stream that's quiet for 60 seconds, and an agent's stream for chunked results carries
  nothing while its results are small, and its other streams nothing between scrapes. A current agent and proxy keep
  their streams open with keepalives, but only when both support them, so this happens when the agent or the proxy is
  from 4.6.1 or earlier, or when a reverse proxy's idle timeout is under `proxy.internal.streamKeepAliveSecs`.
- **Fix:** upgrade both, or raise `grpc_read_timeout` and `client_body_timeout` to a day or more; under a shorter idle
  timeout, lower `streamKeepAliveSecs` below it. See [Nginx Reverse Proxy](advanced.md#nginx-reverse-proxy).

### A change to nginx doesn't reach connected agents

After `nginx -s reload`, agents connected before it behave as before, and nginx's process list shows workers
`shutting down` that never exit.

- **Cause:** a reload leaves each open connection with the old worker it's on, under the old config, until the
  connection ends, and an agent's connection, which its keepalives keep open, doesn't.
- **Fix:** set `worker_shutdown_timeout` in nginx's main context and restart nginx once; from then on, each reload
  hands the agents to the new workers that long after it. See [Nginx Reverse Proxy](advanced.md#nginx-reverse-proxy).

### `RESOURCE_EXHAUSTED` when an agent connects behind Nginx

With `transportFilterDisabled`, the proxy refuses a connect once
`proxy.internal.maxAgentsPerIdentity` (10,000) agents are connected with the same auth identity,
and logs `Refused a connect`. With agent auth off, or on the legacy `proxy.agentToken`, every
agent shares one identity, so the limit counts the whole fleet. The agent keeps retrying, and gets
in once another agent leaves.

- **Fix:** raise the limit, or set it to `0` for none, if the fleet needs more. If it shouldn't,
  look for agents that keep reconnecting, such as ones whose registration is rejected: each attempt
  counts until it is evicted. Giving each team its own `proxy.auth` identity also keeps one team's
  agents from using up another's room. See
  [Transport Filter](configuration/proxy.md#transport-filter).

---

## The dashboard behind a reverse proxy stays on reconnecting…

The page loads, but its live indicator never turns green, and Scrape now or the login may say `Origin not allowed`.

- **Cause:** the reverse proxy doesn't pass the WebSocket handshake's `Upgrade` and `Connection` headers, or passes a
  `Host` header that doesn't match the browser's `Origin`, which the dashboard refuses.
- **Fix:** pass both headers, over HTTP/1.1, and the browser's `Host`, or list the dashboard's public address in
  `proxy.dashboard.allowedOrigins`; see [Behind a reverse proxy](web-dashboard.md#behind-a-reverse-proxy).

---

## Still stuck?

- Cross-check metric meanings in [Monitoring](monitoring.md).
- Confirm option names and resolution order in the [CLI Reference](cli-reference.md)
  (precedence is CLI → env → config → defaults).
- File an issue at
  [github.com/pambrose/prometheus-proxy/issues](https://github.com/pambrose/prometheus-proxy/issues)
  with the relevant proxy/agent logs and the direct `curl -i` output.
