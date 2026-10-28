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

| Field                     | Description                                                       |
|:--------------------------|:------------------------------------------------------------------|
| `targets`                 | The proxy's address, `host:port`, from `targetPrefix`             |
| `labels.__metrics_path__` | The path to scrape on the proxy, after any path in `targetPrefix` |
| `labels.__scheme__`       | The scheme, when `targetPrefix` is a URL                          |
| `labels.agentName`        | Name(s) of agent(s) serving this path                             |
| `labels.hostName`         | Hostname(s) of agent(s) serving this path                         |
| `labels.*`                | Custom labels from agent `pathConfigs`                            |

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

The `targetPrefix` setting is the address Prometheus scrapes the advertised paths at: the
external `host:port` of your proxy, as Prometheus reaches it. The default is `localhost:8080`.

```hocon
proxy.service.discovery.targetPrefix = "proxy.example.com:8080"
```

It can also be an `http` or `https` URL, such as `https://proxy.example.com/prometheus/`.
Prometheus doesn't accept a URL as a target, so the proxy splits it: the host and port become
the target, the scheme becomes each target's `__scheme__` label, and any path goes in front of
`__metrics_path__`. A value that is neither fails the proxy's startup when service discovery is
enabled.

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
They must be a JSON object whose keys are label names that every Prometheus version accepts
(`[a-zA-Z_][a-zA-Z0-9_]*`) and whose values are strings. Prometheus decodes the labels as a map
of string to string, and one entry that isn't fails its whole discovery response, so the proxy
leaves such entries out. The proxy logs a warning naming them when the path registers, and the
agent logs one at startup for a `pathConfigs` entry. The path itself still registers.

A path can't be the discovery path, `favicon.ico`, or the blitz path: the proxy serves those
itself, so it refuses to register them.

!!! note "Reserved label keys"

    The proxy computes `__metrics_path__`, `agentName`, and `hostName` itself, and it also drops every
    agent-supplied label whose key starts with `__`. In HTTP service discovery a target's labels override
    the scrape config's `__scheme__`, `__scrape_interval__`, `__scrape_timeout__`, and `__param_*`, so
    these would let an agent change how Prometheus scrapes it. The proxy logs a warning naming the dropped
    keys when the path registers.

    Agent labels can set `job` and `instance` by default. When agents run under separate
    [per-agent identities](security/index.md#per-agent-identities-and-path-authorization), set
    `proxy.service.discovery.reserveJobAndInstanceLabels = true` so one team's agent can't label its
    targets as another team's job or instance.
