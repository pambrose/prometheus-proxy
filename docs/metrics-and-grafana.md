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

Default endpoint: `http://agent-host:8083/metrics`

### JVM and gRPC Metrics

Both components support optional JVM and gRPC metrics exports:

```hocon
proxy.metrics {
  standardExportsEnabled = false
  memoryPoolsExportsEnabled = false
  garbageCollectorExportsEnabled = false
  threadExportsEnabled = false
  classLoadingExportsEnabled = false
  versionInfoExportsEnabled = false

  grpc {
    metricsEnabled = false
    allMetricsReported = false
  }
}
```

The same options are available under `agent.metrics`.

---

## Proxy Metrics

### Counters

| Metric                                    | Labels  | Description                                      |
|-------------------------------------------|---------|--------------------------------------------------|
| `proxy_scrape_requests`                   | `type`  | Scrape request outcomes. See label values below. |
| `proxy_connect_count`                     | —       | Agent connection count                           |
| `proxy_eviction_count`                    | —       | Stale agent evictions                            |
| `proxy_heartbeat_count`                   | —       | Heartbeats received from agents                  |
| `proxy_chunk_validation_failures_total`   | `stage` | Chunk integrity failures (`chunk` or `summary`)  |
| `proxy_chunked_transfers_abandoned_total` | —       | Chunked transfers abandoned mid-stream           |
| `proxy_agent_displacement_total`          | —       | Path registrations that displaced another agent  |

**`proxy_scrape_requests` type labels:**

| Value                   | Meaning                                                  |
|-------------------------|----------------------------------------------------------|
| `success`               | Scrape completed successfully                            |
| `timed_out`             | Agent did not respond within `scrapeRequestTimeoutSecs`  |
| `no_agents`             | No agents registered for the requested path              |
| `invalid_path`          | Requested path is empty or unrecognized                  |
| `agent_disconnected`    | Agent stream closed before response was received         |
| `missing_results`       | Internal error: results object was null                  |
| `path_not_found`        | Agent returned a non-200 status for the target           |
| `payload_too_large`     | Unzipped content exceeded `maxUnzippedContentSizeMBytes` |
| `invalid_gzip`          | Gzip decompression failed                                |
| `proxy_not_running`     | Proxy is shutting down                                   |
| `invalid_agent_context` | All agents for the path are in an invalid state          |

### Histograms

| Metric                                 | Labels             | Buckets  | Description                                                 |
|----------------------------------------|--------------------|----------|-------------------------------------------------------------|
| `proxy_scrape_request_latency_seconds` | `path`             | 5ms–10s  | End-to-end scrape latency from request creation to response |
| `proxy_scrape_response_bytes`          | `path`, `encoding` | 1KB–10MB | Response payload size after decompression                   |

The `encoding` label is `gzipped` or `plain`.

Latency buckets: `.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10` seconds.

Response size buckets: `1KB, 10KB, 100KB, 500KB, 1MB, 5MB, 10MB`.

### Gauges

| Metric                                | Description                                    |
|---------------------------------------|------------------------------------------------|
| `proxy_start_time_seconds`            | Proxy start time (Unix epoch)                  |
| `proxy_agent_map_size`                | Number of connected agents                     |
| `proxy_path_map_size`                 | Number of registered scrape paths              |
| `proxy_scrape_map_size`               | Number of in-flight scrape requests            |
| `proxy_chunk_context_map_size`        | Number of in-flight chunked transfers          |
| `proxy_cumulative_agent_backlog_size` | Total queued scrape requests across all agents |

Gauges use lazy sampling (`SamplerGaugeCollector`) — values are read from
live data structures on each Prometheus scrape, not pushed on every state change.

---

## Agent Metrics

### Counters

| Metric                       | Labels              | Description                             |
|------------------------------|---------------------|-----------------------------------------|
| `agent_scrape_request_count` | `launch_id`, `type` | Scrape requests processed by this agent |
| `agent_scrape_result_count`  | `launch_id`, `type` | Scrape results sent to proxy            |
| `agent_connect_count`        | `launch_id`, `type` | Connection attempts to the proxy        |

**`agent_scrape_result_count` type labels:** `non-gzipped`, `gzipped`, `chunked`

**`agent_connect_count` type labels:** `success`, `failure`

The `launch_id` label uniquely identifies each agent process lifetime, allowing
you to distinguish metrics from agent restarts.

### Histograms

| Metric                                 | Labels                    | Buckets | Description                                    |
|----------------------------------------|---------------------------|---------|------------------------------------------------|
| `agent_scrape_request_latency_seconds` | `launch_id`, `agent_name` | 5ms–10s | Time to fetch metrics from the target endpoint |

Same bucket boundaries as the proxy latency histogram.

### Gauges

