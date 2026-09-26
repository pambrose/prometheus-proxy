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

The rules below ship as [`grafana/alerts.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/grafana/alerts.yml)
in the repository: add that file to Prometheus' `rule_files`. The thresholds are starting points —
tune them to your environment. Each rule is grounded in a metric documented in
[Monitoring](monitoring.md).

```yaml
--8<-- "grafana/alerts.yml"
```

!!! tip "Detecting restarts"

    `proxy_start_time_seconds` and `agent_start_time_seconds` carry a per-process `launch_id`;
    a change in `launch_id` (or a sudden reset of `*_start_time_seconds`) flags a restart on a
    dashboard or alert.

## See also

- [Monitoring](monitoring.md) — full metric reference and PromQL examples
- [Running in Production](production.md) — where alerting fits in the operational checklist
- [Troubleshooting](troubleshooting.md) — mapping an alerting `type` back to a root cause
