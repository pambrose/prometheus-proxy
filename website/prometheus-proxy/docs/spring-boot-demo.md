---
icon: lucide/leaf
---

# Spring Boot Demo

The repository ships a demo of the agent [embedded](embedded-agent.md) in a Spring Boot app written in Java: a
roastery, which roasts the coffee shop's beans, runs the agent inside its own JVM, started by the
[Spring Boot starter](spring-boot-starter.md) from a few properties. The agent reads the app's metrics from its
Micrometer registry, in-process, and sends them to a proxy, which Prometheus scrapes.

It shows what's particular to Spring Boot:

- the agent's lifecycle with the app's;
- the agent's connection in Actuator's health;
- the app's metrics served with no `/actuator/prometheus` endpoint;
- the versions of the agent's libraries that Spring Boot's dependency management picks;
- a graceful shutdown that takes the agent down first.

The agent's own [dashboard](agent-dashboard.md) is on too, turned on by the environment with no change to the
roastery, and shows the proxy stopping and starting again from the agent's side.

The [Ktor Demo](ktor-demo.md)'s coffee shop shows the rest with an app in Kotlin.

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph TB
  P[Prometheus :9490] -->|scrapes /roastery_metrics| Proxy[Proxy, dashboard :8494]
  %% The agent connects out to the proxy, so its arrow points back. The invisible link keeps the app below.
  Proxy ~~~ Agent
  Agent[embedded agent, run by the Spring Boot starter, dashboard :8495] -->|gRPC over TLS, with a token| Proxy
  Agent -->|reads its metrics registry in-process| App[roastery: Spring Boot, Micrometer]

  subgraph host[Proxy host]
    P
    Proxy
  end

  subgraph roastery[roastery: one container, one JVM]
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
- `curl`, which the demo uses to wait for each service to answer.
- A web browser.

`make up` runs the latest release's proxy image, from Docker Hub, and builds the roastery inside Docker with Maven,
against the latest release's library, from Maven Central, so it needs no JDK or Maven. `make up-local` builds the
library, the proxy, and the roastery from the checkout, which takes a JDK 17 or later and Maven 3.9 or later.

## Running it

Clone the repository at the latest release, then run the demo's commands from its own directory, where `make help` lists
its targets:

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.0 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/spring-boot
make up      # builds the roastery, starts everything, prints the URLs
make tour    # walks through the parts below, pausing before each step
make down    # stops it and removes its containers and network
```

The first `make up` pulls the images and builds the roastery, which takes a few minutes. After that it starts in under
a minute.

| What | URL |
|---|---|
| The roastery's status page | <http://localhost:8490> |
| Proxy dashboard | <http://localhost:8494/dashboard> |
| The roastery agent's dashboard | <http://localhost:8495/dashboard> |
| Prometheus | <http://localhost:9490> |
| The roastery's metrics, through the proxy | <http://localhost:8480/roastery_metrics> |

The ports are the [Basics Demo](basics-demo.md)'s plus 400, so the demos can run side by side. Every port binds to
`127.0.0.1` only. To move one that is already taken, set `SPRING_BOOT_DEMO_APP_PORT`, `SPRING_BOOT_DEMO_DASHBOARD_PORT`,
`SPRING_BOOT_DEMO_AGENT_DASHBOARD_PORT`, `SPRING_BOOT_DEMO_PROMETHEUS_PORT`, or `SPRING_BOOT_DEMO_PROXY_PORT`, for
example `SPRING_BOOT_DEMO_APP_PORT=8491 make up`.

## What's running

| Service | Role |
|---|---|
| `roastery` | A Spring Boot app in Java that roasts a batch of beans every two seconds and ships some to the coffee shop every five, counting both in Micrometer meters. The agent runs inside it, started by the Spring Boot starter, and reads them, with the JVM's and Tomcat's, from the app's metrics registry, in-process; Actuator exposes only its health. Its dashboard is on, at port 8495, from the environment. |
| `proxy` | The stock proxy image, which takes its TLS certificate and key, and the agent's token, from environment variables, with none of them in a file. Its dashboard is on. |
| `prometheus` | Scrapes the roastery's path through the proxy every 5s, and the proxy's own metrics. |

## Where the agent is configured

The roastery has no code for its agent. Its pom depends on the [Spring Boot starter](spring-boot-starter.md),
`com.pambrose:prometheus-proxy-spring-boot-starter`, which runs the agent with the app, and its
[`application.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/spring-boot/app/src/main/resources/application.yml)
sets the agent's properties from the environment the coffee shop's agent reads:

```yaml
--8<-- "demos/spring-boot/app/src/main/resources/application.yml:agent-settings"
```