| Metric                      | Labels      | Description                                  |
|-----------------------------|-------------|----------------------------------------------|
| `agent_start_time_seconds`  | `launch_id` | Agent start time (Unix epoch)                |
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
                 scrapeRequestLatency.startTimer()          │
                             │                             │
                 writeScrapeRequest() ── gRPC stream ──→ fetchScrapeUrl()
                             │                     agentLatency.startTimer()
                             │                             │
                             │                     HTTP GET to target
                             │                             │
                             │                     agentLatency.observeDuration()
                             │                     scrapeResultCount.inc()
                             │                             │
                 assignScrapeResults() ←── gRPC ───────────┘
                             │
                 scrapeResponseBytes.observe()
                 scrapeRequestLatency.observeDuration()
                 scrapeRequestCount.labels(outcome).inc()
                             │
               ←── HTTP response ───
```

### Chunk Metrics

Large responses (> `chunkContentSizeKbs`) are split into chunks with CRC32
checksums. Two metrics track integrity issues:

- `proxy_chunk_validation_failures_total` — incremented when a chunk or summary
  fails CRC validation. Label `stage` is `chunk` (mid-transfer) or `summary`
  (final validation). These indicate network corruption or agent bugs.
- `proxy_chunked_transfers_abandoned_total` — incremented when an agent
  disconnects mid-transfer (stream terminates before the summary message).

### Agent Displacement

When a non-consolidated agent registers a path already owned by another agent,
the previous agent is displaced. `proxy_agent_displacement_total` tracks these
events. Frequent displacement indicates a configuration issue (two agents
claiming the same exclusive path) or deliberate failover.

---

## Grafana Dashboards

Two dashboards are included in `grafana/`:

| File                     | Dashboard         | Purpose                                          |
|--------------------------|-------------------|--------------------------------------------------|
| `prometheus-proxy.json`  | Prometheus Proxy  | Proxy health, throughput, latency, errors        |
| `prometheus-agents.json` | Prometheus Agents | Agent health, scrape activity, per-agent latency |

### Requirements

- Grafana 10.0 or later
- A Prometheus datasource scraping both proxy and agent metrics endpoints

### Import

1. In Grafana, go to **Dashboards > Import**
2. Upload the JSON file or paste its contents
3. Select your Prometheus datasource when prompted

### Dashboard Variables

Both dashboards use template variables:

| Variable     | Dashboard | Purpose                                 |
|--------------|-----------|-----------------------------------------|
| `datasource` | Both      | Prometheus datasource selector          |
| `path`       | Proxy     | Filter panels by registered scrape path |
| `agent`      | Agents    | Filter panels by agent job name         |

### Proxy Dashboard Panels

| Section            | Panels                                                                | What to Watch                                                                             |
|--------------------|-----------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| **Overview**       | Uptime, Connected Agents, Registered Paths, Success Rate, Error Count | Success rate dropping below 99% or error count spiking                                    |
| **Throughput**     | Requests/sec by outcome (stacked), Success vs Error rate              | Sudden changes in request volume or error ratio                                           |
| **Latency**        | P50/P90/P99 percentiles, Per-path P99                                 | P99 creeping up indicates a slow target or network issue                                  |
| **Payload**        | Response size percentiles, Encoding distribution                      | Unexpectedly large responses; shift between gzip and plain                                |
| **Internal State** | Backlog, Scrape map, Chunk context map, Heartbeat rate                | Growing backlog means agents can't keep up; zero heartbeats means agents are disconnected |
| **Errors**         | Error breakdown by type, Agent events (connect/evict/displace)        | Which error types dominate; frequent evictions indicate connectivity problems             |
| **Chunk Health**   | Chunk validation failures, Abandoned transfers                        | Any non-zero value warrants investigation                                                 |

### Agents Dashboard Panels

| Section             | Panels                                                   | What to Watch                                      |
|---------------------|----------------------------------------------------------|----------------------------------------------------|
| **Overview**        | Agent count, Total scrape rate, Uptime table             | Unexpected agent count changes                     |
| **Connections**     | Connection success/failure rate per agent                | Failure spikes indicate proxy or network issues    |
| **Scrape Activity** | Request rate by agent, Result types (gzip/plain/chunked) | Imbalanced load across agents; unexpected chunking |
| **Latency**         | P50/P90/P99 overall, P99 per agent                       | Per-agent latency outliers point to slow targets   |
| **Internals**       | Backlog size, HTTP client cache size                     | Growing backlog means the agent is falling behind  |

---

## Prometheus Scrape Configuration

Add scrape jobs for the proxy and agent metrics endpoints:

```yaml
scrape_configs:
  - job_name: 'prometheus-proxy'
    metrics_path: /metrics
    static_configs:
      - targets: [ 'proxy-host:8082' ]

  - job_name: 'prometheus-agent'
    metrics_path: /metrics
    static_configs:
      - targets: [ 'agent-host:8083' ]
```

Adjust hostnames and ports to match your deployment.

---

## Useful PromQL Queries

**Scrape success rate (last 5 minutes):**

```promql
sum(rate(proxy_scrape_requests{type="success"}[5m]))
  / sum(rate(proxy_scrape_requests[5m])) * 100
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
sum by (type) (rate(proxy_scrape_requests{type!="success"}[5m]))
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
