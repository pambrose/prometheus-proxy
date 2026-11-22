# <img src="website/prometheus-proxy/docs/images/logo.svg" alt="" width="48" height="48" align="top"> Prometheus Proxy

[![GitHub release (latest by date)](https://img.shields.io/github/v/release/pambrose/prometheus-proxy)](https://github.com/pambrose/prometheus-proxy/releases)
[![Maven Central](https://img.shields.io/maven-central/v/com.pambrose/prometheus-proxy)](https://central.sonatype.com/artifact/com.pambrose/prometheus-proxy)
[![Kotlin version](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fpambrose%2Fprometheus-proxy%2Fmaster%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&label=kotlin&color=red&logo=kotlin)](https://kotlinlang.org)
[![Java version](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fpambrose%2Fprometheus-proxy%2Fmaster%2Fgradle%2Flibs.versions.toml&query=%24.versions.jvm&label=java&suffix=%2B&color=orange&logo=openjdk)](https://adoptium.net)
[![CI](https://github.com/pambrose/prometheus-proxy/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/pambrose/prometheus-proxy/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/pambrose/prometheus-proxy/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/prometheus-proxy)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://app.codacy.com/gh/pambrose/prometheus-proxy/dashboard)
[![CodeScene Average Code Health](https://codescene.io/projects/85750/status-badges/average-code-health)](https://codescene.io/projects/85750)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

[![prometheus-agent Stats](https://dockerhub-readme-stats.vercel.app/api?image=pambrose/prometheus-agent&label=Docker%20Stats&hide=stars)](https://hub.docker.com/r/pambrose/prometheus-agent)
[![prometheus-proxy Stats](https://dockerhub-readme-stats.vercel.app/api?image=pambrose/prometheus-proxy&label=Docker%20Stats&hide=stars)](https://hub.docker.com/r/pambrose/prometheus-proxy)

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for
collecting metrics. The pull model is problematic when a firewall separates a Prometheus server and its metrics
endpoints.

[Prometheus Proxy](https://prometheus-proxy.com/) enables Prometheus to scrape metrics endpoints running
behind a firewall and preserves the native pull-based model architecture.

**See it work on your laptop:** three self-contained [demos](#-try-the-demos) run the proxy, its agents, Prometheus,
and Grafana with one command, and a guided tour walks you through each one.

Questions about the API? The [docs site](https://prometheus-proxy.com/) has a
[Context7](https://context7.com/pambrose/prometheus-proxy) chat on every page that answers from this project's
documentation.

## Table of Contents

- [Try the Demos](#-try-the-demos)
- [New Features](#-new-features)
- [Architecture](#-architecture)
- [Quick Start](#-quick-start)
- [Homebrew](#-homebrew)
- [Building from Source](#-building-from-source)
- [Configuration Examples](#-configuration-examples)
- [Docker Usage](#-docker-usage)
- [Advanced Features](#-advanced-features)
- [Monitoring & Observability](#-monitoring--observability)
- [Configuration Options](#-configuration-options)
- [Security & TLS](#-security--tls)
- [Troubleshooting](#-troubleshooting)
- [Documentation](#-documentation)
- [License](#-license)

## 🎬 Try the Demos

The quickest way to understand Prometheus Proxy is to watch it work. The repository ships five self-contained demos,
each started with `make up` and run locally. Each has a guided tour, `make tour`, that walks you through
it one step at a time: what to open, what to look for, and how each step does what it does.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="website/prometheus-proxy/docs/images/demo/agents.webp">
  <img alt="The proxy dashboard in the basics demo: its three agents, and agent-east's registered paths and recent scrapes" src="website/prometheus-proxy/docs/images/demo/agents-light.webp">
</picture>

| Demo | What you'll see | Tour |
|---|---|---|
| [Basics](demos/basics) | The proxy, its live dashboard, and three agents behind a simulated firewall, with Prometheus and Grafana. A service fails and another hangs, a redundant pair of agents keeps a path up, new targets appear without a restart, a frozen agent is evicted, the proxy refuses a path an agent's identity doesn't allow, an agent's own dashboard shows the refusal and a lost proxy from its side, and the dashboard asks for a login. | 11 parts, about 28 minutes |
| [Network](demos/network) | The same proxy and agents with a simulated WAN between them, and a standby proxy. A far link, a thin one fixed by filtering at the agent, a proxy that goes away, a link that dies silently, a flaky link traced in Zipkin, and the whole WAN going down, each also seen from the agent's own dashboard. | 7 parts, about 24 minutes |
| [Kubernetes](demos/kubernetes) | The project's Helm charts on two kind clusters. The target cluster exposes nothing, annotating a Service is all it takes for Prometheus to scrape it, Kubernetes replaces a lost agent by itself, and a config change costs a restart where a target change doesn't, with the agent's own dashboard reached through `kubectl port-forward`. Needs kind, `kubectl`, and Helm as well as Docker. | 5 parts, about 15 minutes |
| [Embedded (Ktor)](demos/embedded) | A Ktor coffee shop with the agent embedded in it, its metrics in Grafana with no port opened for them. A rush fills the queue, a broken machine fires an alert, a deploy hands the agent's paths to the new version, the shop keeps serving while the proxy is away, the agent's own dashboard runs inside the app, and every secret comes from the environment. | 9 parts, about 23 minutes |
| [Spring Boot](demos/spring-boot) | A Spring Boot roastery, written in Java, with the agent embedded through `EmbeddedAgentConfig.builder()`. The agent starts and stops with the app, reports its connection in Actuator's health, reads Actuator's metrics behind Spring Security, runs with the versions of its libraries Spring Boot picks, serves its own dashboard from inside the app, and goes first in a graceful shutdown. | 5 parts, about 11 minutes |

Start with the basics demo. The proxy and agent are the released images, from Docker Hub, so there's nothing to build:
on macOS or Linux, the basics, network, embedded, and Spring Boot demos need only
[Docker](https://docs.docker.com/get-started/get-docker/) with Compose v2, `git`, `make`, `curl`, and a browser; the
embedded and Spring Boot demos build their apps inside Docker. The Kubernetes demo runs two Kubernetes clusters in Docker, so it also
needs [kind](https://kind.sigs.k8s.io/) 0.33.0 or later, [`kubectl`](https://kubernetes.io/docs/tasks/tools/), and
[Helm](https://helm.sh/) 3 or 4. From the repository's root, `make demos-up` (or `make demos-up-local`, to build them
from the checkout) starts every demo, one after another, and `make demos-down` stops them all; together they take a lot
of memory and CPU.

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.6.1 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/basics
make up      # pulls the images, starts everything, and prints the URLs
make tour    # the guided tour
make down    # stops the demo and removes its containers
```

The demos use different ports, so they can run side by side. Each demo's README lists what it needs and has its full
script, and the website's
[Basics Demo](https://prometheus-proxy.com/basics-demo/),
[Network Demo](https://prometheus-proxy.com/network-demo/),
[Kubernetes Demo](https://prometheus-proxy.com/kubernetes-demo/),
[Embedded Demo](https://prometheus-proxy.com/embedded-demo/), and
[Spring Boot Demo](https://prometheus-proxy.com/spring-boot-demo/) pages walk through the same
scenarios.

## ✨ New Features

Version 4.6.1 (released 2026-11-22) puts a login on the proxy's dashboard: set `DASHBOARD_PASSWORD`, and the dashboard
shows a login page, with the username `admin` unless you set `DASHBOARD_USERNAME`. The proxy now logs
a refused agent token where an operator looks, and no longer logs an exception for a connection that closes before it
is ready, such as a port probe or a failed TLS handshake. The Embedded Agent page has a new
[Debugging](https://prometheus-proxy.com/embedded-agent/#debugging) section, which the embedded demo's tour walks
through. The [release notes](RELEASE_NOTES.md) and the [changelog](CHANGELOG.md) have the full list.

Each earlier release's summary is in the [release notes](RELEASE_NOTES.md), and the [changelog](CHANGELOG.md) has
every change.

## 🧱 Architecture

> 📖 **Docs site:** [Architecture](https://prometheus-proxy.com/architecture/) for the full component breakdown and request flow, and the [Glossary](https://prometheus-proxy.com/glossary/) for the core terms used throughout the docs.

The `prometheus-proxy` runtime comprises two services:

* `proxy`: runs in the same network domain as Prometheus server (outside the firewall) and proxies calls from Prometheus
  to the `agent` behind the firewall.
* `agent`: runs in the same network domain as all the monitored hosts/services/apps (inside the firewall). It maps the
  scraping queries coming from the `proxy` to the actual `/metrics` scraping endpoints of the hosts/services/apps.

Prometheus Proxy solves the firewall problem by using a persistent gRPC connection initiated from inside the firewall.
Here's a simplified network diagram of how the deployed `proxy` and `agent` work:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/prometheus-proxy-dark.png">
  <img alt="Architecture diagram: Prometheus scrapes the proxy outside the firewall at proxy:8080/app1_metrics and the other paths; the agent inside the firewall holds a one-way persistent connection out to the proxy and fetches app1, app2, and app3's :9100/metrics" src="docs/prometheus-proxy-light.png">
</picture>

Endpoints running behind a firewall require a `prometheus-agent` (the agent) to be run inside the firewall. An agent
can run as a standalone server or embedded in another JVM application. Agents connect to a `prometheus-proxy` (the
proxy) and register the paths for which they will provide data. One proxy can work with one or many agents.

### Components

- **🌐 Proxy** - Runs outside the firewall alongside Prometheus
  - HTTP server (port 8080) - Serves metrics to Prometheus
  - gRPC server (port 50051) - Accepts agent connections
  - Service discovery support for dynamic targets

- **🔗 Agent** - Runs inside the firewall with monitored services
  - Connects to proxy via gRPC (outbound connection only)
  - Scrapes local metrics endpoints
  - Registers available paths with proxy

### Key Benefits

- ✅ **Firewall-friendly** - Only requires outbound connection from agent
- ✅ **Preserves pull model** - Prometheus continues to pull as normal
- ✅ **High performance** - Built with Kotlin coroutines and gRPC
- ✅ **Secure** - Optional TLS with mutual authentication, plus per-agent identities with path authorization
- ✅ **Scalable** - One proxy supports many agents
- ✅ **Highly available** - Agents fail over across an ordered list of proxies, and back again
- ✅ **Dynamic** - Agents pick up target changes from a watched file, no restart needed
- ✅ **Bandwidth-conscious** - Optional per-path metric filtering drops unwanted families at the agent
- ✅ **Observable** - A live dashboard shows every agent, path, and recent scrape in one place, and scrapes a path on demand
- ✅ **Zero changes** to existing Prometheus configuration patterns

### Common Scenarios

- **🏢 Enterprise environments** - Scrape metrics across firewall boundaries
- **☁️ Multi-cloud deployments** - Bridge different network segments
- **🔒 Secure environments** - Monitor internal services without opening inbound ports
- **🌐 Federation** - Scrape existing Prometheus instances via `/federate` endpoint
- **🚀 Kubernetes** - Monitor services across clusters or namespaces

## 🚀 Quick Start

> 📖 **Docs site:** [Quick Start guide](https://prometheus-proxy.com/getting-started/) walks through these steps with more detail.
>
> 🎬 **Rather watch it first?** The [demos](#-try-the-demos) start the proxy, agents, Prometheus, and Grafana with one command.

**Requirements:** Java 17 or newer

### CLI Quick Start

1. Download the latest proxy and agent JAR files from [releases](https://github.com/pambrose/prometheus-proxy/releases)

2. Start the **proxy** (runs outside the firewall with Prometheus):
   ```bash
   java -jar prometheus-proxy.jar
   ```

3. Start the **agent** (runs inside the firewall with your services):
   ```bash
   java -jar prometheus-agent.jar \
     -Dagent.proxy.hostname=mymachine.local \
     --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf

   # or use --proxy option
   java -jar prometheus-agent.jar \
     --proxy mymachine.local \
     --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf
   ```

4. Configure Prometheus to scrape from the proxy at `http://mymachine.local:8080`

5. Verify it works with:
   ```bash
   curl -s http://mymachine.local:8080/app1_metrics | head

   # or, if SD is enabled
   curl -s http://mymachine.local:8080/discovery | jq '.'
   ```

6. Steps 2 and 3 run in the foreground until Ctrl+C. To keep the proxy and agent running after you close the
   terminal, start each with `nohup`, sending its output to a log file and saving its process ID:
   ```bash
   # On the proxy's machine
   nohup java -jar prometheus-proxy.jar > proxy.log 2>&1 &
   echo $! > proxy.pid

   # On the agent's machine
   nohup java -jar prometheus-agent.jar --proxy mymachine.local --config myapps.conf > agent.log 2>&1 &
   echo $! > agent.pid

   # Later, to follow a log or stop a process
   tail -f proxy.log
   kill "$(cat proxy.pid)"
   ```

   Nothing restarts them if they exit or the machine reboots; `brew services` and Docker's `--restart`, below, do.

### 🍺 Homebrew

On macOS and Linux, the proxy and the agent are also available from Homebrew. Each formula installs the
Java it runs on (`openjdk@25`), so there is no JAR to download:

```bash
# On the proxy's machine, outside the firewall
brew install pambrose/tap/prometheus-proxy
prometheus-proxy

# On the agent's machine, inside the firewall
brew install pambrose/tap/prometheus-agent
prometheus-agent --proxy mymachine.local --config myapps.conf
```

To keep either one running in the background, edit its config in `$(brew --prefix)/etc/` and start it with
`brew services`, which also starts it again at every login: `prometheus-proxy.conf` sets the proxy's ports and
agent authentication, and `prometheus-agent.conf` sets the proxy the agent connects to and the paths it serves.
Each logs to `$(brew --prefix)/var/log/<name>.log`.

```bash
brew services start prometheus-proxy     # start now and at every login
brew services start prometheus-agent

brew services restart prometheus-agent   # pick up an edited config
brew services stop prometheus-agent      # stop, and no longer start at login
```

### 🧰 Building from Source

If you prefer to build the project from source:

1. Clone the repository:
   ```bash
   git clone https://github.com/pambrose/prometheus-proxy.git
   cd prometheus-proxy
   ```

2. Build the fat JARs:
   ```bash
   ./gradlew agentJar proxyJar
   ```

   `agentJar` and `proxyJar` are dedicated `ShadowJar` tasks; the default `shadowJar` task is disabled so it cannot produce a third redundant fat jar.

3. The JARs will be available in `build/libs/`:
   - `build/libs/prometheus-proxy.jar`
   - `build/libs/prometheus-agent.jar`

### Docker Quick Start

```bash
# Create a network for the two containers
docker network create prometheus-proxy-net

# Start proxy, with its metrics enabled
docker run --rm --name prometheus-proxy --network prometheus-proxy-net \
  -p 8080:8080 -p 50051:50051 \
  --env METRICS_ENABLED=true \
  pambrose/prometheus-proxy:4.6.1

# Start agent, in another terminal
docker run --rm --network prometheus-proxy-net \
  --env PROXY_HOSTNAME=prometheus-proxy \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  pambrose/prometheus-agent:4.6.1

# Scrape the proxy's and the agent's own metrics through the proxy
curl http://localhost:8080/proxy_metrics
curl http://localhost:8080/agent_metrics
```

In a container, `localhost` is the container itself, so the agent reaches the proxy by its container name on the
network they share, given in `PROXY_HOSTNAME`. The agent's config, [simple.conf](examples/simple.conf), fetches the
proxy's metrics from that host too, which is why the proxy needs `METRICS_ENABLED`. In a real deployment the agent runs
on another host, inside the firewall, with `PROXY_HOSTNAME` set to the proxy's address and no shared network.

These run in the foreground until Ctrl+C. To run a container in the background instead, and have it start again
if it exits or Docker restarts, replace `--rm` with `--detach --restart unless-stopped`, and give the agent a `--name`
too (Docker doesn't allow `--rm` with `--restart`):

```bash
docker run --detach --name prometheus-proxy --restart unless-stopped --network prometheus-proxy-net \
  -p 8080:8080 -p 50051:50051 --env METRICS_ENABLED=true pambrose/prometheus-proxy:4.6.1

docker logs --follow prometheus-proxy   # follow its log
docker stop prometheus-proxy            # stop it, and no longer restart it
docker rm prometheus-proxy              # remove the stopped container
```

## 📋 Configuration Examples

> 📖 **Docs site:** [Agent configuration](https://prometheus-proxy.com/configuration/agent/) and [Proxy configuration](https://prometheus-proxy.com/configuration/proxy/) document every config key in depth.

### Agent Configuration

If the prometheus-proxy were running on a machine named *mymachine.local* and the `agent.pathConfigs` value in the
[myapps.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf) config file had
the contents:

```hocon
agent {
  pathConfigs: [
    {
      name: "App1 metrics"
      path: app1_metrics
      labels: "{\"key1\": \"value1\", \"key2\": \"2\"}"
      url: "http://app1.local:9100/metrics"
    },
    {
      name: "App2 metrics"
      path: app2_metrics
      labels: "{\"key3\": \"value3\", \"key4\": \"4\"}"
      url: "http://app2.local:9100/metrics"
    },
    {
      name: "App3 metrics"
      path: app3_metrics
      labels: "{\"key5\": \"value5\", \"key6\": \"6\"}"
      url: "http://app3.local:9100/metrics"
    }
  ]
}
```

then the *prometheus.yml* scrape_config would target the three apps with:

* http://mymachine.local:8080/app1_metrics
* http://mymachine.local:8080/app2_metrics
* http://mymachine.local:8080/app3_metrics

If the endpoints were restricted with basic auth/bearer authentication, you could either include the basic-auth
credentials in the URL with: `http://user:pass@hostname/metrics` or they could be configured with `basic_auth`/
`bearer_token` in the scrape-config. The agent sends credentials from the URL with each request, unless the
scrape-config supplies an `Authorization` header, which is sent instead.

### Corresponding Prometheus Configuration

The `prometheus.yml` file would include:

```yaml
scrape_configs:
  - job_name: 'app1 metrics'
    metrics_path: '/app1_metrics'
    bearer_token: 'eyJ....hH9rloA'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
  - job_name: 'app2 metrics'
    metrics_path: '/app2_metrics'
    basic_auth:
      username: 'user'
      password: 's3cr3t'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
  - job_name: 'app3 metrics'
    metrics_path: '/app3_metrics'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
```

### Example Configurations

> 📖 **Docs site:** [Example Configs guide](https://prometheus-proxy.com/examples/) describes each ready-to-run config under
`examples/`.

| Use Case                   | Configuration                                                              |
|:---------------------------|:---------------------------------------------------------------------------|
| **Basic setup**            | [`examples/simple.conf`](examples/simple.conf)                             |
| **Multiple apps**          | [`examples/myapps.conf`](examples/myapps.conf)                             |
| **Metric filtering**       | [`examples/agent-filters.conf`](examples/agent-filters.conf)               |
| **Dynamic discovery**      | [`examples/discovery-targets.conf`](examples/discovery-targets.conf)       |
| **TLS (no mutual auth)**   | [`examples/tls-no-mutual-auth.conf`](examples/tls-no-mutual-auth.conf)     |
| **TLS (with mutual auth)** | [`examples/tls-with-mutual-auth.conf`](examples/tls-with-mutual-auth.conf) |
| **Prometheus federation**  | [`examples/federate.conf`](examples/federate.conf)                         |
| **Nginx reverse proxy**    | [`nginx/nginx-proxy.conf`](nginx/nginx-proxy.conf)                         |

## 🐳 Docker Usage

> 📖 **Docs site:** [Docker deployment guide](https://prometheus-proxy.com/docker/) covers compose files, bind mounts, and container configuration. Running on Kubernetes? See the [Kubernetes guide](https://prometheus-proxy.com/kubernetes/).

### Multi-Platform Images

The docker images support multiple architectures (amd64, arm64, s390x, ppc64le):

```bash
docker pull pambrose/prometheus-proxy:4.6.1
docker pull pambrose/prometheus-agent:4.6.1
```

### Production Docker Setup

These run in the background, and start again if they exit or Docker restarts, on the `prometheus-proxy-net` network
from the [Docker Quick Start](#docker-quick-start) (`docker network create prometheus-proxy-net` if you skipped it). On
separate hosts, leave out `--network` and set the agent's `PROXY_HOSTNAME` to the proxy's address.

Start a proxy container with:

```bash
# Proxy with admin and metrics enabled
docker run --detach --name prometheus-proxy --restart unless-stopped \
        --network prometheus-proxy-net \
        -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        pambrose/prometheus-proxy:4.6.1
```

Start an agent container with:

```bash
# Agent with remote config file
docker run --detach --name prometheus-agent --restart unless-stopped \
        --network prometheus-proxy-net \
        -p 8083:8083 -p 8093:8093 \
        --env PROXY_HOSTNAME=prometheus-proxy \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:4.6.1
```

Or use Docker Compose: [`etc/compose/proxy.yml`](etc/compose/proxy.yml) runs a proxy, an agent, and a Prometheus server
that scrapes through them (`docker compose -f etc/compose/proxy.yml up`).

Using the config file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf),
the proxy and the agent metrics would be available from the proxy's scrape port on *localhost* at:

* http://localhost:8080/proxy_metrics
* http://localhost:8080/agent_metrics

Ports 8082 and 8083 serve the proxy's and the agent's own metrics at `/metrics`, which the agent fetches for those two
paths.

If you want to use a local config file with a docker container (instead of the above HTTP-served config file), use the
docker [mount](https://docs.docker.com/storage/bind-mounts/) option. Assuming the config file `prom-agent.conf`
is in your current directory, run an agent container with:

```bash
# Agent with local config file
docker run --rm -p 8083:8083 -p 8093:8093 \
    --network prometheus-proxy-net \
    --mount type=bind,source="$(pwd)"/prom-agent.conf,target=/app/prom-agent.conf \
    --env AGENT_CONFIG=prom-agent.conf \
    --env PROXY_HOSTNAME=prometheus-proxy \
    pambrose/prometheus-agent:4.6.1
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

## 🧩 Advanced Features

### Embedded Agent

If you are running a JVM-based program, you can run with the agent embedded directly in your app and not use an external
agent. This approach eliminates the need for a separate agent process when your application already runs on the JVM.

> 📖 **Docs site:** [Embedded Agent guide](https://prometheus-proxy.com/embedded-agent/) for the full API surface and lifecycle details.

> 🎬 **See it running:** the [embedded demo](demos/embedded) runs the agent inside a Ktor coffee shop, built with `embeddedAgent { }`, and the [Spring Boot demo](demos/spring-boot) inside a Spring Boot roastery written in Java, built with `EmbeddedAgentConfig.builder()`. Each starts with one command, and the website's [Embedded Demo](https://prometheus-proxy.com/embedded-demo/) and [Spring Boot Demo](https://prometheus-proxy.com/spring-boot-demo/) pages walk through them.

In Kotlin:

```kotlin
// Start embedded agent; exitOnMissingConfig = false makes config errors throw instead of exiting the JVM
val agentInfo = Agent.startAsyncAgent(configFilename = "configFile.conf", exitOnMissingConfig = false)

// Your application code runs here
// The agent runs in the background and does not block your application

// Shutdown the agent when the application terminates
agentInfo.shutdown()
```

In Java:

```java
// Start embedded agent; false (exitOnMissingConfig) makes config errors throw instead of exiting the JVM
EmbeddedAgentInfo agentInfo = Agent.startAsyncAgent("configFile.conf", false);

// Your application code runs here
// The agent runs in the background and does not block your application

// Shutdown the agent when the application terminates
agentInfo.shutdown();
```

If the configuration cannot be loaded (a parse error, an unreachable config URL, or a missing file), or it holds a
setting the agent can't use (a value of the wrong type or out of range, an unresolved `${…}` substitution, or a trust
store that can't be loaded), the embedded `startAsyncAgent` / `startSyncAgent` entry points throw
`io.prometheus.common.ConfigLoadException` for your application to catch, rather than terminating the host JVM via
`exitProcess`; credentials in a config URL are redacted from it. Stand-alone agents (run from the CLI) still log the
reason and exit with code 1. If the agent then fails to start (for example, its admin or metrics port is already in
use), `startAsyncAgent` throws `IllegalStateException` with the startup failure as its cause, after releasing everything
the agent had opened.

An application that holds the agent's settings itself, such as a token, the proxy's certificate, or credentials for
its own `/metrics`, can build them in code instead of in a file, with `embeddedAgent { }` (or
`EmbeddedAgentConfig.builder()` from Java), or pass a Typesafe `Config` rooted at `agent` to
`Agent.startAsyncAgent(config)`. The docs site's
[Cloud Platforms](https://prometheus-proxy.com/cloud-platforms/) page builds an app on ECS, Cloud Run,
or Azure Container Apps around it.

Several agents can run in one JVM with metrics on, at once or one after another: they share one set of agent metrics,
and each series carries its agent's `launch_id`. An embedded agent's `logLevel` sets only the `io.prometheus` loggers,
so it leaves your application's own logging alone. Your application's Logback configuration also configures the
agent's loggers, since the library jar carries none of its own; the standalone agent's sets `io.grpc.internal` to WARN,
which you may want too.

To debug an embedded agent, start by turning on its admin endpoints and its `/debug` page, which are off unless asked
for. The agent reads the app's environment itself, so they need no change to the code, and a restart turns them on or
off:

```bash
ADMIN_ENABLED=true   # the admin endpoints, on port 8093 (ADMIN_PORT for another)
DEBUG_ENABLED=true   # adds /debug
```

In code, `setting("admin.enabled", true)` and `setting("admin.debugEnabled", true)` do the same, though the environment
overrides them. `/debug` shows the agent's name, the proxy it's connected to or trying, and each path with the URL it
scrapes; `/healthcheck` stays healthy while the agent can't reach a proxy, which `EmbeddedAgentInfo.isConnected`
reports. Turn them off once the agent works, and keep the admin port on an internal network while they're on, since it
has no authentication. The docs site's [Debugging](https://prometheus-proxy.com/embedded-agent/#debugging) section lists
every endpoint, and puts the agent on the project's Grafana Agents dashboard (`METRICS_ENABLED=true`), and the
[embedded demo](demos/embedded)'s tour shows both.

### Service Discovery

Enable Prometheus service discovery support:

```bash
java -jar prometheus-proxy.jar \
  --sd_enabled \
  --sd_path discovery \
  --sd_target_prefix proxy-host:8080
```

Access discovery endpoint at: `http://proxy-host:8080/discovery`

`--sd_target_prefix` is the proxy's address as Prometheus reaches it: `host:port` (the default is `localhost:8080`),
or an `http` or `https` URL such as `https://proxy.example.com/prometheus/`, whose scheme and path the proxy gives each
target as its `__scheme__` label and a prefix of its `__metrics_path__`.

> 📖 **Docs site:** [Service Discovery guide](https://prometheus-proxy.com/service-discovery/) explains the discovery payload format and the Prometheus
`http_sd_configs` wiring.

### Dynamic Target Discovery (Agent)

By default the agent's scrape targets come from the static `agent.pathConfigs` list, read once at
startup — adding or removing a target means editing the config and restarting the agent. Enable
dynamic discovery to have the agent reconcile its registered paths against a **watched file** at
runtime, with no restart:

```hocon
agent {
  discovery {
    enabled = true
    file.path = "/etc/prometheus-proxy/targets.conf"  // HOCON or JSON list of paths
    reconcileIntervalSecs = 30                        // Poll and full-resync interval
  }
}
```

The discovery file is a `paths` list of the same `{ name, path, url, labels }` entries as
`pathConfigs`:

```hocon
paths = [
  { name = "app1", path = "app1_metrics", url = "http://app1:9090/metrics" }
  { name = "app2", path = "app2_metrics", url = "http://app2:9090/metrics" }
]
```

Every `reconcileIntervalSecs` the agent reads the file and registers new paths, unregisters ones no
longer listed, and re-registers a path whose URL or labels changed — without disturbing the paths
that did not change. Behavior worth knowing:

- **Additive to the static baseline.** Discovered paths add to `agent.pathConfigs` (which is never
  touched); on a path collision the static entry wins. Leave `pathConfigs` empty to run
  discovery-only.
- **Fail-safe reads.** A missing/unreadable/malformed file keeps the last-known-good set; a
  *valid-but-empty* file removes all discovered paths.
- **Polling, not inotify.** Reliable under Kubernetes ConfigMap updates and bind mounts, where file
  watches often miss changes.
- **Config-file only.** `discovery.file.path` points at a list, so there is no CLI/env equivalent
  (though `enabled`, the path, and the interval are scalars you can also set via `-D`).

Because the file is a plain HOCON/JSON list, it can be **generated** — by a Kubernetes ConfigMap, an
Ansible template, or a cron job querying a service registry — so your automation only needs permission
to write a file, not to restart processes. A fully annotated example ships as
[`examples/discovery-targets.conf`](examples/discovery-targets.conf).

> This is distinct from the [Prometheus Service Discovery](#service-discovery) above: that exposes a
> discovery endpoint so *Prometheus* can find proxied targets, whereas this lets the *agent* pick up
> target changes behind the firewall without a restart.

### Metric Filtering (Agent)

By default the agent forwards every metric a target exposes, so chatty or high-cardinality endpoints
pay full bandwidth across exactly the network boundary this product exists to bridge. An optional
per-path filter drops whole metric families **at the agent**, before the payload is gzipped and
chunked, cutting WAN bandwidth and downstream Prometheus cardinality at the source:

```hocon
agent {
  pathConfigs: [
    { name: "app1", path: "app1_metrics", url: "http://app1:9090/metrics" }
  ]

  filters: [
    {
      path: "app1_metrics"  // The pathConfigs (or discovered) path this applies to
      metricNameAllow: []   // Fully-anchored regexes; empty allows every family
      metricNameDeny: [ "go_gc_.*", "go_memstats_.*" ]
    }
  ]
}
```

Both list fields are required on every element — write `metricNameAllow: []` for a deny-only filter.
The default is `filters: []`, so an existing config with no `filters` key loads unchanged. Behavior
worth knowing:

- **Regexes are fully anchored**, matching Prometheus `relabel_config` semantics: `"go_"` matches
  nothing, `"go_.*"` is required to match `go_goroutines`. `metricNameDeny` is applied after
  `metricNameAllow`, so deny wins on overlap.
- **Matching is per metric family, not per series.** A `# HELP`/`# TYPE` line opens a family and the
  verdict is computed once, so a histogram's `_bucket`, `_sum`, and `_count` series are always kept
  or dropped together with their metadata lines — a filter can never deliver a histogram with some of
  its series missing.
- **Fails open.** A non-text `Content-Type` (protobuf, an HTML error body) or a body that is not
  valid UTF-8 passes through unfiltered and byte-exact. The worst case is bandwidth not saved, never
  a corrupted payload.
- **Applies to discovered paths too.** Filters are matched by path, so they cover both
  `pathConfigs` entries and paths added by [Dynamic Target Discovery](#dynamic-target-discovery-agent).
- **Observable.** `agent_filter_lines_dropped_total` and `agent_filter_bytes_saved_total`, labeled by
  `launch_id` and `path`, report what each filter is actually removing.
- **Config-file only.** `filters` is a list, so there is no CLI/env equivalent.

An invalid regex fails agent startup rather than surfacing later on a scrape. Not implemented:
`dropLabels`, metric renaming/relabeling, and an agent-global filter — every filter is per-path.

### Proxy Failover (High Availability)

A single proxy is a single point of failure for every target behind every agent — losing it (or just
restarting it to change config) blinds Prometheus to everything on the far side of the firewall at
once. For redundancy, give the agent an **ordered list** of proxy endpoints instead of one hostname:

```hocon
agent {
  proxy {
    endpoints = [ "proxy-a.example.com:50051", "proxy-b.example.com:50051" ]
  }
}
```

Or comma-separated on the command line / environment (either **replaces** `endpoints` entirely):

```bash
java -jar prometheus-agent.jar --proxy proxy-a.example.com:50051,proxy-b.example.com:50051
# or: PROXY_HOSTNAME=proxy-a.example.com:50051,proxy-b.example.com:50051
```

The agent uses the first endpoint that accepts it. A **failed connect** moves it to the next, and so
does a proxy that accepts the connection but **rejects the agent's registration**, or rejects every one
of its static paths for a reason that can't clear. A connection that **registered** and later
**dropped** sends it back to the head of the list, so a recovered primary is picked up on the next
reconnect with no health prober and no manual step. Only one connection is active at a time, and a
single value behaves exactly as before — existing configs need no change.

Two things to know before deploying a pair:

- **Scrape both proxies with `static_config`, not `http_sd_config`.** A standby proxy has no agents,
  so its service-discovery endpoint returns an empty list — which Prometheus treats as "these targets
  no longer exist" and silently **deletes** them: no `up=0`, no alert. With `static_config` the
  standby 404s, Prometheus records `up=0`, and your alerting works unchanged.
- **All endpoints share one TLS configuration** (one trust store, one `overrideAuthority`), so proxies
  with different CAs or certificate SANs fail with an opaque handshake error.

> 📖 **Docs site:** [High availability](https://prometheus-proxy.com/production/#high-availability) for the full Prometheus-side setup, and [Running an HA pair](https://prometheus-proxy.com/web-dashboard/#running-an-ha-pair) for how the dashboard shows failed-over agents.

### Prometheus Federation

Scrape an existing Prometheus instance via the `/federate` endpoint:
```hocon
agent.pathConfigs: [{
  name: "Federated Prometheus"
  path: "federated_metrics"
  url: "http://prometheus-server:9090/federate?match[]={__name__=~\"job:.*\"}"
}]
```

This leverages the existing service discovery features already built into Prometheus. A complete example config is
[`examples/federate.conf`](examples/federate.conf).

### Nginx Reverse Proxy

To use with Nginx as a reverse proxy, disable the transport filter on both Proxy and Agent:
```bash
java -jar prometheus-proxy.jar --tf_disabled
java -jar prometheus-agent.jar --tf_disabled --config myconfig.conf
```

An example nginx config is [`nginx/docker/nginx.conf`](nginx/docker/nginx.conf), and an example proxy and agent config
is [`nginx/nginx-proxy.conf`](nginx/nginx-proxy.conf).

**⚠️ Note:** With `transportFilterDisabled`, the proxy can't see a connection close, only the agent's request stream
end. An agent that disconnects ends that stream, and is removed at once; one that dies before opening its stream, or
whose connection stays half-open, is removed after the inactivity timeout (default: 1 minute, controlled by
`proxy.internal.maxAgentInactivitySecs`). The proxy also limits the agents connected as one auth identity to
`proxy.internal.maxAgentsPerIdentity` (10,000), and with agent auth off every agent shares one identity.

> 📖 **Docs site:** [Advanced Topics guide](https://prometheus-proxy.com/advanced/) covers the Nginx reverse-proxy setup, federation, and other advanced configurations.

### Performance Tuning

Configure concurrent scraping:

```bash
java -jar prometheus-agent.jar \
  --max_concurrent_clients 5 \
  --client_timeout_secs 30 \
  --config myconfig.conf
```

## 📊 Monitoring & Observability

> 📖 **Docs site:** [Monitoring guide](https://prometheus-proxy.com/monitoring/) for the full metrics reference and admin endpoints, plus [Grafana & Alerting](https://prometheus-proxy.com/grafana/) for ready-to-import dashboards and alert rules.

### Operational Dashboard

The proxy ships a live-updating dashboard that answers the question this system raises most
often — *why isn't this target scraping?* — without log-diving on two machines. It shows every
connected agent, every registered path (including paths whose agent has **gone**, which would otherwise
vanish exactly when you go looking), and how recent scrapes went:

```bash
java -jar prometheus-proxy.jar --dashboard    # or DASHBOARD_ENABLED=true / proxy.dashboard.enabled
```

Then open `http://proxy-host:8094/dashboard`. Four layouts share the page:

- **Agents**: drill into one agent, its registered paths, and its recent scrapes.
- **Paths**: one row per target, with a History of each path's recent scrapes, a search box and a **Problems only**
  filter whose results are a link you can share, and a Scrape button that scrapes a path at once, through its agent,
  without counting as one of Prometheus's scrapes. List links in `proxy.dashboard.links` and each row opens its path in
  Prometheus, Grafana, or a runbook.
- **Activity**: what the proxy did with its agents and their paths, newest first, including what it refused, such as
  a path an agent's identity may not register, which otherwise reaches only the proxy's log.
- **Settings**: every setting the proxy resolved, from its listeners, agent authentication, and TLS to its limits and
  gRPC settings, with a token, a password, or a PEM shown only as set or not set.

Every layout names agents the same way, so they can be read against each other, and with agent authentication on,
the identity each agent connected as. It runs on its own port — not the admin port — so it can be firewalled without
cutting off Kubernetes health probes, and it loads no CDN assets, so it works in airgapped deployments. It has **no
TLS, and no authentication until you set a password**: keep the port internal. Anyone who can reach it can scrape a
path and read the start of what it returns; `proxy.dashboard.scrapeNowEnabled = false` removes the button.

Set `DASHBOARD_PASSWORD` (or `proxy.dashboard.password`) and the dashboard shows a login page first, with the username
`admin` unless you set `DASHBOARD_USERNAME`; a script sends the login as HTTP Basic Auth. The login crosses the network
unencrypted, so put TLS in front of the dashboard, such as a reverse proxy, unless the network is trusted. See
[Dashboard login](https://prometheus-proxy.com/web-dashboard/#login).

Keeping the port internal does not stop a web page from reaching the dashboard through the browser of
someone who can. The dashboard refuses a WebSocket from a foreign site; behind a reverse proxy that
rewrites the Host header, list the public origin in `proxy.dashboard.allowedOrigins`. It answers to any
host name by default, which leaves it open to DNS rebinding (a page pointing its own DNS name at the
dashboard's address); list the names you reach it by in `proxy.dashboard.allowedHosts` to close that. See
[Dashboard exposure](https://prometheus-proxy.com/web-dashboard/#exposure).

Accessibility: text and UI colors clear the WCAG AA contrast thresholds with a measured floor of
4.59:1, the path table carries proper header semantics, and connection loss and recovery are
announced to screen readers rather than signalled by colour alone.

> 📖 **Docs site:** [Dashboard guide](https://prometheus-proxy.com/web-dashboard/) for the layouts, the links, the status-bar gauges, and HA-pair behavior.

#### The agent's dashboard

The agent can serve a dashboard of its own, for what the proxy's can't see: an agent that can't connect, and a path the
proxy refused. It shows the agent's connection, with the last error from each proxy endpoint while it can't connect,
and every path, configured or discovered, with the proxy's reason for refusing one and when it's tried again, plus each
path's recent scrapes as the agent saw them, a Scrape now button that fetches its target from the agent, each path's
metric filter, the agent's discovery file, an Activity view of what happened to its connection and paths, and a
Settings view of every setting it resolved. It is off by default, takes the same login settings as the proxy's (`agent.dashboard.password`, or
`DASHBOARD_PASSWORD`), and listens on port 8095; the agent's Helm chart turns it on with `dashboard.enabled`, and every
demo turns it on for its agents:

```bash
java -jar prometheus-agent.jar --config agent.conf --dashboard    # or DASHBOARD_ENABLED=true / agent.dashboard.enabled
```

> 📖 **Docs site:** [Agent Dashboard](https://prometheus-proxy.com/agent-dashboard/).

### Built-in Metrics

Both proxy and agent expose their own metrics:

- **Proxy metrics:** `http://proxy-host:8082/metrics`
- **Agent metrics:** `http://agent-host:8083/metrics`. The agent sits behind the firewall, so give it a path for its own
  metrics and let Prometheus scrape them through the proxy, like any other target
- **Admin endpoints:** `http://host:admin-port/ping`, `/healthcheck`, `/version`, `/threaddump`
- **JVM metrics:** off by default; each group is turned on under `proxy.metrics` or `agent.metrics` in the config
  file (`memoryPoolsExportsEnabled`, `garbageCollectorExportsEnabled`, and so on). Version 4.3.0 adds buffer pools
  (`bufferPoolExportsEnabled`), JIT compilation (`compilationExportsEnabled`), and native memory
  (`nativeMemoryExportsEnabled`, which needs the JVM started with `-XX:NativeMemoryTracking=summary`); the
  [Monitoring guide](https://prometheus-proxy.com/monitoring/) lists every metric

## 🔧 Configuration Options

> 📖 **Docs site:** [Configuration overview](https://prometheus-proxy.com/configuration/) and the [CLI Reference](https://prometheus-proxy.com/cli-reference/) document every option and environment variable.

The proxy and agent use the [Typesafe Config](https://github.com/lightbend/config) library. Configuration values are
evaluated in order: **CLI options → environment variables → config file values**.

Typesafe Config highlights include:

* support for files in three formats: Java properties, JSON, and a human-friendly JSON
  superset ([HOCON](https://github.com/lightbend/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment variables, Java system properties, and/or config files.
* config files can reference environment variables

**📖 Complete configuration reference:** the docs site's [**CLI Reference**](https://prometheus-proxy.com/cli-reference/)
lists every CLI option with its environment variable, config property, and default.

### Common Options Summary

| Component | Option             | Env Var                         | Description                                          |
|:----------|:-------------------|:--------------------------------|:-----------------------------------------------------|
| **Both**  | `--config, -c`     | `PROXY_CONFIG` / `AGENT_CONFIG` | Path or URL to config file                           |
| **Both**  | `--admin, -r`      | `ADMIN_ENABLED`                 | Enable admin/health-check endpoints                  |
| **Both**  | `--metrics, -e`    | `METRICS_ENABLED`               | Enable internal metrics collection                   |
| **Both**  | `--agent_token`    | `AGENT_TOKEN`                   | Pre-shared agent auth token; both sides must match (Default: disabled). For per-agent identities see `proxy.auth` |
| **Proxy** | `--port, -p`       | `PROXY_PORT`                    | Port for Prometheus to scrape (Default: 8080)        |
| **Proxy** | `--agent_port, -a` | `AGENT_PORT`                    | Port for Agents to connect via gRPC (Default: 50051) |
| **Proxy** | `--dashboard`      | `DASHBOARD_ENABLED`             | Enable the operational dashboard (Default: disabled) |
| **Proxy** | `--dashboard_port` | `DASHBOARD_PORT`                | Port for the operational dashboard (Default: 8094)   |
| **Proxy** | `--dashboard_host` | `DASHBOARD_HOST`                | Listen address for the dashboard (Default: 0.0.0.0)  |
| **Proxy** |                    | `DASHBOARD_PASSWORD`            | Requires a login on the dashboard (Default: none)    |
| **Proxy** |                    | `DASHBOARD_USERNAME`            | The dashboard login's username (Default: admin)      |
| **Agent** | `--proxy, -p`      | `PROXY_HOSTNAME`                | Hostname/IP of the Proxy. Accepts a comma-separated failover list (e.g. `proxy-a:50051,proxy-b:50051`); see `agent.proxy.endpoints` |

### Configuration Notes

* **Formats:** Supports HOCON (`.conf`), JSON (`.json`), and Java Properties (`.properties`).
* **Logging:** Customize with `-Dlogback.configurationFile=/path/to/logback.xml`. To pick up edits without a restart, set `scan="true"` on that file's `<configuration>` element, as [`logback/docker-logback.xml`](logback/docker-logback.xml) does; the config bundled in the JARs can't be watched.
* **Dynamic Props:** Use `-Dproperty.name=value` for any scalar configuration key (parsed as Java properties, so list/object values like `proxy.auth` must be set in a config file).
* **Per-Agent Auth:** `proxy.auth` (per-agent identities with path authorization) is a list of objects, so it is config-file-only — see [Per-Agent Identities and Path Authorization](#per-agent-identities-and-path-authorization).
* **Keepalives:** See the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/) for tuning details.
* **Exposition formats:** Scrapes are carried as text, so the agent asks targets only for the text and OpenMetrics formats. Native histograms, which Prometheus receives only in the protobuf format, aren't available through the proxy.

## 🔐 Security & TLS

> 📖 **Docs site:** [Security overview](https://prometheus-proxy.com/security/) and the [TLS Setup guide](https://prometheus-proxy.com/security/tls/) cover agent authentication, mutual TLS, and certificate management in depth.

### TLS Configuration Details

Agents connect to a proxy using [gRPC](https://grpc.io). gRPC supports TLS with or without mutual authentication. The
necessary certificate and key file paths can be specified via CLI args, environment variables, and configuration file
settings.

The docs site's [TLS Setup](https://prometheus-proxy.com/security/tls/#creating-certificates) shows
how to create a CA and the proxy's and agents' certificates with OpenSSL.
The [repo](https://github.com/pambrose/prometheus-proxy/tree/master/testing/certs) includes the certificates and keys
necessary to test TLS support.

Running TLS without mutual authentication requires these settings:

* `certChainFilePath` and `privateKeyFilePath` on the proxy
* `trustCertCollectionFilePath` on the agent

Running TLS with mutual authentication requires these settings:

* `certChainFilePath`, `privateKeyFilePath` and `trustCertCollectionFilePath` on the proxy
* `certChainFilePath`, `privateKeyFilePath` and `trustCertCollectionFilePath` on the agent

Each file can be given as its PEM text, or the base64 of it, instead: `certChainPem`, `privateKeyPem`, and
`trustCertCollectionPem`, or the `CERT_CHAIN_PEM`, `PRIVATE_KEY_PEM`, and `TRUST_CERT_COLLECTION_PEM` environment
variables, for a platform that passes secrets as variables and for the Docker images, which have no shell to write
one to a file. See
[Certificates as Environment Variables](https://prometheus-proxy.com/security/tls/#certificates-as-environment-variables).

Run a proxy and an agent with TLS (with mutual authentication) with:

```bash
# Proxy with TLS
java -jar prometheus-proxy.jar \
  --cert /path/to/server.crt \
  --key /path/to/server.key \
  --trust /path/to/ca.crt

# Agent with TLS
java -jar prometheus-agent.jar \
  --config myconfig.conf \
  --trust /path/to/ca.crt \
  --cert /path/to/client.crt \
  --key /path/to/client.key
```

Run a proxy and an agent with TLS (no mutual authentication) using the included testing certs and keys with:

```bash
java -jar prometheus-proxy.jar --config examples/tls-no-mutual-auth.conf

java -jar prometheus-agent.jar --config examples/tls-no-mutual-auth.conf
```

Run a proxy and an agent docker container with TLS (no mutual authentication) using the included testing certs and keys
with:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50440:50440 -p 8080:8080 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env PROXY_CONFIG=tls-no-mutual-auth.conf \
    --env ADMIN_ENABLED=true \
    --env METRICS_ENABLED=true \
    pambrose/prometheus-proxy:4.6.1

docker run --rm \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.local:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:4.6.1
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

### Agent Token Authentication

The proxy accepts agent gRPC connections with no application-level authentication by default — any process that can reach
the agent port (default `50051`) can register as an agent. Set a shared **pre-shared token** so the proxy rejects agents
that do not present it:

* Proxy: `--agent_token`, `AGENT_TOKEN`, or `proxy.agentToken`
* Agent: `--agent_token`, `AGENT_TOKEN`, or `agent.agentToken`

Both sides must use the **same** value. When set, the agent attaches the token as a gRPC metadata header on every call and
the proxy rejects any call with a missing or mismatched token (`UNAUTHENTICATED`). When the token is empty (the default),
the open behavior is preserved and the proxy logs a startup warning — unless mutual TLS is configured, which already
authenticates agents.

```bash
# Proxy requiring a token
java -jar prometheus-proxy.jar --agent_token "$AGENT_TOKEN"

# Agent presenting the token
java -jar prometheus-agent.jar --config myconfig.conf --agent_token "$AGENT_TOKEN"
```

The token is a lightweight, app-level control. For production, prefer mutual TLS (and/or restrict the agent port to
trusted networks); the token complements, but does not replace, those controls. The value is never written to logs.

### Per-Agent Identities and Path Authorization

A single shared `agentToken` authenticates agents but does not distinguish between them: any agent holding the token can
register **any** path, including one already served by another agent. To scope agents, define named identities under
`proxy.auth`, each with its own token and a list of allowed path glob patterns:

```hocon
proxy {
  auth = [
    { name = team-a, token = "team-a-token", paths = ["team_a_*"] }
    { name = team-b, token = "team-b-token", paths = ["team_b_*"] }
    { name = infra,  token = "infra-token",  paths = [] }  # empty paths = may register any path
  ]
}
```

Each agent presents its identity's token the usual way (`--agent_token`, `AGENT_TOKEN`, or `agent.agentToken`); no
agent-side change is needed. The proxy resolves the token to an identity and enforces, on every `registerPath`, that the
requested path matches one of the identity's `paths` patterns. Patterns are single-segment globs where `*` matches any run
of characters and `?` matches one (e.g. `team_a_*`); an **empty** `paths` list authorizes every path. An unknown token is
rejected with `UNAUTHENTICATED`; a path outside the identity's patterns fails registration with a "not authorized" reason.
Because authorization is per-identity-per-path, consolidated mode (multiple agents serving one path) still works as long as
each agent's identity permits the shared path.

`proxy.auth` is a list of objects, so it is set in the config file only — there is no equivalent CLI flag or environment
variable. Identity names must be unique and tokens must be non-empty; the proxy fails fast at startup otherwise.

#### Migrating from a shared token

Setting `proxy.auth` does **not** disable a legacy `proxy.agentToken`. When both are present, the shared token is honored
as an additional **allow-all** identity (and the proxy logs a warning that it is active), so you can adopt per-agent
identities incrementally:

1. Add a `proxy.auth` entry per agent while leaving `proxy.agentToken` in place — existing agents keep connecting with the
   shared token.
2. Move each agent onto its own identity token one at a time.
3. Once every agent presents an identity token, remove `proxy.agentToken` to close the shared allow-all path.

### Auth Header Forwarding

When Prometheus scrape configurations include `basic_auth` or `bearer_token`, the proxy forwards the
`Authorization` header to the agent over the gRPC channel. If TLS is not configured, these credentials
are transmitted in plaintext and could be intercepted on the network between the proxy and agent.

**Enable TLS when forwarding auth headers:**

```bash
# Proxy with TLS to protect forwarded credentials
java -jar prometheus-proxy.jar \
  --cert /path/to/server.crt \
  --key /path/to/server.key

# Agent with TLS
java -jar prometheus-agent.jar \
  --config myconfig.conf \
  --trust /path/to/ca.crt
```

The proxy logs a warning on the first request that includes an `Authorization` header when TLS is not enabled.

### Scrape Port Access

The scrape port (default `8080`, listening on `0.0.0.0`) has no authentication. Anyone who can reach it can have the
agents scrape their targets, with the request's own query string and `Authorization` header passed through, and, with
service discovery on, list every path with its agents' names and hosts. With an exporter that takes its target as a
query parameter, such as blackbox_exporter's `/probe?target=…`, that reaches any host the exporter can. Bind
`proxy.http.host` to a private address and firewall the port so only Prometheus reaches it; see
[Scrape Port Access](https://prometheus-proxy.com/security/#scrape-port-access).

### Scraping HTTPS Endpoints

For HTTPS scrape targets signed by a custom or private CA (e.g. an internal corporate CA), point the agent at
a trust store that contains that CA so certificates are **still validated**:

```bash
java -jar prometheus-agent.jar \
  --https_truststore /etc/agent/truststore.jks \
  --https_truststore_password changeit \
  --config myconfig.conf
```

This is also configurable via the `HTTPS_TRUST_STORE_PATH` / `HTTPS_TRUST_STORE_PASSWORD` environment vars or
the `agent.http.trustStorePath` / `agent.http.trustStorePassword` properties. An empty path uses the JDK
default trust store. The trust store is process-wide — it applies to every HTTPS target the agent scrapes.

As a last resort you can disable certificate verification entirely with the `TRUST_ALL_X509_CERTIFICATES`
environment var, the `--trust_all_x509` CLI option, or the `agent.http.enableTrustAllX509Certificates`
property:

```bash
java -jar prometheus-agent.jar --trust_all_x509 --config myconfig.conf
```

**⚠️ Security Note:** Only use `--trust_all_x509` in development/testing environments. Its scope is
process-global and all-or-nothing: it disables certificate validation for **every** HTTPS target the agent
scrapes, and it takes precedence over `--https_truststore`. Prefer a custom trust store whenever possible.

### gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is **disabled by default** (see the security note below).
Enable it for debugging and tooling with `proxy.reflectionDisabled=false` in the config file,
`-Dproxy.reflectionDisabled=false` on the command line, or `REFLECTION_DISABLED=false`.

**Test with [grpcurl](https://github.com/fullstorydev/grpcurl):**

```bash
# List available services
grpcurl -plaintext localhost:50051 list

# Describe a service
grpcurl -plaintext localhost:50051 describe ProxyService

# When the proxy requires agent tokens, present one
grpcurl -plaintext -H 'agent-token: <token>' localhost:50051 list
```

If you use grpcurl `-plaintext` option, make sure that you run the proxy in plaintext
mode, i.e., do not define any TLS properties.

**⚠️ Security Note:** Reflection exposes the full gRPC API surface: every service, method, and message type. When agent
authentication is configured (the [agent token](#agent-token-authentication) or per-agent identities), reflection calls
need a valid token like every other call on the agent port, and fail with `UNAUTHENTICATED` without one. With no agent
authentication, anyone who can reach the agent port (default `50051`) can use it, so enable it only on a trusted network.
Hiding the API shape is not a substitute for authentication: still pair the agent port with agent tokens, mutual TLS,
and/or network segmentation.

### Reporting a Vulnerability

Report a security problem privately, not in a public issue, pull request, or discussion: use GitHub's
[private vulnerability reporting](https://github.com/pambrose/prometheus-proxy/security/advisories/new).
[SECURITY.md](SECURITY.md) lists what a useful report includes and which documented behavior is by design.

## 🔧 Troubleshooting

> 📖 **Docs site:** [Troubleshooting guide](https://prometheus-proxy.com/troubleshooting/) is a symptom-driven reference covering more failure modes and fixes.

### Common Issues

**Agent can't connect to proxy:**

- Verify proxy hostname and port
- Check firewall rules (agent needs outbound access to proxy port)
- Ensure proxy is running and listening

**TLS connection failures:**

- Verify certificate paths and file permissions
- Check certificate validity and chain
- Ensure clock synchronization between proxy and agent

**Metrics not appearing:**

- Verify agent path configuration matches Prometheus scrape paths
- Check agent logs for scraping errors
- Confirm target endpoints are accessible from agent

**Performance issues:**

- Increase `max_concurrent_clients` for high-throughput scenarios
- Tune the HTTP client cache (`--max_cache_size`, `--max_cache_age_mins`, `--max_cache_idle_mins`,
  `--cache_cleanup_interval_mins`)
- Consider splitting targets across multiple agents (a consolidated path is scraped through every agent that
  serves it, so extra agents on one path add no throughput, and ones that scrape the same endpoint send each series
  twice)

## 📖 Documentation

Full documentation is available at the [Prometheus Proxy Documentation](https://prometheus-proxy.com/) site.

KDoc API documentation is published at
[prometheus-proxy.com/kdocs](https://prometheus-proxy.com/kdocs/).

**Note:** Running on Kubernetes? Helm charts for the proxy and the agent are in [`charts/`](charts/), and the
[Kubernetes deployment guide](https://prometheus-proxy.com/kubernetes/) covers them along with ready-to-use proxy and
agent manifests, standalone and sidecar agent patterns, gRPC exposure for remote agents, and Prometheus Operator
(`ServiceMonitor`) integration. A config that holds secrets, such as `proxy.auth` tokens or credentials in an agent's
target URLs, can come from a Secret (`existingSecret`) instead of the chart's ConfigMap.

**Note:** Deploying to production? The
[Running in Production guide](https://prometheus-proxy.com/production/) pulls the security, reliability,
and tuning settings into a single operational checklist.

### Maven Central

The library is published to Maven Central under `com.pambrose:prometheus-proxy`:

```kotlin
// build.gradle.kts
repositories {
  mavenCentral()
}

dependencies {
  implementation("com.pambrose:prometheus-proxy:4.6.1")
}
```

## 📄 License

This project is licensed under the Apache License 2.0 - see [LICENSE.txt](LICENSE.txt) for details.

## Related Links

* [Prometheus.io](https://prometheus.io)
* [gRPC](https://grpc.io)
* [Typesafe Config](https://github.com/lightbend/config)
* [tscfg](https://github.com/carueda/tscfg), which generates the config classes from the reference config
