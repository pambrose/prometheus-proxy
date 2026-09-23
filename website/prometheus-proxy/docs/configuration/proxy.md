---
icon: lucide/server
---

# Proxy Configuration

The proxy runs outside the firewall alongside Prometheus. It accepts scrape requests from
Prometheus on HTTP and communicates with agents via gRPC.

## HTTP Service

Configure the HTTP service that Prometheus scrapes:

```hocon
--8<-- "ConfigExamples.txt:proxy-http-config"
```

## gRPC Service

Configure the gRPC service that agents connect to:

```hocon
--8<-- "ConfigExamples.txt:proxy-grpc-config"
```

## Agent Authentication

The agent gRPC port is unauthenticated by default. Set a
[pre-shared token](../security/index.md#agent-authentication-pre-shared-token) so the proxy rejects
agents that do not present a matching value (`UNAUTHENTICATED`). Resolved from `--agent_token` →
`AGENT_TOKEN` → `proxy.agentToken`; empty (the default) disables the check and logs a startup warning
unless mutual TLS is configured. The token is never logged.

```hocon
proxy {
  agentToken = "shared-secret"   // Agents must present the same value
}
```

## Per-Agent Identities

Scope agents to specific paths with `proxy.auth`: a list of named identities, each with its own token
and allowed path glob patterns. The proxy resolves an agent's token to an identity and rejects any
`registerPath` for a path that does not match the identity's patterns. An empty `paths` list allows
all paths. A legacy `proxy.agentToken` (above) is honored alongside `auth` as an allow-all identity,
easing migration. See
[Per-Agent Identities and Path Authorization](../security/index.md#per-agent-identities-and-path-authorization)
for details and a migration walkthrough.

```hocon
proxy {
  auth = [
    { name = team-a, token = "team-a-token", paths = ["team_a_*"] }
    { name = team-b, token = "team-b-token", paths = ["team_b_*"] }
  ]
}
```

Because `auth` is a list of objects, it is config-file-only — there is no CLI/env equivalent, and the
`-D` override cannot express a list. Identity names must be unique and tokens non-empty.

## Service Discovery

Enable Prometheus HTTP service discovery:

```hocon
--8<-- "ServiceDiscoveryExamples.txt:sd-enable-config"
```

See [Service Discovery](../service-discovery.md) for complete details.

## Admin Endpoints

Enable admin endpoints for health checks and debugging:

```hocon
proxy {
  admin {
    enabled = true
    port = 8092
    pingPath = "ping"
    versionPath = "version"
    healthCheckPath = "healthcheck"
    threadDumpPath = "threaddump"
    debugEnabled = false
  }
}
```

When enabled, the following endpoints are available:

| Endpoint           | Description                                       |
|:-------------------|:--------------------------------------------------|
| `GET /ping`        | Returns "pong" (liveness check)                   |
| `GET /healthcheck` | Returns health status JSON                        |
| `GET /version`     | Returns version information                       |
| `GET /threaddump`  | Returns JVM thread dump                           |
| `GET /debug`       | Proxy debug info (requires `debugEnabled = true`) |

## Dashboard

A read-only operational dashboard on its own port. Off by default.

```hocon
proxy {
  dashboard {
    enabled = false                 // Enable the operational dashboard
    port = 8094                     // Its own port, NOT the admin port
    path = "dashboard"              // Served at http://<proxy>:8094/dashboard
    host = "0.0.0.0"                // Listen address; "127.0.0.1" keeps it on the proxy host
    refreshIntervalSecs = 2         // Re-push interval for drifting counters
    recentScrapesQueueSize = 200    // Scrape records retained for the dashboard
    maxSessions = 50                // Concurrent WebSocket sessions; more are closed
    allowedOrigins = []             // Browser origins allowed besides the dashboard's own host
    allowedHosts = []               // Host names the dashboard answers to; empty turns the check off
  }
}
```

Also settable as `--dashboard` / `--dashboard_port` / `--dashboard_path` / `--dashboard_host`, or `DASHBOARD_ENABLED` / `DASHBOARD_PORT` / `DASHBOARD_PATH` / `DASHBOARD_HOST`.

The proxy logs a warning at startup when the dashboard is enabled on all interfaces. A WebSocket from a browser
origin other than the dashboard's own host is refused; behind a reverse proxy that rewrites the Host header, list the
public origin (for example `"https://dash.example.com"`) in `allowedOrigins`. Being a list, `allowedOrigins` must be
set in a config file rather than with `-D`.

`allowedHosts` (for example `["dash.internal"]`) lists the names you reach the dashboard by, which stops DNS rebinding.
It is off while empty, the default, and like `allowedOrigins` must be set in a config file. See
[Exposure](../web-dashboard.md#exposure) for what both lists guard against.

It runs on its own port rather than the admin port, partly because the admin port is a servlet container
that cannot host WebSockets, and partly so the dashboard can be firewalled without also cutting off the
`/ping` and `/healthcheck` endpoints Kubernetes probes target.

Like the admin and metrics endpoints, it has **no authentication and no TLS** — treat the port as
internal. See [Dashboard](../web-dashboard.md) for what it shows.

## Metrics

Enable internal metrics collection:

```hocon
--8<-- "MonitoringExamples.txt:enable-metrics-proxy"
```

See [Monitoring](../monitoring.md) for the complete metrics reference.

## Internal Settings

Configure agent cleanup and scrape request management:

```hocon
--8<-- "AdvancedExamples.txt:stale-agent-config"
```

| Setting                             | Default | Description                                                                        |
|:------------------------------------|:--------|:-----------------------------------------------------------------------------------|
| `staleAgentCheckEnabled`            | true    | Enable periodic stale agent cleanup                                                |
| `maxAgentInactivitySecs`            | 60      | Seconds of inactivity before agent is evicted                                      |
| `staleAgentCheckPauseSecs`          | 10      | Interval between cleanup checks                                                    |
| `scrapeRequestTimeoutSecs`          | 90      | Timeout for individual scrape requests                                             |
| `scrapeRequestBacklogUnhealthySize` | 25      | Backlog that marks the proxy unhealthy; each agent's queue is capped at twice this |
| `maxInFlightScrapeRequests`         | 1000    | Max scrapes in flight across all agents; more get a 503                            |
| `maxPathsPerAgent`                  | 20000   | Max paths one agent connection may register; more are refused (0 = unlimited)     |
| `maxPathLength`                     | 512     | Max characters in a registered path (0 = unlimited)                                |
| `maxLabelsSizeBytes`                | 8192    | Max size of a path's labels JSON, in bytes (0 = unlimited)                         |

The three path limits are safety nets against a runaway or misbehaving agent, set well above normal use. A path
over a limit is refused at registration, and the agent logs the rejection once without retrying it. The proxy warns
when an agent reaches 80% of `maxPathsPerAgent`.

## Content Size Limits

```hocon
proxy.internal {
  maxZippedContentSizeMBytes = 5      // Max zipped content size
  maxUnzippedContentSizeMBytes = 10   // Max unzipped content size
}
```

## Transport Filter

The transport filter detects agent disconnections immediately. Disable it when using a
reverse proxy like Nginx:

```hocon
proxy.transportFilterDisabled = true
```

!!! warning

    With `transportFilterDisabled`, agent disconnections are not immediately detected.
    Agent contexts on the proxy are removed after the inactivity timeout
    (default: 60 seconds).

## gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is disabled by default, because it lets anyone who
reaches the agent port list and describe the proxy's API. Enable it for debugging and tooling:

```hocon
proxy.reflectionDisabled = false
```

When agent authentication is configured (`proxy.agentToken` or `proxy.auth`), reflection calls need a valid agent
token, like every other call on the agent port.

## Log Level

```hocon
--8<-- "ConfigExamples.txt:log-level-config"
```

## Full Reference

See the complete configuration schema:
[`config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf)
