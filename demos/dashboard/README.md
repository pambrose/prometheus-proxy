# Dashboard demo

A self-contained demo of Prometheus Proxy for customers. It runs the proxy with its web dashboard, three agents behind a
simulated firewall, a set of simulated services, and Prometheus and Grafana. A presenter can show live traffic, failures
and recovery, redundancy, dynamic discovery, and stale-agent eviction, all on one laptop.

The website's [Live Demo](https://pambrose.github.io/prometheus-proxy/demo/) page walks through the same scenarios with
screenshots of the dashboard and Grafana.

```
 demo-app  (simulated services: orders, payments, inventory, catalog, shipping, and three discoverable ones)
     ▲  scraped from inside the "firewall"
 agent-east ────────┐  static paths, dynamic discovery, and the proxy's own metrics (the proxy monitors itself)
 agent-west-1 ──────┤  a consolidated pair: both serve shipping_metrics
 agent-west-2 ──────┘
     │  gRPC, outbound only
   proxy ── dashboard :8094 (live agents, paths, and scrapes)
     ▲  HTTP service discovery (/discovery) and scrapes through :8080
 Prometheus :9090 (5s scrapes, alerting rules) ──► Grafana :3000 (three dashboards)
```

The proxy and agent images are built from this checkout, so the demo always shows the dashboard as it is in the code.

## Quick start

You need Docker with Compose v2, and a JDK 17 or later to run Gradle. From the repository root:

```bash
make demo-up      # builds the jars and images, starts everything, and prints the URLs
make demo-down    # stops it and removes its containers, network, and volume
```

The first `make demo-up` pulls the Prometheus, Grafana, and Python images and builds the jars, which takes a few
minutes. After that it starts in under a minute, and runs without a network connection. **Run it once before the
meeting.**

| What | URL |
|---|---|
| Proxy dashboard | <http://localhost:8094/dashboard> |
| Grafana (no sign-in needed; admin / admin to edit) | <http://localhost:3000> |
| Prometheus | <http://localhost:9090> (alerts at `/alerts`) |
| The proxy's service discovery | <http://localhost:8080/discovery> |

`./demos/dashboard/demo.sh open` opens the first three in your browser.

If a port is already taken, move it, for example with `DEMO_GRAFANA_PORT=3001 make demo-up`. The variables are
`DEMO_DASHBOARD_PORT`, `DEMO_GRAFANA_PORT`, `DEMO_PROMETHEUS_PORT`, and `DEMO_PROXY_PORT`. Every port binds to
`127.0.0.1` only. The dashboard has no authentication and Grafana uses its default admin password, so this setup is
for a laptop, not for anything shared.

## What's running

| Service | Role |
|---|---|
| `proxy` | The proxy, outside the firewall. Its dashboard is on; service discovery tells Prometheus about every path the agents register. |
| `agent-east` | The agent in the "east" data center. It scrapes the orders, payments, inventory, and catalog services, the proxy's own metrics, its own, and whatever its discovery file lists. |
| `agent-west-1`, `agent-west-2` | A redundant pair in the "west" data center. Both run consolidated and serve `shipping_metrics`, and the proxy merges their two replicas. |
| `demo-app` | The simulated services (`app/demo_app.py`). Traffic follows a 5-minute "day", so the graphs move. It also writes agent-east's discovery file. |
| `prometheus` | Scrapes every path through the proxy every 5s, with no target in its config. It loads the project's alerting rules (`grafana/alerts.yml`) and the demo's fast ones (`prometheus/demo-alerts.yml`). |
| `grafana` | Opens on the **Prometheus Proxy Demo** dashboard. The project's own **Prometheus Proxy** and **Prometheus Agents** dashboards (`grafana/*.json`) are linked from its top bar. |

## Presenter's script

Each act lists what to show and what it means for the customer. Run the scenario commands from the repository root;
each one prints what to watch. Leave about a minute between acts so the graphs catch up.

### 1. The problem, and the shape of the answer

Show the diagram above. Prometheus pulls metrics, but the services live behind a firewall that allows no inbound
connections. The agents sit inside the firewall and connect *out* to the proxy over gRPC. Prometheus scrapes the proxy
as if it were each service.

### 2. The proxy dashboard

Open the dashboard on the **Agents** view.

- Three agents are connected, each with its address and number of paths.
- Click **agent-east**. It shows its host, uptime, and idle time, the paths it registered with their labels, and the
  scrapes flowing through it live: status, time, and size. The catalog payload is about 480,000 characters, large enough that the agent sends it to the
  proxy in chunks.
- `/proxy_metrics` is the proxy's own metrics. They leave the proxy, go through an agent, and come back through the
  proxy to Prometheus, so the proxy monitors itself with the same path as everything else.
- Switch to **Paths**: every path, the agent serving it, its target, its last scrape, and its status.
  `/shipping_metrics` shows `agent-west-1 +1`: two agents serve it.

### 3. Grafana

Open Grafana. It lands on the **Prometheus Proxy Demo** dashboard.

- **Through the firewall** shows the health of every target, scrape outcomes, latency, and payload size per path.
- **Business metrics from behind the firewall** shows orders, payments, inventory, and shipping. These are ordinary
  application metrics that Prometheus collected through the proxy.
- Prometheus's config lists no targets. It asks the proxy (`/discovery`) what exists, and each agent's labels set the
  `job`. Show <http://localhost:8080/discovery>.
- The **Prometheus Proxy** and **Prometheus Agents** links open the dashboards the project ships for operating the
  proxy itself.

### 4. A service fails, and recovers

```bash
./demos/dashboard/demo.sh outage          # payments now answers 500
```

