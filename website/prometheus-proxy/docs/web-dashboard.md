---
icon: lucide/layout-dashboard
---

# Operational Dashboard

A dashboard for answering the question this system raises most often: **why isn't this target scraping?**

Without it, that answer lives in the logs of two different machines, or in a JSON debug servlet. The
proxy already holds everything needed to answer it — which agents are connected, which paths they back,
and how recent scrapes actually went.

!!! warning "Off by default, and open until you set a password"

    The dashboard has **no TLS**, and **no authentication until you set a password** (`DASHBOARD_PASSWORD`; see
    [Login](#login)). It shows agent names, hostnames, target URLs, labels, recent activity, and the proxy's settings,
    though never a token or password, in one place, and anyone who can reach it can have the proxy scrape a path and read the start of what it returns, so treat its port
    as internal and do not expose it publicly, with a login or without one.

    It listens on all interfaces by default, and without a login the proxy logs a warning at startup when it does. Set
    `proxy.dashboard.host` (or `--dashboard_host`) to `127.0.0.1` to keep it on the proxy host.

!!! tip "The agent's side"

    This dashboard shows agents once they connect. For an agent that can't, or a path the proxy refused, turn on the
    agent's own [dashboard](agent-dashboard.md), which shows its connection and each path from the agent's side.

!!! tip "See it running"

    The repository's [`demos/basics`](https://github.com/pambrose/prometheus-proxy/tree/master/demos/basics)
    runs the dashboard with three agents, a set of simulated services, Prometheus, and Grafana, all on one machine:
    `make up` in a clone's `demos/basics` starts it, and `make down` removes it. The [Basics Demo](basics-demo.md) page
    walks through a failing target, paths discovered at runtime, and an evicted agent, with screenshots of each.

## Enabling It

=== "Config file"

    ```hocon
    proxy {
      dashboard {
        enabled = true
        port = 8094                 // Its own port, not the admin port
        path = "dashboard"          // Served at http://<proxy>:8094/dashboard
        host = "0.0.0.0"            // "127.0.0.1" keeps it on the proxy host
        refreshIntervalSecs = 2     // How often drifting counters are re-pushed
        pathHistorySize = 20        // Scrapes kept per path, for History and each agent's Recent scrapes
        departedPathRetentionSecs = 3600  // How long a path whose agent has gone stays listed
        activityLogSize = 500       // Lines kept in the Activity view
        scrapeNowEnabled = true     // A Scrape button on each path; false removes it and its route
        links = []                  // Links from each path into other tools; see Links below
        username = "admin"          // The login's username, once a password is set
        password = ""               // Requires a login; better set as DASHBOARD_PASSWORD, see Login below
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

## Login

Set a password, and the dashboard asks for a login before it shows anything: a browser gets its login page, and after
a right login, the page it asked for. The username is `admin` unless you set another.

<figure markdown="span">
  ![The dashboard's login page](images/demo/login.webp#only-dark){ loading=lazy }
  ![The dashboard's login page](images/demo/login-light.webp#only-light){ loading=lazy }
  <figcaption>The login page, once a password is set</figcaption>
</figure>

=== "Environment"

    ```bash
    DASHBOARD_ENABLED=true DASHBOARD_PASSWORD='<a long password>' java -jar prometheus-proxy.jar
    ```

=== "Config file"

    ```hocon
    proxy {
      dashboard {
        enabled = true
        username = "ops"            // admin unless set; DASHBOARD_USERNAME
        password = ${?OPS_PASSWORD} // From the environment, so the file holds no password
      }
    }
    ```

The username and password have no command-line flags, since a command line shows in `ps`. Set the password from the
environment, or from a Secret on a platform that has them, rather than in a config file others can read.

A login starts a session, which the browser keeps in a cookie. It ends after 24 hours without a request, 7 days after
the login, or with **Log out**, the exit icon at the end of the top bar. The proxy keeps sessions in memory, so a
restart signs everyone out; a page left open then goes to the login page by itself. The cookie is `HttpOnly`,
`SameSite=Lax`, named for the dashboard's port (so two proxies on one host keep their own), set at the dashboard's path,
and at `/screens` too while [screens](#screens) are listed, so the screens page gets it, and `Secure` when the browser
reached the dashboard over `https`, as through a reverse proxy. Signing in from a link into the screens page returns to
it, with the screen and view the link named. With a login set, the dashboard tells the browser to keep none of its pages
(`Cache-Control: no-store`), so a page behind the login isn't kept to be shown again after Log out.

A script sends the login with each request as HTTP Basic Auth instead, and needs no session. A browser can't: it always
gets the login page, even with a Basic login it saved for the dashboard's address earlier, so Log out signs it out.

```bash
curl -u admin:'<the password>' http://proxy-host:8094/dashboard/paths
```

After 10 wrong logins from one address within a minute, or 100 from all of them together, the dashboard refuses every
login from that address, or from any, with a 429, the right password included, until the oldest of those wrong logins
is a minute old. So a guesser can't try passwords in parallel. Behind a reverse proxy every login comes from the
proxy's address, so the first limit is everyone's, and during an attack the real users wait out the minute too.

A page left open when its session ends goes to the login page: at once after Log out in another tab, within one
refresh interval (`proxy.dashboard.refreshIntervalSecs`, 2 seconds by default) after the session expires, and once the
proxy is back after a restart.

The proxy logs the username and that a password is set, never the password. It logs a wrong login at WARN, with the
address it came from, at most once a minute for each address, and logs reaching the limit. It refuses to start with an
empty username, a colon in it (where Basic Auth splits the username from the password), or a control character in
either, such as the trailing newline of a password read from a file.

!!! warning "Put TLS in front of it"

    The dashboard serves plain HTTP, so the password, and the session cookie after it, cross the network readable by
    anyone who can watch the traffic. Off the proxy host, reach the dashboard through a reverse proxy that terminates
    TLS (list its public origin in `allowedOrigins`; see [Exposure](#exposure)), an SSH tunnel, or
    `kubectl port-forward`. A login doesn't make the port safe to expose publicly.

## Four Layouts

The tabs in the top bar switch between them, and each is a real URL you can bookmark or share. The Left and Right
arrow keys open the view before or after the current one, except while you're typing in the search.

| | Answers | Shape |
|---|---|---|
| **Agents** (`/dashboard`) | "what is this agent doing?" | agent list, drill into one |
| **Paths** (`/dashboard/paths`) | "why isn't this target scraping?" | one flat row per path |
| **Activity** (`/dashboard/activity`) | "what just happened?" | one row per event, newest first |
| **Settings** (`/dashboard/settings`) | "how is this proxy set up?" | its settings, in groups |

Start from **Paths** when Prometheus reports a target failing — that is the view organized the way the
alert is. Use **Agents** when you already know which agent you care about. Use **Activity** when an agent's paths
never appeared, or the agent never did: what the proxy refused is in no other view, since nothing was added. Use
**Settings** to check what the proxy is running with.

Every layout shows times in your browser's time zone, as Grafana does, whatever zone the proxy runs in, so a failed
scrape here and the spike it caused in a Grafana panel read the same clock. Hover over a time for its date and zone.

## The Status Bar

The bar across the top appears in every layout. It starts with the logo and which proxy the page is: its host's name
and the port Prometheus scrapes, as `Proxy: proxy:8080`, so the dashboards of several proxies are told apart at a
glance. After the tabs it shows the connection's **live** indicator, the current **agents** and **paths** counts, how
many paths are **failing** (their last scrape failed) or **departed** (their agent has gone) when there are any, and two
health gauges, **chunks** and **scrapes**.

The browser tab's title names the proxy the same way and counts the failing and departed paths too, as
`(3 problems) Proxy: proxy:8080`, so a dashboard left in a background tab still says when something is wrong.

At the bar's end, the theme button shows the theme in force, **light** (the sun), **dark** (the moon), or **auto**
(the half-filled circle), the default, which follows your system's light or dark setting. Each click moves to the next
theme, in that order, and its tooltip names the theme in force and the next. The browser remembers the choice for that
dashboard, and its login page uses it too.

### The Live Indicator

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

### `chunks` and `scrapes`

`chunks` and `scrapes` are **transient in-flight gauges**, not running totals — they count work happening
*right now*, so at rest they sit at `0`. The number after the slash is a health threshold, not a capacity.

- **scrapes `N`/800** — scrape requests currently being serviced. The proxy adds one for each agent it sends a
  scrape to, a [Scrape now](#scrape-now) included, and removes it the moment the agent responds, times out, or
  disconnects. It rises with concurrent in-flight scrapes — many paths at once, a slow endpoint, or a high scrape
  rate — and falls back to `0` as they drain.
- **chunks `N`/800** — large responses currently being reassembled. A response is streamed in chunks only
  when its gzipped body reaches `agent.chunkContentSizeKbs` (default 32 KB); the proxy holds one context per
  such response until its final chunk arrives. Small metrics payloads never touch it, so it stays `0` unless a
  big endpoint is mid-transfer.

Two things keep them near `0` in practice: each entry is removed as soon as its request completes, and the
bar re-samples them on the refresh timer (`proxy.dashboard.refreshIntervalSecs`, ~2 s) rather than on every
scrape — a fast scrape opens and closes well inside that window. To watch them move, point an agent at an
endpoint whose body gzips to 32 KB or more, usually several hundred KB of text (chunks), or drive concurrent or
slow scrapes (scrapes), ideally with a shorter `refreshIntervalSecs`.

The `/800` is the **unhealthy threshold** (`proxy.internal.scrapeRequestMapUnhealthySize` and
`chunkContextMapUnhealthySize`, by default 80% of `maxInFlightScrapeRequests`). When either count reaches
it, both turn amber — a backpressure signal that requests are not draining: agents stuck or slow,
or a leak. Green and near `0` is the healthy resting state.

## The Agents Layout

Agents are listed on the left. Selecting one shows, on the right:

| | |
|---|---|
| **Identity** | agent name, remote address, host name, the [auth identity](security/index.md) it connected as, launch ID, consolidated flag |
| **Liveness** | uptime, idle time, scrape backlog depth, and, while the [stale-agent check](configuration/proxy.md#internal-settings) runs, seconds until it evicts the agent |
| **Paths** | every path this agent has registered, and whether a path is shared with other agents |
| **Recent scrapes** | this agent's latest 25 scrapes across its paths, newest first, with status, duration and payload size |

An agent's recent scrapes come from each path's own history, the one behind the [History](#the-paths-layout) column, so
other agents' busy paths can't push them out. A path several agents share keeps all of their scrapes, and each agent's
panel lists its own.

The remote address is the `host:port` the agent's connection came from, without a reverse DNS lookup. With the
transport filter disabled, as behind an nginx reverse proxy, it reads `Unknown`: the connection there comes from the
reverse proxy, not the agent. Before 4.3.0 it read `Unknown` for every agent.

Selection lives in the URL, so a view of one agent is a link you can bookmark or share.

The page updates **live over a WebSocket** — an agent connecting, disconnecting, or registering a path
appears without a refresh. If the agent you are looking at disconnects, the panel says so explicitly,
naming the agent as the other views do, rather than silently freezing on stale data. (The top-bar **live**
indicator that reflects the connection itself is described under [The status bar](#the-status-bar).)

## The Paths Layout

Every path on one row, with the endpoint behind it and how its last scrape went:

| | |
|---|---|
| **Path** | the registered path Prometheus scrapes, with a button beside it that copies its URL on the proxy, the one to curl, built from the address you opened the dashboard at and the proxy's scrape port, and one that [scrapes it now](#scrape-now) |
| **Agent(s)** | the agent serving it; a consolidated path shows the first plus a `+N` count |
| **Identity** | with [agent authentication](security/index.md) on, the identity that registered the path, which any other agent joining it must share; the legacy shared `agentToken` reads *shared token* |
| **Target** | the URL the agent actually fetches, with a button beside it that copies it to the clipboard |
| **Src** | `cfg` for a statically configured path, `disc` for one from [dynamic discovery](configuration/agent.md#dynamic-target-discovery) |
| **Last scrape / Status / Duration** | the most recent result for that path |
| **History** | the path's recent scrapes as a row of dots, oldest on the left, green for a success and red for a failure, with faint dots holding the places of scrapes still to come, then the share that succeeded |
| **Links** | with [links](#links) configured, the path's links into other tools, such as Prometheus and Grafana, by name or by the tool's icon |

The dashboard explains the columns too: **Column guide**, at the right end of the search bar, opens a short
explanation of each under the bar, which stays open as the table updates.

Status says how the last scrape went; History says whether that is news. A target that fails every scrape and one that
failed once in twenty look the same in Status alone. Each path keeps its last `pathHistorySize` scrapes (default 20)
whatever the other paths are doing, and the percentage is rounded down, so a path with any failure never reads 100%.

The copy button copies the URL as the table shows it. The agent [redacts](security/index.md#credentials-in-urls) a target
URL's user info and query values before sending it to the proxy, so a URL that carries either is copied with
`***` in their place.

### Searching and Filtering

A search box above the table keeps only the rows whose path, agent, target URL or identity contains what you type, as
the row shows it, ignoring case, so `team-a` shows one team's paths, and `/payments_metrics`, copied from Prometheus,
finds that path. **Problems only** keeps the paths whose last scrape failed or whose agent has gone. Both apply as you
type, and while either is on, the table says how many of the paths it is showing.

The search goes into the page's URL, as `/dashboard/paths?q=team-a&problems=1`, so a filtered view is a link you can
bookmark or send. Pressing Enter in the box does the same as typing, and the form works as a plain link without the
page's script.

### Scrape Now

The button after a path's copy button scrapes it at once, through its agent, exactly as Prometheus would, and shows
the result above the table: the status, how long it took, the size, the content type, how many metric families and
samples came back, and the first 2 KB of the body. Only a body in the Prometheus text or OpenMetrics format is counted,
so a protobuf one shows no families or samples. A failed scrape shows the agent's reason instead. A consolidated path
is scraped from every agent and merged, as Prometheus sees it. Use it to check a fix without waiting for Prometheus's
next scrape, or for a curl from a machine that can reach the scrape port.

A scrape from the dashboard isn't one of Prometheus's, so it leaves no trace in what the proxy reports: not in the
`proxy_scrape_*` metrics, the path's History, or the agent's recent scrapes. The [Activity](#the-activity-layout) view
records it instead, with the address of the browser that asked. The proxy tells the agent the scrape came from the
dashboard, so the agent counts it in `agent_dashboard_scrapes_total` rather than its own `agent_scrape_*` counters and
latency histogram. An agent older than this feature can't tell, and counts it as one of Prometheus's. The target sees
it as any other scrape.

So clicks can't crowd out Prometheus, at most four dashboard scrapes run at once, a path is scraped from the dashboard
at most once every five seconds, and each waits at most ten seconds for its target. Past either limit the panel says
why instead. `proxy.dashboard.scrapeNowEnabled = false` removes the button and its route.

### Links

Each row can carry links that open its path in other tools: a Prometheus query, a Grafana dashboard, a runbook, a log
search. Write each as a URL template in `proxy.dashboard.links`, and each row fills in its own path's values. With any
link configured, the table ends with a **Links** column naming each row's links, or showing their tools' icons; each
opens in a new tab.

```hocon
proxy {
  dashboard {
    links = [
      {
        name = "Prometheus"
        url = "http://prometheus:9090/query?g0.expr=up%7Bjob%3D%22{label.job}%22%7D"
        icon = "prometheus"
      }
      { name = "Grafana", url = "http://grafana:3000/d/proxy-targets?var-job={label.job}", icon = "grafana" }
      { name = "Runbook", url = "https://runbooks.example.com/proxy/{path}" }
    ]
  }
}
```

Being a list, `proxy.dashboard.links` must be set in a config file rather than with `-D`.

A link with an `icon` shows its tool's icon, in the tool's own color, in place of its name. Hovering over the icon
shows a tooltip such as *Open in Grafana*, and a screen reader says the name. The dashboard has two, `prometheus` and
`grafana`, matched ignoring case; leave `icon` out, or empty, to show the name. The proxy serves the icons itself, so
the dashboard still needs no access to the internet. The icons are from [Simple Icons](https://simpleicons.org) (CC0);
the marks belong to their owners.

A template can use:

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
`http://prometheus:9090/query?g0.expr=up%7Bpath%3D%22%2F{path}%22%7D`.

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
  placeholder fails too, as does an `icon` the dashboard doesn't have. Any of these stops the proxy at startup with a
  message naming the link.

### Paths Whose Agent Has Gone

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

## The Activity Layout

What the proxy did with its agents and their paths, newest first:

| Event | When |
|---|---|
| `connected` | an agent registered, with the address it connected from and its host name |
| `disconnected` | an agent's connection closed or its stream ended, with how many paths it served |
| `evicted` | the proxy removed an agent it hadn't heard from within [`proxy.internal.maxAgentInactivitySecs`](configuration/proxy.md#internal-settings) |
| `registered` / `unregistered` | an agent added a path, or removed one; re-registering a path it already serves isn't news |
| `taken over` | an agent took a path another agent of the same identity was serving, as a redeploy does |
| `scraped now` | a browser scraped a path with [Scrape now](#scrape-now), with the result and the browser's address |
| `rejected` | the proxy refused a path, with its reason: one its identity may not register, a malformed or overlong path, a path another identity holds, a consolidated agent and a path that isn't (or the reverse), one with more than `maxLabelsPerPath` labels, or one past the agent's `maxPathsPerAgent` or its identity's `maxPathsPerIdentity` |
| `refused` | an identity was already at `proxy.internal.maxAgentsPerIdentity`, so a connect was turned away |
| `bad token` | a call came with no agent token or an unknown one, naming the host it came from |

The last three, and `evicted`, are what **Problems only** keeps, marked in red; a take-over is marked in amber. The
search works as on the Paths layout, matching the event, agent, identity, paths, and detail, and goes into the URL the
same way, and **Column guide** explains this table's columns as it does the Paths table's.

A burst of like events from one agent within five seconds is one row, counted (`registered ×40`) and naming its first
three paths, so an agent registering two thousand paths takes one row rather than the whole feed. A rejection appears
the first time the agent is told it, not on each retry. Bad tokens from one host within a minute are counted on one
row, and at most ten hosts a minute get a row of their own; the rest of that minute's are counted on one, so a flood
from many addresses can't push everything else out of the feed. A connect or a path change appears at once; a refusal
or a bad token, which changes nothing else on the page, by the next refresh. The table is sent to your page again only
when the feed has changed, or your search has, so a row you are reading or selecting isn't replaced on every refresh.

The feed keeps the last `activityLogSize` rows (default 500), in memory only, so a restart empties it. It is a view of
the recent past, for finding out why something didn't appear, not an audit log; the proxy's log has every event.

## The Settings Layout

Every setting the proxy resolved from its flags, environment variables, config file, and defaults, so you can check
what it runs with without reading its startup log:

| Group | What it shows |
|---|---|
| About | the version, launch ID, when the proxy started, where its config came from, and the keys of any `-D` overrides |
| Listeners | the scrape address, its idle timeout and request logging, the agent port, and blitz |
| Agent authentication | off, a shared token, or how many identities; each identity's name and the path globs it may register; reflection; and the transport filter |
| TLS | plaintext, TLS, or mutual TLS, and where each certificate and key comes from |
| Scrapes | the scrape timeout, how many scrapes may be in flight, and the sizes and thresholds that turn the healthcheck unhealthy |
| Agent limits | the stale-agent check, and the limits on what one agent, or one identity's agents, may register and report |
| gRPC server | handshake, keepalive, and connection age settings, with gRPC's default named where one is left unset |
| Service discovery | the endpoint's path and target, and whether agents' `job` and `instance` labels are kept |
| Dashboard | where it listens, its login's username, its limits, what it keeps, its links, and its screens |
| Admin, Metrics, Tracing, Logging | each service's port and paths, the JVM metrics turned on, Zipkin, and the log level |

No secret reaches the page. The agent token, each identity's token, the dashboard's password, and TLS material given as
PEM show only as set or not set, and a `-D` override shows only its key, never its value. File paths, identity names,
their globs, and the login's username are shown: they are what you check when an agent is refused or a scrape fails.

A warning the proxy logs at startup shows here too: an agent port any reachable peer can register on, or agent tokens
sent without TLS.

The settings don't change while the proxy runs, so the page is sent them once when it opens, and a value you select
stays selected. If the proxy restarts, the page reconnects and shows the new process's settings.

## The Screens Page { #screens }

An operator works across several screens: the proxy's dashboard, each agent's, Prometheus, Grafana, and tools of their
own. List them in `proxy.dashboard.screens`, and the proxy serves one page that holds them all, at `/screens` on the
dashboard's port, for example `http://proxy-host:8094/screens`. The dashboard's top bar links to it, and the port's
root, `http://proxy-host:8094/`, leads to it, where it leads to the dashboard while no screens are listed. The root
needs no login: it only redirects, and the page asks for one.

```hocon
proxy.dashboard.screens = [
  { name = "agent-east", url = "http://agent-east.corp.example.com:8095/dashboard" }
  { name = "Prometheus", url = "http://prometheus.corp.example.com:9090", start = "/alerts" }
  { name = "Grafana",    url = ${?GRAFANA_URL}, start = "/d/overview?kiosk" }
]
```

Each screen has a tab, after the proxy's own dashboard, which is always first. A tab's dot is green while its screen
answers and red while it doesn't, checked every 5 seconds while the page is visible with a `HEAD` of the screen's `url`
(the proxy's own `/session`), which the proxy's and the agents' dashboards answer with a 204 before any login, since it
says only that they're up. A pane whose screen isn't answering says so, then reloads by itself once it answers again.
**One** shows the selected screen at full size, **Split** two side by side, each at its own narrow layout (shift-click a
tab to put its screen on the right), and **All** every screen in a grid, each laid out at 1280×800 and scaled to its
tile; click a tile's title to open it. Switching tabs or layouts never reloads a screen, so a dashboard keeps the agent
or path you selected. Each pane's **Open in a tab** opens its screen on its own.

A link can say what the page shows, after its `#`:

| Link | Shows |
|---|---|
| `/screens#all` | Every screen in the grid |
| `/screens#agent-east` | One screen, as you left it, or where it starts the first time |
| `/screens#agent-east/connection` | One screen, at its `url` plus `/connection`, query string included, such as `#proxy/activity?q=agent-rogue` |
| `/screens#proxy/paths+grafana` | Two screens side by side, left then right |

A screen's id in a link is its name in lowercase, each run of other characters a `-`; the proxy's own is `proxy`. A link
with a path loads it, reloading a screen already open; a bare id only brings the screen up. A link opened while the page
is already open in another tab goes to that tab, and the new tab closes itself, or, where the browser won't let it,
says where the link went.

Every screen has to allow framing, and the page can't tell a screen that refused it from a blank one, so it looks
empty; use **Open in a tab** for such a screen. Agents' dashboards allow it from 4.7.1 unless their
`agent.dashboard.framingEnabled` is `false`, and Prometheus always does. Grafana refuses it until `allow_embedding` is
on, `GF_SECURITY_ALLOW_EMBEDDING=true` in its environment.

The page is behind the dashboard's [login](#login), and each screen keeps its own, which it shows in its frame the first
time. A login inside a frame works only when the screen is on the same site as the page, a scheme and a registered
domain, since the dashboards' session cookies and Grafana's are `SameSite=Lax`; otherwise sign in with **Open in a
tab**. For one login across every screen, put single sign-on in front of them all, such as an authenticating reverse
proxy on one domain. The page is the proxy's, so while the proxy is down a page already open keeps its frames, but
can't be reloaded.

The proxy refuses to start with a screen whose name is empty or longer than 64 characters, two names with the same id, a
name whose id is `proxy` or `all`, a `url` that isn't an absolute `http` or `https` URL or that carries a username or
password, which the page would show anyone who can read the dashboard, a `start` that doesn't begin with `/` or `?`,
more than 32 screens, or, with screens listed, a `proxy.dashboard.path` of `/screens`, the page's own path. A `url` read
from an unset variable, as `${?GRAFANA_URL}` is, is empty, and refused by the screen's name. The **Settings** view lists
each screen with its `url`, its query values masked, and where it starts.

## Why It Is on Its Own Port

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

## Offline and Airgapped Deployments

The dashboard loads no external assets. [htmx](https://htmx.org) ships inside the JAR as a WebJar dependency
and is served from the classpath, so the dashboard renders with no outbound network access at all.

That matters for this product specifically: a proxy exists to bridge a network boundary and frequently
runs where outbound internet is restricted. A CDN-hosted script would leave a blank page in exactly that
environment — and it would fail when someone opens the page during an incident, not at deploy time.

## Cost

The dashboard adds little work to the scrape path: each scrape leaves a record in its path's history. Updates are
driven by an internal event bus whose publish operation is non-blocking, so it can never delay an agent registration
or a scrape.

State is collected once per update and shared across every connected browser, so the cost does not scale
with the number of open dashboards. Loading or reloading a page reuses that collection too, as long as it is
less than `refreshIntervalSecs` old and no agent or path has changed since, so requesting pages in a loop
can't make the proxy collect any faster. Counters that drift rather than change — backlog depth, map sizes,
eviction countdowns — refresh on `refreshIntervalSecs` rather than on every event, and an Activity page is sent its
table only when the feed or its search has changed.

## Running an HA Pair

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

Without a [login](#login), anyone who can reach the dashboard's port can read it. Keep the port on an internal
network, or bind it to a private address with `proxy.dashboard.host`; without a login, the proxy warns at startup
when the dashboard listens on all interfaces.

A browser inside that network can still be used to reach it. A web page can do that in three ways; the dashboard
guards against the first on its own, and against the other two with a setting each:

- **A WebSocket from another site.** Browsers let any page open a WebSocket to any address, so the
  dashboard refuses a handshake whose `Origin` is not its own host. Behind a reverse proxy that rewrites
  the Host header, the browser's origin is the proxy's public name, so list it in
  `proxy.dashboard.allowedOrigins`, or pass the browser's Host on; see
  [Behind a reverse proxy](#behind-a-reverse-proxy).
- **DNS rebinding.** A page can point its own DNS name at the dashboard's address, after which the browser
  sends that name as both `Origin` and `Host`, and the Origin check passes. List the names you reach the
  dashboard by in `proxy.dashboard.allowedHosts` and it refuses any other host name with a 403, on every
  route; IP addresses, `localhost`, and the hosts in `allowedOrigins` are always allowed. This check is off
  until you set it.
- **Framing.** A page can show the dashboard in a frame. That's allowed by default (`proxy.dashboard.framingEnabled`),
  so a portal or a page of your own can embed the dashboard, and so can a page on another site that a viewer opens: it
  can hide the dashboard under content of its own and steer the viewer's clicks onto Scrape now or Log out, since the
  frame's requests come from the dashboard's own page and pass the Origin check, though it can't read what the frame
  shows. With a login set, a page on another site gets only the login page, since the session cookie (`SameSite=Lax`)
  isn't sent to a frame on another site, so a login in a frame works only on the same site. A site is a scheme and a
  registered domain, so a page on another port of the dashboard's host, or on a sibling subdomain, is on the same site,
  and frames the signed-in dashboard. Set `proxy.dashboard.framingEnabled = false` and every response forbids framing
  (`Content-Security-Policy: frame-ancestors 'none'`, and `X-Frame-Options: DENY` for older browsers), so no page, on
  any site, can frame it. With [screens](#screens) listed, `false` still lets the proxy's own `/screens` show the
  dashboard: every response says `frame-ancestors 'self'` and `X-Frame-Options: SAMEORIGIN` instead, so only a page of
  the dashboard's own origin can frame it.

What framing lets a page do to the dashboard is small: it can't read the frame, and the only clicks it can steer are
Scrape now, within its cooldown, and Log out. The setting is there mostly for audits. Security scanners such as ZAP,
Burp, and Nessus flag a response with neither `X-Frame-Options` nor `frame-ancestors` as missing an anti-clickjacking
header, so an organization that scans its internal services finds the dashboard by default, and
`framingEnabled = false` answers that finding with one setting, no reverse proxy needed. If your policy requires the
header and the dashboard is [behind a reverse proxy](#behind-a-reverse-proxy), you can add it there instead, as nginx
does with:

```nginx
add_header Content-Security-Policy "frame-ancestors 'none'" always;
add_header X-Frame-Options DENY always;
```

The dashboard logs a request it refuses for its `Origin` or `Host` at INFO, naming the setting that would allow it, at
most once a minute for each address the requests come from, and the rest at DEBUG. It cuts the header to 270
characters, the longest a real one can be, and escapes its control characters, since a client sends it before any
login.

The Activity layout shows the address each call with a bad token came from, so anyone who can read the dashboard
can see who has tried the agent port.

Anyone who can reach the dashboard, with its login when one is set, can also have the proxy [scrape a path](#scrape-now)
and read the first 2 KB of what it returns, even with the scrape port firewalled to Prometheus. The route takes a request only from the dashboard's own
page: it applies the same Origin check as the WebSocket, and requires the header the page's script sends, which a page on
another site can't add without the proxy's permission, so a link or form elsewhere can't make it scrape. Set
`proxy.dashboard.scrapeNowEnabled = false` where that is more than the dashboard's readers should have.

A login doesn't replace either guard, so the dashboard keeps both with a login set, and checks the Origin of the login
and Log out forms as well.

Open pages, one WebSocket each, are also capped at `proxy.dashboard.maxSessions` (default 50). A client that sends an
oversized message is disconnected, and so is one that stops reading, once 32 updates are waiting for it, which frees its
place under the cap; no one client can hold up the others' updates. Neither list stops someone who can reach the port
directly; a login does. Both are lists, so set them in a config file; see
[Dashboard configuration](configuration/proxy.md#dashboard).

### Behind a Reverse Proxy

The dashboard's live updates come over a WebSocket, at `/dashboard/events`, so a reverse proxy in front of it has to
pass the WebSocket's handshake on, and the handshake has to pass the Origin check. With a plain `proxy_pass`, nginx
passes each request as HTTP/1.0 and drops the `Upgrade` and `Connection` headers, so the dashboard answers the
handshake with 400. With those headers but nginx's default `Host`, the upstream's name, the browser's `Origin` doesn't
match it, and the dashboard refuses the handshake, Scrape now, and the login form with 403, and logs
`Refused a dashboard request from origin …; add it to proxy.dashboard.allowedOrigins if expected`. Either way,
without a login the page loads and its [live indicator](#the-live-indicator) stays on **reconnecting…**; with a login,
the login form is refused with 403, so the page never loads. Pass both, and the browser's `Host`:

```nginx
# Connection: upgrade on a WebSocket handshake, and close on anything else.
map $http_upgrade $connection_upgrade {
  default upgrade;
  ''      close;
}

server {
  listen 443 ssl;
  ssl_certificate     /etc/nginx/certs/server.pem;
  ssl_certificate_key /etc/nginx/certs/server.key;

  location /dashboard {
    proxy_pass http://proxy-host:8094;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header Host $http_host;
  }

  # The screens page, outside the dashboard's path, which opens no WebSocket.
  location = /screens {
    proxy_pass http://proxy-host:8094;
    proxy_set_header Host $http_host;
  }
}
```

`$http_host` is the `Host` header as the browser sent it, port included; `$host` drops the port, which breaks the match
on a port other than the scheme's. To keep nginx's own `Host`, list the address the browser opens in
`proxy.dashboard.allowedOrigins` instead, for example `["https://dashboard.example.com"]`. Keep the path as it is: the
page's links, its assets, and its WebSocket are all under `proxy.dashboard.path`, and the [screens page](#screens) is at
`/screens`, so a prefix nginx adds or strips breaks them. The session cookie is set at those two paths, so leave its
paths alone too, rather than have nginx rewrite cookie paths. Over `https`, the dashboard marks its session cookie
`Secure`. Every login reaches the dashboard from nginx's address, so the per-address limit on wrong logins is
everyone's. The agent's dashboard takes the same settings under `agent.dashboard`. The [nginx Demo](nginx-demo.md) shows
each step.

## Limits

- **One action.** Nothing on the dashboard changes the proxy's state. Its one action, [scrape now](#scrape-now),
  scrapes a target as Prometheus would.
- **One shared login, over plain HTTP.** The [login](#login) is one username and password for everyone, and the
  dashboard has no TLS of its own. Without a login, rely on network isolation, as with the admin port; see
  [Exposure](#exposure) for what the dashboard itself guards against.
- **Consolidated paths show one agent's target.** When several agents register the same path, the row
  names one of them, with the others counted in the `+N`, and shows that agent's target URL and source.
  When it leaves, the row names the next agent and shows its target. The path's labels, which service
  discovery serves, stay the first registrant's.
- **Departed paths age out.** A gone path stays for `departedPathRetentionSecs` (default 3600) after its agent
  left or its last scrape, whichever is later, at most 1,000 of them at once, and none survives a proxy restart.
- **An agent's recent scrapes are what its paths keep.** Its panel lists its latest 25 from the `pathHistorySize`
  scrapes (default 20) each path keeps, so an agent with one path lists at most 20, and a path shared with other agents
  holds theirs in the same 20. They go as their paths' histories do, and none survives a proxy restart.
- **The activity feed is short-lived.** It keeps the last `activityLogSize` rows (default 500), and none survives a
  proxy restart.
