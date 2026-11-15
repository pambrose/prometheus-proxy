# Operational Dashboard

A dashboard for answering the question this system raises most often: **why isn't this target scraping?**

Without it, that answer lives in the logs of two different machines, or in a JSON debug servlet. The
proxy already holds everything needed to answer it — which agents are connected, which paths they back,
and how recent scrapes actually went.

!!! warning "Off by default, and unauthenticated"

    The dashboard has **no authentication and no TLS**, the same posture as the admin and metrics endpoints. It
    shows agent names, hostnames, target URLs, labels and recent activity in one place, and anyone who can reach it
    can have the proxy scrape a path and read the start of what it returns, so treat its port as internal and do
    not expose it publicly.

    It listens on all interfaces by default, and the proxy logs a warning at startup when it does. Set
    `proxy.dashboard.host` (or `--dashboard_host`) to `127.0.0.1` to keep it on the proxy host.

!!! tip "See it running"

    The repository's [`demos/dashboard`](https://github.com/pambrose/prometheus-proxy/tree/master/demos/dashboard)
    runs the dashboard with three agents, a set of simulated services, Prometheus, and Grafana, all on one machine:
    `make demo-up` in a clone starts it, and `make demo-down` removes it. The [Live Demo](demo.md) page walks
    through a failing target, paths discovered at runtime, and an evicted agent, with screenshots of each.

## Enabling it

