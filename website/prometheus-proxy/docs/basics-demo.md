---
icon: lucide/monitor-play
---

# Basics Demo

The repository ships a demo that runs the whole system on one machine: the proxy with its
[dashboard](web-dashboard.md), three agents with [theirs](agent-dashboard.md) behind a simulated firewall, a set of
simulated services, and Prometheus and Grafana in front. It is the quickest way to watch the proxy work, and every
screenshot on this page comes from it.

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph LR
  G[Grafana :3000] --> P[Prometheus :9090]
  P -->|discovery and scrapes| Proxy[Proxy, dashboard :8094]
  %% Each agent connects out to the proxy, so its arrow points back. The invisible links keep the agents to
  %% the right of the proxy, as on the home page.
  Proxy ~~~ East
  Proxy ~~~ West1
  Proxy ~~~ West2
  East[agent-east, dashboard :8095] -->|gRPC| Proxy
  West1[agent-west-1, dashboard :8096] -->|gRPC| Proxy
  West2[agent-west-2, dashboard :8097] -->|gRPC| Proxy
  East -->|HTTP fetch| App[demo-app: simulated services]
  West1 -->|HTTP fetch| App
  West2 -->|HTTP fetch| App

  subgraph Outside Firewall
    G
    P
    Proxy
  end

  subgraph Inside Firewall
    East
    West1
    West2
    App
  end
```

## What you need

The proxy and agent are the latest release's images, from Docker Hub, so there's nothing to build. On macOS or Linux,
the demo needs only:

- [Docker](https://docs.docker.com/get-started/get-docker/) with Compose v2: Docker Desktop, or Docker Engine with the
  Compose plugin.
- `git`, to clone the repository, and GNU `make`. On macOS, the Xcode command-line tools (`xcode-select --install`)
  have both. Where GNU make is installed as `gmake`, type `gmake` wherever these instructions say `make`.
- `curl`, which the demo uses to wait for each service to answer.
- A web browser.

## Running it

Clone the repository at the latest release, then run the demo's commands from its own directory, where `make help` lists
its targets:

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.0 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/basics
make up      # pulls the images, starts everything, prints the URLs
make tour    # walks through every scenario below, pausing before each step
make down    # stops it and removes its containers, network, and volume
```

The first `make up` pulls the proxy, agent, Prometheus, Grafana, and Python images, which takes a few minutes. After
that it starts in under a minute, and runs without a network connection.

| What | URL |
|---|---|
| Proxy dashboard | <http://localhost:8094/dashboard> |
| agent-east's dashboard | <http://localhost:8095/dashboard> |
| agent-west-1's and agent-west-2's dashboards | <http://localhost:8096/dashboard>, <http://localhost:8097/dashboard> |
| agent-rogue's and agent-east-copy's dashboards, while their scenarios run | <http://localhost:8098/dashboard>, <http://localhost:8099/dashboard> |
| Grafana (no sign-in needed; admin / admin to edit) | <http://localhost:3000> |
| Prometheus | <http://localhost:9090> (alerts at `/alerts`) |
| The proxy's service discovery | <http://localhost:8080/discovery> |

