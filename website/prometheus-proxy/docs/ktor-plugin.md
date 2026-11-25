---
icon: lucide/plug
---

# Ktor Plugin

A Ktor app can run the [embedded agent](embedded-agent.md) by installing a plugin: `com.pambrose:prometheus-proxy-ktor`
starts the agent with the app, stops it with the app, and serves the app's metrics registry to Prometheus through the
proxy. The agent reads the registry in-process, so the app needs no `/metrics` route, no credentials for one, and no
port for the agent to scrape. The [Ktor Demo](ktor-demo.md)'s coffee shop runs it, under load, with Grafana.

## Adding the Dependency

=== "Gradle (Kotlin DSL)"

    ```kotlin
    --8<-- "EmbeddedAgentExamples.txt:ktor-gradle-dependency"
    ```

=== "Maven"

    ```xml
    --8<-- "EmbeddedAgentExamples.txt:ktor-maven-dependency"
    ```

The plugin needs Ktor 3 and Java 17 or later, and is released with the agent, at the agent's version. The agent runs
Ktor itself, for its HTTP client and its dashboard, so the app's Ktor has to be the agent's version or later. Gradle
picks the later of the two on its own; Maven picks the version nearest the app, so a Maven app declares its Ktor
modules, or imports `io.ktor:ktor-bom`, at the agent's version or later.

## Installing

Install it with the app's registry and the path Prometheus scrapes it at, and give the agent its proxy, here from the
app's environment, with [`fromEnvironment()`](embedded-agent.md#settings-from-the-environment):

```kotlin
--8<-- "ktor-plugin/src/test/kotlin/website/KtorPluginExample.kt:install"
```

With that, the app gets:

- **An agent that starts and stops with the app.** It starts once every module of the app has loaded, and begins
  stopping as soon as the server does, before the server drains the requests in flight: the agent disconnects, the proxy
  drops its paths, and no scrape reaches an app that's going away. The app's shutdown waits for it, up to the agent's
  `stopTimeout`, 30 seconds by default, without taking that time from the server's grace period. In development mode, a
  reload stops the old app's agent and starts the new one's.
