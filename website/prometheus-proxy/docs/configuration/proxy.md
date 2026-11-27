---
icon: lucide/server
---

# Proxy Configuration

The proxy runs outside the firewall alongside Prometheus. It accepts scrape requests from
Prometheus on HTTP and communicates with agents via gRPC.

The settings below are keys in the proxy's config file. Each section's table gives a key over its command-line option
and environment variable, where it has them, and links the key to its row in the
[CLI Reference](../cli-reference.md#proxy-options). A key with neither is set in the config file, or with `-D`.

## HTTP Service

Configure the HTTP service that Prometheus scrapes:

```hocon
--8<-- "ConfigExamples.txt:proxy-http-config"
```

| Setting / CLI Option / Env Var                                                           | Default   | Description                                           |
|:-----------------------------------------------------------------------------------------|:----------|:------------------------------------------------------|
| [`proxy.http.port`](../cli-reference.md#proxy.http.port)<br>`--port, -p`<br>`PROXY_PORT` | 8080      | The port Prometheus scrapes                           |
| `proxy.http.host`                                                                        | "0.0.0.0" | The address the scrape port listens on                |
| `proxy.http.idleTimeoutSecs`                                                             | 45        | Seconds an idle connection from Prometheus stays open |
| `proxy.http.requestLoggingEnabled`                                                       | true      | Log each request the scrape port answers              |

`proxy.http.idleTimeoutSecs` is how long the proxy keeps an idle connection from Prometheus open. `-1` gives the
default, 45 seconds. `0` doesn't turn the timeout off: it, and any other negative value, fails the proxy's startup,
since the scrape port would close every connection before answering it.

## gRPC Service

Configure the gRPC service that agents connect to:

```hocon
--8<-- "ConfigExamples.txt:proxy-grpc-config"
```

| Setting / CLI Option / Env Var                                                                                                                                                   | Default | Description                                                                                         |
|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------|:----------------------------------------------------------------------------------------------------|
| [`proxy.agent.port`](../cli-reference.md#proxy.agent.port)<br>`--agent_port, -a`<br>`AGENT_PORT`                                                                                 | 50051   | The port agents connect to                                                                          |
| [`proxy.grpc.handshakeTimeoutSecs`](../cli-reference.md#proxy.grpc.handshakeTimeoutSecs)<br>`--handshake_timeout_secs`<br>`HANDSHAKE_TIMEOUT_SECS`                               | 120     | Seconds a new connection has to finish its handshake                                                |
| [`proxy.grpc.keepAliveTimeSecs`](../cli-reference.md#proxy.grpc.keepAliveTimeSecs)<br>`--keepalive_time_secs`<br>`KEEPALIVE_TIME_SECS`                                           | 7200    | Seconds a connection goes quiet before the proxy sends a PING                                       |
| [`proxy.grpc.keepAliveTimeoutSecs`](../cli-reference.md#proxy.grpc.keepAliveTimeoutSecs)<br>`--keepalive_timeout_secs`<br>`KEEPALIVE_TIMEOUT_SECS`                               | 20      | Seconds the proxy waits for a PING's acknowledgment before closing the connection                   |
| [`proxy.grpc.permitKeepAliveWithoutCalls`](../cli-reference.md#proxy.grpc.permitKeepAliveWithoutCalls)<br>`--permit_keepalive_without_calls`<br>`PERMIT_KEEPALIVE_WITHOUT_CALLS` | false   | Accept an agent's PINGs while it has no call open                                                   |
| [`proxy.grpc.permitKeepAliveTimeSecs`](../cli-reference.md#proxy.grpc.permitKeepAliveTimeSecs)<br>`--permit_keepalive_time_secs`<br>`PERMIT_KEEPALIVE_TIME_SECS`                 | 300     | Shortest interval between an agent's PINGs the proxy accepts; PINGs more often close the connection |
| [`proxy.grpc.maxConnectionIdleSecs`](../cli-reference.md#proxy.grpc.maxConnectionIdleSecs)<br>`--max_connection_idle_secs`<br>`MAX_CONNECTION_IDLE_SECS`                         | INT_MAX | Seconds a connection with no call open stays before the proxy closes it                             |
| [`proxy.grpc.maxConnectionAgeSecs`](../cli-reference.md#proxy.grpc.maxConnectionAgeSecs)<br>`--max_connection_age_secs`<br>`MAX_CONNECTION_AGE_SECS`                             | INT_MAX | Seconds a connection lasts before the proxy closes it gracefully                                    |
| [`proxy.grpc.maxConnectionAgeGraceSecs`](../cli-reference.md#proxy.grpc.maxConnectionAgeGraceSecs)<br>`--max_connection_age_grace_secs`<br>`MAX_CONNECTION_AGE_GRACE_SECS`       | INT_MAX | Seconds a connection past its age has to finish its calls                                           |

A gRPC setting of `-1`, which the config file has unless you set it, leaves gRPC's own default, the one the table
gives. `INT_MAX` means no limit.

## Agent Authentication

The agent gRPC port is unauthenticated by default. Set a
[pre-shared token](../security/index.md#agent-authentication-pre-shared-token) so the proxy rejects
agents that do not present a matching value (`UNAUTHENTICATED`). Resolved from `--agent_token` →
`AGENT_TOKEN` → `proxy.agentToken`; empty (the default) disables the check unless `proxy.auth` (below) is
set, and logs a startup warning unless `proxy.auth` or mutual TLS is configured. The token is never logged.

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
`-D` override cannot express a list. Identity names must be unique, and tokens non-empty and unique, the
legacy `proxy.agentToken` included. The name `legacy-agent-token` is reserved for the legacy `proxy.agentToken`,
which the logs name that way and the dashboard shows as *shared token*, so the proxy refuses to start with an
entry of that name, whether or not `proxy.agentToken` is set.

## Service Discovery

Enable Prometheus HTTP service discovery:

```hocon
--8<-- "ServiceDiscoveryExamples.txt:sd-enable-config"
```

| Setting / CLI Option / Env Var                                                                                                                   | Default          | Description                                                                                              |
|:-------------------------------------------------------------------------------------------------------------------------------------------------|:-----------------|:---------------------------------------------------------------------------------------------------------|
| [`proxy.service.discovery.enabled`](../cli-reference.md#proxy.service.discovery.enabled)<br>`--sd_enabled`<br>`SD_ENABLED`                       | false            | Serve Prometheus's HTTP service discovery                                                                |
| [`proxy.service.discovery.path`](../cli-reference.md#proxy.service.discovery.path)<br>`--sd_path`<br>`SD_PATH`                                   | "discovery"      | The path it's served at                                                                                  |
| [`proxy.service.discovery.targetPrefix`](../cli-reference.md#proxy.service.discovery.targetPrefix)<br>`--sd_target_prefix`<br>`SD_TARGET_PREFIX` | "localhost:8080" | Each target's address: the proxy's `host:port` as Prometheus reaches it, or an `http(s)` URL             |
| `proxy.service.discovery.reserveJobAndInstanceLabels`                                                                                            | false            | Also drop the `job` and `instance` labels an agent gives a path; labels starting `__` are always dropped |

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

| Setting / CLI Option / Env Var                                                                                 | Default       | Description                               |
|:---------------------------------------------------------------------------------------------------------------|:--------------|:------------------------------------------|
| [`proxy.admin.enabled`](../cli-reference.md#proxy.admin.enabled)<br>`--admin, -r`<br>`ADMIN_ENABLED`           | false         | Serve the endpoints below                 |
| [`proxy.admin.port`](../cli-reference.md#proxy.admin.port)<br>`--admin_port, -i`<br>`ADMIN_PORT`               | 8092          | Their port                                |
| `proxy.admin.pingPath`                                                                                         | "ping"        | The ping endpoint's path                  |
| `proxy.admin.versionPath`                                                                                      | "version"     | The version endpoint's path               |
| `proxy.admin.healthCheckPath`                                                                                  | "healthcheck" | The health check endpoint's path          |
| `proxy.admin.threadDumpPath`                                                                                   | "threaddump"  | The thread dump endpoint's path           |
| [`proxy.admin.debugEnabled`](../cli-reference.md#proxy.admin.debugEnabled)<br>`--debug, -b`<br>`DEBUG_ENABLED` | false         | Serve `/debug`, the proxy's state as text |
| `proxy.admin.recentRequestsQueueSize`                                                                          | 50            | Recent requests `/debug` lists            |

When enabled, the following endpoints are available:

| Endpoint           | Description                                       |
|:-------------------|:--------------------------------------------------|
| `GET /ping`        | Returns "pong" (liveness check)                   |
| `GET /healthcheck` | Returns health status JSON                        |
| `GET /version`     | Returns version information                       |
| `GET /threaddump`  | Returns JVM thread dump                           |
| `GET /debug`       | Proxy debug info (requires `debugEnabled = true`) |

## Dashboard

An operational dashboard on its own port. Off by default.

```hocon
proxy {
  dashboard {
    enabled = true
    port = 8094
    host = "127.0.0.1"   // Keeps it on the proxy's host
    password = ""        // Requires a login; better set as DASHBOARD_PASSWORD
  }
}
```

| Setting / CLI Option / Env Var                                                                                   | Default     | Description                                                                                        |
|:-----------------------------------------------------------------------------------------------------------------|:------------|:---------------------------------------------------------------------------------------------------|
| [`proxy.dashboard.enabled`](../cli-reference.md#proxy.dashboard.enabled)<br>`--dashboard`<br>`DASHBOARD_ENABLED` | false       | Serve the dashboard                                                                                |
| [`proxy.dashboard.port`](../cli-reference.md#proxy.dashboard.port)<br>`--dashboard_port`<br>`DASHBOARD_PORT`     | 8094        | Its port, not the admin port                                                                       |
| [`proxy.dashboard.path`](../cli-reference.md#proxy.dashboard.path)<br>`--dashboard_path`<br>`DASHBOARD_PATH`     | "dashboard" | Its base path, as in `http://<proxy>:8094/dashboard`                                               |
| [`proxy.dashboard.host`](../cli-reference.md#proxy.dashboard.host)<br>`--dashboard_host`<br>`DASHBOARD_HOST`     | "0.0.0.0"   | The address it listens on; `"127.0.0.1"` keeps it on the proxy's host                              |
| [`proxy.dashboard.username`](../cli-reference.md#proxy.dashboard.username)<br>`DASHBOARD_USERNAME`               | "admin"     | The login's username, once a password is set                                                       |
| [`proxy.dashboard.password`](../cli-reference.md#proxy.dashboard.password)<br>`DASHBOARD_PASSWORD`               |             | Requires a login, from the login page or as Basic Auth; empty leaves it open                       |
| `proxy.dashboard.refreshIntervalSecs`                                                                            | 2           | Seconds between pushes of values that drift rather than change, such as backlogs                   |
| `proxy.dashboard.pathHistorySize`                                                                                | 20          | Scrapes kept per path, for the History column and each agent's Recent scrapes                      |
| `proxy.dashboard.departedPathRetentionSecs`                                                                      | 3600        | Seconds a path whose agent has gone stays in the path table                                        |
| `proxy.dashboard.activityLogSize`                                                                                | 500         | Lines kept in the Activity view: agents and paths coming, going, and refused                       |
| `proxy.dashboard.scrapeNowEnabled`                                                                               | true        | A Scrape button on each path, which scrapes it at once and shows the result                        |
| `proxy.dashboard.maxSessions`                                                                                    | 50          | Open WebSocket sessions; more are closed                                                           |
| `proxy.dashboard.allowedOrigins`                                                                                 | []          | Browser origins allowed besides the dashboard's own host                                           |
| `proxy.dashboard.allowedHosts`                                                                                   | []          | Host names the dashboard answers to; empty turns the check off                                     |
| `proxy.dashboard.framingEnabled`                                                                                 | true        | Lets any web page show the dashboard in a frame; false refuses framing                             |
| `proxy.dashboard.links`                                                                                          | []          | Links from each path into other tools, each `{ name = "...", url = "...", icon = "..." }`          |
| `proxy.dashboard.screens`                                                                                        | []          | Screens `/screens` shows beside the dashboard, each `{ name = "...", url = "...", start = "..." }` |

`password` turns on a login, from the dashboard's login page or, for a script, as HTTP Basic Auth, with `username`
(`admin` unless set). Both are settable as `DASHBOARD_USERNAME` / `DASHBOARD_PASSWORD`, but have no command-line flags,
since a command line shows in `ps`. The proxy never logs the password, and refuses to start with an empty username, a
colon in it, or a control character in either. The dashboard serves plain HTTP, so put TLS in front of it; see
[Login](../web-dashboard.md#login).

Without a login, the proxy logs a warning at startup when the dashboard is enabled on all interfaces. A WebSocket from a browser
origin other than the dashboard's own host is refused; behind a reverse proxy that rewrites the Host header, list the
public origin (for example `"https://dash.example.com"`) in `allowedOrigins`. Being a list, `allowedOrigins` must be
set in a config file rather than with `-D`.

`allowedHosts` (for example `["dash.internal"]`) lists the names you reach the dashboard by, which stops DNS rebinding.
It is off while empty, the default, and like `allowedOrigins` must be set in a config file. See
[Exposure](../web-dashboard.md#exposure) for what both lists guard against.

`framingEnabled` lets any web page show the dashboard in a frame, the default; `false` refuses framing on every
response, but for the proxy's own `/screens` while `screens` lists any, which may then frame it from its own origin.
See [Exposure](../web-dashboard.md#exposure) for what framing lets another site's page do.

`links` gives each row of the Paths table links into other tools, each a `name` and a `url` template whose
placeholders (`{path}`, `{agent}`, `{identity}`, and `{label.<name>}`) every row fills from its path, for example
`{ name = "Grafana", url = "http://grafana:3000/d/proxy-targets?var-job={label.job}" }`. It too must be set in a config
file, and a link the dashboard can't use stops the proxy at startup. See [Links](../web-dashboard.md#links).

`screens` lists the screens the proxy's screens page, `/screens` on the dashboard's port, shows beside its own dashboard,
each in a tab: agents' dashboards, Prometheus, Grafana, or any page that allows framing. Each has a `name`, the tab's
title, whose lowercase form is its id in links into the page; a `url`, an absolute `http` or `https` URL, which a value
from the environment can set with `${?NAME}`; and optionally a `start`, the path it opens on, appended to `url`, such as
`/alerts`. While it lists any, the dashboard port's root leads to the page; with none, it leads to the dashboard, and
`/screens` answers 404. It too must be set in a config file, and a list the page can't use stops
the proxy at startup, naming the screen. See [The Screens Page](../web-dashboard.md#screens).

It runs on its own port rather than the admin port, partly because the admin port is a servlet container
that cannot host WebSockets, and partly so the dashboard can be firewalled without also cutting off the
`/ping` and `/healthcheck` endpoints Kubernetes probes target.

It serves plain HTTP, with **no TLS**, and without a `password` it has **no authentication**, like the admin and
metrics endpoints — treat the port as internal. See [Dashboard](../web-dashboard.md) for what it shows.

## Metrics

Enable internal metrics collection:

```hocon
--8<-- "MonitoringExamples.txt:enable-metrics-proxy"
```

| Setting / CLI Option / Env Var                                                                               | Default   | Description                                      |
|:-------------------------------------------------------------------------------------------------------------|:----------|:-------------------------------------------------|
| [`proxy.metrics.enabled`](../cli-reference.md#proxy.metrics.enabled)<br>`--metrics, -e`<br>`METRICS_ENABLED` | false     | Serve the proxy's metrics on a port of their own |
| [`proxy.metrics.port`](../cli-reference.md#proxy.metrics.port)<br>`--metrics_port, -m`<br>`METRICS_PORT`     | 8082      | Their port                                       |
| `proxy.metrics.path`                                                                                         | "metrics" | Their path                                       |

The JVM's metric groups each have a switch under `proxy.metrics`, such as `proxy.metrics.standardExportsEnabled`, all
off by default (see [JVM Metrics](../monitoring.md#jvm-metrics)). See [Monitoring](../monitoring.md) for the complete
metrics reference.

## Internal Settings

Configure agent cleanup and scrape request management. These settings have no command-line option or environment
variable: set them in the config file, or with `-D`, as `-Dproxy.internal.maxAgentInactivitySecs=120`.

```hocon
--8<-- "AdvancedExamples.txt:stale-agent-config"
```

| Setting                                            | Default | Description                                                                                             |
|:---------------------------------------------------|:--------|:--------------------------------------------------------------------------------------------------------|
| `proxy.internal.staleAgentCheckEnabled`            | true    | Enable periodic stale agent cleanup                                                                     |
| `proxy.internal.maxAgentInactivitySecs`            | 60      | Seconds an agent may go without contacting the proxy before the proxy evicts it                         |
| `proxy.internal.staleAgentCheckPauseSecs`          | 10      | Interval between cleanup checks                                                                         |
| `proxy.internal.scrapeRequestTimeoutSecs`          | 90      | Timeout for individual scrape requests                                                                  |
| `proxy.internal.scrapeRequestBacklogUnhealthySize` | 25      | Backlog that marks the proxy unhealthy; each agent's queue is capped at twice this                      |
| `proxy.internal.maxInFlightScrapeRequests`         | 1000    | Max scrapes in flight across all agents; more get a 503                                                 |
| `proxy.internal.scrapeRequestMapUnhealthySize`     | 0       | Scrapes in flight that mark the proxy unhealthy (0 = 80% of `proxy.internal.maxInFlightScrapeRequests`) |
| `proxy.internal.chunkContextMapUnhealthySize`      | 0       | Chunked transfers in progress that mark the proxy unhealthy (0 = 80% of the same)                       |
| `proxy.internal.maxPathsPerAgent`                  | 20000   | Max paths one agent connection may register; more are refused (0 = unlimited)                           |
| `proxy.internal.maxPathsPerIdentity`               | 100000  | Max paths the agents of one auth identity may register together; more are refused (0 = unlimited)       |
| `proxy.internal.maxPathLength`                     | 512     | Max characters in a registered path (0 = unlimited)                                                     |
| `proxy.internal.maxLabelsSizeBytes`                | 8192    | Max size of a path's labels JSON, in bytes (0 = unlimited)                                              |
| `proxy.internal.maxLabelsPerPath`                  | 64      | Max labels in a path's labels JSON object (0 = unlimited)                                               |
| `proxy.internal.maxAgentFieldLength`               | 256     | Max characters of an agent's name, host, launch ID, or endpoint (0 = unlimited)                         |
| `proxy.internal.maxAgentProxyEndpoints`            | 16      | Max entries of an agent's proxy endpoint list (0 = unlimited)                                           |
| `proxy.internal.maxTargetUrlLength`                | 2048    | Max characters of a path's target URL, as the agent reports it (0 = unlimited)                          |
| `proxy.internal.maxAgentsPerIdentity`              | 10000   | Max agents connected as one identity with `transportFilterDisabled` (0 = unlimited)                     |
| `proxy.internal.streamKeepAliveSecs`               | 30      | Seconds a stream to or from an agent goes quiet before a keepalive, both ways (0 = off)                 |

The five path limits are safety nets against a runaway or misbehaving agent, set well above normal use. A path
over a limit is refused at registration, and the agent logs the rejection once without retrying it. A path too long,
or with labels too large, is refused before it is checked against the agent's
[identity patterns](#per-agent-identities); one with too many labels, or past a path count, after. The proxy warns
when an agent reaches 80% of `maxPathsPerAgent`, and when an identity's agents reach 80% of `maxPathsPerIdentity`, and
warns again only once the count has fallen below 70%, so a count that hovers at the threshold is warned of once.

`maxPathsPerAgent` counts one connection's paths, and an agent may open any number of connections, so
`maxPathsPerIdentity` counts the paths of every agent of an [identity](#per-agent-identities) together, whether or not
the transport filter is on. With agent auth off, or every agent on the legacy `agentToken`, all agents share one
identity, and the limit counts the paths of the whole fleet. An agent that takes over a path from another agent of its
identity leaves the count as it was, so a redeploy at the limit still registers, but an agent that joins a consolidated
path adds to it, and one that rejoins before the proxy has dropped its old connection counts twice until the proxy does.

What the proxy keeps of a path is what these limits bound: a path with no labels costs at most about 3 KiB, with
`maxPathLength` and `maxTargetUrlLength` at their defaults, and one with 64 labels filling `maxLabelsSizeBytes` about
20 KiB, or 28 KiB with non-Latin text. Lower `maxPathsPerIdentity` to keep one identity's paths within your heap.

The agent limits bound what an agent reports about itself and its targets. The proxy only displays these values, in
its logs, service discovery labels, dashboard, and `/debug` page, but it keeps them, and repeats some of them for
every path the agent serves. A longer name, host name, launch ID, endpoint, or path source is cut to its first
`maxAgentFieldLength` characters, a longer endpoint list to its first `maxAgentProxyEndpoints` entries, and a longer
target URL to its first `maxTargetUrlLength` characters, after its credentials are redacted. The agent and its paths
stay registered, and the proxy logs one warning naming what it cut. The agent redacts a target URL's query values
before it sends it, so a real one is far shorter than 2048 characters.

`maxAgentsPerIdentity` applies only with the [transport filter](#transport-filter) disabled; see there.

## Content Size Limits

```hocon
proxy.internal {
  maxZippedContentSizeMBytes = 5      // Max zipped content size
  maxUnzippedContentSizeMBytes = 10   // Max unzipped content size
}
```

`maxZippedContentSizeMBytes` bounds a result the agent sends in chunks, as it arrives, before the proxy unzips it: one
past it fails its scrape with a 502 (`invalid_response`). `maxUnzippedContentSizeMBytes` bounds any result once it's
unzipped, and one the agent sent without gzip, as it does a small one: one past it fails its scrape with a 413
(`payload_too_large`). The agent's own
`agent.http.maxContentLengthMBytes` bounds what it takes from a target ([Troubleshooting](../troubleshooting.md#413-payload-too-large)).

## Transport Filter

The transport filter detects agent disconnections immediately. Disable it when using a
reverse proxy like Nginx, on the proxy and on each of its agents, since the proxy refuses an agent
whose setting doesn't match its own:

```hocon
proxy.transportFilterDisabled = true   // The proxy's config
agent.transportFilterDisabled = true   // Each agent's config
```

Each also takes [`--tf-disabled`](../cli-reference.md#proxy.transportFilterDisabled) or
`TRANSPORT_FILTER_DISABLED=true`.

!!! warning

    With `transportFilterDisabled`, the proxy can't see a connection close, only the agent's request
    stream end. An agent that disconnects ends that stream, and is removed at once. One that dies
    before opening its stream, or whose connection stays half-open, is removed after the inactivity
    timeout (default: 60 seconds, [`proxy.internal.maxAgentInactivitySecs`](#internal-settings)).

!!! note "Give each agent its own identity"

    With the filter disabled, the proxy can't tie a call to a connection, so it binds each agent's calls
    to the auth identity the agent connected with. Agents that share an identity, including every agent
    on the legacy `proxy.agentToken`, can't be told apart, so give each agent its own `proxy.auth` token.
    See [Isolation Between Agents](../security/index.md#isolation-between-agents).

!!! note "Agents per identity"

    With the filter disabled, every connect adds an agent on the proxy, so the proxy limits the agents
    connected as one auth identity to `proxy.internal.maxAgentsPerIdentity` (10,000). It refuses a connect
    past the limit with `RESOURCE_EXHAUSTED`, rather than drop an agent already connected, and the refused
    agent tries again after its reconnect pause. The proxy warns as an identity reaches 80% of the limit,
    again only once its count has fallen below 70%, and when it refuses a connect, at most once a minute for
    each identity. With agent auth off, or on the
    legacy `proxy.agentToken`, every agent has the same identity, so the limit is a total. Leave room for
    agents that count twice for a while: one that reconnects while its old connection is half-open, until
    the old one is evicted, and one whose registration is refused, once for each retry until each is
    evicted. Set `0` for no limit.

## gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is disabled by default, because it lets anyone who
reaches the agent port list and describe the proxy's API. Enable it for debugging and tooling:

```hocon
proxy.reflectionDisabled = false
```

Or set `REFLECTION_DISABLED=false`. The [`--ref-disabled`](../cli-reference.md#proxy.reflectionDisabled) flag only
turns it off.

When agent authentication is configured (`proxy.agentToken` or `proxy.auth`), reflection calls need a valid agent
token, like every other call on the agent port.

## Log Level

```hocon
--8<-- "ConfigExamples.txt:log-level-config"
```

## Full Reference

See the complete configuration schema:
[`config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf)