Every port binds to `127.0.0.1` only: the dashboards run without a login, but for the proxy's in [its own
scenario](#a-login-for-the-dashboard), and Grafana keeps its default admin password, which suits a laptop and nothing
shared. To move a port that is already taken, set `DEMO_DASHBOARD_PORT`, `DEMO_GRAFANA_PORT`, `DEMO_PROMETHEUS_PORT`, or
`DEMO_PROXY_PORT`, for example `DEMO_GRAFANA_PORT=3001 make up`, and for an agent's dashboard
`DEMO_AGENT_EAST_DASHBOARD_PORT`, `DEMO_AGENT_WEST_1_DASHBOARD_PORT`, `DEMO_AGENT_WEST_2_DASHBOARD_PORT`,
`DEMO_AGENT_ROGUE_DASHBOARD_PORT`, or `DEMO_AGENT_EAST_COPY_DASHBOARD_PORT`.

## What's running

| Service | Role |
|---|---|
| `proxy` | The proxy, outside the firewall. Its dashboard is on, and its service discovery tells Prometheus about every path the agents register. |
| `agent-east` | Scrapes the orders, payments, inventory, and catalog services, the proxy's own metrics, its own, and whatever its discovery file lists. Its own dashboard is on port 8095. |
| `agent-west-1`, `agent-west-2` | A redundant pair. Both run [consolidated](glossary.md#consolidated-mode) and serve `/shipping_metrics`, and the proxy merges their two replicas. Their dashboards are on ports 8096 and 8097. |
| `agent-rogue` | Started only by `demo.sh rogue-agent`: a contractor's agent whose identity may register only `contractor_*` paths. Its dashboard is on port 8098. |
| `agent-east-copy` | Started only by `demo.sh duplicate-agent`: agent-east's config, deployed a second time. Its dashboard is on port 8099. |
| `demo-app` | The simulated services. Their traffic follows a 5-minute "day", so the graphs move. It also writes agent-east's discovery file. |
| `prometheus` | Scrapes every path through the proxy every 5 seconds, with no targets in its config, and loads the project's alerting rules and a few fast demo ones. |
| `grafana` | Opens on the demo's dashboard, which links to the two dashboards the project ships. |

## The dashboard

The proxy's dashboard has four views: one organized by agent, one by path, one of what has happened, newest first,
and one of its settings. Select any screenshot on this page to see it full size.

<figure markdown="span">
  ![The Agents view, with agent-east selected](images/demo/agents.webp#only-dark){ loading=lazy }
  ![The Agents view, with agent-east selected](images/demo/agents-light.webp#only-light){ loading=lazy }
  <figcaption>The Agents view, with agent-east selected</figcaption>
</figure>

Selecting an agent shows its address, host, uptime, idle time, and eviction countdown; the paths it registered, with
their labels; and its scrapes as they happen, with status, time, and size. The catalog payload is about 480,000
characters, large enough that the agent sends it to the proxy in chunks.

`/proxy_metrics` is the proxy's own metrics. They leave the proxy, pass through agent-east, and come back through the
proxy to Prometheus, so the proxy monitors itself the same way it serves everything else.

<figure markdown="span">
  ![The Paths view](images/demo/paths.webp#only-dark){ loading=lazy }
  ![The Paths view](images/demo/paths-light.webp#only-light){ loading=lazy }
  <figcaption>The Paths view</figcaption>
</figure>

The Paths view lists every path with the agent serving it, its target, and how its last scrape went. `/shipping_metrics`
shows `agent-west-1 +1`, since two agents serve it. The button after a path copies its URL on the proxy, the one
to curl, and the one after a target copies the target's URL.

### Each agent's own dashboard

The proxy's dashboard shows what reaches the proxy. Each agent in the demo also serves its
[own dashboard](agent-dashboard.md), on a port of its own, which shows what only the agent knows: every path it has,
configured or discovered, with where it stands with the proxy and why; each path's recent scrapes as the agent made
them, and a **Scrape now** that fetches a target from the agent; its connection to the proxy; and what has happened
to it. [A scenario below](#an-agents-own-dashboard-shows-a-refused-path-and-a-lost-proxy) shows a refused path and a
lost proxy on it.

## Scenarios

`demo.sh`, in `demos/basics`, drives the scenarios below. Run it from that directory, where `make up` started
the demo; each command prints what to watch, and a minute between them lets the graphs catch up.

### One service fails, another hangs, and the rest keep working

```bash
./demo.sh outage      # payments answers 500
./demo.sh slow        # inventory hangs past the agent's timeout
```

<figure markdown="span">
  ![The Paths view during an outage](images/demo/outage.webp#only-dark){ loading=lazy }
  ![The Paths view during an outage](images/demo/outage-light.webp#only-light){ loading=lazy }
  <figcaption>payments failing with 500, and inventory timing out</figcaption>
</figure>

Payments' scrapes fail with a 500. Inventory's hang until the agent gives up after its 3-second scrape timeout and
reports a 408 (`upstream_timed_out`), so Prometheus gets a clear answer instead of waiting. Every other path keeps
working: one failing target never affects the rest.

<figure markdown="span">
  ![The demo's Grafana dashboard during the outage](images/demo/grafana.webp#only-dark){ loading=lazy }
  ![The demo's Grafana dashboard during the outage](images/demo/grafana-light.webp#only-light){ loading=lazy }
  <figcaption>The same outage in Grafana</figcaption>
</figure>

In Grafana, both targets turn red on **Target health**, **Scrape latency** spikes for inventory alone, and
**Scrape outcomes** shows the errors and timeouts at the proxy. After 15 seconds, **DemoTargetDown** fires for each:
the **Alerts** stat counts them, and so does Prometheus's `/alerts` page.

`./demo.sh recover` returns every service to normal, and the red rows turn green within a scrape or two.

### A second agent keeps a path up when one stops

```bash
./demo.sh agent-stop      # stops agent-west-2
./demo.sh agent-start     # it reconnects and rejoins in seconds
```

A stopped agent leaves the dashboard at once, and `/shipping_metrics` drops from `+1` to agent-west-1 alone. In
Grafana, **Shipping by replica** keeps west-1 flowing, and shipping stays green on **Target health**.

### New services appear without a restart

```bash
./demo.sh discover
```

<figure markdown="span">
  ![The Paths view with three discovered services](images/demo/discovered.webp#only-dark){ loading=lazy }
  ![The Paths view with three discovered services](images/demo/discovered-light.webp#only-light){ loading=lazy }
  <figcaption>Three services added through dynamic discovery</figcaption>
</figure>

Within about 5 seconds, `/search_metrics`, `/recommendations_metrics`, and `/notifications_metrics` appear with the
source `disc` ([discovered](configuration/agent.md#dynamic-target-discovery)), beside the `cfg` (configured) paths.
Prometheus finds them through the proxy's [service discovery](service-discovery.md), with no restart and no change to
its config, and Grafana's **Discovered services** panel fills. `./demo.sh undiscover` removes them just as quietly.

### The proxy evicts an agent that goes silent

```bash
./demo.sh agent-freeze    # agent-west-2: connected, but silent
```

<figure markdown="span">
  ![agent-west-2 frozen, counting down to its eviction](images/demo/evicting.webp#only-dark){ loading=lazy }
  ![agent-west-2 frozen, counting down to its eviction](images/demo/evicting-light.webp#only-light){ loading=lazy }
  <figcaption>A frozen agent, counting down to its eviction</figcaption>
</figure>

The frozen agent's idle time climbs and its eviction countdown runs; the demo evicts an agent after 30 seconds of
silence. A consolidated path waits for every agent that serves it, so `/shipping_metrics` stalls too, and Prometheus's
scrapes of both paths give up at its 4-second timeout, recorded as 499 (`client_cancelled`).

<figure markdown="span">
  ![The Paths view after the eviction](images/demo/evicted.webp#only-dark){ loading=lazy }
  ![The Paths view after the eviction](images/demo/evicted-light.webp#only-light){ loading=lazy }
  <figcaption>After the eviction: a departed path, marked gone</figcaption>
</figure>

Once the proxy evicts it, agent-west-2's own `/agent_west_2_metrics` stays in the Paths view marked `gone`, the status
bar counts it as departed, and agent-west-1 carries `/shipping_metrics` alone, which now shows agent-west-1's own
target. The Activity view lists the eviction as a problem, with how long the proxy waited and the paths the agent
served. `./demo.sh agent-thaw` unfreezes the agent, and it reconnects and registers again.

### Per-team tokens restrict which paths an agent can register

The proxy has [per-agent identities](security/index.md#per-agent-identities-and-path-authorization): each data
center's agents connect with their own token, and an identity may register only the paths its patterns allow.

```bash
./demo.sh rogue-agent     # a contractor's agent tries to register payments_metrics
```

The contractor's agent appears on the dashboard with the one path its identity allows, `/contractor_metrics`. Its
`/payments_metrics` is refused, which no list of agents or paths can show, since nothing was added. The Activity view
shows it, searched here for the contractor's agent:

<figure markdown="span">
  ![The Activity view, searched for agent-rogue](images/demo/activity.webp#only-dark){ loading=lazy }
  ![The Activity view, searched for agent-rogue](images/demo/activity-light.webp#only-light){ loading=lazy }
  <figcaption>The Activity view, searched for agent-rogue</figcaption>
</figure>

The command also prints the proxy's log line:

```
WARN  [ProxyServiceImpl.kt:360] - Agent identity 'contractor' denied registration of path /payments_metrics
```

Payments keeps coming from agent-east, untouched. With a shared token, the newer agent would have taken the path
over. The next scenario reads the same refusal on the contractor's agent's own dashboard.

### An agent's own dashboard shows a refused path and a lost proxy

The proxy's dashboard shows only what reaches the proxy. A path the proxy refused, or a proxy an agent can't reach,
shows on the agent's side, on its [own dashboard](agent-dashboard.md). With the contractor's agent from the previous
scenario still running, open its dashboard, on port 8098, and select `/payments_metrics`:

<figure markdown="span">
  ![agent-rogue's dashboard, with /payments_metrics refused](images/demo/agent.webp#only-dark){ loading=lazy }
  ![agent-rogue's dashboard, with /payments_metrics refused](images/demo/agent-light.webp#only-light){ loading=lazy }
  <figcaption>agent-rogue's own dashboard, with the path the proxy refused selected</figcaption>
</figure>

The path's light is red and its tag `refused`, and under **Refused by the proxy** is the proxy's reason: *Agent identity
'contractor' is not authorized to register path /payments_metrics*. Asking again wouldn't change the answer, so the
agent doesn't try the path again until it reconnects. The **Activity** view lists the refusal beside the agent's
connection and its `/contractor_metrics` registration. `./demo.sh rogue-agent-stop` removes the agent.

An agent's dashboard runs on the agent, so it keeps answering when the proxy is gone:

```bash
./demo.sh proxy-down      # stops the proxy
./demo.sh proxy-up        # starts it again
```

With the proxy stopped, agent-east's **Connection** view, on port 8095, turns `failing`, and its one endpoint,
`proxy:50051`, shows the error from the agent's last try; the agent tries again every 3 seconds. Its **Activity** view
lists `disconnected`, then `connect failed`, once rather than for each retry, and its paths turn red, tagged
`pending`. **Scrape now** on `/orders_metrics` still fetches the orders service and shows a 200: the target is fine,
and what's lost is the way to the proxy. Prometheus can't reach the proxy either, so every target turns DOWN in
Grafana. Once the proxy is back, agent-east connects and registers its paths again within a few seconds. A new proxy
holds nothing from the old one, so the proxy's dashboard starts its histories over.

### A duplicate agent takes over the paths, and the proxy logs the fix

```bash
./demo.sh duplicate-agent    # a second copy of agent-east, without consolidated mode
```

<figure markdown="span">
  ![agent-east with no paths, beside its copy](images/demo/duplicate.webp#only-dark){ loading=lazy }
  ![agent-east with no paths, beside its copy](images/demo/duplicate-light.webp#only-light){ loading=lazy }
  <figcaption>agent-east, left with no paths by its copy</figcaption>
</figure>

Someone deployed agent-east's config twice. The copy registers the same paths with the same identity, and the newer
registration takes each one: agent-east-copy serves all six, and agent-east stays connected with none. The two
settle; agent-east waits rather than taking the paths back, which used to make two such agents swap them every few
seconds. The command prints the proxy's log line, which names both agents and gives the fix: `agent.consolidated =
true` on each, if both should serve the paths. The proxy told agent-east, whose own dashboard lists its six paths as
`retrying`, each taken over by agent-east-copy. `./demo.sh duplicate-agent-stop` removes the copy, and agent-east,
told that no agent serves its paths any more, takes them back within seconds, with no restart.

### A login for the dashboard

```bash
./demo.sh login       # restarts the proxy with a dashboard password in its environment
```

<figure markdown="span">
  ![The dashboard's login page](images/demo/login.webp#only-dark){ loading=lazy }
  ![The dashboard's login page](images/demo/login-light.webp#only-light){ loading=lazy }
  <figcaption>The dashboard's login page</figcaption>
</figure>

Reload the dashboard and it shows its [login page](web-dashboard.md#login). Sign in as `admin`, with the password
`admin`, and it opens the page you asked for; **Log out**, the exit icon at the right end of the top bar, signs you
out. The password comes from the proxy's environment, `DEMO_DASHBOARD_PASSWORD` in `docker/compose.yml`, not from
`config/proxy.conf`, so it never sits in a file, and a script sends the login as HTTP Basic Auth instead:
`curl -u admin:admin http://localhost:8094/dashboard/paths`. A restart clears what the proxy holds in memory, so the
tour does this last: the agents reconnect within a few seconds, and the dashboard's histories start over.
`./demo.sh login-off` restarts the proxy without the password.

## Scenario reference

| Command | Effect | How it works |
|---|---|---|
| `demo.sh outage [service]` | The service answers 500 (default `payments`) | demo-app's control API: `POST /control/<service>/error` |
| `demo.sh slow [service]` | The service hangs past the agent's scrape timeout (default `inventory`) | `POST /control/<service>/slow`: demo-app holds each request 10 seconds, past the agent's 3-second `scrapeTimeoutSecs` |
| `demo.sh recover` | Every service returns to normal | `POST /control/all/ok` |
| `demo.sh discover` / `undiscover` | Adds or removes the search, recommendations, and notifications services through agent-east's discovery file | demo-app rewrites `/discovery/targets.json`, which agent-east re-reads every 5 seconds |
| `demo.sh agent-stop` / `agent-start [agent]` | Stops or starts an agent (default `agent-west-2`) | `docker compose stop` / `start`: the agent's connection closes as it exits |
| `demo.sh agent-freeze` / `agent-thaw [agent]` | Pauses or resumes an agent's container (default `agent-west-2`) | `docker compose pause` / `unpause`: the connection stays open but silent, so the proxy evicts the agent after 30 seconds |
| `demo.sh rogue-agent` / `rogue-agent-stop` | Starts or removes the contractor's agent | Starts or removes `agent-rogue` (compose profile `rogue`), which presents the contractor identity's token, allowed only `contractor_*` paths |
| `demo.sh proxy-down` / `proxy-up` | Stops or starts the proxy | `docker compose stop` / `start proxy`: each agent's connection closes, and the agent tries again every 3 seconds (`agent.internal.reconnectPauseSecs`) until the proxy is back |
| `demo.sh login` / `login-off` | Turns the dashboard's login on (`admin`, password `admin`) or off | Restarts the proxy with or without `DASHBOARD_PASSWORD`, from `DEMO_DASHBOARD_PASSWORD` in `docker/compose.yml` |
| `demo.sh duplicate-agent` / `duplicate-agent-stop` | Starts a second copy of agent-east, or removes it, and agent-east takes its paths back | Starts or removes `agent-east-copy` (profile `duplicate`): agent-east's config under another name, not consolidated |
| `make tour [PART=n]` | Walks through every scenario, from part 1 or the one given, pausing before each step |  |
| `make status` | The containers, the services' modes, and the URLs |  |
| `make logs [SERVICE=name]` | Follows the logs, of every service or the one given |  |
| `make open` | Opens the proxy's dashboard, agent-east's, Grafana, and Prometheus's alerts in the browser |  |

`recover`, `undiscover`, `agent-thaw`, `agent-start`, `rogue-agent-stop`, `proxy-up`, `duplicate-agent-stop`, and
`login-off` undo every scenario. For a clean slate, `make down && make up`.

## Going further

- The demo's [README](https://github.com/pambrose/prometheus-proxy/tree/master/demos/basics) has these scenarios
  as a guided tour, explaining how each one works, and lists the files that set the demo up.
- The [Network Demo](network-demo.md) puts a simulated WAN between the agents and the proxies, and shows what a far,
  thin, dead, flaky, or missing link does. It also covers what this demo leaves out: the agents failing over to a
  standby proxy, a filter at the agent that keeps a payload from crossing the firewall, and a call followed through
  the firewall in Zipkin.
- The [nginx Demo](nginx-demo.md) puts nginx, a reverse proxy that ends the agents' TLS, between the agents and the
  proxy, and in front of the dashboard.
- [Dashboard](web-dashboard.md) covers every part of the dashboard, and how to turn it on for your own proxy.
- [Agent Dashboard](agent-dashboard.md) covers the agent's own dashboard, and how to turn it on for your agents.
- [Grafana & Alerting](grafana.md) covers the dashboards and alerting rules the project ships.
