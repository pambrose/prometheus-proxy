---
icon: lucide/cable
---

# Agent Configuration

The agent runs inside the firewall and scrapes local metrics endpoints on behalf of the proxy.

## Path Configs

The `pathConfigs` array defines which metrics endpoints the agent exposes through the proxy:

### Basic Configuration

```hocon
--8<-- "ConfigExamples.txt:path-config-basic"
```

### Multiple Endpoints

```hocon
--8<-- "ConfigExamples.txt:path-config-multi"
```

### With Labels

Labels are included in service discovery responses and can be used for filtering:

```hocon
--8<-- "ConfigExamples.txt:path-config-with-labels"
```

Each path config entry has these fields:

| Field    | Required | Description                                                   |
|:---------|:---------|:--------------------------------------------------------------|
| `name`   | Yes      | Human-readable endpoint name (for logs and debugging)         |
| `path`   | Yes      | Single URL segment on the proxy that Prometheus scrapes (no embedded `/`) |
| `url`    | Yes      | Actual metrics endpoint the agent fetches from                |
| `labels` | No       | JSON string of labels for service discovery (default: `"{}"`) |

!!! note "Paths are a single segment"

    A `path` is one URL segment — it must not contain an embedded `/`. The proxy serves each
    registered path at `/<path>` (a one-segment route), so a multi-segment value like
    `app/metrics` is rejected at registration (the agent logs the failure rather than reconnecting).
    Use `app_metrics` instead.

## Dynamic Target Discovery

By default `pathConfigs` is read once at startup, so adding or removing a target means editing the
config and restarting the agent. Enable dynamic discovery to have the agent reconcile its registered
paths against a **watched file** at runtime — no restart, and paths that did not change keep scraping:

```hocon
agent {
  discovery {
    enabled = false                                    // Enable dynamic discovery
    file.path = "/etc/prometheus-proxy/targets.conf"   // HOCON/JSON list of paths
    reconcileIntervalSecs = 30                         // Poll and full-resync interval
  }
}
```

The discovery file holds a `paths` list of the same `{ name, path, url, labels }` entries as
`pathConfigs`:

```hocon
paths = [
  { name = "app1", path = "app1_metrics", url = "http://app1:9090/metrics" }
  { name = "app2", path = "app2_metrics", url = "http://app2:9090/metrics" }
]
```

Every interval the agent registers newly-listed paths, unregisters removed ones, and re-registers a
path whose URL or labels changed.

| Situation                               | Behavior                                                     |
|:----------------------------------------|:------------------------------------------------------------|
| Path in both `pathConfigs` and the file | Static wins; the discovered entry is skipped (logged)       |
| File missing / unreadable / malformed   | Keeps the last-known-good set (a read failure drops nothing) |
| Valid but empty file                    | Removes all discovered paths                                |
| `pathConfigs` empty                     | Discovery-only — every path comes from the file             |

!!! note "Polling, not file-watching"

    Discovery polls the file on the interval rather than relying on OS file-change events, which are
    unreliable under Kubernetes ConfigMap updates (symlink swaps) and some bind mounts. The interval
    doubles as a full-resync safety net.

!!! note "Config-file only"

    `discovery.file.path` points at a list, so — like `pathConfigs` — it has no CLI/env equivalent.
    The scalar `enabled`, `file.path`, and `reconcileIntervalSecs` can also be set via `-D` overrides.

Dynamic target discovery is distinct from [Prometheus service discovery](../service-discovery.md),
which exposes an endpoint so *Prometheus* can find proxied targets; discovery instead lets the *agent*
pick up target changes behind the firewall without a restart.

## Proxy Connection

```hocon
agent {
  proxy {
    hostname = "proxy-host.example.com"   // Proxy hostname
    port = 50051                          // Proxy gRPC port
  }
}
```

Or specify on the command line:

```bash
java -jar prometheus-agent.jar --proxy proxy-host.example.com:50051 --config agent.conf
```

## Agent Authentication