=== "Config file"

    ```hocon
    proxy {
      dashboard {
        enabled = true
        port = 8094                 // Its own port, not the admin port
        path = "dashboard"          // Served at http://<proxy>:8094/dashboard
        host = "0.0.0.0"            // "127.0.0.1" keeps it on the proxy host
        refreshIntervalSecs = 2     // How often drifting counters are re-pushed
        recentScrapesQueueSize = 200  // Recent scrapes across all agents, for each agent's panel
        pathHistorySize = 20        // Scrapes kept per path, for the History column
        departedPathRetentionSecs = 3600  // How long a path whose agent has gone stays listed
        activityLogSize = 500       // Lines kept in the Activity view
        scrapeNowEnabled = true     // A Scrape button on each path; false removes it and its route
        links = []                  // Links from each path into other tools; see Links below
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

## Three layouts

The top bar switches between them, and each is a real URL you can bookmark or share:

| | Answers | Shape |
|---|---|---|
| **Agents** (`/dashboard`) | "what is this agent doing?" | agent list, drill into one |
| **Paths** (`/dashboard/paths`) | "why isn't this target scraping?" | one flat row per path |
| **Activity** (`/dashboard/activity`) | "what just happened?" | one row per event, newest first |

Start from **Paths** when Prometheus reports a target failing — that is the view organized the way the
alert is. Use **Agents** when you already know which agent you care about. Use **Activity** when an agent's paths
never appeared, or the agent never did: what the proxy refused is in no other view, since nothing was added.

All three show times in your browser's time zone, as Grafana does, whatever zone the proxy runs in, so a failed
scrape here and the spike it caused in a Grafana panel read the same clock. Hover over a time for its date and zone.

## The status bar

The bar across the top appears in every layout. Left to right it shows the connection's **live**
indicator, the current **agents** and **paths** counts, how many paths are **failing** (their last scrape failed)
or **departed** (their agent has gone) when there are any, and two health gauges, **chunks** and **scrapes**.

The browser tab's title counts the failing and departed paths too, as `(3 problems) prometheus-proxy`, so a
dashboard left in a background tab still says when something is wrong.

### The live indicator

The **live** indicator is the connection's own status, not the proxy's. If the proxy restarts or the
connection drops, the green dot turns red and reads **reconnecting…**; the browser retries roughly every
two seconds and the dot returns to green on its own once it reconnects — no reload. A page that still
says *live* is genuinely receiving updates, so a frozen dashboard cannot masquerade as a healthy one.

A tab in the background gives up its connection and reconnects as soon as you return to it. That is a
pause, not an outage, so it doesn't turn the dot red, and tabs left open in the background don't count
against `proxy.dashboard.maxSessions`.

The retry interval is deliberately short and fixed. htmx's default WebSocket backoff doubles after each
failed attempt, up to a minute, which for an internal dashboard means the page can stay dark for tens of
seconds after the proxy is already back. A steady two-second retry recovers within about that long of
the proxy returning, at the cost of retry traffic that is negligible on a LAN.

### chunks and scrapes

`chunks` and `scrapes` are **transient in-flight gauges**, not running totals — they count work happening
*right now*, so at rest they sit at `0`. The number after the slash is a health threshold, not a capacity.

- **scrapes `N`/800** — scrape requests currently being serviced. The proxy adds one the moment it forwards
  a Prometheus scrape to an agent and removes it the moment the agent responds, times out, or disconnects.
  It rises with concurrent in-flight scrapes — many paths at once, a slow endpoint, or a high scrape rate —
  and falls back to `0` as they drain.
- **chunks `N`/800** — large responses currently being reassembled. A response is streamed in chunks only
  when it exceeds `agent.chunkContentSizeKbs` (default 32 KB); the proxy holds one context per such response
  until its final chunk arrives. Small metrics payloads never touch it, so it stays `0` unless a big
  endpoint is mid-transfer.

Two things keep them near `0` in practice: each entry is removed as soon as its request completes, and the
bar re-samples them on the refresh timer (`proxy.dashboard.refreshIntervalSecs`, ~2 s) rather than on every
scrape — a fast scrape opens and closes well inside that window. To watch them move, point an agent at an
endpoint larger than 32 KB (chunks) or drive concurrent or slow scrapes (scrapes), ideally with a shorter
`refreshIntervalSecs`.

The `/800` is the **unhealthy threshold** (`proxy.internal.scrapeRequestMapUnhealthySize` and
`chunkContextMapUnhealthySize`, by default 80% of `maxInFlightScrapeRequests`). When either count reaches
it, that segment turns amber — a backpressure signal that requests are not draining: agents stuck or slow,
or a leak. Green and near `0` is the healthy resting state.

## The Agents layout

Agents are listed on the left. Selecting one shows, on the right:

| | |
|---|---|
| **Identity** | agent name, remote address, host name, the [auth identity](security/index.md) it connected as, launch ID, consolidated flag |
| **Liveness** | uptime, idle time, scrape backlog depth, seconds until stale-agent eviction |
| **Paths** | every path this agent has registered, and whether a path is shared with other agents |
| **Recent scrapes** | this agent's own scrapes, with status, duration and payload size |

The remote address is the `host:port` the agent's connection came from, without a reverse DNS lookup. With the
transport filter disabled, as behind an nginx reverse proxy, it reads `Unknown`: the connection there comes from the
reverse proxy, not the agent. Before 4.3.0 it read `Unknown` for every agent.

Selection lives in the URL, so a view of one agent is a link you can bookmark or share.

The page updates **live over a WebSocket** — an agent connecting, disconnecting, or registering a path
appears without a refresh. If the agent you are looking at disconnects, the panel says so explicitly
rather than silently freezing on stale data. (The top-bar **live** indicator that reflects the
connection itself is described under [The status bar](#the-status-bar).)

## The Paths layout

Every path on one row, with the endpoint behind it and how its last scrape went:

| | |
|---|---|
| **Path** | the registered path Prometheus scrapes, with a button beside it that copies its URL on the proxy, the one to curl, built from the address you opened the dashboard at and the proxy's scrape port, and one that [scrapes it now](#scrape-now) |
| **Agent(s)** | the agent serving it; a consolidated path shows the first plus a `+N` count |
| **Identity** | with [agent authentication](security/index.md) on, the identity that registered the path, which any other agent joining it must share; the legacy shared `agentToken` reads *shared token* |
| **Target** | the URL the agent actually fetches, with a button beside it that copies it to the clipboard |
| **Src** | `cfg` for a statically configured path, `disc` for one from [dynamic discovery](configuration/agent.md#dynamic-target-discovery) |
| **Last scrape / Status / Duration** | the most recent result for that path |
| **History** | the path's recent scrapes as a row of dots, oldest on the left, green for a success and red for a failure, then the share that succeeded |
| **Links** | with [links](#links) configured, the path's links into other tools, such as Prometheus and Grafana |

Status says how the last scrape went; History says whether that is news. A target that fails every scrape and one that
failed once in twenty look the same in Status alone. Each path keeps its last `pathHistorySize` scrapes (default 20)
whatever the other paths are doing, and the percentage is rounded down, so a path with any failure never reads 100%.

The copy button copies the URL as the table shows it. The agent [redacts](security/index.md#credentials-in-urls) a target
URL's user info and query values before sending it to the proxy, so a URL that carries either is copied with
`***` in their place.

### Searching and filtering

A search box above the table keeps only the rows whose path, agent, target URL or identity contains what you type,
ignoring case, so `team-a` shows one team's paths. **Problems only** keeps the paths whose last scrape failed or whose
agent has gone. Both apply as you type, and while either is on, the table says how many of the paths it is showing.

The search goes into the page's URL, as `/dashboard/paths?q=team-a&problems=1`, so a filtered view is a link you can
bookmark or send. Pressing Enter in the box does the same as typing, and the form works as a plain link without the
page's script.

### Scrape now

The button after a path's copy button scrapes it at once, through its agent, exactly as Prometheus would, and shows
the result above the table: the status, how long it took, the size, the content type, how many metric families and
samples came back, and the first 2 KB of the body. Only a body in the Prometheus text or OpenMetrics format is counted,
so a protobuf one shows no families or samples. A failed scrape shows the agent's reason instead. A consolidated path
is scraped from every agent and merged, as Prometheus sees it. Use it to check a fix without waiting for Prometheus's
next scrape, or for a curl from a machine that can reach the scrape port.

A scrape from the dashboard isn't one of Prometheus's, so it leaves no trace in what the proxy reports: not in the
`proxy_scrape_*` metrics, the path's History, or the agent's recent scrapes. The [Activity](#the-activity-layout) view
records it instead, with the address of the browser that asked. The agent can't tell it from Prometheus's, so it does
count in the agent's own `agent_scrape_*` metrics, and the target sees it as any other scrape.

So clicks can't crowd out Prometheus, at most four dashboard scrapes run at once, a path is scraped from the dashboard
at most once every five seconds, and each waits at most ten seconds for its target. Past either limit the panel says
why instead. `proxy.dashboard.scrapeNowEnabled = false` removes the button and its route.

### Links

Each row can carry links that open its path in other tools: a Prometheus query, a Grafana dashboard, a runbook, a log
search. Write each as a URL template in `proxy.dashboard.links`, and each row fills in its own path's values. With any
link configured, the table ends with a **Links** column naming each row's links; each opens in a new tab.

```hocon
proxy {
  dashboard {
    links = [
      { name = "Prometheus", url = "http://prometheus:9090/graph?g0.expr=up%7Bjob%3D%22{label.job}%22%7D" }
      { name = "Grafana", url = "http://grafana:3000/d/proxy-targets?var-job={label.job}" }
      { name = "Runbook", url = "https://runbooks.example.com/proxy/{path}" }
    ]
  }
}
```

Being a list, `proxy.dashboard.links` must be set in a config file rather than with `-D`. A template can use:

| Placeholder | Filled with |
|---|---|
| `{path}` | the path, without its leading slash |
| `{agent}` | the name of the agent serving the path, as the Agent(s) column shows it |
| `{identity}` | the path's [auth identity](security/index.md); there is none with agent auth off or with the legacy shared `agentToken` |
| `{label.<name>}` | the value of the label `<name>` the agent registered the path with, such as `{label.job}` |

The proxy can't build a Prometheus link on its own. Service discovery gives every path the same target, the proxy's
scrape address, and Prometheus drops `__metrics_path__` once relabeling is done, so what tells one path's series from
another's is up to your deployment: usually a label the agent registers the path with, such as a job of its own, which
is what `{label.job}` reads. To identify series by path instead, keep the path as a label when Prometheus relabels the
proxy's targets:

```yaml
scrape_configs:
  - job_name: prometheus-proxy
    http_sd_configs:
      - url: http://proxy:8080/discovery
    relabel_configs:
      - source_labels: [__metrics_path__]
        target_label: path
