---
icon: lucide/coffee
---

# Ktor Demo

The repository ships a demo of the agent [embedded](embedded-agent.md) in an app: a coffee shop, a small Ktor app with
built-in customers, runs the agent inside its own JVM. The agent connects to a proxy, and Prometheus and Grafana sit
beside the proxy. It's the setup [Cloud Platforms](cloud-platforms.md) describes, with the app on ECS, Cloud Run, or
Azure Container Apps and the proxy on another host, run on one machine.

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph TB
  accTitle: The Ktor Demo's containers
  accDescr {
    On the proxy host, Grafana on port 3300 reads Prometheus on 9390, which scrapes /coffee_shop_metrics through the
    proxy, dashboard 8394. On the app's platform, a load balancer on 8390 sends traffic to the coffee shop, a Ktor
    app, whose embedded agent, dashboard 8395, reads its registry and connects to the proxy over gRPC with TLS and a
    token.
  }
  G[Grafana :3300] --> P[Prometheus :9390]
  P -->|scrapes /coffee_shop_metrics| Proxy[Proxy, dashboard :8394]
  %% The agent connects out to the proxy, so its arrow points back. The invisible link keeps the app below.
  Proxy ~~~ Agent
  Agent[embedded agent, dashboard :8395] -->|gRPC over TLS, with a token| Proxy
  Agent -->|reads its registry| App[coffee shop: Ktor]
  LB[load balancer :8390] --> App

  subgraph Proxy host
    G
    P
    Proxy
  end

  subgraph The app's platform
    LB
    Agent
    App
  end
```

## What you need

On macOS or Linux, the demo needs:

- [Docker](https://docs.docker.com/get-started/get-docker/) with Compose v2: Docker Desktop, or Docker Engine with the
  Compose plugin.
- `git`, to clone the repository, and GNU `make`. On macOS, the Xcode command-line tools (`xcode-select --install`)
  have both. Where GNU make is installed as `gmake`, type `gmake` wherever these instructions say `make`.
- `curl`, which the demo uses to wait for each service to answer, and to turn the coffee shop's knobs.
- A web browser.

`make up` runs the latest release's proxy image, from Docker Hub, and builds the coffee shop inside Docker against the
latest release's library, from Maven Central, so it needs no JDK. Until a release has everything the demo shows, it
builds from the checkout instead, which needs a JDK 17 or later, as `make up-local` always does.

## Running it

Clone the repository at the latest release, then run the demo's commands from its own directory, where `make help` lists
its targets:

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.0 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/ktor
make up      # builds the coffee shop, starts everything, prints the URLs
make tour    # walks through every scenario below, pausing before each step
make down    # stops it and removes its containers and network
```

The first `make up` pulls the images and builds the coffee shop, which takes a few minutes. After that it starts in
under a minute.

| What | URL | Port variable |
|---|---|---|
| The coffee shop, through its load balancer | <http://localhost:8390> | `KTOR_DEMO_APP_PORT` |
| Proxy dashboard | <http://localhost:8394/dashboard> | `KTOR_DEMO_DASHBOARD_PORT` |
| Grafana (no sign-in needed; admin / admin to edit) | <http://localhost:3300> | `KTOR_DEMO_GRAFANA_PORT` |
| Prometheus | <http://localhost:9390> (alerts at `/alerts`) | `KTOR_DEMO_PROMETHEUS_PORT` |
| The coffee shop's metrics, through the proxy | <http://localhost:8380/coffee_shop_metrics> | `KTOR_DEMO_PROXY_PORT` |
| The coffee shop agent's dashboard | <http://localhost:8395/dashboard> | `KTOR_DEMO_AGENT_DASHBOARD_PORT` |
| Version 1.1's agent's dashboard, after `./demo.sh deploy` | <http://localhost:8396/dashboard> | `KTOR_DEMO_NEXT_AGENT_DASHBOARD_PORT` |
| The agent's `/debug` page, on its admin port | <http://localhost:8393/debug> | `KTOR_DEMO_AGENT_ADMIN_PORT` |

