---
icon: lucide/cable
---

# Agent Configuration

The agent runs inside the firewall and scrapes local metrics endpoints on behalf of the proxy.

The settings below are keys in the agent's config file. Each section's table gives a key over its command-line option
and environment variable, where it has them, and links the key to its row in the
[CLI Reference](../cli-reference.md#agent-options). A key with neither is set in the config file, or with `-D`.

## Path Configs

The `pathConfigs` array defines which metrics endpoints the agent exposes through the proxy. Two entries for the
same path, with or without a leading `/`, fail the agent's startup, since the second would replace the first's URL.
[Path rules](#path-rules) below covers what else a path may and may not be.

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
| `path`   | Yes      | Single URL segment on the proxy that Prometheus scrapes (no embedded `/`, not blank; no whitespace, control characters, `?`, or `#`) |
| `url`    | Yes      | Actual metrics endpoint the agent fetches from                |
| `labels` | No       | JSON string of labels for service discovery (default: `"{}"`); keys starting with `__` are dropped |

### Path Rules

!!! note "A path is one segment of plain characters"

    An entry whose `path` is blank or just `/`, or whose `url` is blank, is skipped at startup, with a
    warning naming it, and the agent's other paths still register: the proxy answers a blank path with a
    404, so such an entry could only ever be a dead registration.

    A `path` is one URL segment — it must not contain an embedded `/`, nor a second leading one, as
    `//app_metrics` has. The proxy serves each registered path at `/<path>` (a one-segment route),
    so a multi-segment value like `app/metrics` could never be scraped: the agent refuses a
    `pathConfigs` entry with one at startup, naming it, and skips a discovery entry with one, and the
    proxy refuses such a path at registration. Use `app_metrics` instead.

    A `path` also can't contain whitespace, a control character, `?`, or `#`: no scrape's request path
    can carry one, so Prometheus could never reach such a path. The agent refuses a `pathConfigs` entry
    with one at startup, naming it, and the proxy refuses such a path at registration. Every other
    character is allowed, including `.`, `-`, `~`, and `:`. The proxy also refuses a path it answers
    itself: `favicon.ico`, and `proxy.service.discovery.path` or `proxy.internal.blitz.path` while
    that feature is on.

    The proxy matches a scrape's path as the client sends it, without decoding percent-escapes, so
    keep a `path` to characters a client sends as they are. A client that percent-encodes one, such
    as `%5F` for `_`, asks for a different path and gets a 404, and Prometheus encodes some
    characters itself, such as a non-ASCII letter. A `path` of ASCII letters, digits, `_`, `.`, `-`,
    and `~` is never encoded by anyone.

## Dynamic Target Discovery

By default `pathConfigs` is read once at startup, so adding or removing a target means editing the
config and restarting the agent. Enable dynamic discovery to have the agent reconcile its registered
paths against a **watched file** at runtime — no restart, and paths that did not change keep scraping:

```hocon
agent {
  discovery {
    enabled = true
    file.path = "/etc/prometheus-proxy/targets.conf"  // A list of paths
    reconcileIntervalSecs = 30                        // Poll, and resync
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

Because the file is a plain list, it can be **generated** rather than hand-maintained — a Kubernetes
`ConfigMap` mounted into the agent pod (see
[the Kubernetes walkthrough](../kubernetes.md#updating-targets-without-restarting-the-agent)), an
Ansible template, or a cron job querying a service registry. Automation then needs permission only to
write a file, never to restart the agent, and the churning target list stays separate from the stable
config holding ports, TLS, and the proxy address.

A fully annotated version of this file — required versus defaulted fields, the single-segment `path`
rule, HOCON substitutions, and the empty-versus-deleted distinction — ships as
[`examples/discovery-targets.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/discovery-targets.conf)
and is shown verbatim on the [Example Configs](../examples.md) page.

| Situation                                        | Behavior                                                                                                                                                                                                                                                                                                  |
|:-------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Path in both `pathConfigs` and the file          | Static wins; the discovered entry is skipped (logged)                                                                                                                                                                                                                                                     |
| File missing / unreadable / malformed            | Keeps the last-known-good set (a read failure drops nothing), and registers it again after a reconnect                                                                                                                                                                                                    |
| Valid but empty file                             | Removes all discovered paths                                                                                                                                                                                                                                                                              |
| Entry with a blank `path` (or just `/`) or `url` | Skipped when the file is read, reported once until the set of skipped entries changes; the file's other entries still apply                                                                                                                                                                               |
| Entry whose `path` has more than one segment     | Skipped the same way, whether `app/metrics` or `//app_metrics`, whose first segment is empty                                                                                                                                                                                                              |
| Seeing what discovery did                        | The agent's `/debug` page lists the discovered paths it registered and the entries it skipped                                                                                                                                                                                                             |
| `pathConfigs` empty                              | Discovery-only — every path comes from the file                                                                                                                                                                                                                                                           |
| Proxy rejects a discovered path                  | Logged once. A rejection that can clear is retried with a backoff: on the next poll, then after one `reconcileIntervalSecs`, then after waits that double up to `agent.internal.rejectedPathRetryMaxSecs` (5 min). Any other rejection is tried again only when the entry changes or the agent reconnects |

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

On the proxy's [dashboard](../web-dashboard.md#the-paths-layout), each path is tagged `cfg` (static)
or `disc` (discovered), so when a target is unexpectedly present or absent you can tell at a glance
whether a human or the automation registered it.

## Proxy Connection

```hocon
agent {
  proxy {
    hostname = "proxy-host.example.com"   // Proxy hostname
    port = 50051                          // Proxy gRPC port
  }
}
```

| Setting / CLI Option / Env Var                                                                          | Default     | Description                                                            |
|:--------------------------------------------------------------------------------------------------------|:------------|:-----------------------------------------------------------------------|
| [`agent.proxy.hostname`](../cli-reference.md#agent.proxy.hostname)<br>`--proxy, -p`<br>`PROXY_HOSTNAME` | "localhost" | The proxy's host, or `host:port`, used when `endpoints` is empty. `--proxy` and `PROXY_HOSTNAME` also take a comma-separated list, and replace `endpoints` ([below](#proxy-failover)) |
| `agent.proxy.port`                                                                                      | 50051       | The proxy's port, and the port of an `endpoints` entry that names none |
| `agent.proxy.endpoints`                                                                                 | []          | Proxies to fail over between, in order ([below](#proxy-failover))      |

Or on the command line:

```bash
java -jar prometheus-agent.jar \
  --proxy proxy-host.example.com:50051 --config agent.conf
```

### Proxy Failover

For redundancy, give the agent an ordered list of proxy endpoints instead of a single hostname. It
uses the first that accepts it, and moves to the next on a failed connect or when a proxy rejects its
registration (or every one of its static paths, for a cause that can't clear); when a registered
connection drops it returns to the head of the list, so a recovered primary is picked up on the next
reconnect. Only one connection is active at a time.

```hocon
agent {
  proxy {
    port = 50051                    // For an entry that names no port
    endpoints = [
      "proxy-a.example.com:50051",  // Tried first
      "proxy-b.example.com:50051"   // Used when proxy-a is unreachable
    ]
  }
}
```

`--proxy` and `PROXY_HOSTNAME` accept the same list as a comma-separated value, and either one
**replaces** `endpoints` entirely rather than adding to it:

```bash
java -jar prometheus-agent.jar \
  --proxy proxy-a.example.com:50051,proxy-b.example.com:50051
```

A single value behaves exactly as it always has, so existing configurations need no change. `hostname`
is used only when `endpoints` is empty; the two are never merged. An unparseable entry fails at
startup rather than surfacing later as a connection error.

!!! warning "All endpoints share one TLS configuration"

    The agent builds one TLS context and one authority override for the whole list, so its trust
    collection must hold every proxy's CA, and an `overrideAuthority` must be a name every proxy's
    certificate carries; otherwise the handshake fails with an opaque error rather than a clear
    configuration error.

See [High availability](../production.md#high-availability) for the Prometheus side — in particular,
scrape an HA pair with `static_config`, never `http_sd_config`.

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

## gRPC Settings

Configure the agent's gRPC connection to the proxy:

```hocon
agent {
  grpc {
    keepAliveTimeSecs = -1        // -1 leaves gRPC's default: no PINGs
    keepAliveTimeoutSecs = 20
    keepAliveWithoutCalls = false
    unaryDeadlineSecs = 30
  }
}
```

| Setting / CLI Option / Env Var                                                                                                                         | Default | Description                                                                                                 |
|:-------------------------------------------------------------------------------------------------------------------------------------------------------|:--------|:------------------------------------------------------------------------------------------------------------|
| [`agent.grpc.keepAliveTimeSecs`](../cli-reference.md#agent.grpc.keepAliveTimeSecs)<br>`--keepalive_time_secs`<br>`KEEPALIVE_TIME_SECS`                 | INT_MAX | Seconds the connection goes quiet before the agent sends a PING                                             |
| [`agent.grpc.keepAliveTimeoutSecs`](../cli-reference.md#agent.grpc.keepAliveTimeoutSecs)<br>`--keepalive_timeout_secs`<br>`KEEPALIVE_TIMEOUT_SECS`     | 20      | Seconds the agent waits for a PING's acknowledgment before closing the connection                           |
| [`agent.grpc.keepAliveWithoutCalls`](../cli-reference.md#agent.grpc.keepAliveWithoutCalls)<br>`--keepalive_without_calls`<br>`KEEPALIVE_WITHOUT_CALLS` | false   | Send PINGs while no call is open                                                                            |
| [`agent.grpc.unaryDeadlineSecs`](../cli-reference.md#agent.grpc.unaryDeadlineSecs)<br>`--unary_deadline_secs`<br>`UNARY_DEADLINE_SECS`                 | 30      | Seconds a single call to the proxy, such as a connect or a path's registration, may take; streams have none |

A keepalive setting of `-1`, which the config file has unless you set it, leaves gRPC's own default, the one the table
gives. `INT_MAX` means the agent sends no PINGs. The proxy closes a connection whose PINGs come more often than its
`proxy.grpc.permitKeepAliveTimeSecs`, 300 seconds by default, or that sends one while no call is open, unless its
`proxy.grpc.permitKeepAliveWithoutCalls` is on, so set the agent's to match (see [gRPC Service](proxy.md#grpc-service)
and [gRPC Keepalive Tuning](../advanced.md#grpc-keepalive-tuning)).

`unaryDeadlineSecs` is also how long a connect to a proxy whose host is gone, rather than refusing connections, takes to
fail, before the agent tries again, or tries its next endpoint ([Proxy Failover](#proxy-failover)). A heartbeat's
deadline is the shorter of it and `agent.internal.heartbeatMaxInactivitySecs` ([Internal Settings](#internal-settings)).

## Transport Filter

Behind a reverse proxy such as nginx, disable the transport filter on each agent and on the proxy, since the proxy
refuses an agent whose setting doesn't match its own (see [Transport Filter](proxy.md#transport-filter) and
[nginx Reverse Proxy](../advanced.md#nginx-reverse-proxy)):

```hocon
agent.transportFilterDisabled = true
```

| Setting / CLI Option / Env Var                                                                                                                       | Default | Description                                         |
|:-----------------------------------------------------------------------------------------------------------------------------------------------------|:--------|:----------------------------------------------------|
| [`agent.transportFilterDisabled`](../cli-reference.md#agent.transportFilterDisabled)<br>`--tf-disabled, --tf_disabled`<br>`TRANSPORT_FILTER_DISABLED` | false   | Disable the transport filter, as the proxy has done |

## HTTP Client Settings

Configure how the agent makes HTTP requests to scrape endpoints:

```hocon
--8<-- "ConfigExamples.txt:agent-http-config"
```

| Setting / CLI Option / Env Var                                                                                                                                      | Default | Description                                                                             |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------|:----------------------------------------------------------------------------------------|
| [`agent.http.maxConcurrentClients`](../cli-reference.md#agent.http.maxConcurrentClients)<br>`--max_concurrent_clients`<br>`MAX_CONCURRENT_CLIENTS`                  | 1       | Scrapes the agent runs at once                                                          |
| [`agent.http.clientTimeoutSecs`](../cli-reference.md#agent.http.clientTimeoutSecs)<br>`--client_timeout_secs`<br>`CLIENT_TIMEOUT_SECS`                              | 90      | Seconds a request to a target may take                                                  |
| [`agent.http.maxContentLengthMBytes`](../cli-reference.md#agent.http.maxContentLengthMBytes)<br>`--max_content_length_mbytes`                                       | 10      | Largest scrape response, in megabytes                                                   |
| [`agent.http.enableTrustAllX509Certificates`](../cli-reference.md#agent.http.enableTrustAllX509Certificates)<br>`--trust_all_x509`<br>`TRUST_ALL_X509_CERTIFICATES` | false   | Skip certificate verification on HTTPS targets                                          |
| [`agent.http.trustStorePath`](../cli-reference.md#agent.http.trustStorePath)<br>`--https_truststore`<br>`HTTPS_TRUST_STORE_PATH`                                    | ""      | A JKS or PKCS12 trust store for HTTPS targets a private CA signed; empty uses the JDK's |
| [`agent.http.trustStorePassword`](../cli-reference.md#agent.http.trustStorePassword)<br>`--https_truststore_password`<br>`HTTPS_TRUST_STORE_PASSWORD`               | ""      | The trust store's password                                                              |

### HTTP Client Cache

The agent caches HTTP clients keyed by the basic auth credentials in a target's URL, if any. Configure
cache behavior:

```hocon
--8<-- "ConfigExamples.txt:agent-cache-config"
```

| Setting / CLI Option / Env Var                                                                                                                                                            | Default | Description                      |
|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------|:---------------------------------|
| [`agent.http.clientCache.maxSize`](../cli-reference.md#agent.http.clientCache.maxSize)<br>`--max_cache_size`<br>`MAX_CLIENT_CACHE_SIZE`                                                   | 100     | HTTP clients kept                |
| [`agent.http.clientCache.maxAgeMins`](../cli-reference.md#agent.http.clientCache.maxAgeMins)<br>`--max_cache_age_mins`<br>`MAX_CLIENT_CACHE_AGE_MINS`                                     | 30      | Minutes a client is kept         |
| [`agent.http.clientCache.maxIdleMins`](../cli-reference.md#agent.http.clientCache.maxIdleMins)<br>`--max_cache_idle_mins`<br>`MAX_CLIENT_CACHE_IDLE_MINS`                                 | 10      | Minutes an unused client is kept |
| [`agent.http.clientCache.cleanupIntervalMins`](../cli-reference.md#agent.http.clientCache.cleanupIntervalMins)<br>`--cache_cleanup_interval_mins`<br>`CLIENT_CACHE_CLEANUP_INTERVAL_MINS` | 5       | Minutes between cleanups         |

## Scraping HTTPS Endpoints

For HTTPS scrape targets signed by a private or internal CA, point the agent at a trust store that
contains that CA (`--https_truststore` / `HTTPS_TRUST_STORE_PATH` / `agent.http.trustStorePath`, with
the matching `*_password`) so certificates are still validated. An empty path uses the JDK default
trust store, and `--trust_all_x509` (which disables verification entirely) takes precedence. The trust store is
loaded as the agent starts, so a missing file or a wrong password fails the agent's startup with an error naming
`agent.http.trustStorePath`, rather than every scrape. See
[Scraping HTTPS Endpoints](../security/index.md#scraping-https-endpoints) for details.

## Scrape Settings

```hocon
--8<-- "ConfigExamples.txt:agent-scrape-config"
```

| Setting / CLI Option / Env Var                                                                                        | Default | Description                                                                                                                                                                               |
|:----------------------------------------------------------------------------------------------------------------------|:--------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`agent.scrapeTimeoutSecs`](../cli-reference.md#agent.scrapeTimeoutSecs)<br>`--timeout`<br>`SCRAPE_TIMEOUT_SECS`      | 15      | Total time allowed for a scrape including retries; a shorter Prometheus scrape timeout wins, and the agent sends whichever applies to the target in `X-Prometheus-Scrape-Timeout-Seconds` |
| [`agent.scrapeMaxRetries`](../cli-reference.md#agent.scrapeMaxRetries)<br>`--max_retries`<br>`SCRAPE_MAX_RETRIES`     | 0       | Maximum retries; 0 disables retries                                                                                                                                                       |
| [`agent.chunkContentSizeKbs`](../cli-reference.md#agent.chunkContentSizeKbs)<br>`--chunk`<br>`CHUNK_CONTENT_SIZE_KBS` | 32      | Gzipped result chunked at this size; at most 4032                                                                                                                                         |
| [`agent.minGzipSizeBytes`](../cli-reference.md#agent.minGzipSizeBytes)<br>`--gzip`<br>`MIN_GZIP_SIZE_BYTES`           | 512     | Gzip-compressed above this; at most 4128768                                                                                                                                               |

!!! note "Text exposition formats only"

    The agent and proxy carry a scrape as text, so the agent asks targets only for the text and
    OpenMetrics formats: it drops the protobuf entry from the `Accept` header Prometheus sends. With
    native histograms enabled, Prometheus asks for protobuf first; through the proxy it receives the
    text format instead, and native histograms aren't available. Classic histograms are unaffected.

## Metric Filtering

The agent-to-proxy hop is usually the expensive one — a WAN link, metered egress, a constrained
tunnel — yet filtering with Prometheus `metric_relabel_configs` discards data only *after* it has
crossed it. Filtering at the agent drops unwanted metric families before they are sent, and it runs
before the payload is gzipped and chunked, so the saving composes with `chunkContentSizeKbs` and
`minGzipSizeBytes` above. Filters are declared in a top-level `agent.filters` list, keyed by `path`
rather than nested inside `pathConfigs`:

```hocon
--8<-- "examples/agent-filters.conf"
```

| Field             | Required | Description                                                    |
|:------------------|:---------|:----------------------------------------------------------------|
| `path`            | Yes      | The `pathConfigs` (or discovered) path this filter applies to |
| `metricNameAllow` | Yes      | Fully-anchored regexes; an empty list allows every family     |
| `metricNameDeny`  | Yes      | Fully-anchored regexes; evaluated after `metricNameAllow`     |

Both list fields must be present on every element — write `metricNameAllow: []` for a deny-only
filter. The default is `filters: []` (no filtering), so an existing config with no `filters` key loads
unchanged.

**Regexes are fully anchored** (`Regex.matches()`), matching Prometheus `relabel_config` /
`metric_relabel_configs` semantics: `deny: [ "go_" ]` matches nothing, `deny: [ "go_.*" ]` is required
to match `go_goroutines`.

**Matching is against the metric family, not each series.** A `# HELP` or `# TYPE` line (or `# UNIT`,
for OpenMetrics) opens a family and the allow/deny verdict is computed once against the family name;
every sample line that belongs to that family — the exact name, or the name plus a recognized
histogram/summary/OpenMetrics suffix (`_bucket`, `_sum`, `_count`, `_created`, `_total`, `_gsum`,
`_gcount`, `_info`) — inherits that verdict instead of being matched again. A histogram's `_bucket`,
`_sum`, and `_count` series are therefore always kept or dropped together, along with the family's
metadata lines. One consequence worth knowing: once a family is open, a series-level rule such as
`metricNameDeny: [ "http_req_duration_seconds_bucket" ]` is a silent no-op, since the `_bucket` lines
inherit the family's verdict rather than being matched themselves. A sample line with no open family
(a payload with no `TYPE` lines at all) is judged literally on its own full name. A name outside the
legacy character set, which Prometheus 3 targets write quoted (`# TYPE "http.requests" counter`, and
`{"http.requests",method="GET"} 3`), is matched without its quotes or escapes, so
`metricNameAllow: [ "http\\.requests" ]` matches it.

**An empty `metricNameAllow` allows everything.** `metricNameDeny` is applied *after* allow, so deny
wins on overlap.

To make family scoping concrete, given this filter:

```hocon
{ path: "app1_metrics", metricNameAllow: [], metricNameDeny: [ "go_.*" ] }
```

a scraped payload is transformed like this:

=== "Scraped from the target"

    ```
    # HELP go_goroutines Number of goroutines
    # TYPE go_goroutines gauge
    go_goroutines 12
    # HELP http_req_duration_seconds Request duration
    # TYPE http_req_duration_seconds histogram
    http_req_duration_seconds_bucket{le="0.1"} 5
    http_req_duration_seconds_bucket{le="+Inf"} 9
    http_req_duration_seconds_sum 3.2
    http_req_duration_seconds_count 9
    ```

=== "Sent to the proxy"

    ```
    # HELP http_req_duration_seconds Request duration
    # TYPE http_req_duration_seconds histogram
    http_req_duration_seconds_bucket{le="0.1"} 5
    http_req_duration_seconds_bucket{le="+Inf"} 9
    http_req_duration_seconds_sum 3.2
    http_req_duration_seconds_count 9
    ```

The `go_goroutines` family goes entirely, `# HELP` and `# TYPE` lines included. The histogram is
untouched: `deny` never matched it, and its `_bucket` / `_sum` / `_count` series are evaluated as one
family rather than individually — which is what stops a filter from delivering a histogram with some
of its series missing.

!!! note "Fails open on payloads it can't safely filter"

    A non-text `Content-Type` (protobuf, an HTML error body) passes through unfiltered. A body that is
    not valid UTF-8 also passes through unfiltered and byte-exact rather than risk corrupting it. Both
    conditions log a warning once per path — the worst case is bandwidth not saved, never a corrupted
    payload.

Filters apply by path to both statically configured paths and paths added by
[dynamic discovery](#dynamic-target-discovery); a filter's `path` is matched the same way regardless
of which route registered it. An invalid regex fails agent startup rather than surfacing later at
scrape time.

!!! tip "Confirming a filter attached"

    A filter whose `path` does not match a registered path is otherwise silent — it simply never runs.
    The agent's registration log line for each path reports whether one attached:

    ```text
    Registered http://app1:9090/metrics as /app1_metrics with labels {} (static) with a metric filter
    ```

    If the line for your path does not end with `with a metric filter`, the `path` in your `filters`
    entry does not match the registered path. The `agent_filter_*` counters below are the other way to
    tell: they are only ever created for paths whose filter actually ran.

Three counters, each labeled by `launch_id` and `path`, track filtering and are only created for paths
that actually have a filter configured:

| Metric                             | Labels                        | Description                                  |
|:-----------------------------------|:------------------------------|:---------------------------------------------|
| `agent_filter_lines_dropped_total` | `launch_id`, `path`           | Exposition lines dropped by the filter       |
| `agent_filter_bytes_saved_total`   | `launch_id`, `path`           | Bytes removed from the payload by the filter |
| `agent_filter_fail_open_total`     | `launch_id`, `path`, `reason` | Scrapes let through unfiltered, by reason    |

Not implemented: `dropLabels`, metric renaming/relabeling, and an agent-global filter — every filter
is per-path.

## Consolidated Mode

By default, each path is owned by a single agent. Enable consolidated mode to allow multiple
agents to register the same path:

```hocon
--8<-- "ConfigExamples.txt:consolidated-mode"
```

Also set with [`--consolidated`](../cli-reference.md#agent.consolidated) or `CONSOLIDATED=true`.

This is useful for redundancy — if one agent goes down, the others keep serving the path. Give each
agent its own instance of the service to scrape: the proxy merges every agent's answer into each scrape,
so agents that scrape the same endpoint send each series twice. See
[Consolidated Mode](../advanced.md#consolidated-mode).

## Agent Naming

Give agents descriptive names for easier identification in logs and metrics:

```bash
java -jar prometheus-agent.jar --name production-agent-01 --config agent.conf
```

Or with `AGENT_NAME`, or in the config file:

```hocon
agent.name = "production-agent-01"
```

If no name is provided, the agent uses `Unnamed-<hostname>`.

## Admin Endpoints

Enable admin endpoints for health checks and debugging:

```hocon
agent {
  admin {
    enabled = true
    port = 8093
    pingPath = "ping"
    versionPath = "version"
    healthCheckPath = "healthcheck"
    threadDumpPath = "threaddump"
    debugEnabled = false
  }
}
```

| Setting / CLI Option / Env Var                                                                                 | Default       | Description                               |
|:---------------------------------------------------------------------------------------------------------------|:--------------|:------------------------------------------|
| [`agent.admin.enabled`](../cli-reference.md#agent.admin.enabled)<br>`--admin, -r`<br>`ADMIN_ENABLED`           | false         | Serve the endpoints below                 |
| [`agent.admin.port`](../cli-reference.md#agent.admin.port)<br>`--admin_port, -i`<br>`ADMIN_PORT`               | 8093          | Their port                                |
| `agent.admin.pingPath`                                                                                         | "ping"        | The ping endpoint's path                  |
| `agent.admin.versionPath`                                                                                      | "version"     | The version endpoint's path               |
| `agent.admin.healthCheckPath`                                                                                  | "healthcheck" | The health check endpoint's path          |
| `agent.admin.threadDumpPath`                                                                                   | "threaddump"  | The thread dump endpoint's path           |
| [`agent.admin.debugEnabled`](../cli-reference.md#agent.admin.debugEnabled)<br>`--debug, -b`<br>`DEBUG_ENABLED` | false         | Serve `/debug`, the agent's state as text |

When enabled, the following endpoints are available:

| Endpoint           | Description                                       |
|:-------------------|:--------------------------------------------------|
| `GET /ping`        | Returns "pong" (liveness check)                   |
| `GET /healthcheck` | Returns health status JSON                        |
| `GET /version`     | Returns version information                       |
| `GET /threaddump`  | Returns JVM thread dump                           |
| `GET /debug`       | Agent debug info (requires `debugEnabled = true`) |

`/healthcheck` reports the agent's own state, not its connection: it stays healthy while the agent can't reach a proxy,
and turns unhealthy once `agent.internal.scrapeRequestBacklogUnhealthySize` scrape requests wait for the agent
([Internal Settings](#internal-settings)). The admin port answers on every interface without authentication, so keep it
on an internal network. [Admin and Debug Endpoints](../embedded-agent.md#admin-and-debug-endpoints) says what each
endpoint shows.

## Dashboard

The agent can serve a dashboard of its own, on its own port, off unless turned on: its connection to the proxy, each
path and why the proxy refused one, each path's recent scrapes and a Scrape now button, its filters, discovery,
settings, and what has happened to it. See [Agent Dashboard](../agent-dashboard.md).

```hocon
agent {
  dashboard {
    enabled = true
    port = 8095
    host = "127.0.0.1"   // Keeps it on the agent's host
    password = ""        // Requires a login; better set as DASHBOARD_PASSWORD
  }
}
```

| Setting / CLI Option / Env Var                                                                                   | Default     | Description                                                                       |
|:-----------------------------------------------------------------------------------------------------------------|:------------|:----------------------------------------------------------------------------------|
| [`agent.dashboard.enabled`](../cli-reference.md#agent.dashboard.enabled)<br>`--dashboard`<br>`DASHBOARD_ENABLED` | false       | Serve the agent's dashboard                                                       |
| [`agent.dashboard.port`](../cli-reference.md#agent.dashboard.port)<br>`--dashboard_port`<br>`DASHBOARD_PORT`     | 8095        | Its port                                                                          |
| [`agent.dashboard.path`](../cli-reference.md#agent.dashboard.path)<br>`--dashboard_path`<br>`DASHBOARD_PATH`     | "dashboard" | Its base path                                                                     |
| [`agent.dashboard.host`](../cli-reference.md#agent.dashboard.host)<br>`--dashboard_host`<br>`DASHBOARD_HOST`     | "0.0.0.0"   | The address it listens on; `"127.0.0.1"` keeps it on the agent's host             |
| [`agent.dashboard.username`](../cli-reference.md#agent.dashboard.username)<br>`DASHBOARD_USERNAME`               | "admin"     | The login's username, once a password is set                                      |
| [`agent.dashboard.password`](../cli-reference.md#agent.dashboard.password)<br>`DASHBOARD_PASSWORD`               |             | Requires a login, from the login page or as Basic Auth; empty leaves it open      |
| `agent.dashboard.refreshIntervalSecs`                                                                            | 2           | Seconds between pushes of values that drift rather than change, such as uptime    |
| `agent.dashboard.pathHistorySize`                                                                                | 20          | Scrapes kept per path, for its history strip and Recent scrapes                   |
| `agent.dashboard.scrapeNowEnabled`                                                                               | true        | A Scrape now button on each path, which scrapes its target from the agent at once |
| `agent.dashboard.activityLogSize`                                                                                | 500         | Lines kept in the Activity feed                                                   |
| `agent.dashboard.maxSessions`                                                                                    | 50          | Open WebSocket sessions; more are closed                                          |
| `agent.dashboard.allowedOrigins`                                                                                 | []          | Browser origins allowed besides the dashboard's own host                          |
| `agent.dashboard.allowedHosts`                                                                                   | []          | Host names the dashboard answers to; empty turns the check off                    |
| `agent.dashboard.framingEnabled`                                                                                 | true        | Lets any web page show the dashboard in a frame; false refuses framing            |

## The Agent's Own Metrics

The agent's own metrics, the `agent_*` series the [Agents dashboard](../grafana.md#dashboards) reads, are off unless
turned on, on a metrics port of their own, at a path of the proxy's, or both:

```hocon
agent {
  metrics {
    enabled = true
    port = 8083

    // Since 4.7.0: served through the proxy at /east_agent_metrics
    proxyPath = east_agent_metrics
    proxyPathLabels = "{\"region\": \"east\"}"
  }
}
```

| Setting / CLI Option / Env Var                                                                                             | Default   | Description                                                                      |
|:---------------------------------------------------------------------------------------------------------------------------|:----------|:---------------------------------------------------------------------------------|
| [`agent.metrics.enabled`](../cli-reference.md#agent.metrics.enabled)<br>`--metrics, -e`<br>`METRICS_ENABLED`               | false     | Serve the agent's metrics on a port of their own                                 |
| [`agent.metrics.port`](../cli-reference.md#agent.metrics.port)<br>`--metrics_port, -m`<br>`METRICS_PORT`                   | 8083      | Their port                                                                       |
| `agent.metrics.path`                                                                                                       | "metrics" | Their path on that port                                                          |
| [`agent.metrics.proxyPath`](../cli-reference.md#agent.metrics.proxyPath)<br>`--metrics_proxy_path`<br>`METRICS_PROXY_PATH` | ""        | A proxy path the agent serves its metrics at, read in-process; empty serves none |
| `agent.metrics.proxyPathLabels`                                                                                            | "{}"      | The proxy path's service discovery labels, as JSON                               |

Prometheus, outside the firewall, reaches the agent's metrics only through the proxy, so the proxy path is all most
agents need. The port suits a scraper on the agent's own network, which can read the agent while it can't reach the
proxy. A standalone agent serves the same metrics at either, its JVM metrics included (`standardExportsEnabled` and
the rest). See [Monitoring](../monitoring.md#port-or-proxy-path) for when each fits. Agents that share a config file
each need a path of their own (see [Scraping Internal Metrics](../monitoring.md#scraping-internal-metrics)).

## Internal Settings

Configure reconnects, retries, heartbeats, and the health check's threshold. These settings have no command-line
option or environment variable: set them in the config file, or with `-D`, as
`-Dagent.internal.reconnectPauseSecs=5`.

```hocon
agent {
  internal {
    reconnectPauseSecs = 3            // Seconds between connect attempts
    rejectedPathRetrySecs = 10        // First wait before retrying a refused path
    rejectedPathRetryMaxSecs = 300    // Longest wait between retries
    heartbeatMaxInactivitySecs = 5    // Quiet seconds before a heartbeat
  }
}
```

| Setting                                            | Default | Description                                                                                                                                                     |
|:---------------------------------------------------|:--------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `agent.internal.reconnectPauseSecs`                | 3       | Seconds between attempts to connect to the proxy                                                                                                                |
| `agent.internal.rejectedPathRetrySecs`             | 10      | Seconds before the agent retries a static path the proxy refused for a cause that can clear, or reclaims one another agent took over; doubles with each refusal |
| `agent.internal.rejectedPathRetryMaxSecs`          | 300     | Longest wait between those retries, and a discovered path's                                                                                                     |
| `agent.internal.heartbeatEnabled`                  | true    | Send a heartbeat when the connection goes quiet                                                                                                                 |
| `agent.internal.heartbeatMaxInactivitySecs`        | 5       | Seconds the connection goes quiet before a heartbeat                                                                                                            |
| `agent.internal.heartbeatCheckPauseMillis`         | 500     | Milliseconds between checks for a quiet connection                                                                                                              |
| `agent.internal.scrapeRequestBacklogUnhealthySize` | 25      | Scrape requests waiting for the agent that mark it unhealthy; its queue holds twice this                                                                        |
| `agent.internal.cioTimeoutSecs`                    | 90      | Deprecated for `agent.http.clientTimeoutSecs`; used only when changed while that stays at its default                                                           |

A heartbeat keeps the proxy from evicting a quiet agent as inactive (`proxy.internal.maxAgentInactivitySecs`, 60
seconds by default), and finds a connection that died: after three failed heartbeats in a row, the agent drops the
connection and reconnects. `reconnectPauseSecs`, `rejectedPathRetrySecs`, `rejectedPathRetryMaxSecs`,
`heartbeatCheckPauseMillis`, and `scrapeRequestBacklogUnhealthySize` must be more than zero, or the agent fails at
startup. `agent.internal.zipkin` sends traces of the agent's scrapes and gRPC calls to Zipkin, off by default; see
[Zipkin Tracing](../advanced.md#zipkin-tracing).

## Log Level

```hocon
--8<-- "ConfigExamples.txt:log-level-config"
```

Unset, a standalone agent logs at `info`; an embedded agent's loggers follow its application's logging (see
[Logging](../embedded-agent.md#logging)).

## Full Example

Here is a real-world config from the `examples/` directory:

```hocon
--8<-- "examples/simple.conf"
```

See also:

- [`examples/myapps.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/myapps.conf) — multiple endpoints
- [`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf) — Prometheus federation
- [`examples/discovery-targets.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/discovery-targets.conf) — a [dynamic discovery](#dynamic-target-discovery) targets file (watched, not passed to `--config`)

## Full Reference

See the complete configuration schema:
[`config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf)
