---
icon: lucide/activity
---

# Monitoring & Observability

Both the proxy and agent expose their own operational metrics and admin endpoints.

## Enabling Metrics

Metrics are disabled by default. Enable them via CLI, environment variables, or config file.

=== "Proxy"

    ```hocon
    --8<-- "MonitoringExamples.txt:enable-metrics-proxy"
    ```

    Or via CLI: `--metrics` or `METRICS_ENABLED=true`

    Default endpoint: `http://proxy-host:8082/metrics`

=== "Agent"

    ```hocon
    --8<-- "MonitoringExamples.txt:enable-metrics-agent"
    ```

    Or via CLI: `--metrics` or `METRICS_ENABLED=true`

    Default endpoint: `http://agent-host:8083/metrics`

### Scraping Internal Metrics

Add these scrape jobs to your `prometheus.yml`:

```yaml
--8<-- "PrometheusConfigs.txt:metrics-scrape-config"
```

### JVM and gRPC Metrics

Both components support optional JVM and gRPC metrics:

```hocon
--8<-- "MonitoringExamples.txt:jvm-metrics-config"
```

The same options are available under `agent.metrics`.

The JVM metrics come from the Prometheus Java client 1.x. Since the move to it, the memory metrics put the unit
last (`jvm_memory_used_bytes`, not `jvm_memory_bytes_used`; `jvm_memory_pool_used_bytes`, not
`jvm_memory_pool_bytes_used`; the same for `committed`, `max` and `init`), `jvm_info` is `jvm_runtime_info`, and
`memoryPoolsExportsEnabled` also adds `jvm_memory_pool_allocated_bytes_total`.

---

## Proxy Metrics

### Counters

| Metric                                    | Labels  | Description                                     |
|:------------------------------------------|:--------|:------------------------------------------------|
| `proxy_scrape_requests_total`             | `type`  | Scrape request outcomes (see below)             |
| `proxy_connect_count_total`               | --      | Agent connection count                          |
| `proxy_eviction_count_total`              | --      | Stale agent evictions                           |
| `proxy_heartbeat_count_total`             | --      | Heartbeats received from agents                 |
| `proxy_chunk_validation_failures_total`   | `stage` | Chunk integrity failures (`chunk` or `summary`) |
| `proxy_chunked_transfers_abandoned_total` | --      | Chunked transfers abandoned mid-stream          |
| `proxy_agent_displacement_total`          | --      | Path registrations that displaced another agent |

**`proxy_scrape_requests_total` type labels:**

| Value                   | Meaning                                                             |
|:------------------------|:---------------------------------------------------------------------|
| `success`               | Scrape completed successfully                                       |
| `timed_out`             | Agent did not answer within the proxy's `scrapeRequestTimeoutSecs`  |
| `upstream_timed_out`    | Agent answered, but its own fetch of the target timed out (408/504) |
| `no_agents`             | The path's registration had no agents left to send the scrape to    |
| `invalid_path`          | No agent has registered the requested path                          |
| `missing_path`          | The request had no path (a scrape of `/`)                           |
| `agent_disconnected`    | Agent disconnected, evicted, or displaced before answering (503)    |
| `agent_backlog_full`    | Agent's queue was full (2 × `scrapeRequestBacklogUnhealthySize`)    |
| `proxy_in_flight_limit` | In-flight scrapes across agents hit `maxInFlightScrapeRequests`     |
| `client_cancelled`      | Prometheus hung up before the agent answered                        |
| `missing_results`       | Internal error: results object was null                             |
| `path_not_found`        | Agent has no registration for the target path (404)                 |
| `upstream_error`        | Target returned a non-2xx status not covered above, e.g. 5xx        |
| `invalid_response`      | Agent answered, but a chunk or summary failed validation (502)      |
| `content_too_large`     | Target response exceeded the agent's content-length limit (413)     |
| `payload_too_large`     | Unzipped content exceeded the proxy's size limit                    |
| `invalid_gzip`          | Gzip decompression failed                                           |
| `proxy_stopped`         | Proxy is shutting down (503)                                        |
| `invalid_agent_context` | All agents for the path are in an invalid state                     |

