---
icon: lucide/life-buoy
---

# Troubleshooting

!!! tip "Try the web UI first"

    The proxy ships a read-only [operational web UI](web-ui.md) that shows connected agents, the paths
    each one backs, and their recent scrape results on one screen. It is off by default; enable it with
    `--ui` and it will answer most of the questions below without log-diving.

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
4. **Watch the outcome metric**: `proxy_scrape_requests` is labeled by `type`
   (`success`, `timed_out`, `upstream_timed_out`, `no_agents`, `path_not_found`, `upstream_error`,
   `content_too_large`, `payload_too_large`, …). Whichever `type` is incrementing names the failure
   mode; see [Monitoring](monitoring.md) for the full table.
5. **Read the logs.** The proxy and agent log the reason for most rejections at WARN/INFO.

---

## Agent can't connect to the proxy

### `Address types of NameResolver 'unix' ... not supported by transport`

The gRPC client fell back to the `unix` name-resolver scheme on a hostname target because the
DNS resolver provider was missing from the shaded JAR.

- **Cause:** a fat JAR built before this was fixed dropped grpc's `DnsNameResolverProvider`
  from `META-INF/services`.
- **Fix:** use the **3.2.0 or newer** `prometheus-proxy.jar` / `prometheus-agent.jar` (the
  providers are re-registered via `src/shadow/resources`). As a stopgap, addressing the proxy
  by **IP** instead of hostname also avoids DNS resolution.

### Connection refused / timeouts on the gRPC port

- Confirm the agent's `PROXY_HOSTNAME` (and port, if not the default `50051`) points at the
  proxy's reachable address. In Kubernetes this is usually a `LoadBalancer` host — see
  [Kubernetes](kubernetes.md#exposing-grpc-to-remote-agents).
- Confirm a firewall/security group allows the agent **egress** to the proxy gRPC port.
- gRPC is HTTP/2 — if an L7 proxy or ingress sits in front, it must speak HTTP/2/gRPC.

### `UNAUTHENTICATED: Missing or invalid agent token`

The proxy has an agent token configured and the agent's token is missing or doesn't match.

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

### `503 Service Unavailable`

The path is known but cannot be served right now.

- **Proxy shutting down** (`proxy_not_running`).
- **No agent for the path** (`no_agents`) — the owning agent disconnected. Check
  `proxy_agent_map_size` and whether the agent is up.
- **Agent disconnected mid-scrape** (`agent_disconnected`). Transient during agent restarts;
  for redundancy use [consolidated mode](advanced.md#consolidated-mode).

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
  (default `15`) and/or `clientTimeoutSecs` (default `90`), or fix the target.
- **`timed_out`** — the agent is unresponsive or the link to it is broken. Check the agent is still
  connected (`proxy_connected_agents`) and raise `proxy.internal.scrapeRequestTimeoutSecs` only if
  the agent legitimately needs longer than 90s.
- Either can mean the agent is saturated — raise `maxConcurrentClients` (default `1`) so scrapes
  run in parallel; watch `agent_scrape_backlog_size` and `proxy_cumulative_agent_backlog_size`.
- See [Performance Tuning](advanced.md#performance-tuning).

---

## Registration is rejected

The proxy logs the reason and returns `valid = false` to the agent:

| Log reason                                                  | Cause / fix                                                                 |
|:------------------------------------------------------------|:----------------------------------------------------------------------------|
| `Multi-segment path not supported (use a single path segment)` | The `path` contains an embedded `/`. Use one segment.                    |
| `Consolidated agent rejected for non-consolidated path`     | A consolidated agent tried to join a path another agent owns non-consolidated. Make **all** agents for the path consolidated, or none. |
| `Non-consolidated agent rejected for consolidated path`     | The reverse of the above — same fix.                                        |

See [Consolidated Mode](advanced.md#consolidated-mode) for the all-or-nothing rule.

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

## Metrics or admin endpoints return 404

The endpoints are **disabled by default**. Enable them with `--admin` / `--metrics` (or
`ADMIN_ENABLED=true` / `METRICS_ENABLED=true`) and scrape the metrics port (`8082` proxy,
`8083` agent), not the admin port.

---

## Disconnects aren't detected behind Nginx

With `transportFilterDisabled` (required when fronting gRPC with Nginx), agent disconnects
aren't noticed immediately — stale contexts are cleaned up only after
`proxy.internal.maxAgentInactivitySecs` (default 60s). This is expected; see
[Nginx Reverse Proxy](advanced.md#nginx-reverse-proxy).

---

## Still stuck?

- Cross-check metric meanings in [Monitoring](monitoring.md).
- Confirm option names and resolution order in the [CLI Reference](cli-reference.md)
  (precedence is CLI → env → config → defaults).
- File an issue at
  [github.com/pambrose/prometheus-proxy/issues](https://github.com/pambrose/prometheus-proxy/issues)
  with the relevant proxy/agent logs and the direct `curl -i` output.
