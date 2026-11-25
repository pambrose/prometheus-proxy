---
icon: lucide/sprout
---

# Spring Boot Starter

A Spring Boot app can run the [embedded agent](embedded-agent.md) with a dependency and a few properties:
`com.pambrose:prometheus-proxy-spring-boot-starter` starts the agent with the app, serves the app's metrics to
Prometheus through the proxy, and reports the agent's connection in Actuator's health. The agent reads the app's
metrics registry in-process, so the app needs no `/actuator/prometheus` endpoint, no credentials for one, and no web
server at all: a worker that consumes a queue is scraped as a web app is. The [Spring Boot Demo](spring-boot-demo.md)
runs it in a roastery app.

## Adding the Dependency

=== "Maven"

    ```xml
    --8<-- "EmbeddedAgentExamples.txt:starter-maven-dependency"
    ```

=== "Gradle (Kotlin DSL)"

    ```kotlin
    --8<-- "EmbeddedAgentExamples.txt:starter-gradle-dependency"
    ```

The starter needs Spring Boot 4.2 or later and Java 17 or later, and is released with the agent, at the agent's version.
It brings Micrometer's Prometheus registry, so with Actuator, or `spring-boot-starter-micrometer-metrics` in an app
without it, Spring Boot puts the app's meters in a `PrometheusRegistry` bean, which the agent serves. Spring Boot's
dependency management sets that registry's version, as it does the app's own dependencies'.

On Spring Boot 4.0 or 4.1, whose dependency management sets kotlinx-coroutines and the Prometheus Java client below the
versions the agent runs on, set those two to the agent's: `kotlin-coroutines.version` to `1.11.0`, and
`prometheus-client.version` to `1.10.0`, in the pom's `<properties>`, or with Spring's dependency-management plugin in
Gradle, as `extra["kotlin-coroutines.version"]` and `extra["prometheus-client.version"]`. Without them the agent
doesn't start, and the reason it gives names each property to set.

## Configuring

Only the proxy is needed. Read it, and the rest, from the environment, so the agent stays off where it isn't set, as
on a developer's machine:

```yaml
--8<-- "spring-boot-starter/src/test/resources/website/application.yml:starter-yaml"
```

With that, the app gets:

- **An agent that starts and stops with the app.** It starts once the app is up, in the last lifecycle phase, and stops
  first on shutdown, before a graceful shutdown drains the web server: the agent disconnects, the proxy drops its
  paths, and no scrape reaches an app that's going away. The shutdown waits for that up to `stop-timeout`, 30 seconds
  by default: Spring's own phase timeout doesn't cut it short, since the stop runs in the agent bean's `stop()`. Its
  thread doesn't keep the JVM running, so an app with no
  web server or other thread to hold it, such as a batch job, exits once `main` returns, and the context's shutdown
  stops the agent; one that should keep running sets `spring.main.keep-alive: true`.