!!! tip "Proxy-side and agent-side pairs"

    `timed_out` means the proxy gave up waiting on the agent; `upstream_timed_out` means the agent
    answered promptly to report that the *target* was slow. Since `agent.scrapeTimeoutSecs` (15s)
    is well under the proxy's `scrapeRequestTimeoutSecs` (90s), `upstream_timed_out` is the one you
    will usually see for a slow endpoint. The agent also stops at Prometheus's own `scrape_timeout` when that is
    shorter.

    Likewise `content_too_large` is the agent's `maxContentLengthMBytes` limit, while
    `payload_too_large` is the proxy's unzip limit. Both surface as HTTP 413.

    `upstream_error` is always the target's own status. A scrape the proxy fails itself names why
    instead: `agent_disconnected` (look at the agent's connection, not the target), `proxy_stopped`,
    or `invalid_response`.

### Histograms

| Metric                                 | Labels             | Description                               |
|:---------------------------------------|:-------------------|:------------------------------------------|
| `proxy_scrape_request_latency_seconds` | `path`, `outcome`  | End-to-end scrape latency                 |
| `proxy_scrape_response_bytes`          | `path`, `encoding` | Response payload size after decompression |

A path's series are removed when its last registration goes away, whether it is unregistered or its agent disconnects, so a retired path stops appearing on `/metrics`. A scrape still in flight when that happens is not recorded, so it cannot bring the series back.

The latency histogram's `outcome` label takes the `proxy_scrape_requests_total` `type` values above, so latency can be split
by result.

Latency buckets: 5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s, 15s, 30s, 60s, 90s

Response size buckets: 1KB, 10KB, 100KB, 500KB, 1MB, 5MB, 10MB

### Gauges

| Metric                                | Description                                    |
|:--------------------------------------|:-----------------------------------------------|
| `proxy_start_time_seconds`            | Proxy start time (Unix epoch)                  |
| `proxy_agent_map_size`                | Number of connected agents                     |
| `proxy_path_map_size`                 | Number of registered scrape paths              |
| `proxy_scrape_map_size`               | Number of in-flight scrape requests            |
| `proxy_chunk_context_map_size`        | Number of in-flight chunked transfers          |
| `proxy_cumulative_agent_backlog_size` | Total queued scrape requests across all agents |

---

## Agent Metrics

### Counters

| Metric                             | Labels              | Description                                        |
|:-----------------------------------|:--------------------|:---------------------------------------------------|
| `agent_scrape_request_count_total` | `launch_id`, `type` | Scrape requests processed                          |
| `agent_scrape_result_count_total`  | `launch_id`, `type` | Results sent (`non-gzipped`, `gzipped`, `chunked`) |
| `agent_connect_count_total`        | `launch_id`, `type` | Connection attempts (`success`, `failure`)         |
| `agent_filter_lines_dropped_total` | `launch_id`, `path` | Lines removed by the path's metric filter          |
| `agent_filter_bytes_saved_total`   | `launch_id`, `path` | Bytes saved before gzip by the metric filter       |

The `launch_id` label uniquely identifies each agent process lifetime.

The two `agent_filter_*` counters only create series for paths that have a filter configured, so
they are absent entirely unless [metric filtering](configuration/agent.md#metric-filtering) is in
use. See that section for what the filter does and does not drop.

### Histograms

| Metric                                 | Labels                    | Description                        |
|:---------------------------------------|:--------------------------|:-----------------------------------|
| `agent_scrape_request_latency_seconds` | `launch_id`, `agent_name` | Time to fetch from target endpoint |

### Gauges

| Metric                      | Labels      | Description                    |
|:----------------------------|:------------|:-------------------------------|
| `agent_start_time_seconds`  | `launch_id` | Agent start time (Unix epoch)  |
| `agent_scrape_backlog_size` | `launch_id` | Pending scrape requests queued |
| `agent_client_cache_size`   | `launch_id` | Number of cached HTTP clients  |

---

## Metric Flow

```text
Prometheus --- HTTP GET ---> Proxy                        Agent
                              |                             |
                  latency.startTimer()                      |
                              |                             |
                  writeScrapeRequest() -- gRPC stream --> fetchScrapeUrl()
                              |                     agentLatency.startTimer()
                              |                             |
                              |                     HTTP GET to target
                              |                             |
                              |                     agentLatency.observeDuration()
                              |                     scrapeResultCount.inc()
                              |                             |
                  assignScrapeResults() <-- gRPC -----------+
                              |
                  responseBytes.observe()
                  latency.observeDuration()
                  scrapeRequestCount.labels(outcome).inc()
                              |
                <-- HTTP response ---
```

---

## PromQL Examples

### Scrape Success Rate

```promql
--8<-- "MonitoringExamples.txt:promql-success-rate"
```

### P99 Scrape Latency

```promql
--8<-- "MonitoringExamples.txt:promql-p99-latency"
```

### P99 Latency Per Path

```promql
--8<-- "MonitoringExamples.txt:promql-p99-per-path"
```

### Error Rate by Type

```promql
--8<-- "MonitoringExamples.txt:promql-error-rate"
```

### Agent Latency by Name

```promql
--8<-- "MonitoringExamples.txt:promql-agent-latency"
```

---

## Admin Endpoints

```text
--8<-- "MonitoringExamples.txt:admin-endpoints"
```

Enable admin endpoints:

```bash
java -jar prometheus-proxy.jar --admin
java -jar prometheus-agent.jar --admin
```

---

## Grafana Dashboards

```text
--8<-- "MonitoringExamples.txt:grafana-import"
```

### Proxy Dashboard

Key panels to monitor:

| Section            | What to Watch                                            |
|:-------------------|:---------------------------------------------------------|
| **Overview**       | Success rate dropping below 99%, error count spikes      |
| **Throughput**     | Sudden changes in request volume or error ratio          |
| **Latency**        | P99 creeping up indicates slow targets or network issues |
| **Payload**        | Unexpectedly large responses, gzip vs plain distribution |
| **Internal State** | Growing backlog means agents can't keep up               |
| **Errors**         | Which error types dominate, frequent evictions           |

### Agents Dashboard

Key panels to monitor:

| Section             | What to Watch                                     |
|:--------------------|:--------------------------------------------------|
| **Overview**        | Unexpected agent count changes                    |
| **Connections**     | Failure spikes indicate proxy or network issues   |
| **Scrape Activity** | Imbalanced load across agents                     |
| **Latency**         | Per-agent latency outliers point to slow targets  |
| **Internals**       | Growing backlog means the agent is falling behind |