- The dashboard: `/payments_metrics` turns red with a 500 in the Paths view, and in agent-east's recent scrapes.
- Grafana: payments turns red on **Target health**. After 15 seconds, **DemoTargetDown** fires (the **Alerts** stat and
  the **Firing alerts** table; also Prometheus's `/alerts` page).
- The other services keep working: one failing target never affects the rest.

```bash
./demos/dashboard/demo.sh slow            # inventory now hangs
```

- The agent gives up after its 3-second scrape timeout and reports 408 (`upstream_timed_out`), so Prometheus gets a
  clear answer instead of waiting. **Scrape latency** spikes for inventory only.

```bash
./demos/dashboard/demo.sh recover         # everything back to normal
```

### 5. Redundancy

```bash
./demos/dashboard/demo.sh agent-stop      # stops agent-west-2
```

- agent-west-2 leaves the dashboard at once, and `/shipping_metrics` drops from `+1` to just agent-west-1.
- Grafana: **Shipping by replica** keeps west-1 flowing, and shipping stays green on **Target health**.

```bash
./demos/dashboard/demo.sh agent-start     # it reconnects and rejoins in seconds
```

### 6. New services appear without a restart

```bash
./demos/dashboard/demo.sh discover
```

- Within about 5 seconds, `/search_metrics`, `/recommendations_metrics`, and `/notifications_metrics` appear in the
  Paths view with source `disc` (discovered), alongside the `cfg` (configured) ones.
- Prometheus finds them through the proxy's service discovery, and Grafana's **Discovered services** panel fills. No
  restart, and no change to Prometheus's config.

```bash
./demos/dashboard/demo.sh undiscover      # they go away just as quietly
```

In the Paths view, a removed path that had been scraped stays listed as `gone`, so it's clear what left.

### 7. An agent that goes silent

```bash
./demos/dashboard/demo.sh agent-freeze    # freezes agent-west-2: connected, but silent
```

- In the dashboard, click agent-west-2. Its **idle** time climbs and the **evicted in** countdown runs.
- A consolidated path waits for all of its agents, so `/shipping_metrics` stalls while agent-west-2 is frozen.
- After 30 seconds the proxy evicts it. **DemoAgentEvicted** fires, its own `/agent_west_2_metrics` path shows as
  `gone` (the status bar counts it as departed), the **Prometheus Proxy** dashboard's **Agent Events** panel records
  the eviction, and agent-west-1 carries shipping alone.

```bash
./demos/dashboard/demo.sh agent-thaw      # it reconnects and registers again
```

## Scenario reference

| Command | Effect |
|---|---|
| `demo.sh outage [service]` | The service answers 500 (default `payments`) |
| `demo.sh slow [service]` | The service hangs past the agent's scrape timeout (default `inventory`) |
| `demo.sh recover` | Every service returns to normal |
| `demo.sh discover` / `undiscover` | Adds or removes the search, recommendations, and notifications services through agent-east's discovery file |
| `demo.sh agent-stop` / `agent-start [agent]` | Stops or starts an agent (default `agent-west-2`) |
| `demo.sh agent-freeze` / `agent-thaw [agent]` | Pauses or resumes an agent's container (default `agent-west-2`) |
| `demo.sh status` | The containers, the services' modes, and the URLs |
| `demo.sh logs [service]` | Follows the logs |

The services are `orders`, `payments`, `inventory`, `catalog`, and `shipping`, plus `search`, `recommendations`, and
`notifications` once discovered. Freezing `agent-east` also stops the proxy's own metrics, which travel through it.

## How it's put together

| File | What it sets up |
|---|---|
| `compose.yml` | The services, their ports, and the images built from this checkout |
| `config/proxy.conf` | The dashboard, service discovery, and a 30-second eviction limit, so act 7 fits a demo |
| `config/agent-*.conf` | Each agent's paths and their labels (the `job` Prometheus uses), discovery for agent-east, consolidated mode for the west pair, and a 3-second scrape timeout |
| `prometheus/prometheus.yml` | One HTTP service discovery job against the proxy |
| `prometheus/demo-alerts.yml` | The fast demo alerts |
| `grafana/` | The provisioned datasource, dashboard folder, and demo dashboard |
| `app/demo_app.py` | The simulated services and their control API |
| `demo.sh` | Starting, stopping, and the scenarios |
| `screenshots.sh` | The Live Demo page's screenshots, in the dark and light themes. `make demo-screenshots` builds and starts the demo, plays the scenarios, and writes them to `website/prometheus-proxy/docs/images/demo/` (needs Chrome, ImageMagick 7, and `cwebp`) |

`make check-rules` validates the Prometheus config and both rule files with promtool, and `ConfigFileKeysTest` checks
every key in `config/*.conf` against `config/config.conf`.

To change the demo dashboard, edit `grafana/dashboards/demo-overview.json` and run
`docker compose -f demos/dashboard/compose.yml restart grafana`. Grafana doesn't reliably notice a change to a
mounted file.

## Troubleshooting

- **`port is already allocated`**: another service holds the port. Move it with the variables above.
- **The dashboard is empty, or shows only some agents**: the agents connect a few seconds after the proxy starts.
  Give it ten seconds, or check `./demos/dashboard/demo.sh logs agent-east`.
- **A scenario left things in an odd state**: `./demos/dashboard/demo.sh recover`, `undiscover`, `agent-thaw`, and
  `agent-start` undo every scenario. For a clean slate, `make demo-down && make demo-up`.
- The proxy logs a warning at startup that the dashboard has no authentication. That's expected here, and is why
  every port binds to `127.0.0.1`.
