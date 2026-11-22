# Network demo

A self-contained demo of what the network between the agents and the proxies does to Prometheus Proxy, and how the
proxy and agents cope. It runs a proxy and its standby, each with its web dashboard, two agents behind a simulated
firewall, each with a dashboard of its own, a simulated WAN between them
([Toxiproxy](https://github.com/Shopify/toxiproxy)), a set of simulated services, and Prometheus, Grafana, and Zipkin,
all on one laptop. It shows a far link, a thin one and filtering at the agent, a proxy that goes away, a link that dies
silently, a flaky link followed in Zipkin, and the whole WAN going down and coming back, each seen from both ends: on
the proxies' dashboards and on the agents' own.

It follows the [basics demo](../basics), which shows the dashboard, Grafana, and the proxy's features: start with
that one. This one keeps the same pieces and changes only the link through the firewall, the thing the product
exists to cross. The website's [Network Demo](https://prometheus-proxy.com/network-demo/) page walks
through the same scenarios with screenshots. The [Kubernetes demo](../kubernetes) follows this one, and deploys the
same proxy and agent with their Helm charts on two kind clusters; it needs kind, `kubectl`, and Helm as well as Docker.

```
 network-app  (simulated services: orders and payments at headquarters, inventory and shipping at a branch)
     ▲  scraped from inside the "firewall"
 agent-hq ──────┐  headquarters: two small services, the steady reference; dashboard :8196
 agent-branch ──┤  a branch office: a large inventory payload, with one big family no one uses, and a small
                │  service; dashboard :8197
                │  gRPC, outbound only; each agent dials its link to the primary, then its link to the standby
 wan (Toxiproxy): four links, one per agent per proxy
    hq-to-proxy:50051   hq-to-standby:50052   branch-to-proxy:50053   branch-to-standby:50054
                ▼
   proxy ── dashboard :8194          proxy-standby ── dashboard :8195
     ▲  HTTP service discovery (/discovery) and scrapes through :8080, on each proxy; each proxy's own metrics directly
 Prometheus :9190 (5s scrapes, 4s timeout, fast alerts) ──► Grafana :3100       every proxy and agent ──► Zipkin :9511
```

The proxies see every agent at the WAN's address, as they would behind a NAT. Each agent's endpoints are its two
links, so the dashboards' "via" line names the link it connected through.

## What you need

The proxy and agent are the latest release's images, from Docker Hub, the same ones the basics demo runs, so there's
nothing to build. On macOS or Linux, the demo needs only:

- [Docker](https://docs.docker.com/get-started/get-docker/) with Compose v2: Docker Desktop, or Docker Engine with the
  Compose plugin.
- `git`, to clone the repository, and GNU `make`. On macOS, the Xcode command-line tools (`xcode-select --install`)
  have both. Where GNU make is installed as `gmake`, type `gmake` wherever these instructions say `make`.
- `curl`, which the demo uses to wait for each service to answer.
- A web browser.

## Quick start

Clone the repository at the latest release, then run the demo's commands from its own directory, where `make help` lists
its targets:

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.6.1 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/network
make up      # pulls the images, starts everything, and prints the URLs
make tour    # walks through the guided tour below, pausing before each step
make down    # stops it and removes its containers and network
```

The first `make up` pulls the proxy, agent, Prometheus, Grafana, Zipkin, Toxiproxy, and Python images, which takes a few
minutes. After that it starts in under a minute, even without a network connection.

| What | URL |
|---|---|
| Proxy dashboard | <http://localhost:8194/dashboard> |
| Standby proxy's dashboard | <http://localhost:8195/dashboard> |
| agent-hq's dashboard | <http://localhost:8196/dashboard> |
| agent-branch's dashboard | <http://localhost:8197/dashboard> (its connection at `/connection`, its history at `/activity`) |
| Grafana (no sign-in needed; admin / admin to edit) | <http://localhost:3100> |
| Prometheus | <http://localhost:9190> (alerts at `/alerts`) |
| The proxy's service discovery | <http://localhost:8180/discovery> |
| Zipkin | <http://localhost:9511/zipkin/> |

`make open` opens both proxy dashboards, agent-branch's dashboard, Grafana, and Prometheus's alerts in your browser.

Every port is the basics demo's plus 100, so both demos can run at once. If a port is already taken, move it, for
example with `NETWORK_DEMO_GRAFANA_PORT=3101 make up`. The variables are `NETWORK_DEMO_DASHBOARD_PORT`,
`NETWORK_DEMO_STANDBY_DASHBOARD_PORT`, `NETWORK_DEMO_HQ_DASHBOARD_PORT`, `NETWORK_DEMO_BRANCH_DASHBOARD_PORT`,
`NETWORK_DEMO_GRAFANA_PORT`, `NETWORK_DEMO_PROMETHEUS_PORT`, `NETWORK_DEMO_PROXY_PORT`, and `NETWORK_DEMO_ZIPKIN_PORT`;
the proxy dashboard's links into Prometheus and Grafana keep the default ports (`config/proxy.conf`). Every port binds
to `127.0.0.1` only. The dashboards run without their login and Grafana uses its default admin password, so this setup
is for a laptop, not for anything shared.

## What's running

| Service | Role |
|---|---|
| `proxy` | The proxy, outside the firewall. Its dashboard is on; service discovery tells Prometheus about every path the agents connected to it register. |
| `proxy-standby` | The standby: the same config, with its own dashboard. Each agent lists its link to the standby second, so the standby serves nothing until an agent can't connect to the primary. |
| `wan` | Toxiproxy, the WAN between the firewall and the proxies: four links, one per agent per proxy, each reached by its own name. `demo.sh` degrades them with Toxiproxy's toxics, and takes them down and up. |
| `agent-hq` | The agent at headquarters. It scrapes the orders and payments services and its own metrics. The scenarios that degrade one agent's links leave its links alone unless they're given its name, so it is the steady reference. Its [dashboard](https://prometheus-proxy.com/agent-dashboard/) is on, on port 8196. |
| `agent-branch` | The agent at a branch office. It scrapes the inventory service, whose payload is about 496,000 characters (about 56 KB gzipped, so the agent sends it in chunks), the shipping service, and its own metrics. Its dashboard is on, on port 8197, and shows the agent's side of each scenario: its connection, each proxy endpoint's last error, and what has happened, newest first. |
| `network-app` | The simulated services (`app/network_app.py`). Traffic follows a 5-minute "day", so the graphs move. |
| `prometheus` | Scrapes every agent's paths through whichever proxy advertises them, every 5s with a 4s timeout, and both proxies' own metrics directly, from this side of the WAN. It loads the demo's fast alerts (`prometheus/network-alerts.yml`). |
| `grafana` | Opens on the **Prometheus Proxy Network Demo** dashboard. |
| `zipkin` | Every proxy and agent reports a span for each gRPC call between them but heartbeats, and each scrape's spans, under its own name. |

## Guided tour

`make tour` walks through these parts one step at a time. To follow them here instead, run the scenario commands
from `demos/network`; each one prints what to watch. `./demo.sh heal` ends each part: it removes every toxic, brings back every link and
proxy, and returns any agent on the standby to the primary. Leave about a minute between parts so the graphs catch up.

### 1. Two agents reach the proxy over a simulated WAN

These are the basics demo's proxy and agents, now with a simulated WAN between them.

- Open the proxy's dashboard. Two agents are connected; click agent-hq: **via hq-to-proxy:50051 (1 of 2)**, its link
  to the primary, the first of its two endpoints. agent-branch is **via branch-to-proxy:50053 (1 of 2)**. The standby's
  dashboard, <http://localhost:8195/dashboard>, has no agents.
- Open agent-branch's own dashboard, <http://localhost:8197/dashboard>. Each agent serves one, from behind the
  firewall, showing what only the agent can see. Its three paths are registered, each with a green light and its last
  scrapes under its URL. On its **Connection** tab it's connected through branch-to-proxy:50053, and **Proxy
  endpoints** lists its two links in failover order, the one in use tagged **in use**. agent-hq's is at
  <http://localhost:8196/dashboard>. The later parts come back to the Connection view and the **Activity** tab.
- Open Grafana. Every target is up on **Target health**, and **Scrape time through the WAN, by agent** shows the two
  agents side by side, each well under a tenth of a second.

### 2. A high-latency link slows scrapes, but nothing fails

```bash
./demo.sh far             # agent-branch's links add 250 ms, give or take 50 ms, each way
```

- In Grafana, agent-branch's scrape time steps up to about half a second, one round trip more, while agent-hq's stays
  flat. In agent-branch's **Recent scrapes** on the proxy's dashboard, every scrape still succeeds.
- Nothing fails. The agent's connection is already open, so a scrape needs no new handshake through the firewall: it
  costs the round trip and nothing more.

```bash
./demo.sh heal
```

### 3. A low-bandwidth link fails scrapes; filtering at the agent fixes it

```bash
./demo.sh thin            # agent-branch's links carry 8 KB/s toward the proxies
```

- The inventory payload is about 56 KB gzipped, which takes 7 seconds on this link, and Prometheus gives up at 4.
- Every path shares the link, so it backs up behind the inventory: within about 15 seconds the shipping service and
  the agent's own metrics fail too, and at about 30 seconds **NetworkTargetDown** fires for all three. agent-hq, on
  its own links, is untouched.
- agent-branch stays connected the whole time. Its answers keep arriving, too late for Prometheus, and each one tells
  the proxy the agent is alive, so on the proxy's dashboard its **idle** time stays near zero and nothing is evicted:
  a saturated link is slow, not dead. Part 5 shows a link that is.
- agent-branch's own dashboard shows the other side: every path's last scrape is a success, fetched from its target in
  a fraction of a second. The targets are fine; the time goes on the link.

```bash
./demo.sh filter          # restarts agent-branch with a filter that drops the inventory's price list
```

- The filter (`config/agent-branch-filtered.conf`) drops the per-item price family, which no dashboard uses, at the
  agent, before the payload is compressed and chunked. In agent-branch's **Recent scrapes** on the proxy's dashboard,
  the inventory falls from about 496,000 characters to about 199,000, 21 KB gzipped, and each scrape takes about 3
  seconds on the thin link.
- Every path is green again within about 5 seconds, and **Filter bytes saved at the agent** climbs to about 60 KB/s.
  What never crosses the WAN costs nothing to send, store, or query.
- The restarted agent connects at once. Its old connection stays on the proxy dashboard's agent list, with no paths,
  for about 20 seconds more: what it had queued has to drain through the thin link before its close gets through.
- agent-branch's own dashboard reconnects to the restarted agent by itself. The inventory path's page,
  <http://localhost:8197/dashboard/paths/inventory_metrics>, shows the filter's rules and how many lines and bytes it
  has dropped, and its **Recent scrapes** give each body's size before and after the filter.

```bash
./demo.sh heal
./demo.sh unfilter        # back to the full payload
```

`heal` alone, without the filter, brings every path back within a second or two: what the thin link had queued
drains at full speed.

### 4. The primary proxy stops, and the agents fail over to the standby

Open agent-branch's **Connection** view, <http://localhost:8197/dashboard/connection>, beside the standby's
dashboard. The proxies can show an agent only once it has connected to them; this view shows the agent's side of the
failover as it happens: the endpoint it's on or trying, and the last error it got connecting to each.

```bash
./demo.sh proxy-down      # stops the primary proxy
```

- The primary's connections close, so both agents know at once. Within about 4 seconds they're on the standby: its
  dashboard, <http://localhost:8195/dashboard>, shows each one **via hq-to-standby:50052 (2 of 2)** or
  **via branch-to-standby:50054 (2 of 2)**, marked **failed over**. Nobody touched the agents.
- Grafana: within about 15 seconds every service is up again on **Target health**, scraped through the standby, and
  **Connected agents, by proxy** has moved to the standby. Only `prometheus-proxy`, the primary's own metrics, stays
  red, and **NetworkTargetDown** fires for it alone. **Agents off their primary** shows each agent at 1, the second
  endpoint in its list, from `agent_proxy_endpoint_index`, which each agent reports itself.
- agent-branch's Connection view reads **connecting**, then **failing**, as its connect through branch-to-proxy:50053
  fails, then **connected** through branch-to-standby:50054, marked **failed over**. Under **Proxy endpoints**,
  branch-to-proxy:50053 keeps the error its connect got: a proxy that's down shows there, not only in the agent's log.
- Its **Activity** view, <http://localhost:8197/dashboard/activity>, lists what the agent went through, newest first:
  **disconnected**, **connect failed** on branch-to-proxy:50053, **failed over** from it to branch-to-standby:50054,
  **connected**, and **registered**, one line for its three paths. agent-hq's dashboard,
  <http://localhost:8196/dashboard/activity>, shows the same for its own links.

```bash
./demo.sh proxy-up        # the primary returns; the agents stay on the standby
```

An agent goes back to the head of its list only when its connection drops, so the agents stay on the standby, and
agent-branch's Connection view doesn't change: branch-to-proxy:50053 keeps its error until a connect through it
succeeds. `heal` closes each agent's connection through its standby link once, and they return to the primary within
seconds. agent-branch's Activity view adds **disconnected**, **back to primary**, and **connected**, and its Connection
view drops the **failed over** tag:

```bash
./demo.sh heal
```

### 5. A link goes silent; the agent detects it and fails over

Contrast this with part 4, where the connection closed. Here agent-branch's link to the primary stops passing anything,
the agent's goodbye included, and closes nothing: what a stateful firewall or NAT that drops a connection does. Nothing
tells either end. It takes about 50 seconds to play out. Open agent-branch's Connection view,
<http://localhost:8197/dashboard/connection>, beside the primary's dashboard: one shows what the proxy hears, the other
what the agent tries.

```bash
./demo.sh cut             # agent-branch's link to the primary goes silent
```

- Within about 10 seconds agent-branch's scrapes start timing out, and on the primary's dashboard its **idle** time
  climbs and its **evicted in** countdown runs.
- The agent's heartbeat fails three times, with a 5-second deadline each, so at about 20 seconds it gives up on the
  connection. It dials the head of its list again, the same dead link, where the connect waits out its 30-second
  deadline (`agent.grpc.unaryDeadlineSecs`). agent-branch's Connection view shows that wait: from about 20 seconds it
  reads **connecting**, with branch-to-proxy:50053 tagged **trying**, until the deadline passes, and then that endpoint
  gets its error, `DEADLINE_EXCEEDED`, and the agent fails over.
- Meanwhile the primary, which hears nothing, evicts the agent at about 30 seconds, and **NetworkAgentEvicted**
  fires. Grafana's **Disconnects, by proxy and reason** shows it as an `eviction` at the primary. This is the proxy's
  side, as in the basics demo's frozen agent; here we watch the agent's.
- At about 50 seconds agent-branch connects through its other link: the standby's dashboard shows it **via
  branch-to-standby:50054 (2 of 2)**, failed over, while the primary is still up, and Grafana's **Agents off their
  primary** shows it at 1. agent-hq is untouched throughout.
- agent-branch's **Activity** view, <http://localhost:8197/dashboard/activity>, keeps the whole sequence:
  **disconnected**, **connect failed** on branch-to-proxy:50053, **failed over** to branch-to-standby:50054,
  **connected**, and **registered**. The proxies saw only the eviction and the new connection.

```bash
./demo.sh heal
```

### 6. A link that keeps dropping: the agent reconnects, traced in Zipkin

```bash
./demo.sh flaky           # agent-branch's link to the primary drops its connection about every 15 seconds
```

- The link closes each connection once it has carried 170 KB toward the proxy, about 15 seconds of agent-branch's
  traffic. Each time, the agent reconnects within milliseconds and stays on the primary: a connection that registered
  and then dropped retries from the head of the list.
- Grafana's **Agent connects, by agent** climbs, and **Disconnects, by proxy and reason** shows each drop at the
  primary as a `termination`: the connection closed, rather than going quiet. Most scrapes succeed; the one in flight as
  a connection drops fails as `agent_disconnected` in **Scrape outcomes at the proxies**.
- On agent-branch's Connection view, the line under its state counts its connects, one more with each drop, and it
  stays on branch-to-proxy:50053 with no **failed over** tag. Its Activity view shows each drop as **disconnected**,
  then **connected** and **registered**.
- Every proxy and agent reports a span to Zipkin for each gRPC call between them but heartbeats, under its own
  service name. In Zipkin, the search is `serviceName` = `agent-branch` and `spanName` = `proxyservice/connectagent`.
  Open <http://localhost:9511/zipkin/?serviceName=agent-branch&spanName=proxyservice%2Fconnectagent>, which fills that search in, and click **Run Query**. Each reconnect is a `connectagent` trace,
  then `registeragent` and a `registerpath` for each path. Zipkin joins the agent's client side and the proxy's server side of each call into one span, so the
  trace names both services, and its annotations mark when the agent sent the call and the proxy answered it.
- A connection's scrape streams (`readrequestsfromproxy`, `writeresponsestoproxy`, `writechunkedresponsestoproxy`) are
  traced too, and appear as the connection closes, each about 15 seconds long: as long as the connection lasted.
- Each scrape is a trace of its own: the proxy's `scrape` span, a `forward` span for the agent it went to, and under that
  agent-branch's `fetch` of the target, which joins the trace the proxy sent with the request. Open
  <http://localhost:9511/zipkin/?serviceName=agent-branch&spanName=fetch> and click **Run Query**. A failed scrape's spans are red: the one in flight as a connection drops ends
  its `forward` span as `agent_disconnected`.

```bash
./demo.sh heal
```

### 7. The whole WAN goes down: an alert fires, and the agents reconnect

```bash
./demo.sh wan-down        # every link goes down
```

- Both agents leave both proxies' dashboards at once, and retry every 3 seconds, alternating between their two links.
- Their paths leave the proxies' `/discovery`, so within about 15 seconds their targets vanish from Prometheus rather
  than staying red: on **Target health** their rows go red for a scrape or two, then stop.
- Prometheus scrapes the proxies directly, on its own side of the WAN, so it still sees them. The **Agents** stat reads
  0, and at about 20 seconds **NetworkNoAgentsConnected** fires, built on the proxies' `proxy_agent_map_size`.
- The proxies can't show an agent that can't reach them, but its own dashboard can. agent-branch's Connection view
  reads **failing**, and the endpoint it's trying alternates between its two links, each with the error from its last
  connect. Its Activity view records **disconnected**, then **connect failed** once for each link, since a repeat of
  the same failure isn't listed again, and **failed over**, which repeats with each pass back through its link to the
  primary, counted on one line rather than listed each time.

```bash
./demo.sh wan-up          # every link comes back
```

- Within a few seconds each agent reconnects, through whichever of its links it tries next, so one may land on the
  standby. Their targets return, the alert resolves, and the graphs keep a gap where nothing could be scraped.
- agent-branch's Connection view reads **connected** again, marked **failed over** if it reached its link to the
  standby. Only the endpoint it connected through loses its error; the other keeps its error until a connect through
  it succeeds.

```bash
./demo.sh heal            # returns any agent on the standby to the primary
```

That's the tour. The same proxy and agent, deployed on Kubernetes with their Helm charts, are in the
[Kubernetes demo](../kubernetes), which needs kind, `kubectl`, and Helm as well as Docker.

## Scenario reference

The optional `[agent]` is `agent-branch` (the default) or `agent-hq`.

| Command | Effect | How it works |
|---|---|---|
| `demo.sh far [agent]` | Adds 250 ms of latency, give or take 50 ms, each way on both of the agent's links | A Toxiproxy `latency` toxic each way on both links |
| `demo.sh thin [agent]` | Caps both of the agent's links at 8 KB/s toward the proxies | A Toxiproxy `bandwidth` toxic toward the proxies on both links |
| `demo.sh filter` / `unfilter` | Restarts agent-branch with or without the inventory filter | Recreates agent-branch's container with `config/agent-branch-filtered.conf`, whose `agent.filters` drops `inventory_item_price_dollars`, or with `config/agent-branch.conf` |
| `demo.sh proxy-down` / `proxy-up [proxy]` | Stops or starts a proxy (default `proxy`, the primary; or `proxy-standby`) | `docker compose stop` / `start`; an agent whose connection closes dials its next endpoint |
| `demo.sh cut [agent]` | Silences the agent's link to the primary, both ways, closes included | Two `timeout` toxics with a timeout of 0, which drop all data, and two `slow_close` toxics, which hold the closes |
| `demo.sh flaky [agent]` | Closes each connection through the agent's link to the primary after about 15 seconds of its traffic | A `limit_data` toxic, which closes each connection after a set number of bytes |
| `demo.sh wan-down` / `wan-up` | Takes every link down, or brings every link back | Disables or enables all four Toxiproxy links; a disabled link closes its connections and refuses new ones |
| `demo.sh heal` | Removes every toxic, brings back every link and proxy, and returns any agent on the standby to the primary | Removes the toxics, enables the links, starts the proxies, and disables an agent's link to the standby for a second, so it reconnects to the primary |
| `make tour [PART=n]` | Walks through the guided tour above, from part 1 or the one given, pausing before each step |  |
| `make status` | The containers, each link's state and toxics, and the URLs |  |
| `make logs [SERVICE=name]` | Follows the logs, of every service or the one given (`wan` is Toxiproxy) |  |

`heal` and `unfilter` undo every scenario. For a clean slate, `make down && make up`.

## How it's put together

| File | What it sets up |
|---|---|
| `docker/compose.yml` | The services, their ports, the WAN's network aliases, and their images |
| `config/toxiproxy.json` | The WAN's four links: each one's name, the port it listens on, and the proxy it reaches |
| `config/proxy.conf` | The dashboard, service discovery, a shared agent token, tracing, and a 30-second eviction limit, so part 5 fits a demo |
| `config/proxy-standby.conf` | The standby: `proxy.conf`, with its own scrape address for service discovery |
| `config/agent-hq.conf`, `config/agent-branch.conf` | Each agent's two links in order, its token, its dashboard and the port it listens on, tracing, paths and their labels (the `job` Prometheus uses), and a 3-second scrape timeout |
| `config/agent-branch-filtered.conf` | `agent-branch.conf`, dashboard included, plus the filter that drops the inventory's price list, for part 3 |
| `prometheus/prometheus.yml` | One HTTP service discovery job against both proxies, and one job for each proxy's own metrics |
| `prometheus/network-alerts.yml` | The fast demo alerts: a target down, an agent evicted, and no agents connected |
| `grafana/` | The provisioned datasource, dashboard folder, and the network dashboard |
| `app/network_app.py` | The simulated services |
| `Makefile` | The demo's targets: `make up`, `make up-local` (which runs the proxy and agent built from this checkout, for trying a change to them), `down`, `status`, `open`, `tour`, and `logs`, which `make help` lists, and `screenshots`, which it leaves out, since it's for maintaining the site |
| `demo.sh` | Starting, stopping, and the scenarios. It drives the WAN with `toxiproxy-cli` inside the `wan` container, under fixed toxic names, so `heal` finds them all. Its `NEEDS_RELEASE_AFTER` is the latest release that predates what the demo shows: until a later one is out, `make up` builds the proxy and agent from this checkout, as `make up-local` does, so set it to the latest release when the demo comes to use something unreleased |
| `bin/screenshots.sh` | The Network Demo page's screenshots, in the dark and light themes. `make screenshots` builds and starts the demo, waits a minute so the agents' uptimes read in minutes, plays parts 2 to 7, and writes them to `website/prometheus-proxy/docs/images/network-demo/` (needs Chrome, ImageMagick 7, and `cwebp`). While checking a change, `SKIP_UPTIME_WAIT=1` skips the minute, and `WAIT_SECS` sets how long each wait for a scene may take before the script fails (default 150): `make screenshots SKIP_UPTIME_WAIT=1 WAIT_SECS=60` |

The toxics behind each scenario: `far` is a `latency` toxic on both streams of both of the agent's links; `thin` a
`bandwidth` toxic toward the proxies on both; `cut` a `timeout` toxic (with no timeout, so it never closes) on both
streams of the link to the primary, plus a `slow_close` toxic on each, so the agent's own close doesn't cross either;
and `flaky` a `limit_data` toxic toward the proxy on the link to the primary. Toxiproxy's `reset_peer` toxic doesn't
suit `flaky`: it holds a connection's data until it resets it, so an agent could never register through it.

From the repository root, `make check-rules` validates the Prometheus config and rules with promtool, and
`ConfigFileKeysTest` checks every key in `config/*.conf` against `config/config.conf`, and that each file loads as the
proxy or agent loads it.

To change the dashboard, edit `grafana/dashboards/network-overview.json` and run
`docker compose -f docker/compose.yml restart grafana`. Grafana doesn't reliably notice a change to a mounted file, and
Prometheus doesn't notice one at all, so `make up` reloads Prometheus's config and restarts Grafana each time. Toxiproxy
reads `config/toxiproxy.json` only as it starts, so a change to the links takes `make down && make up`; `make up` on a
running demo resets the links it has: every link up, with no toxics.

## Troubleshooting

- **`port is already allocated`**: another service holds the port; the basics demo's are 100 lower, so it isn't
  that. Move it with the variables above.
- **The dashboards are empty**: the agents connect through the WAN a few seconds after it starts. Give it ten seconds,
  or check `make logs SERVICE=agent-branch` and `make status`, which shows each link's state and toxics.
- **An agent is on the standby when it shouldn't be**: after `wan-up`, an agent can land on its standby link.
  `./demo.sh heal` returns it.
- **A scenario says a link is cut**: the toxics a cut leaves can't take another (Toxiproxy 2.13.0 panics adding one), so
  `demo.sh` refuses; run `./demo.sh heal` first. `heal` creates a cut link again rather than clearing it. If the
  `wan` service ever restarts on its own, every connection through it drops: the agents reconnect, perhaps onto the
  standby, and `heal` sorts them out.
- **The primary's dashboard doesn't load during part 4**: that's the point; use the standby's at
  <http://localhost:8195/dashboard>, and agent-branch's Connection view for the agent's side.
- **agent-branch's Activity view is empty, or starts over**: `filter` and `unfilter` restart agent-branch, and an agent
  keeps its activity in memory only. Its dashboard reconnects to the restarted agent on its own.
- The proxies log a warning at startup that the dashboard has no authentication; the agents, that their dashboard
  listens on every interface without a login, and that their token crosses without TLS; and Toxiproxy, that its API is
  unauthenticated. All are expected here, and are why every port binds to `127.0.0.1` and the WAN's API is published
  nowhere.