The starter does the rest, as [Configuring](spring-boot-starter.md#configuring) describes, and the demo's scenarios
show each part:

- The agent is named `roastery-1.0`, after `spring.application.name` and the version in the app's build info, and
  serves the app's metrics at the proxy's `/roastery_metrics`. `./demo.sh settings` shows the settings it started
  with, the token and the certificate only as set.
- It reads the roastery's meters from the app's `PrometheusRegistry` bean, so Actuator exposes only health, its
  default, and there's no `/actuator/prometheus`, password, or Spring Security to set up. `./demo.sh peek` shows it.
- It's the `prometheusAgent` component of `/actuator/health`, UP while it's connected and UNKNOWN, not DOWN, while
  it isn't, so a lost proxy leaves the roastery's health UP. `./demo.sh health` shows it.

[In Spring Boot](embedded-agent.md#in-spring-boot) shows how to wire the agent into an app yourself instead.

## Spring Boot's versions of the agent's libraries

Spring Boot's dependency management, which an app takes from `spring-boot-starter-parent`, sets the version of every
library it knows, whatever version a dependency asks for. Some of the agent's libraries are among them, and the
roastery runs some of those older than the agent is built with. `./demo.sh versions` prints both, from the jars the
running roastery holds and the project's version catalog, and marks each of the roastery's older, the same, or newer.

The agent runs with Spring Boot's versions, as the roastery shows. On every pull request, the project builds the
roastery and runs its
[`RoasteryAgentTest`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/spring-boot/app/src/test/java/roastery/RoasteryAgentTest.java),
which starts the agent against a proxy and scrapes through it, so a Spring Boot release that breaks the agent fails
there. Were one to break it, the app's pom sets that library to the agent's version with the property Spring Boot names
it by, such as `grpc-java.version` or `netty.version`, which Spring Boot's
[Version Properties](https://docs.spring.io/spring-boot/appendix/dependency-versions/properties.html) lists for every
library it manages. netty-tcnative has none, since Netty's BOM sets it: the pom pins `netty-tcnative-classes` and
`netty-tcnative-boringssl-static`, with each native's classifier, in its own `dependencyManagement`.

## The agent's dashboard

The agent serves a [dashboard](agent-dashboard.md) of its own, from inside the roastery, at
<http://localhost:8495/dashboard>. The demo's compose file turns it on with an environment variable, which Spring Boot
binds to the starter's `prometheus-proxy.agent.dashboard.port`, so `application.yml` needn't change:

```yaml
services:
  roastery:
    environment:
      PROMETHEUSPROXY_AGENT_DASHBOARD_PORT: "8495"
```

It has no password here, so the agent warns at startup, in the roastery's log, that it listens on all interfaces
without a login; the demo publishes its port on `127.0.0.1` only.

Its Paths view lists `/roastery_metrics`, registered, with the recent scrapes the proxy asked for. Select it, and
**Scrape now**, the circular arrow after the path's name, has the agent read the roastery's registry at once. The
Activity view lists what has happened to the agent, newest first, the Connection view its proxy endpoint, and the
Settings view the settings it resolved.

`./demo.sh proxy-stop` stops the proxy, and the proxy's dashboard with it, but the agent's still answers. Its
Connection view turns failing, with the error the agent got connecting to the proxy, and its Activity view adds
disconnected and connect failed. The roastery keeps serving, and its health stays UP while the agent's component turns
UNKNOWN. Scrape now still reads the roastery's metrics, since it doesn't need the proxy. `./demo.sh proxy-start`
brings the proxy back: the agent connects within a few seconds and registers its path again, and the Activity view
adds connected and registered.

## A graceful shutdown

`./demo.sh stop-app` stops the roastery with SIGTERM, as a platform does on a deploy or a scale-down. The agent, in the
last lifecycle phase, stops first, and the roastery's log shows the order:

```text
Stopping the Prometheus agent roastery-1.0
Commencing graceful shutdown. Waiting for active requests to complete
Graceful shutdown complete
```

`./demo.sh start-app` starts it again, and the agent registers its path once more.

## Scenario reference

| Command | Effect |
|---|---|
| `./demo.sh settings` | Shows the agent's settings as Spring Boot bound them, and the agent starting |
| `./demo.sh peek` | Tries `/actuator/prometheus`, which the roastery doesn't expose, then reads its metrics through the proxy |
| `./demo.sh health` | Shows the agent in Actuator's `/actuator/health`: connected, and to which proxy |
| `./demo.sh versions` | Compares the versions of the agent's libraries in the roastery with the agent's own |
| `./demo.sh proxy-stop` / `proxy-start` | Stops the proxy, or starts it again, which the agent's dashboard shows |
| `./demo.sh stop-app` / `start-app` | Stops the roastery with SIGTERM, or starts it again |
| `make status` | The containers, the roastery's state, and the URLs |
| `make logs SERVICE=roastery` | Follows one service's log |

## Going further

- The demo's [README](https://github.com/pambrose/prometheus-proxy/tree/master/demos/spring-boot) has its guided tour
  and lists the files that set it up. The roastery's code is in `demos/spring-boot/app`.
- [Spring Boot Starter](spring-boot-starter.md) covers the starter the roastery runs its agent with: its properties,
  and changing the agent's settings in code.
- [Embedded Agent](embedded-agent.md) covers the API the starter builds on.
- [Agent Dashboard](agent-dashboard.md) covers the agent's dashboard: its views, and the settings that turn it on.
- The [Ktor Demo](ktor-demo.md) runs a Kotlin app with the agent embedded under load, with Grafana, alerts, a
  deploy, the proxy going away, and secrets from the environment.
- [Cloud Platforms](cloud-platforms.md) sets an app with the agent embedded up for real, and
  [Packaging the App](cloud-platforms.md#packaging-the-app) covers its image.