- **Its metrics at the proxy's `/roastery_metrics`,** the app's name followed by `_metrics`, read from the app's
  registry for each scrape, in the format Prometheus asks for, and with the agent's limits and the path's filter, as
  [Metrics Served In-Process](embedded-agent.md#metrics-served-in-process) describes.
- **An agent named for the app,** `roastery`, followed by the build's version when the app has build info
  (`spring-boot-maven-plugin`'s `build-info` goal, or `springBoot { buildInfo() }` in Gradle), such as
  `roastery-1.4.2`, so the proxy's dashboard tells an old deployment from a new one.
- **A `prometheusAgent` health component,** with Actuator: UP, with the agent's name and the proxy it reached, while
  it's connected, and UNKNOWN while it's off or reconnecting. Never DOWN, so a proxy that's away doesn't take the app
  out of a load balancer or have it restarted. `management.health.prometheusagent.enabled: false` leaves it out.
- **An app that runs without an agent that can't start.** The agent logs why and stays off: a setting it rejects,
  such as an unreadable certificate, doesn't stop the app. Set `required: true` to have the app fail to start instead,
  then without a proxy too, or have a customizer add an `onFailure` handler, which is told why
  ([below](#customizing-in-code)).

Each property can also come straight from the environment, by Spring Boot's rules: `PROMETHEUSPROXY_AGENT_PROXY` sets
`prometheus-proxy.agent.proxy`. As with any embedded agent, the agent's own environment variables, such as
`AGENT_TOKEN` or `PROXY_HOSTNAME`, take precedence over its settings, and `PROXY_HOSTNAME` alone starts it, so give
the app's other names, as above.

## Properties

Each is under `prometheus-proxy.agent`, and an IDE completes and describes them from the starter's metadata.

| Property                   | Default                          | What it sets                                                                                                                                                                  |
|----------------------------|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `proxy`                    |                                  | The proxy, as `host` or `host:port` (the port defaults to 50051), or several, separated by commas, in the order the agent fails over through them. Unset, the agent stays off, unless a customizer, a `settings` key, or `PROXY_HOSTNAME` names one |
| `token`                    |                                  | The token the agent presents to the proxy, which has to match the proxy's                                                                                                     |
| `trust-certificate`        |                                  | The certificate the agent trusts the proxy's by, as PEM text or its base64, which turns [TLS](security/tls.md) on                                                             |
| `authority`                |                                  | The name the proxy's certificate was issued to, when it isn't the host the agent connects to; used with `trust-certificate`                                                   |
| `client-certificate`       |                                  | The agent's certificate chain, as PEM text or its base64, for a proxy that requires mutual TLS                                                                                |
| `client-key`               |                                  | The private key of `client-certificate`, as PEM text or its base64                                                                                                            |
| `name`                     | the app's name and build version | The agent's name, as the proxy's dashboard and logs show it; `Unnamed-` and the host without `spring.application.name`                                                        |
| `path`                     | the app's name, then `_metrics`  | The proxy's path the app's metrics are served at; `application_metrics` without `spring.application.name`                                                                     |
| `labels.<name>`            |                                  | The labels of the app's path, in the proxy's [service discovery](service-discovery.md)                                                                                        |
| `source`                   | `registry`                       | `registry` serves the app's `PrometheusRegistry` bean; `none` serves only `paths` and what a customizer adds                                                                  |
| `paths[n].path`, `.url`    |                                  | Another target the agent scrapes over HTTP, served at a path of its own, with `.username`, `.password`, and `.labels.<name>`                                                  |
| `consolidated`             | `false`                          | Whether the agent registers its paths in [consolidated mode](advanced.md#consolidated-mode), alongside other agents serving them                                               |
| `metrics-port`             |                                  | The port of the agent's own metrics, which are off without one, for a scraper on the app's own network ([Port or Proxy Path](monitoring.md#port-or-proxy-path)) |
| `metrics-proxy-path`       |                                  | The proxy's path the [agent's own metrics](embedded-agent.md#the-agents-own-metrics) are served at, read in-process, with no port; off without one. They stay apart from the app's path, whose JVM metrics they'd repeat |
| `metrics-proxy-path-labels.<name>` |                          | The labels of `metrics-proxy-path`, in the proxy's [service discovery](service-discovery.md)                                                                                   |
| `dashboard.port`, `.host`  |                                  | The [agent's dashboard](agent-dashboard.md), off without a port, listening on every interface unless `host` names one, such as `127.0.0.1`                                    |
| `dashboard.password`       |                                  | Requires a login to the dashboard, as `dashboard.username` (`admin` by default)                                                                                               |
| `required`                 | `false`                          | Whether the app fails to start when the agent can't, or has no proxy                                                                                                          |
| `stop-timeout`             | `30s`                            | Since 4.7.1, how long the app's shutdown waits for the agent's stop, more than zero, as a number of seconds or a duration such as `10s` or `PT10S`; once it's up, the app stops anyway, and the agent goes on stopping |
| `settings.<key>`           |                                  | Any other [agent setting](configuration/agent.md), by its key under `agent`, such as `settings.scrapeTimeoutSecs: 20`. It takes precedence over the properties above        |
| `enabled`                  | `true`                           | Whether to run the agent at all                                                                                                                                               |

A `settings` key keeps its case only in a properties or YAML file: Spring Boot lowercases one read from an environment
variable, which then names no setting.

## Customizing in Code

For what properties can't hold, such as a certificate the app reads from a secret store, or a registry that isn't a
bean, a `PrometheusProxyAgentCustomizer` bean is given the agent's
[`EmbeddedAgentConfig.Builder`](embedded-agent.md#embeddedagentconfig) once the properties are applied, just before
the agent starts. Several run in their `@Order`, each after the one before. A builder method a customizer calls
replaces what a property set, but not a `settings.<key>` property, which takes precedence over the builder's methods,
as anything `setting()` sets does: change one of those with `setting()` and its key. The starter decides whether to
start the agent once they've run, so a customizer can give it its proxy, as `fromEnvironment()` does. Since 4.7.1, a
customizer can also add an `onFailure` handler, such as `builder.onFailure((reason, e) -> alerts.send(reason))`, which
is told why an agent that isn't `required` couldn't start, as the log gives it, to report it somewhere else too. That
includes a property the builder can't take, such as a `stop-timeout` of zero, and a customizer that throws: the starter
runs every customizer whatever failed, so each one's handler is added, then tells them.

```java
--8<-- "spring-boot-starter/src/test/java/website/AgentCustomizations.java:starter-customizer"
```

The agent is the `PrometheusProxyAgent` bean, whose `agent()` gives the running agent's
[`EmbeddedAgentInfo`](embedded-agent.md#embeddedagentinfo), or nothing while it's off. An app's own
`PrometheusProxyAgent` bean takes the place of the starter's, and has the health component; an app with more than one
has none, since it couldn't choose between them.

## Spring Boot's Versions of the Agent's Libraries

Spring Boot's dependency management sets the versions of the libraries it knows, some of the agent's among them, and
some of those run older than the agent's own. The agent runs with Spring Boot 4.2's, and with 4.0's and 4.1's once the
two [above](#adding-the-dependency) are set. The starter's tests run it at 4.2's, and at 4.0's last release's with those
two set, and the [Spring Boot Demo](spring-boot-demo.md#spring-boots-versions-of-the-agents-libraries) runs it at
4.2's, whose `./demo.sh versions` shows which are older. Were a Spring Boot release to break it, the app's pom sets that
library to the agent's version with the property Spring Boot names it by, such as `kotlin-coroutines.version`,
`prometheus-client.version`, `grpc-java.version`, or `netty.version`, which Spring Boot's
[Version Properties](https://docs.spring.io/spring-boot/appendix/dependency-versions/properties.html) lists for every
library it manages. netty-tcnative has none, since Netty's BOM sets it: pin `netty-tcnative-classes` and
`netty-tcnative-boringssl-static`, with each native's classifier, in the pom's own `dependencyManagement`.

## Without the Starter

[In Spring Boot](embedded-agent.md#in-spring-boot) on the Embedded Agent page shows what the starter does in a few beans
of the app's own, for an app that wants to wire the agent itself.
