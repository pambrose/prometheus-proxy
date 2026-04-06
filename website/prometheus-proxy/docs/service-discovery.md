---
icon: lucide/radar
---

# Service Discovery

Prometheus Proxy supports [Prometheus HTTP service discovery](https://prometheus.io/docs/prometheus/latest/http_sd/),
allowing Prometheus to automatically discover scrape targets as agents register paths.

## Enabling Service Discovery

=== "CLI"

    ```bash
    --8<-- "ServiceDiscoveryExamples.txt:sd-enable-cli"
    ```

=== "Config File"

    ```hocon
    --8<-- "ServiceDiscoveryExamples.txt:sd-enable-config"
    ```

=== "Environment Variables"

    ```text
    --8<-- "ServiceDiscoveryExamples.txt:sd-env-vars"
    ```

## Discovery Endpoint

Once enabled, the proxy exposes a JSON endpoint at the configured path (default: `/discovery`).

Query it with:

```bash
curl -s http://proxy-host.example.com:8080/discovery | jq '.'
```

### Response Format

The endpoint returns a JSON array in the format expected by Prometheus HTTP SD:

```json
--8<-- "ServiceDiscoveryExamples.txt:sd-json-response"
```

Each entry contains:

| Field | Description |
|:------|:------------|
| `targets` | Array containing the proxy endpoint (from `targetPrefix`) |
| `labels.__metrics_path__` | The path to scrape on the proxy |
| `labels.agentName` | Name(s) of agent(s) serving this path |
| `labels.hostName` | Hostname(s) of agent(s) serving this path |
| `labels.*` | Custom labels from agent `pathConfigs` |

## Prometheus Configuration

Configure Prometheus to use HTTP service discovery:

```yaml
--8<-- "ServiceDiscoveryExamples.txt:sd-prometheus-config"
```

The `refresh_interval` controls how often Prometheus queries the discovery endpoint for changes.

!!! tip "Dynamic target management"

    With service discovery enabled, you don't need to update `prometheus.yml` when agents
    register new paths. Prometheus automatically picks up new targets on the next
    refresh interval.

## Target Prefix

The `targetPrefix` setting determines the base URL in the `targets` array. Set this to the
external address of your proxy:

```hocon
proxy.service.discovery.targetPrefix = "http://proxy.example.com:8080/"
```

This ensures Prometheus can reach the proxy from its network location.

## Labels

Labels configured in agent `pathConfigs` are included in the service discovery response.
Prometheus can use these for relabeling, filtering, or target grouping:

```hocon
agent.pathConfigs: [
  {
    name: "Production API"
    path: prod_api_metrics
    labels: "{\"env\": \"production\", \"team\": \"backend\", \"service\": \"api\"}"
    url: "http://api.internal:9100/metrics"
  }
]
```

The labels appear in the discovery response and can be used in Prometheus relabeling rules.
