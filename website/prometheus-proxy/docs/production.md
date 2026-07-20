---
icon: lucide/clipboard-check
---

# Running in Production

Guidance for deploying Prometheus Proxy in a production environment, pulling together the
security, reliability, and tuning knobs documented elsewhere into one operational checklist.

## Security

- **Encrypt the gRPC channel with TLS**, and prefer **mutual TLS** so the proxy authenticates
  agents and vice-versa. See [TLS Setup](security/tls.md).
- **Set an agent token** (`--agent_token` / `AGENT_TOKEN`) as a lightweight app-level control
  in addition to TLS. An empty token leaves the agent port open and logs a startup warning.
- **Segment the network** so only trusted agents can reach the gRPC port (`50051`).
- **Do not expose the admin port publicly.** `/threaddump` and friends are operational tools,
  not public endpoints — keep `8092`/`8093` on an internal network only.
- When forwarding auth headers to targets, **require TLS** so credentials aren't sent in
  plaintext between proxy and agent. See
  [Auth Header Forwarding](security/index.md#auth-header-forwarding).

## High availability

- For **proxy** redundancy, give the agent an ordered list of proxy endpoints. It connects to the
  first that answers, and on a failed connect moves to the next; when a working connection drops it
  returns to the head of the list, so a recovered primary is picked up on the next reconnect without
  any manual step.

    ```hocon
    agent {
      proxy {
        endpoints = [ "proxy-a.example.com:50051", "proxy-b.example.com:50051" ]
      }
    }
    ```

    Equivalently `--proxy proxy-a.example.com:50051,proxy-b.example.com:50051`, or the same
    comma-separated value in `PROXY_HOSTNAME`.

    Only one connection is active at a time; the agent registers its paths on whichever proxy it is
    connected to. Point Prometheus at **both** proxies with identical target lists — see
    [Scraping an HA pair](#scraping-an-ha-pair) below, which has a requirement that is easy to get
    wrong.

    All endpoints must share the same TLS configuration. The agent builds one TLS context and one
    authority override for the whole list, so endpoints with different CAs or certificate SANs fail
    with an opaque handshake error rather than a clear configuration error.

- For **agent** redundancy, use [consolidated mode](advanced.md#consolidated-mode): multiple
  agents register the same path, and the proxy keeps serving it as long as one remains
  connected. This also smooths **rolling upgrades** — the new agent registers before the old
  one drains.

### Scraping an HA pair

!!! warning "Use `static_config`, not `http_sd_config`"

    A standby proxy — one no agent is currently connected to — returns an **empty list** from its
    service-discovery endpoint. Under `http_sd_config` Prometheus treats that as "these targets no
    longer exist" and **deletes** them: no `up=0`, no failed scrape, no alert. The series simply stop,
    which is the one failure mode an HA pair is supposed to make impossible.

    With `static_config`, the standby returns `404` for a path it does not know, Prometheus records
    `up=0` for that target, and your existing alerting works unchanged.

```yaml
scrape_configs:
  - job_name: proxied-metrics
    static_configs:
      - targets: ["proxy-a.example.com:8080", "proxy-b.example.com:8080"]
```

Both proxies are scraped every interval. Exactly one of them has the agent connected and returns the
metrics; the other returns `404` and shows as `up=0`. Deduplicate in the usual Prometheus HA way
(distinct `external_labels` plus a downstream deduplicating reader, or `honor_labels`).
- Set Kubernetes liveness/readiness probes to `/ping` and `/healthcheck` so unhealthy pods are
  restarted and kept out of rotation. See [Kubernetes](kubernetes.md#health-probes-resources).

## Sizing & tuning

Start from the defaults and adjust against the backlog and latency metrics:

| Parameter                                  | Default | When to change                                       |
|:-------------------------------------------|:--------|:-----------------------------------------------------|
| `agent.maxConcurrentClients`               | 1       | Raise for many endpoints or slow targets             |
| `agent.scrapeTimeoutSecs`                  | 15      | Raise for slow targets                               |
| `agent.http.clientTimeoutSecs`             | 90      | Lower for fast-failing scrapes                       |
| `agent.chunkContentSizeKbs`                | 32      | Raise for large payloads to cut chunk count          |
| `agent.minGzipSizeBytes`                   | 512     | Lower to compress more aggressively                  |
| `agent.http.maxContentLengthMBytes`        | 10      | Raise for large scrape bodies (guards agent heap)    |
| `proxy.internal.maxUnzippedContentSizeMBytes` | —    | Raise for large decompressed payloads                |
| `proxy.internal.maxAgentInactivitySecs`    | 60      | Tune stale-agent eviction window                     |

Size the JVM heap for the largest **decompressed** payload times the scrape concurrency, and
watch `agent_scrape_backlog_size` / `proxy_cumulative_agent_backlog_size` — a steadily growing
backlog means agents can't keep up. See [Performance Tuning](advanced.md#performance-tuning).

## Observability

- **Enable metrics** on proxy and agent and scrape their internal `/metrics`. See
  [Monitoring](monitoring.md#scraping-internal-metrics).
- **Import the dashboards and alert rules** from [Grafana & Alerting](grafana.md).
- Alert on success rate, P99 latency, agent count, and backlog growth — the rules on that page
  cover each.

## Logging

- Leave `requestLoggingEnabled` on if you want per-scrape logs — they emit at **DEBUG**, so
  they won't flood INFO on a busy proxy.
- Use `logLevel = "trace"` for the most verbose output; the legacy `"all"` level was removed
  and now fails fast at startup.

## Shutdown

- Standalone proxy/agent processes shut down cleanly on SIGTERM. **Embedded** agents should be
  stopped via `EmbeddedAgentInfo.shutdown()` (or `close()`), which blocks until terminated.
  See [Embedded Agent](embedded-agent.md).

## Pre-flight checklist

- [ ] TLS (ideally mutual) enabled on the gRPC channel
- [ ] Agent token set, or mutual TLS in place
- [ ] gRPC port reachable by agents; admin port **not** publicly exposed
- [ ] Metrics enabled and scraped by Prometheus
- [ ] Dashboards imported and alert rules loaded
- [ ] `maxConcurrentClients` / timeouts tuned for your targets
- [ ] Content-size limits sized for your largest payload
- [ ] JVM heap sized for peak decompressed payload × concurrency
- [ ] Liveness/readiness probes wired to `/ping` and `/healthcheck`
- [ ] Consolidated mode configured where you need agent redundancy