- **Its metrics at the proxy's `/my_app_metrics`,** read from the registry for each scrape, in the format Prometheus
  asks for, and with the agent's limits and the path's filter, as
  [Metrics Served In-Process](embedded-agent.md#metrics-served-in-process) describes. Micrometer's
  `PrometheusMeterRegistry` gives its registry as `prometheusRegistry`; an app on the Prometheus Java client gives its
  own, such as `PrometheusRegistry.defaultRegistry`. A path is required with a registry: there's no default, since two
  apps given the same one would take the path from each other.
- **An app that runs without an agent that can't start.** Without a proxy, from the file, the install block, or the
  agent's own `PROXY_HOSTNAME`, as on a developer's machine or in `testApplication`, the agent logs a line and stays
  off. A setting it rejects, such as an unreadable certificate, is
  logged, and the agent stays off, without stopping the app. Set `required = true` to have the app fail to start
  instead, then without a proxy too, or add an `onFailure` handler in the `agent { }` block to have the app told why
  ([below](#a-failed-start-and-the-stop)).

The `agent { }` block takes the agent's [`EmbeddedAgentConfig.Builder`](embedded-agent.md#embeddedagentconfig), with a
typed method for each setting, such as a certificate the app reads from a secret store, and `agentMetricsAt(path)`,
which serves the [agent's own metrics](embedded-agent.md#the-agents-own-metrics) through the proxy too, with no port,
as the file's `metrics-proxy-path` does.
As with any embedded agent, the agent's own environment variables, such as `AGENT_TOKEN` or `PROXY_HOSTNAME`, take
precedence over its settings, and `PROXY_HOSTNAME` alone starts it, so give the app's other names, as
`fromEnvironment()`'s `PROMETHEUS_` ones are.

## A Failed Start, and the Stop

Since 4.7.1, the `agent { }` block can add a failure handler and set a stop timeout. Both are the agent's own
settings, the builder's, so they go there, as for any [embedded agent](embedded-agent.md#embeddedagentconfig).

An agent that can't start stays off while the app runs on, and the plugin logs why. A handler the block adds with
`onFailure { reason, cause -> }` is told too, with the reason the log gives, which holds no token, password, or PEM,
and what was thrown, so the app can send it wherever someone will see it, such as a chat channel. That includes a
failure before the agent's start, such as a value in the file the plugin can't read, or an `agent { }` block that
throws: the plugin runs every block whatever failed, so each one's handler is added, then tells them. It isn't told of
a `required` agent's failure, which fails the app's start, nor of an agent left off on purpose, not `enabled` or without
a proxy. A handler that throws is logged, without failing the app's start.

`stopTimeout` is how long the app's shutdown waits for the agent's stop, 30 seconds unless the file's `stop-timeout` or
the block sets another: set it inside the shutdown window the app's platform gives, such as Cloud Run's 10 seconds
after `SIGTERM`. Once it's up, the app stops anyway, with a warning, and the agent goes on stopping on its own.

```kotlin
--8<-- "ktor-plugin/src/test/kotlin/website/KtorPluginExample.kt:on-failure"
```

## Settings in application.conf

An app that reads its settings from a file, through `EngineMain` or `configure()`, can keep the agent's there too,
under `prometheus-proxy.agent`, in HOCON or the same keys in YAML:

```hocon
--8<-- "ktor-plugin/src/test/resources/website/application.conf:conf"
```

The install block is then left with what a file can't hold, the registry:

```kotlin
--8<-- "ktor-plugin/src/test/kotlin/website/KtorPluginExample.kt:install-file"
```

The install block runs after the file is read, so a setting it makes takes precedence, but not over a `settings.<key>`
key, which takes precedence over the builder's methods, as anything `setting()` sets does: change one of those with
`setting()` and its key. An app started with `embeddedServer` reads no file, so it has only the block's settings. The
keys are the [Spring Boot Starter](spring-boot-starter.md)'s properties, and a blank value counts as unset, as an unset
environment variable gives:

| Key                        | Default                    | What it sets                                                                                                                                                                  |
|----------------------------|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `proxy`                    |                            | The proxy, as `host` or `host:port` (the port defaults to 50051), or several, separated by commas, in the order the agent fails over through them. Unset, the agent stays off, unless the install block, a `settings` key, or `PROXY_HOSTNAME` names one |
| `token`                    |                            | The token the agent presents to the proxy, which has to match the proxy's                                                                                                     |
| `trust-certificate`        |                            | The certificate the agent trusts the proxy's by, as PEM text or its base64, which turns [TLS](security/tls.md) on                                                             |
| `authority`                |                            | The name the proxy's certificate was issued to, when it isn't the host the agent connects to; used with `trust-certificate`                                                   |
| `client-certificate`       |                            | The agent's certificate chain, as PEM text or its base64, for a proxy that requires mutual TLS                                                                                |
| `client-key`               |                            | The private key of `client-certificate`, as PEM text or its base64                                                                                                            |
| `name`                     | `Unnamed-` and the host    | The agent's name, as the proxy's dashboard and logs show it                                                                                                                   |
| `path`                     |                            | The proxy's path the registry is served at; required with a registry                                                                                                          |
| `labels.<name>`            |                            | The labels of the registry's path, in the proxy's [service discovery](service-discovery.md)                                                                                   |
| `paths`                    |                            | Other targets the agent scrapes over HTTP, a list, each served at a `path` of its own from its `url`, with `username`, `password`, and `labels`                                |
| `consolidated`             | `false`                    | Whether the agent registers its paths in [consolidated mode](advanced.md#consolidated-mode), alongside other agents serving them                                               |
| `metrics-port`             |                            | The port of the agent's own metrics, which are off without one, for a scraper on the app's own network ([Port or Proxy Path](monitoring.md#port-or-proxy-path)) |
| `metrics-proxy-path`       |                            | The proxy's path the [agent's own metrics](embedded-agent.md#the-agents-own-metrics) are served at, read in-process, with no port; off without one. They stay apart from the app's path, whose JVM metrics they'd repeat |
| `metrics-proxy-path-labels.<name>` |                   | The labels of `metrics-proxy-path`, in the proxy's [service discovery](service-discovery.md)                                                                                   |
| `dashboard.port`, `.host`  |                            | The [agent's dashboard](agent-dashboard.md), off without a port, listening on every interface unless `host` names one, such as `127.0.0.1`                                    |
| `dashboard.password`       |                            | Requires a login to the dashboard, as `dashboard.username` (`admin` by default)                                                                                               |
| `required`                 | `false`                    | Whether the app fails to start when the agent can't, or has no proxy                                                                                                          |
| `stop-timeout`             | `30s`                      | Since 4.7.1, the agent's `stopTimeout`: how long the app's shutdown waits for its stop, more than zero, as a number of seconds or a duration such as `10s` or `PT10S`                      |
| `settings.<key>`           |                            | Any other [agent setting](configuration/agent.md), by its key under `agent`, such as `settings.scrapeTimeoutSecs = 20`. It takes precedence over the keys above              |
| `enabled`                  | `true`                     | Whether to run the agent at all                                                                                                                                               |

`enabled`, `required`, `path`, and `labels` are the install block's properties too; the rest are the builder's.

## The Agent's Connection

While the agent runs, `application.prometheusProxyAgent` is its
[`EmbeddedAgentInfo`](embedded-agent.md#embeddedagentinfo), with its connection to the proxy, read live; it's null
while the agent is off. A route can show it:

```kotlin
--8<-- "ktor-plugin/src/test/kotlin/website/KtorPluginExample.kt:health"
```

The agent starts before the server listens on its port. The registry doesn't mind, but a path the agent scrapes at the
app's own port, from `paths` or the builder's `scrapeSelf`, can fail a scrape that comes in that moment. A server that
can't listen, on a port another process holds, fails the app's start, and the app exits all the same: the agent's
thread doesn't keep the JVM running, and Ktor's shutdown hook stops the agent as the JVM exits. With `metrics-port`,
or the agent's admin port, on, that port's server does keep the JVM running; `metrics-proxy-path` opens no port.

## Without the Plugin

[Configuring in Code](embedded-agent.md#configuring-in-code) on the Embedded Agent page starts the agent from any app,
in Ktor or not, with its settings built in code, and stops it when the app calls `shutdown()`.
