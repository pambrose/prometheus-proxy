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

    Default endpoint: `http://agent-host:8083/metrics`. Since 4.7.0, an agent can serve its metrics through the
    proxy instead, with no port (see [Scraping Internal Metrics](#scraping-internal-metrics)).

### Scraping Internal Metrics

The proxy runs next to Prometheus, so Prometheus scrapes its metrics port directly. The agents sit behind firewalls,
where Prometheus can't reach their ports, so it scrapes each agent's metrics through the proxy, at a path of the
agent's own, one job per agent (the Agents dashboard tells agents apart by `job`). Since 4.7.0, the agent serves them
at that path itself, read in-process, with no metrics port to open (`agent.metrics.proxyPath`):

```hocon
--8<-- "MonitoringExamples.txt:agent-metrics-proxy-path"
```

Or via CLI: `--metrics_proxy_path cluster_a_agent_metrics` or `METRICS_PROXY_PATH=cluster_a_agent_metrics`. Its
labels, `agent.metrics.proxyPathLabels`, are the path's labels in the proxy's
[service discovery](service-discovery.md), as a `pathConfigs` entry's are, and only the config sets them. The path is
checked as a `pathConfigs` entry is: one another path also names, or that has a character the proxy refuses, stops the
agent at startup.

Give each agent a path of its own, as each is a job of its own. Agents that share a config file, such as replicas or
the agents of a [consolidated](advanced.md#consolidated-mode) path, would otherwise name the same one, and the proxy
would serve one agent there, or, consolidated, one job for them all. Set each one's path with `METRICS_PROXY_PATH` or
`--metrics_proxy_path`, or build it in the shared file from an environment variable each agent has its own value of,
such as the host name a container gets: `proxyPath = ${HOSTNAME}_agent_metrics`.

Then add the scrape jobs to your `prometheus.yml`:

```yaml
--8<-- "PrometheusConfigs.txt:metrics-scrape-config"
```

Before 4.7.0, an agent served them only on its metrics port, so it scraped its own port through a path of its own.
That still works, and suits an agent whose port is on for a reason of its own
(see [Port or Proxy Path](#port-or-proxy-path)):

```hocon
agent {
  metrics.enabled = true
  pathConfigs: [
    # ... the agent's targets, plus its own metrics, from its own port:
    { name: "Agent metrics", path: cluster_a_agent_metrics, url: "http://localhost:8083/metrics" }
  ]
}
```

An [embedded agent](embedded-agent.md) sets the path with its builder's `agentMetricsAt(path)`, or with the
`metrics-proxy-path` property of the [Spring Boot Starter](spring-boot-starter.md) or the
[Ktor Plugin](ktor-plugin.md). It serves its own `agent_*` series there, and leaves the JVM's metrics to the app's own
path (see [The Agent's Own Metrics](embedded-agent.md#the-agents-own-metrics)).

### Port or Proxy Path

An agent serves its metrics on its metrics port, at its proxy path, or both:

|                          | Metrics port                                                                                   | Proxy path                                                                                  |
|:-------------------------|:-----------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------|
| Turned on by             | `agent.metrics.enabled`, `--metrics`, `METRICS_ENABLED`                                        | `agent.metrics.proxyPath`, `--metrics_proxy_path`, `METRICS_PROXY_PATH`                     |
| Read                     | At `http://agent-host:8083/metrics`, by whatever can reach the agent's host                    | Through the proxy, as the agent's targets are, while the agent is connected                 |
| Opens a port             | Yes                                                                                            | No                                                                                          |
| A standalone agent's     | Its `agent_*` metrics, and the [JVM metrics](#jvm-metrics) its `agent.metrics` settings turn on | The same                                                                                    |
| An embedded agent's      | Everything in its JVM's default registry, the app's metrics too, when they're kept there       | Its own `agent_*` series alone: the app's path carries the JVM's                            |

Prometheus, outside the firewall, can read only the proxy path, so an agent that serves its metrics only to it needs no
port. The port is for something inside the firewall:

- **A scraper on the agent's own network,** such as a Prometheus, Grafana Alloy, or an OpenTelemetry Collector there,
  which reads the agent whether or not it reaches the proxy.
- **An agent that can't reach the proxy.** What the proxy serves of an agent stops when the agent's link does: an
  agent that never connects, for a wrong endpoint, token, or certificate, one whose registration fails, and every agent
  while the proxy, or the network between, is down. Prometheus then sees only the path go: its job's `up` goes to 0,
  and `agent_connected` can't report 0 (see [Agent Metrics](#agent-metrics)). The agent keeps counting,
  `agent_connect_count_total{type="failure"}` among them, but nothing outside reads it until it reconnects; a scraper
  inside reads it throughout.

Without such a scraper, leave the port off: nothing outside the firewall can reach it, and the proxy's own metrics and
[dashboard](web-dashboard.md), and the job's `up`, report an agent that's gone. Both can be on at once, serving the same
series, so have each scraper read one of them.

### JVM Metrics

Both components support optional JVM metrics:

```hocon
--8<-- "MonitoringExamples.txt:jvm-metrics-config"
```

The same options are available under `agent.metrics`. An agent serves the groups they turn on on its metrics port, and
a standalone agent at its proxy path too, with the port off or on (see [Port or Proxy Path](#port-or-proxy-path)). An
embedded agent's JVM metrics are its app's, so with its port off the options do nothing.

The JVM metrics come from the Prometheus Java client 1.x. Since the move to it, the memory metrics put the unit
last (`jvm_memory_used_bytes`, not `jvm_memory_bytes_used`; `jvm_memory_pool_used_bytes`, not
`jvm_memory_pool_bytes_used`; the same for `committed`, `max` and `init`), `jvm_info` is `jvm_runtime_info`, and
`memoryPoolsExportsEnabled` also adds `jvm_memory_pool_allocated_bytes_total`.

Since 4.3.0, three more groups are available, also off by default:

- `bufferPoolExportsEnabled` adds `jvm_buffer_pool_used_bytes`, `jvm_buffer_pool_capacity_bytes` and
  `jvm_buffer_pool_used_buffers`, by `pool` (`direct` and `mapped`): the memory the JVM's NIO buffer pools hold
  outside the heap.
- `compilationExportsEnabled` adds `jvm_compilation_time_seconds_total`, the time the JIT compiler has spent.
- `nativeMemoryExportsEnabled` adds `jvm_native_memory_reserved_bytes` and `jvm_native_memory_committed_bytes`, by
  `pool`. It reads the JVM's Native Memory Tracking, which is off unless the JVM starts with
  `-XX:NativeMemoryTracking=summary` (for the Docker images, set `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary`).
  Without it the flag exports nothing, and tracking adds some overhead of its own.

## Proxy Metrics

### Counters

| Metric                                    | Labels    | Description                                                     |
|:------------------------------------------|:----------|:----------------------------------------------------------------|
| `proxy_scrape_requests_total`             | `type`    | Scrape request outcomes (see below)                             |
| `proxy_connect_count_total`               | —         | Agent connects the proxy accepted                               |
| `proxy_agent_connect_refusals_total`      | `reason`  | Agent connects refused (see below)                              |
| `proxy_agent_disconnects_total`           | `reason`  | Agents the proxy removed: `eviction`, `termination`, `stream_terminated` |
| `proxy_eviction_count_total`              | —         | Stale agent evictions                                           |
| `proxy_heartbeat_count_total`             | —         | Heartbeats the proxy accepted                                   |
| `proxy_agent_auth_failures_total`         | `reason`  | Agent calls refused for their token: `missing_token`, `unknown_token` |
| `proxy_path_rejections_total`             | `cause`   | Path registrations refused, by cause (see below)                |
| `proxy_binding_mismatches_total`          | `rpc`     | Agent calls naming another agent or identity than their connection's |
| `proxy_late_scrape_results_total`         | —         | Scrape results that arrived after their scrape had ended        |
| `proxy_chunk_validation_failures_total`   | `stage`   | Chunk integrity failures (`chunk` or `summary`)                 |
| `proxy_chunked_transfers_abandoned_total` | —         | Chunked transfers whose scrape ended before they finished       |
| `proxy_agent_displacement_total`          | —         | Path registrations that displaced another agent                 |
| `proxy_dashboard_logins_total`            | `outcome` | Dashboard logins: `success`, `failure`, `blocked`               |

**`proxy_scrape_requests_total` type labels:**

| Value                   | Meaning                                                             |
|:------------------------|:---------------------------------------------------------------------|
| `success`               | Scrape completed successfully                                       |
| `timed_out`             | Agent did not answer within the proxy's `scrapeRequestTimeoutSecs`  |
| `upstream_timed_out`    | The agent's fetch timed out, or the target answered 408 or 504      |
| `invalid_path`          | No agent has registered the requested path                          |
| `missing_path`          | The request had no path (a scrape of `/`)                           |
| `agent_disconnected`    | Agent disconnected, evicted, or displaced before answering (503)    |
| `agent_backlog_full`    | Agent's queue was full (2 × `scrapeRequestBacklogUnhealthySize`)    |
| `proxy_in_flight_limit` | In-flight scrapes across agents hit `maxInFlightScrapeRequests`     |
| `client_cancelled`      | Prometheus hung up before the agent answered                        |
| `missing_results`       | Internal error: results object was null                             |
| `path_not_found`        | The agent doesn't serve the path, or the target answered 404        |
| `upstream_error`        | Any other non-2xx from the target, or an unreachable target (503)   |
| `invalid_response`      | Agent answered, but a chunk failed validation or its status can't be served (502) |
| `content_too_large`     | Over the agent's content-length limit, or the target answered 413   |
| `payload_too_large`     | Unzipped content exceeded the proxy's size limit                    |
| `invalid_gzip`          | Gzip decompression failed                                           |
| `proxy_stopped`         | Proxy is shutting down (503)                                        |
| `invalid_agent_context` | All agents for the path are in an invalid state                     |
| `internal_error`        | The scrape threw, and the client got a 500                          |

**Agents and paths the proxy refused:**

- `proxy_agent_connect_refusals_total`'s `reason` is `identity_limit`, a connect past
  `proxy.internal.maxAgentsPerIdentity` (with `transportFilterDisabled`), or `transport_filter_mismatch`, an agent and
  proxy that disagree on `transportFilterDisabled`. A refused connect isn't in `proxy_connect_count_total`.
- `proxy_path_rejections_total`'s `cause` is the reason the agent is told, lowercased: `held_by_another_identity`,
  `consolidation_mismatch`, `not_authorized`, `invalid_path`, `invalid_agent`, `binding_mismatch`,
  `path_limit_reached`, or `held_by_another_agent`, a displaced agent's attempt to take back a path another agent still
  serves, which it repeats until that agent leaves. Every rejection counts, retries included, while the dashboard's
  Activity view shows a path's first, and leaves out `held_by_another_agent`, which the path's `taken over` line
  already explains.
- `proxy_agent_auth_failures_total` counts every refused call, though the proxy's warning about a host's bad token is
  logged at most once a minute. An agent with a wrong token reconnects every few seconds, so it climbs steadily.
- `proxy_binding_mismatches_total` stays at zero for the stock agent. Its `rpc` label names the call, such as
  `registerPath` or `writeResponsesToProxy`.

Each of these counters exposes every value of its label at zero from the start, so `increase()` and `rate()`, and an
alert built on them, see the first event rather than only a second one.

**Agents that leave:** `proxy_agent_disconnects_total` counts each agent the proxy removes, by why:
`eviction` (no contact within [`maxAgentInactivitySecs`](configuration/proxy.md#internal-settings), also in
`proxy_eviction_count_total`), `termination` (its
connection closed), or `stream_terminated` (its scrape stream ended, with `transportFilterDisabled`). A connection that
never authenticated, such as a port probe, isn't counted.

**Late and abandoned results:** a result that arrives after its scrape ended, usually at Prometheus's or the proxy's
timeout, counts in `proxy_late_scrape_results_total`. The scrape itself was already counted as `timed_out` or
`client_cancelled`. A chunked transfer under way when its scrape ends counts once in
`proxy_chunked_transfers_abandoned_total`.

!!! tip "Proxy-side and agent-side pairs"

    `timed_out` means the proxy gave up waiting on the agent; `upstream_timed_out` means the agent
    answered promptly to report that the *target* was slow. Since `agent.scrapeTimeoutSecs` (15s)
    is well under the proxy's `scrapeRequestTimeoutSecs` (90s), `upstream_timed_out` is the one you
    will usually see for a slow endpoint. The agent also stops at Prometheus's own `scrape_timeout` when that is
    shorter.

    Likewise `content_too_large` is the agent's `maxContentLengthMBytes` limit, or the target's own 413,
    while `payload_too_large` is the proxy's unzip limit. Both surface as HTTP 413.

    The target's own non-2xx status passes through, so each agent-side label is the agent's own condition or
    the target's same status: `path_not_found` for 404, `upstream_timed_out` for 408 or 504,
    `content_too_large` for 413, and `upstream_error` for the rest. A target the agent couldn't reach, such as
    a refused connection, is the agent's own 503 `upstream_error`; the agent's
    `agent_scrape_request_count_total{type="failure"}` tells those apart. A scrape the proxy fails itself names
    why instead: `agent_disconnected` (look at the agent's connection, not the target), `proxy_stopped`, or
    `invalid_response`, an answer it can't use, such as a status outside 200–599, or a 204, 205, or 304.

### Histograms

| Metric                                 | Labels             | Description                                                   |
|:---------------------------------------|:-------------------|:--------------------------------------------------------------|
| `proxy_scrape_request_latency_seconds` | `path`, `outcome`  | End-to-end scrape latency                                     |
| `proxy_scrape_queue_wait_seconds`      | —                  | Time a scrape waited before the agent's stream took it        |
| `proxy_scrape_response_bytes`          | `path`, `encoding` | Response payload size after decompression                     |

A path's series are removed when its last registration goes away, whether it is unregistered or its agent disconnects, so a retired path stops appearing on `/metrics`. A scrape still in flight when that happens is not recorded, so it cannot bring the series back.

The latency histogram's `outcome` label takes the `proxy_scrape_requests_total` `type` values above, so latency can be split
by result.

`proxy_scrape_queue_wait_seconds` is the part of the end-to-end latency spent waiting behind the agent's other scrapes.
When the latency climbs and the queue wait climbs with it, the agent is busy; when the queue wait stays low, the time
is in the network or the target. Like the latency histogram, it leaves out the dashboard's Scrape now.

Latency buckets: 5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s, 15s, 30s, 60s, 90s

The queue wait histogram has the same buckets.

Response size buckets: 1KB, 10KB, 100KB, 500KB, 1MB, 5MB, 10MB

### Gauges

| Metric                                | Description                                    |
|:--------------------------------------|:-----------------------------------------------|
| `proxy_start_time_seconds`            | Proxy start time (Unix epoch), by `launch_id`  |
| `proxy_build_info`                    | Always 1, by `launch_id` and `version`         |
| `proxy_agent_map_size`                | Number of connected agents                     |
| `proxy_path_map_size`                 | Number of registered scrape paths              |
| `proxy_scrape_map_size`               | Number of in-flight scrape requests            |
| `proxy_chunk_context_map_size`        | Number of in-flight chunked transfers          |
| `proxy_cumulative_agent_backlog_size` | Total queued scrape requests across all agents |
| `proxy_dashboard_sessions`            | Dashboard pages open                           |

`proxy_dashboard_logins_total` counts a sign-in on the login page as `success`, a wrong login, from the page or a
script's Basic Auth, as `failure`, and a login refused because its address made too many wrong ones as `blocked`. A
script's right Basic Auth logs in on every request, so it isn't counted.

## Agent Metrics

### Counters

| Metric                             | Labels              | Description                                                                                   |
|:-----------------------------------|:--------------------|:----------------------------------------------------------------------------------------------|
| `agent_scrape_request_count_total` | `launch_id`, `type` | Scrape requests processed (`success`, `unsuccessful`, `timed_out`, `failure`, `invalid_path`) |
| `agent_scrape_result_count_total`  | `launch_id`, `type` | Results sent (`non-gzipped`, `gzipped`, `chunked`, `dropped`)                                 |
| `agent_connect_count_total`        | `launch_id`, `type` | Connection attempts (`success`, `failure`, `rejected`)                                        |
| `agent_disconnects_total`          | `launch_id`         | Registered connections to the proxy that ended                                                |
| `agent_endpoint_advances_total`    | `launch_id`         | Moves to the next proxy endpoint after a failed connect or a rejected registration            |
| `agent_heartbeats_total`           | `launch_id`, `outcome` | Heartbeats sent (`success`, `evicted`, `transient_failure`)                                |
| `agent_path_rejections_total`      | `launch_id`, `cause` | Path registrations the proxy refused, by the cause it gave                                   |
| `agent_discovery_reconciles_total` | `launch_id`, `outcome` | Path discovery reconciles (`success`, `failure`)                                           |
| `agent_scrape_retries_total`       | `launch_id`         | Retries of a target's request, under `agent.scrapeMaxRetries`                                 |
| `agent_filter_lines_dropped_total` | `launch_id`, `path` | Lines removed by the path's metric filter                                                     |
| `agent_filter_bytes_saved_total`   | `launch_id`, `path` | Bytes saved before gzip by the metric filter                                                  |
| `agent_filter_fail_open_total`     | `launch_id`, `path`, `reason` | Scrapes the filter passed through unfiltered (`non_text`, `invalid_utf8`)           |
| `agent_dashboard_scrapes_total`    | `launch_id`         | Scrapes the proxy's dashboard asked for, which the `agent_scrape_*` metrics leave out         |
| `agent_dashboard_logins_total`     | `launch_id`, `outcome` | The agent dashboard's logins (`success`, `failure`, `blocked`)                             |

The `launch_id` label identifies each agent instance: an agent gets a new one each time it starts, and each of several
agents embedded in one JVM has its own. `unsuccessful` means the target answered with a non-2xx status or a response
over `agent.http.maxContentLengthMBytes`; `timed_out` means the target didn't answer within the agent's scrape
timeout; `failure` means the fetch failed without an answer, such as a refused connection or a DNS failure;
`invalid_path` means the proxy asked for a path the agent doesn't serve. A result counts as sent once gRPC takes it
to send, and as `dropped` when the connection to the proxy closes first.

A connect counts as `success` once the proxy has registered the agent, as `rejected` when the proxy refuses the
registration, and as `failure` when it can't be made or registered. `agent_disconnects_total` counts each registered
connection that ends, however it ends, unless the agent is stopping, and `agent_endpoint_advances_total` each move to
the next endpoint in `agent.proxy.endpoints` after a failed connect or a rejected registration; a return to the primary
shows as `agent_proxy_endpoint_index` going back to 0. A heartbeat is `evicted` when the proxy no longer knows the agent.

`agent_path_rejections_total` counts every refusal, retries included, while the agent's dashboard and log show a path's
first; its `cause` is the cause the proxy gave, lowercased, or `unspecified` from a proxy that predates it.
`agent_discovery_reconciles_total` counts each poll of the discovery file, a failure keeping the paths read last, and
`agent_filter_fail_open_total` each scrape a filter let through unfiltered, which the agent logs only the first time.

The connection's counters, the heartbeats', the path rejections', and the retries' expose each value of their labels at
zero from the start, and discovery's and the dashboard's logins' do on an agent that has them, so `increase()` and
`rate()` see the first event.

The `agent_scrape_*` counters and latency histogram count only Prometheus's scrapes. A
[scrape from the dashboard](web-dashboard.md#scrape-now) counts in `agent_dashboard_scrapes_total` instead, whatever
its outcome, which the dashboard shows to whoever asked. Neither it nor a [Scrape now](agent-dashboard.md#scrape-now) on
the agent's own dashboard counts in the `agent_filter_*` counters, and the agent's own counts in none of these.

The three `agent_filter_*` counters only create series for paths that have a filter configured, so
they are absent entirely unless [metric filtering](configuration/agent.md#metric-filtering) is in
use. See that section for what the filter does and does not drop.

### Histograms

| Metric                                 | Labels                    | Description                                                  |
|:---------------------------------------|:--------------------------|:-------------------------------------------------------------|
| `agent_scrape_request_latency_seconds` | `launch_id`, `agent_name` | Time to fetch from target endpoint                           |
| `agent_scrape_queue_wait_seconds`      | `launch_id`               | Time a scrape request waited before its scrape started       |

Both have the proxy latency histogram's buckets, up to 90s, past the agent's 15s scrape timeout. The queue wait is the
time a request waited for one of `agent.http.maxConcurrentClients`; it leaves out a dashboard's scrape, as the latency
histogram does.

### Gauges

| Metric                                           | Labels                             | Description                                                |
|:-------------------------------------------------|:-----------------------------------|:-----------------------------------------------------------|
| `agent_start_time_seconds`                       | `launch_id`                        | Agent start time (Unix epoch)                              |
| `agent_build_info`                               | `launch_id`, `agent_name`, `version` | Always 1                                                 |
| `agent_connected`                                | `launch_id`                        | 1 while connected to a proxy and registered, else 0        |
| `agent_proxy_endpoint_index`                     | `launch_id`                        | The endpoint in use, by its place in `agent.proxy.endpoints`: 0 is the primary |
| `agent_paths`                                    | `launch_id`, `source`              | Paths registered with the proxy (`static`, `discovered`)   |
| `agent_discovery_last_success_timestamp_seconds` | `launch_id`                        | Path discovery's last successful reconcile (Unix epoch)    |
| `agent_dashboard_sessions`                       | `launch_id`                        | The agent dashboard's open pages, with the dashboard on    |
| `agent_scrape_backlog_size`                      | `launch_id`                        | Pending scrape requests queued                             |
| `agent_client_cache_size`                        | `launch_id`                        | Number of cached HTTP clients                              |

An agent's metrics reach Prometheus through the proxy unless it scrapes the agent directly, so a disconnected agent
can't report `agent_connected` as 0: its job's `up` goes to 0 instead. A scraper on the agent's own network can read
it from its metrics port throughout (see [Port or Proxy Path](#port-or-proxy-path)).

## Metric Flow

```text
Prometheus --- HTTP GET ---> Proxy                        Agent
                              |                             |
                  records the start time                    |
                              |                             |
                  writeScrapeRequest()                      |
                  queueWait.observe(age) -- gRPC stream --> fetchScrapeUrl()
                              |                     agentLatency.startTimer()
                              |                             |
                              |                     HTTP GET to target
                              |                             |
                              |                     agentLatency.observeDuration()
                              |                     scrapeRequestCount.inc()
                              |                     scrapeResultCount.inc()
                              |                             |
                  assignScrapeResults() <-- gRPC -----------+
                              |
                  responseBytes.observe()
                  latency.observe(elapsed)
                  scrapeRequestCount.labelValues(outcome).inc()
                              |
                <-- HTTP response ---
```

## PromQL Examples

### Scrape Success Rate

```promql
--8<-- "MonitoringExamples.txt:promql-success-rate"
```

### P99 Scrape Latency

```promql
--8<-- "MonitoringExamples.txt:promql-p99-latency"
```

### P99 Latency per Path

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

## Admin Endpoints

```text
--8<-- "MonitoringExamples.txt:admin-endpoints"
```

Enable admin endpoints:

```bash
java -jar prometheus-proxy.jar --admin
java -jar prometheus-agent.jar --admin
```

## Grafana Dashboards

```text
--8<-- "MonitoringExamples.txt:grafana-import"
```

### Proxy Dashboard

Key panels to monitor:

| Section                   | What to Watch                                                                                                        |
|:--------------------------|:---------------------------------------------------------------------------------------------------------------------|
| **Overview**              | Success rate dropping below 99%, error count spikes, each proxy's version                                            |
| **Throughput**            | Sudden changes in request volume or error ratio                                                                      |
| **Latency**               | P99 creeping up indicates slow targets or network issues; queue wait climbing with it means a busy agent            |
| **Payload**               | Unexpectedly large responses, gzip vs plain distribution                                                             |
| **Internal State**        | Growing backlog means agents can't keep up; agents heartbeat only when idle, so zero heartbeats is normal under load |
| **Errors & Events**       | Which error types dominate; agents disconnecting, and why; refused connects; results arriving after a timeout        |
| **Security & Rejections** | Agents refused for their token, paths refused and why, any binding mismatch                                          |
| **Chunk Health**          | Any chunk validation failure or abandoned transfer                                                                   |
| **Dashboard**             | Wrong or blocked dashboard logins, and how many pages are open                                                       |

The **Job** and **Instance** pickers pick out one proxy when Prometheus scrapes more than one, such as a
high-availability pair.

### Agents Dashboard

Key panels to monitor:

| Section             | What to Watch                                                                         |
|:--------------------|:--------------------------------------------------------------------------------------|
| **Overview**          | Unexpected agent count changes; an agent not connected, or on a backup endpoint; each agent's version |
| **Connections**       | Failure spikes indicate proxy or network issues; a rejected connect is the proxy refusing the agent; an agent scraped through the proxy can't be read while it can't connect, so watch its job's `up` |
| **Scrape Activity**   | Imbalanced load; unsuccessful scrapes point at a failing target; any `dropped` result; retries; a filter letting scrapes through unfiltered |
| **Paths & Discovery** | A path the proxy keeps refusing, by cause; a discovery file that fails to read                        |
| **Latency**           | Per-agent latency outliers point to slow targets; queue wait climbing means too few `agent.http.maxConcurrentClients` |
| **Agent Internals**   | Growing backlog means the agent is falling behind                                                     |
| **Dashboard**         | Wrong or blocked logins to an agent's dashboard                                                       |

Every agent panel keys on `job`, so scrape each agent in its own job, as in
[Scraping Internal Metrics](#scraping-internal-metrics).