Each port is the [Basics Demo](basics-demo.md)'s plus 300, so the demos can run side by side. If a port is already
taken, set its variable from the table for `make up`, for example `KTOR_DEMO_GRAFANA_PORT=3301 make up`. `make up` saves
it in `docker/.env`, which every later command and docker compose read, so you set it once; delete its line there to go
back to the default.

Every port binds to `127.0.0.1` only. The dashboards and the agent's admin endpoints have no authentication, the coffee
shop's knobs need none, and Grafana uses its default admin password, so this setup is for a laptop, not for anything
shared.

## What's running

| Service | Role |
|---|---|
| `coffee-shop` | The app, version 1.0: a Ktor server whose built-in customers order over HTTP, and whose baristas brew from a queue. Its metrics, with Ktor's HTTP and the JVM's, are in a Micrometer registry, and it serves no `/metrics`. The agent runs inside it, installed with the [Ktor plugin](ktor-plugin.md) from settings the app builds in code, and reads the registry in-process. Its admin endpoints, `/debug` among them, are on port 8393, for debugging it, and its [dashboard](agent-dashboard.md) on port 8395. |
| `coffee-shop-next` | Started only by `demo.sh deploy`: version 1.1 of the same image, beside 1.0, as a rolling deploy does. Its agent's dashboard is on port 8396. |
| `load-balancer` | The coffee shop's one address, on port 8390, as a platform's load balancer is: it sends every request to 1.0, or to 1.1 once a deploy has stopped 1.0. |
| `proxy` | The stock proxy image. It takes its TLS certificate and key as [PEM in environment variables](security/tls.md#certificates-as-environment-variables), and the agent's token from one too, with none of them in a file. |
| `prometheus` | Scrapes the coffee shop's paths through the proxy every 5 seconds, and the proxy's own metrics, and loads the project's alerting rules and the coffee shop's. |
| `grafana` | Opens on the Coffee Shop dashboard, which links to the two dashboards the project ships. |

The secrets are the repository's test certificates and a token, which `make up` writes to the demo's `docker/.env`
the first time it runs, beside any port you moved. The certificates are public, so this suits a laptop and nothing
shared; [Cloud Platforms](cloud-platforms.md#securing-the-link) shows how to make your own.

## The app and its agent

Select any screenshot on this page to see it full size.

<figure markdown="span">
  ![The coffee shop's status page](images/ktor-demo/coffee-shop.webp#only-dark){ loading=lazy }
  ![The coffee shop's status page](images/ktor-demo/coffee-shop-light.webp#only-light){ loading=lazy }
  <figcaption>The coffee shop's status page</figcaption>
</figure>

The coffee shop's status page shows the queue, the baristas on shift, and the drinks served. It has no `/metrics`:
`./demo.sh peek` shows it answering 404, then reads the shop's metrics through the proxy, where Prometheus finds them.
The agent reads the app's registry in-process, so Prometheus needs no password, port, or way into the app.

<figure markdown="span">
  ![The proxy dashboard's Agents view, with the coffee shop's agent selected](images/ktor-demo/agents.webp#only-dark){ loading=lazy }
  ![The proxy dashboard's Agents view, with the coffee shop's agent selected](images/ktor-demo/agents-light.webp#only-light){ loading=lazy }
  <figcaption>The coffee shop's embedded agent on the proxy dashboard</figcaption>
</figure>

On the proxy dashboard, the embedded agent is an agent like any other: `coffee-shop-1.0`, named after the app's version,
with two paths, the coffee shop's metrics and its own, and its scrapes as they happen.

The agent has a [dashboard](agent-dashboard.md) of its own, served from inside the app, with its side of the same two
paths: each one's registration with the proxy, and its recent scrapes as the agent saw them. Select
`coffee_shop_metrics`, whose target is `in-process`, and **Scrape now** reads the app's registry at once, from inside
the app, and shows what came back, whether or not the proxy has the path. Its Connection view shows the agent's
connection to the proxy, and its Activity view what the agent did, in order. Open it at
<http://localhost:8395/dashboard>

The coffee shop's environment turns the agent's dashboard on, with nothing in the app's code: `DASHBOARD_ENABLED=true`,
and a `DASHBOARD_PORT` for each version, 8395 for 1.0 and 8396 for 1.1, which the agent reads itself, over anything the
app's code sets.

## Where the agent is configured

The coffee shop configures its agent in code, with no config file. `installPrometheusAgent`, in
[`PrometheusAgent.kt`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/ktor/app/src/main/kotlin/coffeeshop/PrometheusAgent.kt),
builds the agent's settings from the app's environment in the [Ktor plugin](ktor-plugin.md)'s `agent { }` block, with
[`fromEnvironment()`](embedded-agent.md#settings-from-the-environment), serves the agent's own metrics beside the app's
with [`agentMetricsAt`](embedded-agent.md#the-agents-own-metrics), and installs the plugin with the app's registry:

```kotlin
--8<-- "demos/ktor/app/src/main/kotlin/coffeeshop/PrometheusAgent.kt:agent-config"
```

The app's Ktor module, `coffeeShop` in
[`Main.kt`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/ktor/app/src/main/kotlin/coffeeshop/Main.kt),
calls it with the registry that Ktor's metrics and the shop's share; the plugin starts the agent once the module has
loaded, and stops it as the server stops:

```kotlin
--8<-- "demos/ktor/app/src/main/kotlin/coffeeshop/Main.kt:app-module"
```

- The app depends on `com.pambrose:prometheus-proxy-ktor`, which brings the agent, as
  [Adding the Dependency](ktor-plugin.md#adding-the-dependency) shows; the demo's build uses the latest release
  (`make up`) or this checkout (`make up-local`).
- Its image is built from
  [`app/Dockerfile`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/ktor/app/Dockerfile), as
  [Packaging the App](cloud-platforms.md#packaging-the-app) describes, with a second way in that copies in a build
  made on the host, for `make up-local`.
- The environment comes from the demo's
  [`docker/compose.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/ktor/docker/compose.yml),
  whose `x-coffee-shop-env` block gives the proxy's address and the name its certificate was issued to, and reads the
  token and the CA certificate from `docker/.env`, which `make up` writes the first time
  it runs. It also turns on the agent's admin endpoints, `/debug`, and its dashboard, with `ADMIN_ENABLED`,
  `DEBUG_ENABLED`, and `DASHBOARD_ENABLED`, which the agent reads itself, and each version's service gives the
  dashboard its port, in `DASHBOARD_PORT`.

## The coffee shop in Grafana

<figure markdown="span">
  ![The Coffee Shop dashboard during a rush, with the espresso machine broken](images/ktor-demo/grafana.webp#only-dark){ loading=lazy }
  ![The Coffee Shop dashboard during a rush, with the espresso machine broken](images/ktor-demo/grafana-light.webp#only-light){ loading=lazy }
  <figcaption>The Coffee Shop dashboard during a rush, with the espresso machine broken</figcaption>
</figure>

Grafana opens on the Coffee Shop dashboard: the shop right now, whether its agent is connected to the proxy, drinks
served by type, orders by outcome, the queue against the baristas, brew time, the order counter's HTTP statuses, and,
below them, Ktor's HTTP latency, the JVM's heap, and how long each scrape through the proxy took. Here a rush has filled
the queue, and with the espresso machine broken, about three orders in four fail, and `CoffeeShopErrors` fires.

```bash
./demo.sh rush            # customers order three times as often
./demo.sh baristas 8      # more baristas drain the queue
./demo.sh calm            # ends the rush
./demo.sh break-machine   # every espresso drink fails
./demo.sh fix-machine
```

## A deploy

<figure markdown="span">
  ![The proxy dashboard's Paths view after a deploy: coffee-shop-1.1 serves both paths](images/ktor-demo/deploy.webp#only-dark){ loading=lazy }
  ![The proxy dashboard's Paths view after a deploy: coffee-shop-1.1 serves both paths](images/ktor-demo/deploy-light.webp#only-light){ loading=lazy }
  <figcaption>The proxy dashboard's Paths view after a deploy: version 1.1's agent serves both paths</figcaption>
</figure>

`./demo.sh deploy` starts version 1.1 beside 1.0, then stops 1.0, as a rolling deploy does. 1.1's agent registers the
same paths with the same token, and the proxy gives each one to the newer registration, so Prometheus scrapes 1.0
through 1.0's agent, then 1.1 through 1.1's, with no gap in the graphs. The coffee shop keeps one address throughout:
its load balancer sends requests to 1.0 while it runs, and to 1.1 once it has stopped, so the status page now reads
Version 1.1. The proxy tells 1.0's agent it gave the paths away, so until 1.0 stops, its own dashboard shows them as
`retrying`, taken over by `coffee-shop-1.1`, and had 1.1 stopped first, as a failed deploy's new version does, 1.0's
agent would have taken them back within seconds. `./demo.sh rollback` puts 1.0 back.

Each version's agent has its own dashboard, inside its own app, and 1.1's lists the two paths registered:
<http://localhost:8396/dashboard>

## Debugging the agent

When an embedded agent doesn't connect, or a path doesn't scrape, its admin endpoints show what it's doing. The coffee
shop's environment turns them on with `ADMIN_ENABLED`, and its `/debug` page with `DEBUG_ENABLED`, with nothing in its
code, and the demo publishes the port on `127.0.0.1:8393`. The agent reads them itself, from the `x-coffee-shop-env`
block of the demo's `docker/compose.yml`:

```yaml
ADMIN_ENABLED: "true"     # its admin endpoints, on port 8093, published on 127.0.0.1:8393
DEBUG_ENABLED: "true"     # adds /debug
```

`./demo.sh debug` calls each endpoint and shows what it answered:

| Endpoint | What it answers |
|---|---|
| `/ping` | `pong`, while the agent is running, for a liveness probe |
| `/version` | The agent's version, release date, and build time, as JSON |
| `/healthcheck` | The agent's health checks, as JSON: its own health, not its connection, so it stays healthy while the proxy is down |
| `/threaddump` | Every thread in the coffee shop's JVM, the app's as well as the agent's |
| `/debug` | Only with `DEBUG_ENABLED`: the agent's name, the ID the proxy gave it when it connected, which is empty while it isn't connected, the proxy it's connected to or trying, and each path with the URL it scrapes, or `in-process` for the app's registry |

Turn them off once the agent works, since the admin port has no authentication and `/threaddump` shows the app's
threads: take `ADMIN_ENABLED` and `DEBUG_ENABLED` out of the app's environment and restart it, with no new build, and
set them again when you need to look. `ADMIN_PORT` moves the port. In code, `setting("admin.enabled", true)` and
`setting("admin.debugEnabled", true)` do the same, though the environment overrides them.
[Debugging](embedded-agent.md#debugging) on the Embedded Agent page has more.

The agent's [dashboard](agent-dashboard.md) answers the same questions in a browser, and some the admin endpoints
can't. Its Connection view shows the last error from each proxy endpoint while the agent can't connect, its Paths view
why the proxy refused a path, and its Activity view the order things happened in. The coffee shop logs a warning at
startup that the dashboard has no authentication and listens on all interfaces; inside the container it has to, for
Docker to publish its port, which the demo binds to `127.0.0.1`.

## The proxy goes away

`./demo.sh proxy-stop` stops the proxy. The coffee shop keeps serving, and an order still goes through
(`./demo.sh order`), since its agent retries in the background and the app never waits on it. Prometheus can't reach
the proxy, so the graphs stop, Grafana's **Agent** reads Disconnected, and `CoffeeShopDown` fires. The agent's
`/debug` page shows an empty AgentId, since the agent has lost its connection, and the proxy it keeps trying, while its
`/healthcheck` still reads healthy: <http://localhost:8393/debug>

On the agent's dashboard, the Connection view reads `failing`, with the error the agent's last try to reach
`proxy:50051` got, and its paths read `pending`. It's the first place to look when an agent won't connect:
<http://localhost:8395/dashboard/connection>

`./demo.sh proxy-start` brings the proxy back, and the agent reconnects within a few seconds and registers its paths
again. The dashboard's Activity view then tells the outage in order, newest first: the paths `registered`,
`connected`, `connect failed`, and `disconnected`. Open it at <http://localhost:8395/dashboard/activity>

## Secrets from the environment

`./demo.sh secrets` lists the coffee shop's and the proxy's secrets by name, and shows that the app's container holds
no config file. The app builds its agent's settings in code from its environment, with `fromEnvironment()`, and the
stock proxy image, which has
no shell to write a variable to a file, takes its certificate and key as base64 PEM in `CERT_CHAIN_PEM` and
`PRIVATE_KEY_PEM`, and logs only whether each is set. On a cloud platform, they're the service's variables.

## Scenario reference

| Command | Effect | How it works |
|---|---|---|
| `demo.sh peek` | Looks for the coffee shop's `/metrics`, which it doesn't have, then reads its metrics through the proxy | `curl` of the app's `/metrics`, then of the proxy's `/coffee_shop_metrics` |
| `demo.sh order [drink]` | Orders a drink at the counter (default `latte`) | `POST /orders?drink=<drink>` |
| `demo.sh rush` | Customers order three times as often | `POST /admin/rush?on=true` |
| `demo.sh baristas <count>` | Puts this many baristas on shift (1 to 12) | `POST /admin/baristas?count=<count>`: one sent home finishes the drink in hand |
| `demo.sh calm` | Ends the rush, and keeps three baristas | `POST /admin/rush?on=false` and `/admin/baristas?count=3` |
| `demo.sh break-machine` / `fix-machine` | Every espresso drink fails, or is served again | `POST /admin/machine?broken=true` / `false` |
| `demo.sh deploy` / `rollback` | Deploys version 1.1 beside 1.0 and stops 1.0, or puts 1.0 back | Starts `coffee-shop-next` (compose profile `deploy`), whose agent takes the paths, then stops `coffee-shop`, and the load balancer sends the shop's requests to 1.1; `rollback` the other way round |
| `demo.sh proxy-stop` / `proxy-start` | Stops or starts the proxy | `docker compose stop` / `start proxy`: the agent retries every 3 seconds until it's back |
| `demo.sh debug` | Calls each of the agent's admin endpoints, and shows its `/debug` page | `curl` of `/ping`, `/version`, `/healthcheck`, `/threaddump`, and `/debug` on the agent's admin port, which the coffee shop's environment turns on |
| `demo.sh secrets` | Shows where the secrets come from | The names of the app's and the proxy's variables, and the app's container's files |
| `make tour [PART=n]` | Walks through every scenario, from part 1 or the one given, pausing before each step |  |
| `make status` | The containers, the coffee shop's state, and the URLs |  |
| `make logs [SERVICE=name]` | Follows the logs, of every service or the one given |  |
| `make open` | Opens the coffee shop, the proxy's and the agent's dashboards, Grafana, and Prometheus's alerts in the browser |  |

`calm`, `fix-machine`, `rollback`, and `proxy-start` undo every scenario. For a clean slate, `make down && make up`.

## Going further

- The demo's [README](https://github.com/pambrose/prometheus-proxy/tree/master/demos/ktor) has these scenarios as a
  guided tour, explaining how each one works, and lists the files that set the demo up. The coffee shop's code is in
  `demos/ktor/app`.
- [Cloud Platforms](cloud-platforms.md) sets the same thing up for real: the app on ECS, Cloud Run, or Azure Container
  Apps, and the proxy on AWS, GCP, Azure, DigitalOcean, or Railway, with your own certificate and token.
- [Embedded Agent](embedded-agent.md) covers the API the coffee shop uses, and the agent's logging and metrics inside
  a host app.
- The other demos, in the order the [Demos](demos/index.md) page gives them:
    - [Basics Demo](basics-demo.md): the whole system on one machine, the proxy and its dashboard, three agents behind
      a simulated firewall, Prometheus, and Grafana
    - [Network Demo](network-demo.md): a simulated WAN between the agents and the proxies, and what a far, thin, dead,
      flaky, or missing link does
    - [nginx Demo](nginx-demo.md): nginx between the agents and the proxy, its stream timeouts, its reloads, and TLS
      ended on one port
    - [Kubernetes Demo](kubernetes-demo.md): the Helm charts on two kind clusters, with Kubernetes doing the operating
      and the target cluster exposing nothing
    - [Spring Boot Demo](spring-boot-demo.md): a roastery, a Spring Boot app in Java whose agent the starter starts
      from properties
    - [Plain Kotlin Demo](plain-kotlin-demo.md): a bakery's ovens, workers with no framework or HTTP server, each
      reading its own metrics in-process
