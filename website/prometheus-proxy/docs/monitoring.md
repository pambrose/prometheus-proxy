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

---

## Proxy Metrics

### Counters

| Metric                                    | Labels  | Description                                     |
|:------------------------------------------|:--------|:------------------------------------------------|
| `proxy_scrape_requests`                   | `type`  | Scrape request outcomes (see below)             |
| `proxy_connect_count`                     | --      | Agent connection count                          |
| `proxy_eviction_count`                    | --      | Stale agent evictions                           |
| `proxy_heartbeat_count`                   | --      | Heartbeats received from agents                 |
| `proxy_chunk_validation_failures_total`   | `stage` | Chunk integrity failures (`chunk` or `summary`) |
| `proxy_chunked_transfers_abandoned_total` | --      | Chunked transfers abandoned mid-stream          |
| `proxy_agent_displacement_total`          | --      | Path registrations that displaced another agent |

**`proxy_scrape_requests` type labels:**

| Value                   | Meaning                                                             |
|:------------------------|:---------------------------------------------------------------------|
| `success`               | Scrape completed successfully                                       |
| `timed_out`             | Agent did not answer within the proxy's `scrapeRequestTimeoutSecs`  |
| `upstream_timed_out`    | Agent answered, but its own fetch of the target timed out (408/504) |
| `no_agents`             | No agents registered for the requested path                         |
| `invalid_path`          | Requested path is empty or unrecognized                             |
| `agent_disconnected`    | Agent stream closed before response was received                    |
| `missing_results`       | Internal error: results object was null                             |
| `path_not_found`        | Agent has no registration for the target path (404)                 |
| `upstream_error`        | Target returned a non-2xx status not covered above, e.g. 5xx        |
| `content_too_large`     | Target response exceeded the agent's content-length limit (413)     |
| `payload_too_large`     | Unzipped content exceeded the proxy's size limit                    |
| `invalid_gzip`          | Gzip decompression failed                                           |
| `proxy_not_running`     | Proxy is shutting down                                              |
| `invalid_agent_context` | All agents for the path are in an invalid state                     |

!!! tip "Proxy-side and agent-side pairs"

    `timed_out` means the proxy gave up waiting on the agent; `upstream_timed_out` means the agent
    answered promptly to report that the *target* was slow. Since `agent.scrapeTimeoutSecs` (15s)
    is well under the proxy's `scrapeRequestTimeoutSecs` (90s), `upstream_timed_out` is the one you
    will usually see for a slow endpoint.

    Likewise `content_too_large` is the agent's `maxContentLengthMBytes` limit, while
    `payload_too_large` is the proxy's unzip limit. Both surface as HTTP 413.

### Histograms

| Metric                                 | Labels             | Description                               |
|:---------------------------------------|:-------------------|:------------------------------------------|
| `proxy_scrape_request_latency_seconds` | `path`             | End-to-end scrape latency                 |
| `proxy_scrape_response_bytes`          | `path`, `encoding` | Response payload size after decompression |

Latency buckets: 5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s

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

| Metric                       | Labels              | Description                                        |
|:-----------------------------|:--------------------|:---------------------------------------------------|
| `agent_scrape_request_count` | `launch_id`, `type` | Scrape requests processed                          |
| `agent_scrape_result_count`  | `launch_id`, `type` | Results sent (`non-gzipped`, `gzipped`, `chunked`) |
| `agent_connect_count`        | `launch_id`, `type` | Connection attempts (`success`, `failure`)         |
| `agent_filter_lines_dropped` | `launch_id`, `path` | Lines removed by the path's metric filter          |
| `agent_filter_bytes_saved`   | `launch_id`, `path` | Bytes saved before gzip by the metric filter       |

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