```

The label holds the path with its leading slash, after any path prefix in `proxy.service.discovery.targetPrefix`, so
with no prefix a link can query `up{path="/{path}"}`, written into the URL as
`http://prometheus:9090/graph?g0.expr=up%7Bpath%3D%22%2F{path}%22%7D`.

- **A missing value drops the link.** A row whose path lacks a value a template needs, such as a label it didn't
  register, gets no link for that template, never one with an empty slot. A row left with no link shows a dash.
- **Paths whose agent has gone** keep no labels, so they keep only the links that use no `{label.…}`. They keep
  their identity, so an `{identity}` link still works on them.
- **Values are encoded.** Each value is percent-encoded as a URL component, so a label can't add a path, query, or
  fragment of its own, and a slash in a path becomes `%2F`. Write the rest of the template already encoded: PromQL's
  braces and quotes as `%7B`, `%7D`, and `%22`, as above. A label reads as the agent registered it, including one
  [service discovery](service-discovery.md#labels) keeps out, such as a job with `reserveJobAndInstanceLabels` on.
- **Checked at startup.** A link needs a name and an absolute `http` or `https` URL, and may use only the placeholders
  above, and only after the URL's host, so a label can't choose the site a link opens. A brace that isn't part of a
  placeholder fails too. Any of these stops the proxy at startup with a message naming the link.

### Paths whose agent has gone

A path whose agent disconnected **stays on this screen**, marked `gone`, still naming the agent that was
serving it, the identity it was registered as, and the target URL that agent scraped, with its copy button.
This is the layout's main reason to exist.

The proxy deletes a path the moment its last agent departs, so the Agents layout cannot show it — a
target that *stopped* working simply disappears from that view, at exactly the moment you are looking
for it. The Paths layout keeps those rows from what the proxy recorded as each path left, and from its
scrape history, both of which outlive the agent. A path whose agent left before Prometheus ever scraped it
still gets its row, with an en dash for its scrape columns. Otherwise the row keeps its History column, so
you can see whether the target was failing before its agent went. The search finds a gone path by its
identity as it does a live one, so `?q=team-a` shows a team's gone paths too.

A gone path stays for `departedPathRetentionSecs` (default 3600, an hour) after it was last seen: when its agent
left, or its last scrape, if that came later. That is measured in time, not in scrapes, so a busy proxy keeps a
gone path as long as a quiet one does. Earlier versions rebuilt these rows from the 200 most recent scrapes
across all paths, which on a proxy with a hundred paths scraped every 15 seconds is about 30 seconds of history.
At most 1,000 gone paths are kept, the one seen least recently dropped first, and none survives a proxy restart.
It is a recent-past view, not an audit log.

## The Activity layout

What the proxy did with its agents and their paths, newest first:

| Event | When |
|---|---|
| `connected` | an agent registered, with the address it connected from and its host name |
| `disconnected` | an agent's connection closed or its stream ended, with how many paths it served |
| `evicted` | the proxy removed an agent it hadn't heard from within `proxy.internal.maxAgentInactivitySecs` |
| `registered` / `unregistered` | an agent added a path, or removed one; re-registering a path it already serves isn't news |
| `taken over` | an agent took a path another agent of the same identity was serving, as a redeploy does |
| `rejected` | the proxy refused a path, with its reason: one its identity may not register, a malformed or overlong path, a path another identity holds, a consolidated agent and a path that isn't (or the reverse), or one past the agent's `maxPathsPerAgent` |
| `refused` | an identity was already at `proxy.internal.maxAgentsPerIdentity`, so a connect was turned away |
| `bad token` | a call came with no agent token or an unknown one, naming the host it came from |

The last four, and `evicted`, are what **Problems only** keeps, marked in red; a take-over is marked in amber. The
search works as on the Paths layout, matching the event, agent, identity, paths, and detail, and goes into the URL the
same way.

A burst of like events from one agent within five seconds is one row, counted (`registered ×40`) and naming its first
three paths, so an agent registering two thousand paths takes one row rather than the whole feed. A rejection appears
the first time the agent is told it, not on each retry. Bad tokens from one host within a minute are counted on one
row, and at most ten hosts a minute get a row of their own; the rest of that minute's are counted on one, so a flood
from many addresses can't push everything else out of the feed. A connect or a path change appears at once; a refusal
or a bad token, which changes nothing else on the page, by the next refresh.

The feed keeps the last `activityLogSize` rows (default 500), in memory only, so a restart empties it. It is a view of
the recent past, for finding out why something didn't appear, not an audit log; the proxy's log has every event.

## Why it is on its own port

The proxy's admin port (8092) is a servlet container that cannot host WebSockets, so the dashboard could not
live there even if that were desirable.

It also turns out to be the safer arrangement. Kubernetes liveness and readiness probes target `/ping` on
the admin port. If the dashboard shared that port, you could not firewall the dashboard without also cutting
off your health probes.

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
with the number of open dashboards. Loading or reloading a page reuses that collection too, as long as it is
less than `refreshIntervalSecs` old and no agent or path has changed since, so requesting pages in a loop
can't make the proxy collect any faster. Counters that drift rather than change — backlog depth, map sizes,
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

## Exposure

The dashboard has no authentication, so anyone who can reach its port can read it. Keep the port on an
internal network, or bind it to a private address with `proxy.dashboard.host`; the proxy warns at startup
when the dashboard listens on all interfaces.

A browser inside that network can still be used to reach it. The dashboard guards against the two ways a
web page can do that:

- **A WebSocket from another site.** Browsers let any page open a WebSocket to any address, so the
  dashboard refuses a handshake whose `Origin` is not its own host. Behind a reverse proxy that rewrites
  the Host header, the browser's origin is the proxy's public name, so list it in
  `proxy.dashboard.allowedOrigins`.
- **DNS rebinding.** A page can point its own DNS name at the dashboard's address, after which the browser
  sends that name as both `Origin` and `Host`, and the Origin check passes. List the names you reach the
  dashboard by in `proxy.dashboard.allowedHosts` and it refuses any other host name with a 403, on every
  route; IP addresses, `localhost`, and the hosts in `allowedOrigins` are always allowed. This check is off
  until you set it.

The Activity layout shows the address each call with a bad token came from, so anyone who can read the dashboard
can see who has tried the agent port.

Anyone who can reach the dashboard can also have the proxy [scrape a path](#scrape-now) and read the first 2 KB of what
it returns, even with the scrape port firewalled to Prometheus. The route takes a request only from the dashboard's own
page: it applies the same Origin check as the WebSocket, and requires the header the page's script sends, which a page on
another site can't add without the proxy's permission, so a link or form elsewhere can't make it scrape. Set
`proxy.dashboard.scrapeNowEnabled = false` where that is more than the dashboard's readers should have.

Sessions are also capped at `proxy.dashboard.maxSessions` (default 50), and a client that stops reading or
sends an oversized message is disconnected. Neither list stops someone who can reach the port directly.
Both are lists, so set them in a config file; see [Dashboard configuration](configuration/proxy.md#dashboard).

## Limits

- **One action.** Nothing on the dashboard changes the proxy's state. Its one action, [scrape now](#scrape-now),
  scrapes a target as Prometheus would.
- **No authentication.** Rely on network isolation, as with the admin port; see [Exposure](#exposure) for
  what the dashboard itself guards against.
- **Consolidated paths show one agent's target.** When several agents register the same path, the row
  names one of them, with the others counted in the `+N`, and shows that agent's target URL and source.
  When it leaves, the row names the next agent and shows its target. The path's labels, which service
  discovery serves, stay the first registrant's.
- **Departed paths age out.** A gone path stays for `departedPathRetentionSecs` (default 3600) after its agent
  left or its last scrape, whichever is later, at most 1,000 of them at once, and none survives a proxy restart.
- **The activity feed is short-lived.** It keeps the last `activityLogSize` rows (default 500), and none survives a
  proxy restart.
