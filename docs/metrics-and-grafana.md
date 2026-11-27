# Metrics and Grafana Dashboards

This document describes the metrics exposed by the proxy and agent components,
how to enable and configure them, and how to use the included Grafana dashboards.

## Enabling Metrics

Metrics are disabled by default. Enable them via CLI flags, environment variables, or config file.

### Proxy

| Method | Example                        |
|--------|--------------------------------|
| CLI    | `--metrics` or `-e`            |
| Env    | `METRICS_ENABLED=true`         |
| Config | `proxy.metrics.enabled = true` |

Default endpoint: `http://proxy-host:8082/metrics`

Port and path are configurable:

```hocon
proxy.metrics {
  enabled = true
  port = 8082
  path = "metrics"
}
```

### Agent

| Method | Example                        |
|--------|--------------------------------|
| CLI    | `--metrics` or `-e`            |
| Env    | `METRICS_ENABLED=true`         |
| Config | `agent.metrics.enabled = true` |

Default endpoint: `http://agent-host:8083/metrics`. Since 4.7.0, the agent can serve its metrics through the proxy
instead, at a path of its own, read in-process, with no port: `agent.metrics.proxyPath`, `--metrics_proxy_path`, or
`METRICS_PROXY_PATH` (see [Prometheus Scrape Configuration](#prometheus-scrape-configuration)).

### JVM Metrics

Both components support optional JVM metrics exports:

```hocon
proxy.metrics {
  standardExportsEnabled = false
  memoryPoolsExportsEnabled = false
  garbageCollectorExportsEnabled = false
  threadExportsEnabled = false
  classLoadingExportsEnabled = false
  versionInfoExportsEnabled = false
  bufferPoolExportsEnabled = false
  compilationExportsEnabled = false
  nativeMemoryExportsEnabled = false
}
```

The same options are available under `agent.metrics`.

The JVM metrics come from the Prometheus Java client 1.x. Since the move to it, the memory metrics put the unit
last (`jvm_memory_used_bytes`, not `jvm_memory_bytes_used`; `jvm_memory_pool_used_bytes`, not
`jvm_memory_pool_bytes_used`; the same for `committed`, `max` and `init`), `jvm_info` is `jvm_runtime_info`, and
`memoryPoolsExportsEnabled` also adds `jvm_memory_pool_allocated_bytes_total`.

Three more groups are off by default:

- `bufferPoolExportsEnabled` adds `jvm_buffer_pool_used_bytes`, `jvm_buffer_pool_capacity_bytes` and
  `jvm_buffer_pool_used_buffers`, by `pool` (`direct` and `mapped`): the memory the JVM's NIO buffer pools hold
  outside the heap.
- `compilationExportsEnabled` adds `jvm_compilation_time_seconds_total`, the time the JIT compiler has spent.
- `nativeMemoryExportsEnabled` adds `jvm_native_memory_reserved_bytes` and `jvm_native_memory_committed_bytes`, by
  `pool`. It reads the JVM's Native Memory Tracking, which is off unless the JVM starts with
  `-XX:NativeMemoryTracking=summary` (for the Docker images, set `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary`).
  Without it the flag exports nothing, and tracking adds some overhead of its own.

---

## Proxy Metrics

### Counters

| Metric                                    | Labels    | Description                                                         |
|-------------------------------------------|-----------|---------------------------------------------------------------------|
| `proxy_scrape_requests_total`             | `type`    | Scrape request outcomes. See label values below.                    |
| `proxy_connect_count_total`               | —         | Agent connects the proxy accepted                                   |
| `proxy_agent_connect_refusals_total`      | `reason`  | Agent connects refused: `identity_limit`, `transport_filter_mismatch` |
| `proxy_agent_disconnects_total`           | `reason`  | Agents the proxy removed: `eviction`, `termination`, `stream_terminated`, `replaced` |
| `proxy_eviction_count_total`              | —         | Stale agent evictions                                               |
| `proxy_heartbeat_count_total`             | —         | Heartbeats the proxy accepted                                       |
| `proxy_agent_auth_failures_total`         | `reason`  | Agent calls refused for their token: `missing_token`, `unknown_token` |
| `proxy_path_rejections_total`             | `cause`   | Path registrations refused, by `PathRejectionCause`, lowercased     |
| `proxy_binding_mismatches_total`          | `rpc`     | Agent calls naming another agent or identity than their connection's |
| `proxy_late_scrape_results_total`         | —         | Scrape results that arrived after their scrape had ended            |
| `proxy_chunk_validation_failures_total`   | `stage`   | Chunk integrity failures (`chunk` or `summary`)                     |
| `proxy_chunked_transfers_abandoned_total` | —         | Chunked transfers whose scrape ended before they finished           |
| `proxy_agent_displacement_total`          | —         | Path registrations that displaced another agent                     |
| `proxy_dashboard_logins_total`            | `outcome` | Dashboard logins: `success`, `failure`, `blocked`                   |

**`proxy_scrape_requests_total` type labels:**

| Value                   | Meaning                                                                      |
|-------------------------|------------------------------------------------------------------------------|
| `success`               | Scrape completed successfully                                                |
| `timed_out`             | Agent did not answer within the proxy's `scrapeRequestTimeoutSecs`           |
| `upstream_timed_out`    | Agent answered, but its fetch exceeded `agent.scrapeTimeoutSecs`, or Prometheus's shorter scrape timeout, or the target answered 408 or 504 |
| `invalid_path`          | No agent has registered the requested path                                   |
| `missing_path`          | The request had no path (a scrape of `/`)                                    |
| `agent_disconnected`    | Agent disconnected, was evicted, or was displaced before it answered (503)   |
| `agent_backlog_full`    | Agent's queue was full (2 × `scrapeRequestBacklogUnhealthySize`)             |
| `proxy_in_flight_limit` | In-flight scrapes across agents hit `maxInFlightScrapeRequests`              |
| `client_cancelled`      | Prometheus hung up before the agent answered                                 |
| `missing_results`       | Internal error: results object was null                                      |
| `path_not_found`        | The agent doesn't serve the path, or the target answered 404                 |
| `upstream_error`        | Any other non-2xx from the target, or an unreachable target (503)            |
| `invalid_response`      | Agent answered, but a chunk or summary failed validation (502)               |
| `content_too_large`     | Over `agent.http.maxContentLengthMBytes`, or the target answered 413         |
| `payload_too_large`     | Unzipped content exceeded `maxUnzippedContentSizeMBytes`                     |
| `invalid_gzip`          | Gzip decompression failed                                                    |
| `proxy_stopped`         | Proxy is shutting down (503)                                                 |
| `invalid_agent_context` | All agents for the path are in an invalid state                              |
| `internal_error`        | The scrape threw, and the client got a 500                                   |

Several values come in proxy-side / agent-side pairs, and the distinction determines where to look:

- `timed_out` is the proxy giving up on an unresponsive agent; `upstream_timed_out` is the agent
  reporting promptly that the *target* was slow. Under stock config the agent's 15s timeout trips
  well before the proxy's 90s, so `upstream_timed_out` is the common case — raise
  `agent.scrapeTimeoutSecs` (and Prometheus's `scrape_timeout`, which the agent also honors) or fix the slow target,
  rather than touching the proxy timeout.
- `content_too_large` is the agent's own `maxContentLengthMBytes` limit rejecting the target
  response, or the target's own 413; `payload_too_large` is the proxy's unzip limit. Both surface as HTTP 413.
- The target's own non-2xx status passes through, so each agent-side label is the agent's own condition or the
  target's same status: `path_not_found` for 404, `upstream_timed_out` for 408 or 504, `content_too_large` for 413,
  and `upstream_error` for the rest. A target the agent couldn't reach, such as a refused connection, is the agent's
  own 503 `upstream_error`; the agent's `agent_scrape_request_count_total{type="failure"}` tells those apart. When
  the proxy fails a scrape itself it says why: `agent_disconnected` (the agent went away mid-scrape — look at the
  agent's connection, not the target), `proxy_stopped`, or `invalid_response`.

### Histograms

| Metric                                 | Labels             | Buckets  | Description                                                 |
|----------------------------------------|--------------------|----------|-------------------------------------------------------------|
| `proxy_scrape_request_latency_seconds` | `path`, `outcome`  | 5ms–90s  | End-to-end scrape latency from request creation to response |
| `proxy_scrape_queue_wait_seconds`      | —                  | 5ms–90s  | Time from request creation until the agent's stream took it |
| `proxy_scrape_response_bytes`          | `path`, `encoding` | 1KB–10MB | Response payload size after decompression                   |

A path's series are removed when its last registration goes away, whether it is unregistered or its agent disconnects, so a retired path stops appearing on `/metrics`. A scrape still in flight when that happens is not recorded, so it cannot bring the series back.

The `outcome` label takes the `proxy_scrape_requests_total` `type` values above, so latency can be split by result.
The `encoding` label is `gzipped` or `plain`.

Latency buckets: `.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10, 15, 30, 60, 90` seconds. They reach the proxy's
default `scrapeRequestTimeoutSecs` (90s), so a timed-out scrape lands in a bucket rather than `+Inf`.

Response size buckets: `1KB, 10KB, 100KB, 500KB, 1MB, 5MB, 10MB`.

### Gauges

| Metric                                | Description                                    |
|---------------------------------------|------------------------------------------------|
| `proxy_start_time_seconds`            | Proxy start time (Unix epoch), by `launch_id`  |
| `proxy_build_info`                    | Always 1, by `launch_id` and `version`         |
| `proxy_agent_map_size`                | Number of connected agents                     |
| `proxy_path_map_size`                 | Number of registered scrape paths              |
| `proxy_scrape_map_size`               | Number of in-flight scrape requests            |
| `proxy_chunk_context_map_size`        | Number of in-flight chunked transfers          |
| `proxy_cumulative_agent_backlog_size` | Total queued scrape requests across all agents |
| `proxy_dashboard_sessions`            | Dashboard pages open                           |

All but `proxy_start_time_seconds` and `proxy_build_info` are sampled: each is a `GaugeWithCallback`, built by `MetricBuilders.sampledGauge`,
that reads its value from live data structures on each Prometheus scrape, rather than being set on every state
change.

---

## Agent Metrics

### Counters

| Metric                             | Labels                        | Description                                                            |
|------------------------------------|-------------------------------|------------------------------------------------------------------------|
| `agent_scrape_request_count_total` | `launch_id`, `type`           | Scrape requests processed by this agent                                |
| `agent_scrape_result_count_total`  | `launch_id`, `type`           | Scrape results sent to proxy                                           |
| `agent_connect_count_total`        | `launch_id`, `type`           | Connection attempts, by outcome                                        |
| `agent_disconnects_total`          | `launch_id`                   | Registered connections that ended                                      |
| `agent_endpoint_advances_total`    | `launch_id`                   | Moves to the next proxy endpoint after a failed connect or a rejected registration |
| `agent_heartbeats_total`           | `launch_id`, `outcome`        | Heartbeats sent: `success`, `evicted`, `transient_failure`             |
| `agent_path_rejections_total`      | `launch_id`, `cause`          | Path registrations the proxy refused, by cause                         |
| `agent_discovery_reconciles_total` | `launch_id`, `outcome`        | Path discovery reconciles: `success`, `failure`                        |
| `agent_scrape_retries_total`       | `launch_id`                   | Retries of a target's request                                          |
| `agent_filter_fail_open_total`     | `launch_id`, `path`, `reason` | Scrapes a filter passed through unfiltered: `non_text`, `invalid_utf8` |
| `agent_dashboard_logins_total`     | `launch_id`, `outcome`        | The agent dashboard's logins: `success`, `failure`, `blocked`          |
| `agent_filter_lines_dropped_total` | `launch_id`, `path`           | Lines removed by the path's metric filter                              |
| `agent_filter_bytes_saved_total`   | `launch_id`, `path`           | Bytes saved before gzip by the metric filter                           |
| `agent_dashboard_scrapes_total`    | `launch_id`                   | Scrapes the proxy's dashboard asked for                                |

**`agent_scrape_request_count_total` type labels:** `success`, `unsuccessful` (the target answered with a non-2xx
status or a response over `agent.http.maxContentLengthMBytes`), `timed_out` (the target didn't answer within the
agent's scrape timeout), `failure` (the fetch failed without an answer, such as a refused connection or a DNS
failure), `invalid_path` (the proxy asked for a path this agent doesn't serve)

**`agent_scrape_result_count_total` type labels:** `non-gzipped`, `gzipped`, `chunked`, `dropped` (a result lost
because the connection to the proxy closed before it was sent)

**`agent_connect_count_total` type labels:** `success` (the proxy registered the agent), `rejected` (the proxy
refused the registration), `failure` (the connect or the registration failed)

The `agent_scrape_*` counters and latency histogram count only Prometheus's scrapes. A scrape the proxy's dashboard
asked for (its scrape-now), which the proxy marks with the scrape request's `dashboard_scrape` field, counts in
`agent_dashboard_scrapes_total` instead, whatever its outcome: the person who asked sees that, and the proxy's
activity feed records it. An agent that predates the field counts a dashboard scrape as Prometheus's.

The `launch_id` label identifies each agent instance: an agent gets a new one each time it starts, and each of
several agents embedded in one JVM has its own, so you can tell their series apart.

The three `agent_filter_*` counters only create series for paths that have a metric filter configured
(`agent.filters`), so they are absent entirely unless metric filtering is in use.

### Histograms

| Metric                                 | Labels                    | Buckets | Description                                    |
|----------------------------------------|---------------------------|---------|------------------------------------------------|
| `agent_scrape_request_latency_seconds` | `launch_id`, `agent_name` | 5ms–90s | Time to fetch metrics from the target endpoint |
| `agent_scrape_queue_wait_seconds`      | `launch_id`               | 5ms–90s | Time a request waited for one of `agent.http.maxConcurrentClients` |

Same bucket boundaries as the proxy latency histogram.

### Gauges

| Metric                      | Labels      | Description                                  |
|-----------------------------|-------------|----------------------------------------------|
| `agent_start_time_seconds`  | `launch_id` | Agent start time (Unix epoch)                |
| `agent_build_info`          | `launch_id`, `agent_name`, `version` | Always 1                    |
| `agent_connected`           | `launch_id` | 1 while connected and registered, else 0     |
| `agent_proxy_endpoint_index` | `launch_id` | Endpoint in use, by its place in `agent.proxy.endpoints`; 0 is the primary |
| `agent_paths`               | `launch_id`, `source` | Paths registered with the proxy: `static`, `discovered` |
| `agent_discovery_last_success_timestamp_seconds` | `launch_id` | Discovery's last successful reconcile (Unix epoch) |
| `agent_dashboard_sessions`  | `launch_id` | The agent dashboard's open pages             |
| `agent_scrape_backlog_size` | `launch_id` | Pending scrape requests queued at this agent |
| `agent_client_cache_size`   | `launch_id` | Number of cached HTTP clients                |

---

## Design Rationale

### Why Histograms Instead of Summaries

Latency metrics use histograms rather than summaries for two reasons:

1. **Aggregation** — `histogram_quantile()` can aggregate across multiple proxy
   or agent instances. Summary quantiles cannot be meaningfully aggregated.
2. **Per-path breakdown** — the `path` label on the proxy histogram lets you
   identify slow scrape targets. This is safe because paths are bounded by
   explicit agent registration (not arbitrary request input).

### Metric Placement

Metrics are incremented at specific points in the request lifecycle:

```
Prometheus ─── HTTP GET ──→ Proxy                        Agent
                             │                             │
                 records the request's start time          │
                             │                             │
                 scrapeQueueWait.observe(age)              │
                 writeScrapeRequest() ── gRPC stream ──→ fetchScrapeUrl()
                             │                     agentLatency.startTimer()
                             │                             │
                             │                     HTTP GET to target
                             │                             │
                             │                     agentLatency.observeDuration()
                             │                     scrapeRequestCount.labelValues(type).inc()
                             │                     scrapeResultCount.labelValues(type).inc()
                             │                             │
                 assignScrapeResults() ←── gRPC ───────────┘
                             │
                 scrapeResponseBytes.observe()
                 scrapeRequestLatency.observe(elapsed)
                 scrapeRequestCount.labelValues(outcome).inc()
                             │
               ←── HTTP response ───
```

### Chunk Metrics

A response whose gzipped body reaches `chunkContentSizeKbs` (default 32 KB) is split into chunks with CRC32
checksums. Two metrics track integrity issues:

- `proxy_chunk_validation_failures_total` — incremented when a chunk or summary
  fails CRC validation. Label `stage` is `chunk` (mid-transfer) or `summary`
  (final validation). These indicate network corruption or agent bugs.
- `proxy_chunked_transfers_abandoned_total` — incremented when a transfer ends
  unfinished: its agent disconnects or is evicted, its stream ends before the
  summary, or its scrape ends first (Prometheus's or the proxy's timeout, or
  Prometheus hanging up). Once per transfer, whichever of the agent's removal,
  the stream's end, the scrape's handler, or the transfer's next message
  removes it.

### Agent Displacement

When a non-consolidated agent registers a path already owned by another agent,
the previous agent is displaced. `proxy_agent_displacement_total` tracks these
events. A displaced agent of another launch stays connected without the path, so
two agents claiming the same exclusive path settle with the later one serving it,
and the counter rises once per path rather than on every reconnect; a redeploy or
a deliberate failover looks the same. The proxy logs, at INFO, each agent left
connected without paths and the agent that took them, and tells the displaced
agent, which takes the path back once no agent serves it; each refused attempt
counts in `proxy_path_rejections_total` as `held_by_another_agent`. A live agent's path is
displaced only by an agent of the same auth identity, as in a redeploy; another
identity's registration is rejected and logged at WARN instead.

---

## Grafana Dashboards

Two dashboards are included in `grafana/`:

| File                     | Dashboard         | Purpose                                          |
|--------------------------|-------------------|--------------------------------------------------|
| `prometheus-proxy.json`  | Prometheus Proxy  | Proxy health, throughput, latency, errors        |
| `prometheus-agents.json` | Prometheus Agents | Agent health, scrape activity, per-agent latency |

`grafana/alerts.yml` holds Prometheus alerting rules for the same metrics: success rate, P99 latency, connected
agents, backlogs, oversized payloads, evictions, refused agent tokens and paths, agent connect failures and
disconnects, failing discovery, an agent off its primary proxy, and the proxy itself being down. Add it to Prometheus'
`rule_files` and tune the thresholds; `make check-rules` validates it with `promtool`.

### Requirements

- Grafana 10.0 or later
- A Prometheus datasource scraping the proxy's and the agents' metrics endpoints, with each agent in its own job (see
  [Prometheus Scrape Configuration](#prometheus-scrape-configuration))

### Import

1. In Grafana, go to **Dashboards > Import**
2. Upload the JSON file or paste its contents
3. Open the dashboard and pick your Prometheus datasource in its **Datasource** drop-down; it starts on Grafana's
   default datasource

### Dashboard Variables

Both dashboards use template variables:

| Variable     | Dashboard | Purpose                                                                                  |
|--------------|-----------|------------------------------------------------------------------------------------------|
| `datasource` | Both      | Prometheus datasource selector                                                           |
| `job`        | Proxy     | The job or jobs the proxy's metrics are scraped under                                    |
| `instance`   | Proxy     | One proxy, when a job scrapes more than one (a high-availability pair)                   |
| `path`       | Proxy     | The paths shown in the Per-Path P99 Latency panel                                        |
| `agent`      | Agents    | The agents shown, by job: every agent panel keys on `job`, so scrape each in its own job |

### Proxy Dashboard Panels

| Section             | Panels                                                                | What to Watch                                                                                             |
|---------------------|-----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| **Overview**        | Uptime, Connected Agents, Registered Paths, Success Rate, Error Count, Version | Success rate dropping below 99% or error count spiking                                           |
| **Throughput**      | Requests/sec by outcome (stacked), Success vs Error rate              | Sudden changes in request volume or error ratio                                                           |
| **Latency**         | P50/P90/P99 percentiles, Queue wait P50/P90/P99, Per-path P99         | P99 creeping up indicates a slow target or network issue; queue wait climbing with it, a busy agent       |
| **Payload**         | Response size percentiles, Encoding distribution                      | Unexpectedly large responses; shift between gzip and plain                                                |
| **Internal State**  | Backlog, Scrape map, Chunk context map, Heartbeat rate                | Growing backlog means agents can't keep up; agents heartbeat only when idle, so zero is normal under load |
| **Errors & Events** | Error breakdown by type, Agent disconnects by reason, Connects/refusals/displacements, Late results | Which error types dominate; frequent evictions indicate connectivity problems   |
| **Security & Rejections** | Auth failures by reason, Path rejections by cause, Binding mismatches by RPC | An agent with a bad token, or a path that keeps being refused                                |
| **Chunk Health**    | Chunk validation failures, Abandoned transfers                        | Any non-zero value warrants investigation                                                                 |
| **Dashboard**       | Logins by outcome, Open pages                                         | Wrong or blocked logins                                                                                   |

### Agents Dashboard Panels

| Section             | Panels                                                             | What to Watch                                                                                                                 |
|---------------------|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| **Overview**        | Agent count, Connected, Not on primary, Total scrape rate, Uptime and Versions tables | Unexpected agent count changes; an agent not connected, or on a backup endpoint                       |
| **Connections**     | Successful connections, failures and rejections, Connected by agent, Disconnects and endpoint advances, Heartbeats by outcome | Failure spikes indicate proxy or network issues; a rejected connect is the proxy refusing the agent; an agent scraped through the proxy can't be read while it can't connect, so watch its job's `up` |
| **Scrape Activity** | Request rate by agent, Unsuccessful scrapes by agent, Result types, Retries, Filter fail-open | Imbalanced load; unsuccessful scrapes point at a failing target; any `dropped` result is a scrape lost to a closed connection |
| **Paths & Discovery** | Registered paths by source, Path rejections by cause, Discovery reconciles, Since last discovery | A path the proxy keeps refusing; a discovery file that fails to read                                  |
| **Latency**         | P50/P90/P99 overall, Queue wait P50/P90/P99, P99 per agent         | Per-agent latency outliers point to slow targets; queue wait climbing means too few `agent.http.maxConcurrentClients`              |
| **Agent Internals** | Backlog size, HTTP client cache size                               | Growing backlog means the agent is falling behind                                                                             |
| **Dashboard**       | Logins by outcome, Open pages                                      | Wrong or blocked logins to an agent's dashboard                                                                               |

---

## Prometheus Scrape Configuration

The proxy runs next to Prometheus, so Prometheus scrapes its metrics port directly. The agents sit behind firewalls,
so Prometheus scrapes each agent's metrics through the proxy, at a path of the agent's own. Since 4.7.0, the agent
serves them at that path itself, read in-process, with no metrics port to open:

```hocon
agent {
  metrics.proxyPath = cluster_a_agent_metrics
}
```

Or the agent turns on its metrics port and scrapes it through a path of its own, as it did before 4.7.0:

```hocon
agent {
  metrics.enabled = true
  pathConfigs: [
    # ... the agent's targets, plus its own metrics, from its own port:
    { name: "Agent metrics", path: cluster_a_agent_metrics, url: "http://localhost:8083/metrics" }
  ]
}
```

```yaml
scrape_configs:
  - job_name: 'prometheus-proxy'
    static_configs:
      - targets: [ 'proxy-host:8082' ]

  # One job per agent, each scraping that agent's metrics path through the proxy
  - job_name: 'agent-cluster-a'
    metrics_path: /cluster_a_agent_metrics
    static_configs:
      - targets: [ 'proxy-host:8080' ]
```

Give each agent its own job: the Agents dashboard tells agents apart by `job`. `grafana/alerts.yml`'s `ProxyDown` rule
expects the proxy's job to be named `prometheus-proxy`. Adjust hostnames and ports to match your deployment.

---

## Useful PromQL Queries

**Scrape success rate (last 5 minutes):**

```promql
sum(rate(proxy_scrape_requests_total{type="success"}[5m]))
  / sum(rate(proxy_scrape_requests_total[5m])) * 100
```

**P99 scrape latency:**

```promql
histogram_quantile(0.99,
  sum by (le) (rate(proxy_scrape_request_latency_seconds_bucket[5m]))
)
```

**P99 latency per path:**

```promql
histogram_quantile(0.99,
  sum by (le, path) (rate(proxy_scrape_request_latency_seconds_bucket[5m]))
)
```

**Median response size:**

```promql
histogram_quantile(0.5,
  sum by (le) (rate(proxy_scrape_response_bytes_bucket[5m]))
)
```

**Error rate by type:**

```promql
sum by (type) (rate(proxy_scrape_requests_total{type!="success"}[5m]))
```

**Agent scrape latency by agent name:**

```promql
histogram_quantile(0.99,
  sum by (le, agent_name) (rate(agent_scrape_request_latency_seconds_bucket[5m]))
)
```

**Proxy uptime:**

```promql
time() - proxy_start_time_seconds
```
