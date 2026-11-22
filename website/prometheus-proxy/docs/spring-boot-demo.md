---
icon: lucide/leaf
---

# Spring Boot Demo

The repository ships a demo of the agent [embedded](embedded-agent.md) in a Spring Boot app written in Java: a
roastery, which roasts the coffee shop's beans, runs the agent inside its own JVM, started by a Spring bean from
settings Spring Boot binds. The agent reads the metrics Spring Boot's Actuator serves behind Spring Security, and sends
them to a proxy, which Prometheus scrapes.

It shows what's particular to Spring Boot:

- the agent's lifecycle with the app's;
- the agent's connection in Actuator's health;
- Actuator's metrics behind a password;
- the versions of the agent's libraries that Spring Boot's dependency management picks;
- a graceful shutdown that takes the agent down first.

The agent's own [dashboard](agent-dashboard.md) is on too, turned on by the environment with no change to the
roastery's code, and shows the proxy stopping and starting again from the agent's side.

The [Embedded Demo](embedded-demo.md)'s coffee shop shows the rest with an app in Kotlin.

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph LR
  P[Prometheus :9090] -->|scrapes /roastery_metrics| Proxy[Proxy, dashboard :8094]
  %% The agent connects out to the proxy, so its arrow points back. The invisible link keeps the app to the right.
  Proxy ~~~ Agent
  Agent[embedded agent, a SmartLifecycle bean, dashboard :8495] -->|gRPC over TLS, with a token| Proxy
  Agent -->|reads /actuator/prometheus on 127.0.0.1| App[roastery: Spring Boot, Actuator behind Spring Security]

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
git clone -c advice.detachedHead=false --depth 1 --branch 4.6.1 https://github.com/pambrose/prometheus-proxy.git
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
| `roastery` | A Spring Boot app in Java that roasts a batch of beans every two seconds and ships some to the coffee shop every five, counting both in Micrometer meters. Actuator serves them, with the JVM's and Tomcat's, at `/actuator/prometheus`, behind Spring Security's Basic Auth. The agent runs inside it, and reads `/actuator/prometheus` on `127.0.0.1`. Its dashboard is on, at port 8495, from the environment. |
| `proxy` | The stock proxy image, which takes its TLS certificate and key, and the agent's token, from environment variables, with no file mounted. Its dashboard is on. |
| `prometheus` | Scrapes the roastery's path through the proxy every 5s, and the proxy's own metrics. |

## Where the agent is configured

The roastery starts its agent from a bean, `PrometheusAgentLifecycle`, in
[`PrometheusAgentLifecycle.java`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/spring-boot/app/src/main/java/roastery/PrometheusAgentLifecycle.java).
It builds the agent's settings with `EmbeddedAgentConfig.builder()`, the Java form of
[`embeddedAgent { }`](embedded-agent.md#configuring-in-code):

```java
--8<-- "demos/spring-boot/app/src/main/java/roastery/PrometheusAgentLifecycle.java:agent-lifecycle"
```

- **The settings:** `AgentSettings`, a `@ConfigurationProperties` record, binds them from `roastery.monitoring` in
  [`application.yml`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/spring-boot/app/src/main/resources/application.yml),
  whose values read the environment the coffee shop's agent reads: `PROMETHEUS_PROXY`, `PROMETHEUS_AGENT_TOKEN`,
  `PROMETHEUS_PROXY_CERT`, and the rest. Its `toString` leaves the secrets out, so the app can log it.
- **The password:** `SecurityConfig` lets only the agent's user read `/actuator/prometheus`; the status page and
  `/actuator/health` are open. `scrapeSelf` percent-encodes the credentials into the scrape URL.
- **Its health:** `PrometheusAgentHealthIndicator` puts the agent in `/actuator/health`, as `prometheusAgent`, from
  `EmbeddedAgentInfo.isConnected` and `proxyEndpoint`: UP while it's connected, with the proxy's address, and UNKNOWN,
  not DOWN, when it's off or reconnecting, so a lost proxy leaves the roastery's health UP. `./demo.sh health` shows
  it.

[In Spring Boot](embedded-agent.md#in-spring-boot) has the same points for an app of your own.

## Spring Boot's versions of the agent's libraries

Spring Boot's dependency management, which an app takes from `spring-boot-starter-parent`, sets the version of every
library it knows, whatever version a dependency asks for. Some of the agent's libraries are among them, and the
roastery runs kotlinx-serialization, Jackson 2, Logback, and gRPC older than the agent is built with.
`./demo.sh versions` prints both, from the jars the running roastery holds and the project's version catalog.

The agent runs with Spring Boot's versions, as the roastery shows. On every pull request, the project builds the
roastery and runs its
[`RoasteryAgentTest`](https://github.com/pambrose/prometheus-proxy/blob/master/demos/spring-boot/app/src/test/java/roastery/RoasteryAgentTest.java),
which starts the agent against a proxy and scrapes through it, so a Spring Boot release that breaks the agent fails
there. Were one to break it, a property in the app's pom sets that library back: `kotlin-serialization.version`,
`jackson-2-bom.version`, `logback.version`, or `grpc-java.version`.

## The agent's dashboard

The agent serves a [dashboard](agent-dashboard.md) of its own, from inside the roastery, at
<http://localhost:8495/dashboard>. The demo's compose file turns it on with two environment variables, which the agent
reads itself, over what the app's code sets, so the roastery's code didn't change:

```yaml
services:
  roastery:
    environment:
      DASHBOARD_ENABLED: "true"
      DASHBOARD_PORT: "8495"
```

In code, the builder's `.dashboard(8495)` turns it on the same way. It has no password here, so the agent warns at
startup, in the roastery's log, that it listens on all interfaces without a login; the demo publishes its port on
`127.0.0.1` only.

Its Paths view lists `/roastery_metrics`, registered, with the recent scrapes the proxy asked for. Select it, and
**Scrape now** has the agent read Actuator's metrics at once, from inside the roastery. The Activity view lists what has
happened to the agent, newest first, the Connection view its proxy endpoint, and the Settings view the settings it
resolved.

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
| `./demo.sh peek` | Tries `/actuator/prometheus` without the password, then reads it through the proxy |
| `./demo.sh health` | Shows the agent in Actuator's `/actuator/health`: connected, and to which proxy |
| `./demo.sh versions` | Compares the versions of the agent's libraries in the roastery with the agent's own |
| `./demo.sh proxy-stop` / `proxy-start` | Stops the proxy, or starts it again, which the agent's dashboard shows |
| `./demo.sh stop-app` / `start-app` | Stops the roastery with SIGTERM, or starts it again |
| `make status` | The containers, the roastery's state, and the URLs |
| `make logs SERVICE=roastery` | Follows one service's log |

## Going further

- The demo's [README](https://github.com/pambrose/prometheus-proxy/tree/master/demos/spring-boot) has its guided tour
  and lists the files that set it up. The roastery's code is in `demos/spring-boot/app`.
- [Embedded Agent](embedded-agent.md#in-spring-boot) covers the agent in a Spring Boot app, and the API the roastery
  uses.
- [Agent Dashboard](agent-dashboard.md) covers the agent's dashboard: its views, and the settings that turn it on.
- The [Embedded Demo](embedded-demo.md) runs a Kotlin app with the agent embedded under load, with Grafana, alerts, a
  deploy, the proxy going away, and secrets from the environment.
- [Cloud Platforms](cloud-platforms.md) sets an app with the agent embedded up for real, and
  [Packaging the App](cloud-platforms.md#packaging-the-app) covers its image.
