# Operational Web UI

A read-only dashboard for answering the question this system raises most often: **why isn't this target
scraping?**

Without it, that answer lives in the logs of two different machines, or in a JSON debug servlet. The
proxy already holds everything needed to answer it — which agents are connected, which paths they back,
and how recent scrapes actually went.

!!! warning "Off by default, and unauthenticated"

    The UI has **no authentication and no TLS**, the same posture as the admin and metrics endpoints. It
    shows agent names, hostnames, target URLs, labels and recent activity in one place, so treat its
    port as internal and do not expose it publicly.

## Enabling it

=== "Config file"

    ```hocon
    proxy {
      ui {
        enabled = true
        port = 8094                 // Its own port, not the admin port
        path = "ui"                 // Served at http://<proxy>:8094/ui
        refreshIntervalSecs = 2     // How often drifting counters are re-pushed
        recentScrapesQueueSize = 200
      }
    }
    ```

=== "Command line"

    ```bash
    java -jar prometheus-proxy.jar --ui --ui_port 8094
    ```

=== "Environment"

    ```bash
    UI_ENABLED=true UI_PORT=8094 java -jar prometheus-proxy.jar
    ```

Then open `http://<proxy-host>:8094/ui`.

## What it shows

Agents are listed on the left. Selecting one shows, on the right:

| | |
|---|---|
| **Identity** | agent name, remote address, host name, launch ID, consolidated flag |
| **Liveness** | uptime, idle time, scrape backlog depth, seconds until stale-agent eviction |
| **Paths** | every path this agent has registered, and whether a path is shared with other agents |
| **Recent scrapes** | this agent's own scrapes, with status, duration and payload size |

Selection lives in the URL, so a view of one agent is a link you can bookmark or share.

The page updates **live over a WebSocket** — an agent connecting, disconnecting, or registering a path
appears without a refresh. If the agent you are looking at disconnects, the panel says so explicitly
rather than silently freezing on stale data.

## Why it is on its own port

The proxy's admin port (8092) is a servlet container that cannot host WebSockets, so the UI could not
live there even if that were desirable.

It also turns out to be the safer arrangement. Kubernetes liveness and readiness probes target `/ping`
and `/healthcheck` on the admin port. If the UI shared that port, you could not firewall the dashboard
without also cutting off your health probes.

Ports in a fully-enabled proxy:

| Port | Purpose | Default |
|---|---|---|
| 8080 | Prometheus scrapes | enabled |
| 50051 | Agent gRPC connections | enabled |
| 8082 | Proxy's own metrics | disabled |
| 8092 | Admin servlets (`/ping`, `/healthcheck`, `/debug`) | disabled |
| **8094** | **Operational web UI** | **disabled** |

## Offline and airgapped deployments

The UI loads no external assets. [htmx](https://htmx.org) ships inside the JAR as a WebJar dependency
and is served from the classpath, so the dashboard renders with no outbound network access at all.

That matters for this product specifically: a proxy exists to bridge a network boundary and frequently
runs where outbound internet is restricted. A CDN-hosted script would leave a blank page in exactly that
environment — and it would fail when someone opens the page during an incident, not at deploy time.

## Cost

The UI adds no work to the scrape path. Updates are driven by an internal event bus whose publish
operation is non-blocking, so it can never delay an agent registration or a scrape.

State is collected once per update and shared across every connected browser, so the cost does not scale
with the number of open dashboards. Counters that drift rather than change — backlog depth, map sizes,
eviction countdowns — refresh on `refreshIntervalSecs` rather than on every event.

## Running an HA pair

Each proxy's UI shows **only the agents connected to that proxy**. With
[proxy failover](production.md#high-availability) configured, an agent holds one connection at a time, so
it appears on exactly one dashboard.

An agent that reached this proxy by failing over says so. Its detail panel shows the endpoint it
connected through and its position in the configured list:

```
via proxy-b.example.com:50051 (2 of 2)   [failed over]
```

The **failed over** marker appears whenever the agent is on anything other than its first endpoint — so
an agent showing up on the standby is distinguishable from one starting fresh there. Agents with a single
endpoint, or running a version predating this, show no position line.

To follow one specific agent across the pair, use its **launch ID**. That value is generated once per
agent process, so it survives a failover and identifies the same process on whichever proxy it lands on.
The agent ID will *not* match — that one is assigned by each proxy independently.

## Limits

- **Read-only.** There is nothing to click that changes proxy state.
- **No authentication.** Rely on network isolation, as with the admin port.
- **A disconnected agent's paths disappear.** When an agent drops, the proxy deletes its registered
  paths, and the dashboard is organized by agent — so a path that has stopped serving is not shown as
  broken, it is simply absent. If Prometheus reports a target failing and you cannot find that path
  here, an agent having gone away is the likely reason; check the **Agents** list for what is missing
  rather than looking for the path.
- **Path source is not shown.** Whether a path came from static config or
  [dynamic discovery](configuration/agent.md#dynamic-target-discovery) is known only to the agent and is
  not currently sent to the proxy.
- **Per-agent identities** from [agent authentication](security/index.md) are not surfaced yet.

The last two share one cause: they are facts the agent knows and the registration RPC does not carry.
Surfacing either requires extending that RPC, not just the UI — which is how failover position was
added.
