# Operational Dashboard

A read-only dashboard for answering the question this system raises most often: **why isn't this target
scraping?**

Without it, that answer lives in the logs of two different machines, or in a JSON debug servlet. The
proxy already holds everything needed to answer it — which agents are connected, which paths they back,
and how recent scrapes actually went.

!!! warning "Off by default, and unauthenticated"

    The dashboard has **no authentication and no TLS**, the same posture as the admin and metrics endpoints. It
    shows agent names, hostnames, target URLs, labels and recent activity in one place, so treat its
    port as internal and do not expose it publicly.

## Enabling it

=== "Config file"

    ```hocon
    proxy {
      dashboard {
        enabled = true
        port = 8094                 // Its own port, not the admin port
        path = "dashboard"          // Served at http://<proxy>:8094/dashboard
        refreshIntervalSecs = 2     // How often drifting counters are re-pushed
        recentScrapesQueueSize = 200
      }
    }
    ```

=== "Command line"

    ```bash
    java -jar prometheus-proxy.jar --dashboard --dashboard_port 8094
    ```

=== "Environment"

    ```bash
    DASHBOARD_ENABLED=true DASHBOARD_PORT=8094 java -jar prometheus-proxy.jar
    ```

Then open `http://<proxy-host>:8094/dashboard`.

## Two layouts

The top bar switches between them, and each is a real URL you can bookmark or share:

| | Answers | Shape |
|---|---|---|
| **Agents** (`/dashboard`) | "what is this agent doing?" | agent list, drill into one |
| **Paths** (`/dashboard/paths`) | "why isn't this target scraping?" | one flat row per path |

Start from **Paths** when Prometheus reports a target failing — that is the view organized the way the
alert is. Use **Agents** when you already know which agent you care about.

## The status bar

The bar across the top appears in both layouts. Left to right it shows the connection's **live**
indicator, the current **agents** and **paths** counts, and two health gauges, **chunks** and **scrapes**.

### The live indicator

The **live** indicator is the connection's own status, not the proxy's. If the proxy restarts or the
connection drops, the green dot turns red and reads **reconnecting…**; the browser retries roughly every
two seconds and the dot returns to green on its own once it reconnects — no reload. A page that still
says *live* is genuinely receiving updates, so a frozen dashboard cannot masquerade as a healthy one.

The retry interval is deliberately short and fixed. htmx's default WebSocket backoff doubles after each
failed attempt, up to a minute, which for an internal dashboard means the page can stay dark for tens of
seconds after the proxy is already back. A steady two-second retry recovers within about that long of
the proxy returning, at the cost of retry traffic that is negligible on a LAN.

### chunks and scrapes

`chunks` and `scrapes` are **transient in-flight gauges**, not running totals — they count work happening
*right now*, so at rest they sit at `0`. The number after the slash is a health threshold, not a capacity.

- **scrapes `N`/25** — scrape requests currently being serviced. The proxy adds one the moment it forwards
  a Prometheus scrape to an agent and removes it the moment the agent responds, times out, or disconnects.
  It rises with concurrent in-flight scrapes — many paths at once, a slow endpoint, or a high scrape rate —
  and falls back to `0` as they drain.
- **chunks `N`/25** — large responses currently being reassembled. A response is streamed in chunks only
  when it exceeds `agent.chunkContentSizeKbs` (default 32 KB); the proxy holds one context per such response
  until its final chunk arrives. Small metrics payloads never touch it, so it stays `0` unless a big
  endpoint is mid-transfer.

Two things keep them near `0` in practice: each entry is removed as soon as its request completes, and the
bar re-samples them on the refresh timer (`proxy.dashboard.refreshIntervalSecs`, ~2 s) rather than on every
scrape — a fast scrape opens and closes well inside that window. To watch them move, point an agent at an
endpoint larger than 32 KB (chunks) or drive concurrent or slow scrapes (scrapes), ideally with a shorter
`refreshIntervalSecs`.

