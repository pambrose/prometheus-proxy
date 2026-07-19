---
icon: lucide/gauge
---

# Grafana & Alerting

Ready-to-import Grafana dashboards and Prometheus alerting rules built on the proxy and agent
metrics. For the full metric reference and what each panel watches, see
[Monitoring](monitoring.md).

!!! info "Prerequisite"

    Metrics must be enabled (`--metrics` / `METRICS_ENABLED=true`) and a Prometheus datasource
    must be scraping both the proxy's and the agents' internal `/metrics` endpoints. See
    [Scraping Internal Metrics](monitoring.md#scraping-internal-metrics).

## Dashboards

Two dashboards ship in the repository:

| File | Covers |
|:-----|:-------|
| [`grafana/prometheus-proxy.json`](https://github.com/pambrose/prometheus-proxy/blob/master/grafana/prometheus-proxy.json) | Proxy health, throughput, latency, payload sizes, internal state, errors |
| [`grafana/prometheus-agents.json`](https://github.com/pambrose/prometheus-proxy/blob/master/grafana/prometheus-agents.json) | Agent health, connections, scrape activity, per-agent latency |

### Importing

In Grafana (10.0+): **Dashboards → Import**, then either upload the JSON file or paste the raw
contents and pick your Prometheus datasource:

```bash
# Proxy dashboard
curl -O https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/grafana/prometheus-proxy.json

# Agents dashboard
curl -O https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/grafana/prometheus-agents.json
```

The per-panel breakdown of each dashboard is documented under
[Grafana Dashboards](monitoring.md#grafana-dashboards).

## Alerting rules

Drop the following into a Prometheus `rule_files` group. The thresholds are starting points —
tune them to your environment. Each rule is grounded in a metric documented in
[Monitoring](monitoring.md).

```yaml
groups:
  - name: prometheus-proxy
    rules:
      - alert: ProxyScrapeSuccessRateLow
        expr: |
          sum(rate(proxy_scrape_requests{type="success"}[5m]))
            / sum(rate(proxy_scrape_requests[5m])) < 0.99
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Proxy scrape success rate below 99%"

      - alert: ProxyScrapeLatencyHigh
        expr: |
          histogram_quantile(0.99,
            sum by (le) (rate(proxy_scrape_request_latency_seconds_bucket[5m]))) > 2
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Proxy P99 scrape latency above 2s"

      - alert: ProxyNoAgentsConnected
        expr: proxy_agent_map_size == 0
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "No agents connected to the proxy"

      - alert: ProxyBacklogGrowing
        expr: proxy_cumulative_agent_backlog_size > 100
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Proxy agent scrape backlog is large ({{ $value }} queued)"

      # Covers both size limits: content_too_large is the agent's maxContentLengthMBytes rejecting
      # the target response, payload_too_large is the proxy's unzipped-size guard.
      - alert: ProxyPayloadTooLarge
        expr: rate(proxy_scrape_requests{type=~"payload_too_large|content_too_large"}[5m]) > 0
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "Scrapes are being rejected for exceeding the size limit ({{ $labels.type }})"

      - alert: ProxyFrequentEvictions
        expr: rate(proxy_eviction_count[5m]) > 0
        for: 15m
        labels: { severity: warning }
        annotations:
          summary: "Proxy is evicting stale agents repeatedly"

  - name: prometheus-agent
    rules:
      - alert: AgentConnectFailures
        expr: rate(agent_connect_count{type="failure"}[5m]) > 0
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Agent {{ $labels.launch_id }} is failing to connect to the proxy"

      - alert: AgentBacklogGrowing
        expr: agent_scrape_backlog_size > 50
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Agent {{ $labels.launch_id }} scrape backlog is large"
```

!!! tip "Detecting restarts"

    `proxy_start_time_seconds` and `agent_start_time_seconds` carry a per-process `launch_id`;
    a change in `launch_id` (or a sudden reset of `*_start_time_seconds`) flags a restart on a
    dashboard or alert.

## See also

- [Monitoring](monitoring.md) — full metric reference and PromQL examples
- [Running in Production](production.md) — where alerting fits in the operational checklist
- [Troubleshooting](troubleshooting.md) — mapping an alerting `type` back to a root cause