If the proxy requires a [pre-shared agent token](../security/index.md#agent-authentication-pre-shared-token),
set the matching value on the agent. It is presented as a gRPC metadata header on every call and is
never logged. Resolved from `--agent_token` → `AGENT_TOKEN` → `agent.agentToken`; empty (the default)
sends no token.

```hocon
agent {
  agentToken = "shared-secret"   // Must match the proxy's proxy.agentToken
}
```

## HTTP Client Settings

Configure how the agent makes HTTP requests to scrape endpoints:

```hocon
--8<-- "ConfigExamples.txt:agent-http-config"
```

### HTTP Client Cache

The agent caches HTTP clients keyed by authentication credentials (for basic auth / bearer token
scenarios). Configure cache behavior:

```hocon
--8<-- "ConfigExamples.txt:agent-cache-config"
```

## Scraping HTTPS Endpoints

For HTTPS scrape targets signed by a private or internal CA, point the agent at a trust store that
contains that CA (`--https_truststore` / `HTTPS_TRUST_STORE_PATH` / `agent.http.trustStorePath`, with
the matching `*_password`) so certificates are still validated. An empty path uses the JDK default
trust store, and `--trust_all_x509` (which disables verification entirely) takes precedence. See
[Scraping HTTPS Endpoints](../security/index.md#scraping-https-endpoints) for details.

## Scrape Settings

```hocon
--8<-- "ConfigExamples.txt:agent-scrape-config"
```

| Setting               | Default | Description                                       |
|:----------------------|:--------|:--------------------------------------------------|
| `scrapeTimeoutSecs`   | 15      | Total time allowed for a scrape including retries |
| `scrapeMaxRetries`    | 0       | Maximum retries; 0 disables retries               |
| `chunkContentSizeKbs` | 32      | Responses larger than this are chunked            |
| `minGzipSizeBytes`    | 512     | Responses larger than this are gzip-compressed    |

## Metric Filtering

Drop unwanted metric families at the agent, before a scraped payload is gzipped and chunked, so the
bandwidth saving composes with `chunkContentSizeKbs` and `minGzipSizeBytes` above. Filters are declared
in a top-level `agent.filters` list, keyed by `path` rather than nested inside `pathConfigs`:

```hocon
--8<-- "examples/agent-filters.conf"
```

| Field             | Required | Description                                                    |
|:------------------|:---------|:----------------------------------------------------------------|
| `path`            | Yes      | The `pathConfigs` (or discovered) path this filter applies to |
| `metricNameAllow` | Yes      | Fully-anchored regexes; an empty list allows every family     |
| `metricNameDeny`  | Yes      | Fully-anchored regexes; evaluated after `metricNameAllow`     |

Both list fields must be present on every element -- write `metricNameAllow: []` for a deny-only
filter. The default is `filters: []` (no filtering), so an existing config with no `filters` key loads
unchanged.

**Regexes are fully anchored** (`Regex.matches()`), matching Prometheus `relabel_config` /
`metric_relabel_configs` semantics: `deny: [ "go_" ]` matches nothing, `deny: [ "go_.*" ]` is required
to match `go_goroutines`.

**Matching is against the metric family, not each series.** A `# HELP` or `# TYPE` line (or `# UNIT`,
for OpenMetrics) opens a family and the allow/deny verdict is computed once against the family name;
every sample line that belongs to that family -- the exact name, or the name plus a recognized
histogram/summary/OpenMetrics suffix (`_bucket`, `_sum`, `_count`, `_created`, `_total`, `_gsum`,
`_gcount`, `_info`) -- inherits that verdict instead of being matched again. A histogram's `_bucket`,
`_sum`, and `_count` series are therefore always kept or dropped together, along with the family's
metadata lines. One consequence worth knowing: once a family is open, a series-level rule such as
`metricNameDeny: [ "http_req_duration_seconds_bucket" ]` is a silent no-op, since the `_bucket` lines
inherit the family's verdict rather than being matched themselves. A sample line with no open family
(a payload with no `TYPE` lines at all) is judged literally on its own full name.

**An empty `metricNameAllow` allows everything.** `metricNameDeny` is applied *after* allow, so deny
wins on overlap.

!!! note "Fails open on payloads it can't safely filter"

    A non-text `Content-Type` (protobuf, an HTML error body) passes through unfiltered. A body that is
    not valid UTF-8 also passes through unfiltered and byte-exact rather than risk corrupting it. Both
    conditions log a warning once per path -- the worst case is bandwidth not saved, never a corrupted
    payload.

Filters apply by path to both statically configured paths and paths added by
[dynamic discovery](#dynamic-target-discovery); a filter's `path` is matched the same way regardless
of which route registered it. An invalid regex fails agent startup rather than surfacing later at
scrape time.

Two counters, both labeled by `launch_id` and `path`, track filtering and are only created for paths
that actually have a filter configured:

| Metric                        | Labels              | Description                                   |
|:-------------------------------|:--------------------|:-----------------------------------------------|
| `agent_filter_lines_dropped`  | `launch_id`, `path` | Exposition lines dropped by the filter        |
| `agent_filter_bytes_saved`    | `launch_id`, `path` | Bytes removed from the payload by the filter  |

Not implemented: `dropLabels`, metric renaming/relabeling, and an agent-global filter -- every filter
is per-path.

## Consolidated Mode

By default, each path is owned by a single agent. Enable consolidated mode to allow multiple
agents to register the same path:

```hocon
--8<-- "ConfigExamples.txt:consolidated-mode"
```

This is useful for redundancy -- if one agent goes down, another can serve the same path.

## Agent Naming

Give agents descriptive names for easier identification in logs and metrics:

```bash
java -jar prometheus-agent.jar --name production-agent-01 --config agent.conf
```

Or in the config file:

```hocon
agent.name = "production-agent-01"
```

If no name is provided, the agent uses `Unnamed-<hostname>`.

## Full Example

Here is a real-world config from the `examples/` directory:

```hocon
--8<-- "examples/simple.conf"
```

See also:

- [`examples/myapps.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/myapps.conf) -- multiple endpoints
- [`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf) -- Prometheus federation
