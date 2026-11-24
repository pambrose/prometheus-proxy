# nginx demo

A self-contained demo of Prometheus Proxy behind nginx, the reverse proxy many sites put in front of a service to end
its TLS on one port. It runs a proxy with its web dashboard, nginx in front of it, two agents behind a simulated
firewall that reach the proxy only through nginx, a set of simulated services, and Prometheus, all on one laptop. It
shows the agents connecting over TLS that nginx ends, what nginx's stream timeouts do to an agent from before the
proxy's keepalives, a stream timeout shorter than the keepalive, why a reload of nginx doesn't reach a connected agent,
and what nginx has to pass on for the dashboard's live updates to work.

It follows the [network demo](../network), which follows the [basics demo](../basics): start with the basics. This one
keeps the same proxy and agents and puts nginx between them. The website's
[nginx Demo](https://prometheus-proxy.com/nginx-demo/) page walks through the same scenarios with screenshots. The
[Kubernetes demo](../kubernetes) follows this one, and deploys the same proxy and agent with their Helm charts on two
kind clusters; it needs kind, `kubectl`, and Helm as well as Docker.

```
 nginx-app  (simulated services: a web storefront and a warehouse)
     ▲  scraped from inside the "firewall"
 agent-current ──┐  this release; dashboard :8595
 agent-legacy  ──┤  release 4.6.0, from before the stream keepalives; no dashboard
                 │  gRPC over TLS to nginx.test.google.fr:443, outbound only
                 ▼
 nginx ── ends the agents' TLS, passes their calls to the proxy's agent port (50051) in plaintext
   │   └─ the dashboard's entrance :8590, passed to the proxy's dashboard
   ▼
 proxy ── dashboard :8594
   ▲  HTTP service discovery (/discovery) and scrapes through :8080; the proxy's own metrics directly
 Prometheus :9590 (5s scrapes)
```

Only nginx reaches the proxy's agent port. The proxy's transport filter is off behind nginx, so it records no address
for an agent, and lists each at the address **Unknown**.

## What you need

The proxy and agent are the latest release's images, from Docker Hub, the same ones the other demos run, so there's
nothing to build, and agent-legacy is release 4.6.0's image. On macOS or Linux, the demo needs only:

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
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.0 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/nginx
make up      # pulls the images, starts everything, and prints the URLs
make tour    # walks through the guided tour below, pausing before each step
make down    # stops it and removes its containers and network
```

The first `make up` pulls the proxy, agent, nginx, Prometheus, and Python images, which takes a few minutes. After that
it starts in under a minute, even without a network connection.

| What | URL |
|---|---|
| Proxy dashboard | <http://localhost:8594/dashboard> (its history at `/activity`) |
| The proxy dashboard, through nginx | <http://localhost:8590/dashboard> |
| agent-current's dashboard | <http://localhost:8595/dashboard> (its connection at `/connection`, its history at `/activity`) |
| Prometheus | <http://localhost:9590> |
| The proxy's service discovery | <http://localhost:8580/discovery> |

`make open` opens the proxy dashboard, directly and through nginx, agent-current's dashboard, and a Prometheus graph of
whether each agent's path answered.

Every port is the basics demo's plus 500, so the demos can run at once. If a port is already taken, move it, for example
with `NGINX_DEMO_PROMETHEUS_PORT=19590 make up`. The variables are `NGINX_DEMO_DASHBOARD_PORT`, `NGINX_DEMO_NGINX_PORT`,
`NGINX_DEMO_AGENT_DASHBOARD_PORT`, `NGINX_DEMO_PROMETHEUS_PORT`, and `NGINX_DEMO_PROXY_PORT`; the proxy dashboard's link
into Prometheus keeps the default port (`config/proxy.conf`). Every port binds to `127.0.0.1` only. The dashboards run
without their login, so this setup is for a laptop, not for anything shared.

## What's running

| Service | Role |
|---|---|
| `proxy` | The proxy, outside the firewall, with its transport filter off, as it must be behind a reverse proxy. Its dashboard is on; service discovery tells Prometheus about every path the agents register. Each agent has an identity of its own (`proxy.auth`). |
| `nginx` | The reverse proxy: the agents' entrance, TLS on port 443 with the repository's test certificate, which passes their gRPC calls to the proxy's agent port, and the dashboard's entrance, on port 8590. `demo.sh` swaps each entrance's config for another and reloads nginx. |
| `agent-current` | An agent of this release, beside a web storefront. It and the proxy send a keepalive on each stream between them after 30 seconds of quiet. Its [dashboard](https://prometheus-proxy.com/agent-dashboard/) is on, on port 8595. |
| `agent-legacy` | An agent of release 4.6.0, beside a warehouse, from before the stream keepalives. It has no dashboard, which came later. |
| `nginx-app` | The simulated services (`app/nginx_app.py`), with small metrics. |
| `prometheus` | Scrapes both agents' paths through the proxy every 5 seconds, and the proxy's own metrics directly. |

## Guided tour

`make tour` walks through these parts one step at a time. To follow them here instead, run the scenario commands
from `demos/nginx`; each one prints what to watch.

### 1. Two agents reach the proxy through nginx, over TLS

Each agent dials nginx at `nginx.test.google.fr:443` over TLS. nginx ends the TLS and passes each gRPC call on to the
proxy's agent port, which only nginx can reach.

- Open the proxy's dashboard. Both agents are connected, each listed at the address **Unknown**. nginx relays each
  agent's calls over connections of its own, so the proxy can't watch an agent's connection, as its transport filter
  does, and both the proxy and the agents turn the filter off. The proxy then learns an agent has gone when the
  agent's stream of scrape requests ends, and ties each agent's calls to the identity its token names, so each agent
  has a token of its own. Click agent-current: its host is storefront-01, which the agent reports itself.

```bash
./demo.sh nginx-log      # the last gRPC calls nginx passed to the proxy, by agent
```

- An agent connects with three calls: `connectAgentWithTransportFilterDisabled`, `registerAgent`, and `registerPath`
  for each path. Its streams, four for agent-current and three for agent-legacy, which has no stream of path notices,
  stay open while it's connected, so nginx logs one only when it ends.
- nginx counts all an agent sends on a stream as one request body, and stops a body at `client_max_body_size`, 1 MB by
  default, so a stream of results, open while the agent is connected, would end with 413 once its results added up to
  a megabyte. Every entrance in `nginx/` turns the limit off with `client_max_body_size 0`, as any nginx in front of the
  proxy needs.
- Open agent-current's Connection view, <http://localhost:8595/dashboard/connection>: it's connected to
  nginx.test.google.fr:443, using TLS (no mutual auth). The agent checks nginx's certificate against the name it
  dials: the certificate names `*.test.google.fr`, and the agent trusts the CA that signed it
  (`agent.tls.trustCertCollectionFilePath`). An agent uses TLS only when it's given what to trust, so this is needed
  even for a certificate a public CA signed: then point it at a CA bundle.

### 2. nginx's stream timeouts cut off an agent from 4.6.0 every minute

An agent keeps several gRPC streams open to the proxy, and nginx closes a stream that carries nothing for 60 seconds:
its `grpc_read_timeout` covers the streams the proxy sends on, and `client_body_timeout` the streams the agent sends
results on.

- In the proxy dashboard's Activity view, <http://localhost:8594/dashboard/activity>, agent-legacy disconnects about
  once a minute, with the reason **Stream terminated (transport filter disabled)**, and connects again a few seconds
  later. agent-current doesn't.
- An agent's stream for results sent in chunks carries something only when a result is over 32 KB gzipped, so it's
  quiet even while Prometheus scrapes the agent every 5 seconds. agent-current and the proxy send a keepalive on each
  stream after 30 seconds of quiet. agent-legacy predates them, and the proxy, seeing it doesn't take them, sends it
  none. `./demo.sh nginx-log` shows nginx ending its stream with 408, from `client_body_timeout`.
- In Prometheus, `increase(proxy_agent_disconnects_total{reason="stream_terminated"}[1m])` climbs to about 1, and
  `up{job="warehouse"}` dips to 0 now and then: a scrape that lands while agent-legacy reconnects finds its path gone.

```bash
./demo.sh nginx-long-timeouts   # raises both timeouts to a day, and reloads nginx
```

- agent-legacy is cut off once more, within a minute, by the worker it's on, which keeps the old timeouts, then
  reconnects to a new worker under the day-long ones, and stays connected. For an agent or a proxy from 4.6.0 or
  earlier, this is the fix short of upgrading both.

### 3. A shorter stream timeout, and a reload that doesn't reach connected agents

Some load balancers close a quiet connection sooner than nginx's 60 seconds. Here nginx's timeouts come down to 20
seconds, under the 30 seconds after which the keepalives start.

```bash
./demo.sh nginx-short-timeouts   # cuts both timeouts to 20 seconds, and reloads nginx
```

- agent-current isn't cut off, though its keepalives come after 30 seconds. A reload is how nginx takes a new config
  without dropping a connection: it starts new workers under the new config, and tells the old ones to take no new
  connections and to exit once the ones they have end. An old worker keeps those connections under the config they
  opened with, and agent-current's connection, whose streams its keepalives keep open, doesn't end.

```bash
./demo.sh nginx-workers   # nginx's processes
```

- Old workers, **shutting down**, hold the agents' connections from before the reload, under the old timeouts. They
  never exit, and each reload leaves more.

```bash
./demo.sh nginx-shutdown-timeout   # sets worker_shutdown_timeout to 10s, and restarts nginx
```

- `worker_shutdown_timeout` has an old worker close the connections it still has after 10 seconds. A worker takes it
  from the config it started with, so it covers the reloads after it's in place; the restart brings it in. From now
  on, a reload reaches every agent within 10 seconds, at the cost of one reconnect each, which a scrape can land in.
- Each agent reconnects once, now under the 20-second timeouts, and agent-current is cut off about every 20 seconds.
  Two of its streams go quiet for 20 seconds before their keepalives come, at 30: its stream for results in chunks,
  which nginx ends with 408, from `client_body_timeout`, and the proxy's stream of path notices to it, which nginx ends
  with 504, from `grpc_read_timeout`. Its Activity view shows each disconnect, with the 504 as its reason:
  <http://localhost:8595/dashboard/activity>

```bash
./demo.sh keepalive 10   # recreates the proxy with a keepalive interval of 10 seconds
```

- The proxy sets the interval for both directions, `proxy.internal.streamKeepAliveSecs`: it uses it on the streams it
  sends on, and gives it to each agent as it registers. Each agent reconnects once to the new proxy; agent-current's
  log says **Sending stream keepalives to proxy nginx.test.google.fr after 10s without a result**, and it stays
  connected. agent-legacy, which sends none, is still cut off.

```bash
./demo.sh reset   # nginx and the proxy as make up starts them
```

### 4. The dashboard behind nginx

The proxy's dashboard updates live over a WebSocket, which a reverse proxy has to pass on.

- Open the dashboard through nginx, <http://localhost:8590/dashboard>. The page loads, then the status bar's live
  indicator turns into a blinking red **reconnecting...** that never clears. nginx's entrance is a plain `proxy_pass`,
  which passes each request as HTTP/1.0 and drops the `Upgrade` and `Connection` headers, so the dashboard can't take
  the WebSocket handshake, and answers 400.

```bash
./demo.sh dashboard-upgrade   # passes the WebSocket handshake's headers, over HTTP/1.1
```

- Still **reconnecting...**: the `Host` header nginx passes is the upstream's name, `proxy:8094`, while the browser's
  `Origin` names `localhost:8590`. The dashboard takes a WebSocket, and any POST, only from a page of its own, so
  another site's page can't use it, and refuses this one with 403. The proxy logs `Refused a dashboard request from
  origin http://localhost:8590; add it to proxy.dashboard.allowedOrigins if expected`, and the scrape button in the
  Paths view says **Origin not allowed**.

```bash
./demo.sh dashboard-host      # passes the browser's Host header too
```

- The page goes live. `proxy_set_header Host $http_host` passes the `Host` header as the browser sent it, port
  included (`$host` drops it). Listing the address in `proxy.dashboard.allowedOrigins` works too. Here nginx passes
  the dashboard on over plain HTTP; if it ended TLS in front of the dashboard, the browser's `Origin` would start with
  `https`, and a login's session cookie would be marked `Secure`, so the browser would send it only over TLS.

## Scenario reference

| Command | Effect | How it works |
|---|---|---|
| `demo.sh nginx-log [n]` | Shows the last n gRPC calls nginx passed to the proxy (default 12), by agent | Reads nginx's access log, a line per call as it ends, and names each agent by its address |
| `demo.sh nginx-stock` | The agents' entrance with nginx's default stream timeouts, 60 seconds | Copies `nginx/agents-stock.conf` in as `conf.d/agents.conf` and reloads nginx |
| `demo.sh nginx-long-timeouts` | Both stream timeouts raised to a day | `nginx/agents-long-timeouts.conf`: `client_body_timeout` and `grpc_read_timeout` of `1d` |
| `demo.sh nginx-short-timeouts` | Both stream timeouts cut to 20 seconds | `nginx/agents-short-timeouts.conf`: both at `20s` |
| `demo.sh nginx-workers` | Lists nginx's processes, the old workers a reload left among them | `ps` in the nginx container |
| `demo.sh nginx-shutdown-timeout` | nginx's old workers close their connections 10 seconds after a reload | Copies `nginx/main-shutdown-timeout.conf` (`worker_shutdown_timeout 10s`) into `main.d` and restarts nginx |
| `demo.sh keepalive <secs>` | The proxy's stream keepalive interval, for both directions | Recreates the proxy with `-Dproxy.internal.streamKeepAliveSecs=<secs>`, and restarts nginx, which looks up the new proxy's address |
| `demo.sh dashboard-stock` | The dashboard's entrance as a plain `proxy_pass` | `nginx/dashboard-stock.conf` in as `conf.d/dashboard.conf`, and a reload |
| `demo.sh dashboard-upgrade` | The same, passing the WebSocket handshake's headers | `nginx/dashboard-upgrade.conf`: HTTP/1.1, `Upgrade`, and `Connection` |
| `demo.sh dashboard-host` | The same, passing the browser's `Host` too | `nginx/dashboard-host.conf`: `proxy_set_header Host $http_host` |
| `demo.sh reset` | nginx and the proxy as `make up` starts them | The stock configs, no `worker_shutdown_timeout`, a restart of nginx if anything differed, and the default keepalive |
| `make tour [PART=n]` | Walks through the guided tour above, from part 1 or the one given, pausing before each step |  |
| `make status` | The containers, nginx's configs in use, its old workers, the proxy's keepalive, and the URLs |  |
| `make logs [SERVICE=name]` | Follows the logs, of every service or the one given |  |

`reset` undoes every scenario. For a clean slate, `make down && make up`.

## How it's put together

| File | What it sets up |
|---|---|
| `docker/compose.yml` | The services, their ports, nginx's network alias, which its certificate covers, and their images, agent-legacy's pinned to release 4.6.0 |
| `nginx/nginx.conf` | nginx's main config: the image's own, with an include of `main.d` and two workers, so `nginx-workers` stays short |
| `nginx/agents-*.conf` | The agents' entrance: TLS on 443, `grpc_pass` to the proxy's agent port, no limit on a request body (`client_max_body_size 0`), and its stream timeouts |
| `nginx/dashboard-*.conf` | The dashboard's entrance: a `proxy_pass` to the proxy's dashboard, without and with what its WebSocket needs |
| `nginx/main-shutdown-timeout.conf` | `worker_shutdown_timeout`, for `nginx-shutdown-timeout` |
| `config/proxy.conf` | The transport filter off, the dashboard, service discovery, and an identity per agent |
| `config/agent-current.conf`, `config/agent-legacy.conf` | Each agent's transport filter off, nginx's address, the CA it trusts, its token, its path, and agent-current's dashboard |
| `prometheus/prometheus.yml` | One HTTP service discovery job against the proxy, and one job for the proxy's own metrics |
| `app/nginx_app.py` | The simulated services |
| `Makefile` | The demo's targets: `make up`, `make up-local` (which runs the proxy and agent built from this checkout, for trying a change to them), `down`, `status`, `open`, `tour`, and `logs`, which `make help` lists, and `screenshots`, which it leaves out, since it's for maintaining the site |
| `demo.sh` | Starting, stopping, and the scenarios. It changes nginx by copying a variant into the container's `conf.d` and reloading it, so the files here never change. Its `NEEDS_RELEASE_AFTER` is the latest release that predates what the demo shows: until a later one is out, `make up` builds the proxy and agent from this checkout, as `make up-local` does |
| `bin/screenshots.sh` | The nginx Demo page's screenshots, in the dark and light themes, written to `website/prometheus-proxy/docs/images/nginx-demo/` (needs Chrome, ImageMagick 7, and `cwebp`). `WAIT_SECS` sets how long each wait for a scene may take before the script fails (default 150) |

nginx's TLS certificate and key, and the CA certificate the agents trust, are the repository's test certificates,
`testing/certs`, mounted read-only: never use them for anything real.

From the repository root, `make check-rules` validates the Prometheus config with promtool, and `ConfigFileKeysTest`
checks every key in `config/*.conf` against `config/config.conf`, and that each file loads as the proxy or agent loads
it. nginx's configs are in `nginx/`, not `config/`, since they're nginx's own format.

## Troubleshooting

- **`port is already allocated`**: another service holds the port; the other demos' are 100 to 500 lower, so it isn't
  one of them. Move it with the variables above.
- **A config change doesn't seem to take**: an agent's connection stays with the nginx worker it opened on, under the
  config it opened with, until `worker_shutdown_timeout` is set (part 3). `./demo.sh reset` restarts nginx, and every
  agent reconnects under the stock config.
- **The dashboard through nginx is live from the start**: a scenario changed its entrance; `./demo.sh dashboard-stock`
  puts the plain `proxy_pass` back, and reloading the page shows **reconnecting...** again.
- **agent-current's Activity view is empty, or starts over**: `keepalive` recreates the proxy, not the agent, so this
  means the agent restarted; an agent keeps its activity in memory only.
- The proxy logs warnings at startup that the dashboard has no authentication, and that agent tokens are set without
  TLS on its agent port: here TLS ends at nginx, and the hop from nginx to the proxy is the demo's private network.
  agent-current logs that its dashboard has no authentication. Both are expected here, and are why every port binds to
  `127.0.0.1`. An agent logs `Cannot connect to proxy` while nginx restarts, and connects again a second later.