The `/25` is the **unhealthy threshold** (`proxy.internal.scrapeRequestMapUnhealthySize` and
`chunkContextMapUnhealthySize`). When either count reaches it, that segment turns amber — a backpressure
signal that requests are not draining: agents stuck or slow, or a leak. Green and near `0` is the healthy
resting state.

## The Agents layout

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
rather than silently freezing on stale data. (The top-bar **live** indicator that reflects the
connection itself is described under [The status bar](#the-status-bar).)

## The Paths layout

Every path on one row, with the endpoint behind it and how its last scrape went:

| | |
|---|---|
| **Path** | the registered path Prometheus scrapes |
| **Agent(s)** | the agent serving it; a consolidated path shows the first plus a `+N` count |
| **Target** | the URL the agent actually fetches |
| **Src** | `cfg` for a statically configured path, `disc` for one from [dynamic discovery](configuration/agent.md#dynamic-target-discovery) |
| **Last scrape / Status / Duration** | the most recent result for that path |

### Paths whose agent has gone

A path whose agent disconnected **stays on this screen**, marked `gone`, still naming the agent that was
serving it. This is the layout's main reason to exist.

The proxy deletes a path the moment its last agent departs, so the Agents layout cannot show it — a
target that *stopped* working simply disappears from that view, at exactly the moment you are looking
for it. The Paths layout reconstructs those rows from recent scrape history, which outlives the agent.

That history is bounded by `recentScrapesQueueSize` (default 200 records), so a path that departed long
enough ago eventually ages out. It is a recent-past view, not an audit log.

## Why it is on its own port

The proxy's admin port (8092) is a servlet container that cannot host WebSockets, so the dashboard could not
live there even if that were desirable.

It also turns out to be the safer arrangement. Kubernetes liveness and readiness probes target `/ping`
and `/healthcheck` on the admin port. If the dashboard shared that port, you could not firewall the dashboard
without also cutting off your health probes.

Ports in a fully-enabled proxy:

| Port | Purpose | Default |
|---|---|---|
| 8080 | Prometheus scrapes | enabled |
| 50051 | Agent gRPC connections | enabled |
| 8082 | Proxy's own metrics | disabled |
| 8092 | Admin servlets (`/ping`, `/healthcheck`, `/debug`) | disabled |
| **8094** | **Operational dashboard** | **disabled** |

## Offline and airgapped deployments

The dashboard loads no external assets. [htmx](https://htmx.org) ships inside the JAR as a WebJar dependency
and is served from the classpath, so the dashboard renders with no outbound network access at all.

That matters for this product specifically: a proxy exists to bridge a network boundary and frequently
runs where outbound internet is restricted. A CDN-hosted script would leave a blank page in exactly that
environment — and it would fail when someone opens the page during an incident, not at deploy time.

## Cost

The dashboard adds no work to the scrape path. Updates are driven by an internal event bus whose publish
operation is non-blocking, so it can never delay an agent registration or a scrape.

State is collected once per update and shared across every connected browser, so the cost does not scale
with the number of open dashboards. Counters that drift rather than change — backlog depth, map sizes,
eviction countdowns — refresh on `refreshIntervalSecs` rather than on every event.

## Running an HA pair

Each proxy's dashboard shows **only the agents connected to that proxy**. With
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
- **Consolidated paths report one agent's metadata.** When several agents register the same path, the
  target URL and source shown are the **first registrant's**. Later agents contribute their contexts —
  they are counted in the `+N` — but not their own target URL, which matches how path labels have
  always behaved.
- **Departed paths age out.** They survive only as long as their last scrape stays in the recent-scrape
  window (`recentScrapesQueueSize`, default 200).
- **Per-agent identities** from [agent authentication](security/index.md) are not surfaced yet — the
  identity lives in a gRPC context key and is never stored on the agent context.
