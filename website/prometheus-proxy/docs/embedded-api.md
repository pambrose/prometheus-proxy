---
icon: lucide/braces
---

# Embedded Agent API

The methods and types an application that [embeds the agent](embedded-agent.md) calls, with what each takes,
returns, and throws.

## `Agent.startAsyncAgent()`

Starts the agent in the background, on a daemon thread, and returns once it has started (see
[`EmbeddedAgentInfo`](#embeddedagentinfo) for how long it runs).

| Parameter             | Type      | Description                                                                                                                              |
|:----------------------|:----------|:-----------------------------------------------------------------------------------------------------------------------------------------|
| `configFilename`      | `String`  | Path to the HOCON config file                                                                                                            |
| `exitOnMissingConfig` | `Boolean` | Pass `false` to embed: a config error or invalid option throws `ConfigLoadException`. `true` exits the JVM, as the standalone agent does |
| `logBanner`           | `Boolean` | Log the startup banner (default: `true`, which Java callers get by leaving it out too)                                                   |

Or, to start the agent from a config built in memory (see [A `Config` of Your Own](embedded-agent.md#a-config-of-your-own)):

| Parameter   | Type      | Description                                                                                         |
|:------------|:----------|:----------------------------------------------------------------------------------------------------|
| `config`    | `Config`  | The agent's configuration, rooted at `agent`, as a Typesafe `Config`                                |
| `logBanner` | `Boolean` | Log the startup banner (default: `true`, which Java callers get by leaving it out too)              |

This form is always embedded: a config error throws `ConfigLoadException`, and never exits the JVM.

**Returns:** `EmbeddedAgentInfo`

**Throws:** `ConfigLoadException`, when `exitOnMissingConfig` is `false` or the config is in memory, for a config that
can't be loaded or parsed, or for a setting the agent can't use, from the config or an environment variable:

- a value of the wrong type or out of range, such as `scrapeTimeoutSecs = "abc"` or `0`;
- an unresolved `${…}` substitution;
- a malformed proxy endpoint;
- a duplicate `pathConfigs` or `filters` entry, or a filter regex that doesn't compile;
- an HTTPS trust store (`agent.http.trustStorePath`) or TLS certificate file that is missing, unreadable, or
  malformed, or a TLS certificate or key given as PEM that can't be read, or given as both a file and PEM.

Credentials in a config URL are redacted from its message and from every message in its cause chain. The cause is the
exception that rejected the setting, usually an `IllegalArgumentException`. When that exception's message held a URL
with credentials, as a Typesafe `ConfigException`'s does for a value read from a config URL, the cause is a stand-in
`ConfigLoadException` with the redacted message and the original stack trace.

`IllegalStateException` when the agent fails to start (for example, its admin, metrics, or dashboard port is already
in use), with the startup failure as its cause, or doesn't start within 30 seconds, with a `TimeoutException` as its
cause. A failed start releases everything the agent had opened, and an agent still starting when the wait ends stops
as soon as its startup does, without connecting. Also, before the agent is built, when the app's dependency
management sets kotlinx-coroutines below 1.11.0 or the Prometheus Java client below 1.7.0, as Spring Boot 4.0 and 4.1
do ([In Spring Boot](embedded-agent.md#in-spring-boot)): its message names the Spring Boot property that sets each.

## `EmbeddedAgentConfig`

An embedded agent's settings, built in code (see [Configuring in Code](embedded-agent.md#configuring-in-code)). From Kotlin,
`embeddedAgent { … }` builds one, its block calling the builder's methods; from Java, `EmbeddedAgentConfig.builder()`
returns the builder, whose methods chain and end with `build()`. A method called twice keeps its last value, and
`scrapeSelf`, `path`, and `source` add a path each time, and `onFailure` a handler.

| Builder method                                                     | Sets                                                | Description                                                                                                                               |
|:-------------------------------------------------------------------|:----------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------|
| `name(name)`                                                       | `agent.name`                                        | The agent's name, as the proxy's dashboard and logs show it                                                                               |
| `proxy(endpoints…)`                                                | `agent.proxy.endpoints`                             | The proxy's agent port, as `host` or `host:port` (the port defaults to 50051), or several, in the order the agent fails over through them |
| `token(token)`                                                     | `agent.agentToken`                                  | The token the agent presents to the proxy                                                                                                 |
| `trustCertificatePem(pem, authority)`                              | `agent.tls.trustCertCollectionPem`, `agent.tls.overrideAuthority` | The certificate the agent trusts the proxy's by, as PEM or its base64, which turns TLS on, and, optionally, the name the proxy's certificate was issued to |
| `clientCertificatePem(certChainPem, privateKeyPem)`                | `agent.tls.certChainPem`, `agent.tls.privateKeyPem` | The agent's own certificate chain and key, as PEM or its base64, for a proxy that requires mutual TLS                                     |
| `fromEnvironment()`                                                | `proxy`, `token`, `trustCertificatePem`'s           | Since 4.7.0, set them from `PROMETHEUS_PROXY`, `PROMETHEUS_AGENT_TOKEN`, `PROMETHEUS_PROXY_CERT`, and `PROMETHEUS_PROXY_AUTHORITY`, those set ([Settings From the Environment](embedded-agent.md#settings-from-the-environment)) |
| `consolidated(consolidated)`                                       | `agent.consolidated`                                | Register the agent's paths in consolidated mode; `consolidated()` alone turns it on                                                       |
| `agentMetrics(port)`                                               | `agent.metrics.enabled`, `agent.metrics.port`       | Turn on the agent's own metrics endpoint, which is off unless asked for                                                                   |
| `agentMetricsAt(path, labels)`                                     | `agent.metrics.proxyPath`, `agent.metrics.proxyPathLabels` | Since 4.7.0, serve the agent's own metrics at `path`, read in-process, with no port ([The Agent's Own Metrics](embedded-agent.md#the-agents-own-metrics)); `labels` is optional |
| `dashboard(port, host)`                                            | `agent.dashboard.enabled`, `agent.dashboard.port`, `agent.dashboard.host` | Since 4.7.0, turn on the agent's [dashboard](agent-dashboard.md), which is off unless asked for; `host`, such as `127.0.0.1`, is optional |
| `dashboardLogin(password, username)`                               | `agent.dashboard.password`, `agent.dashboard.username` | Since 4.7.0, require a login to the agent's dashboard; `username` defaults to `admin`                                                  |
| `scrapeSelf(path, port, username, password, metricsPath, labels)` | `agent.pathConfigs`                                 | Scrape the app at `http://127.0.0.1:<port>/<metricsPath>` (default `metrics`) and serve it at `path`; the rest are optional               |
| `path(path, url, username, password, labels)`                      | `agent.pathConfigs`                                 | Scrape any target at `url` and serve it at `path`; the rest are optional, and the URL then carries no credentials of its own              |
| `source(path, registry)`, `source(path, labels, registry)`         | none: it's code                                     | Since 4.7.0, serve the `PrometheusRegistry` at `path`, read in-process ([Metrics Served In-Process](embedded-agent.md#metrics-served-in-process)), with the path's labels or none |
| `source(path, source)`, `source(path, labels, source)`             | none: it's code                                     | Since 4.7.0, serve what the `MetricsSource` writes at `path`, read in-process, with the path's labels or none                            |
| `setting(key, value)`                                              | the key, under `agent`                              | Any other setting, such as `http.clientTimeoutSecs`; it takes precedence over a method that sets the same key                             |
| `onFailure(handler)`                                               | none: it's code                                     | Since 4.7.1, add a handler `startIfConfigured()` tells of a start that fails, with why and what was thrown ([Starting Where There's a Proxy](embedded-agent.md#starting-where-theres-a-proxy)) |
| `reportFailure(cause)`                                             | none: it's an action                                | Since 4.7.1, log a failure that came while the settings were being made, before the agent could start, and tell the `onFailure` handlers, as a failed start does, for a host that makes the settings from its own configuration ([Starting Where There's a Proxy](embedded-agent.md#starting-where-theres-a-proxy)) |
| `stopTimeout(timeout)`                                             | none: it's the handle's                             | Since 4.7.1, how long the handle's `shutdown()` waits for the agent to stop, more than zero, or `Duration.INFINITE` for as long as it takes; `null`, the default, leaves the wait to the agent's own stop, 30 seconds ([`EmbeddedAgentInfo`](#embeddedagentinfo)) |

In `embeddedAgent { }`, `name`, `proxy`, `token`, `consolidated`, `agentMetricsPort`, `dashboardPort`, and `stopTimeout`
are properties too, so `name = "my-app"` does what `name("my-app")` does; `proxy` takes several endpoints separated by
commas, as `PROXY_HOSTNAME` does, `stopTimeout` takes a Kotlin `Duration`, where its method takes a
`java.time.Duration`, and `null` takes `name`, `proxy`, `token`, `agentMetricsPort`, `dashboardPort`, or `stopTimeout`
out. Java sees only the methods.

A username and password are percent-encoded into the URL and sent as Basic Auth; both empty, as unset environment
variables give, means none. Labels are a map of names to values. A value the builder can tell is wrong, such as a blank
path, `/` alone, a path of more than one segment (`app/metrics` or `//metrics`), a blank URL, or a port out of range,
throws `ConfigLoadException` as the method is called, as `start()` does for a setting the agent rejects.

??? note "Through 4.7.0"

    `/` alone and a blank URL passed, and the agent dropped the target at startup with a warning, as it does a config
    file's placeholder, and a path of more than one segment passed too, and no scrape reached it.

| `EmbeddedAgentConfig` member         | Type                 | Description                                                                                            |
|:-------------------------------------|:---------------------|:-------------------------------------------------------------------------------------------------------|
| `start(logBanner)`                   | `EmbeddedAgentInfo`  | Starts the agent, as `startAsyncAgent(config)` does, and throws as it does; `logBanner` defaults to `true` |
| `startIfConfigured(logBanner, onFailure)` | `EmbeddedAgentInfo?` | Since 4.7.0, starts the agent where the settings or `PROXY_HOSTNAME` name a proxy, and returns `null` without one, or for a start that fails, which it logs and tells the builder's `onFailure` handlers, then `onFailure`, optional, with why and what was thrown ([Starting Where There's a Proxy](embedded-agent.md#starting-where-theres-a-proxy)) |
| `namesProxy()`                       | `Boolean`            | Since 4.7.0, whether the settings or `PROXY_HOSTNAME` name a proxy, the rule `startIfConfigured()` starts by |
| `toConfig()`                         | `Config`             | The settings as the tree a config file holds, rooted at `agent`, without the paths `source` adds, which are code |
| `toString()`                         | `String`             | The settings, with the token, passwords, and PEMs shown only as set, a path's credentials masked, and a `source` path as `in-process`, so an app can log them |

`MetricsSource.of(registry)`, a static method from Java, is the `MetricsSource` that `source(path, registry)` serves a
`PrometheusRegistry` with.

## `EmbeddedAgentInfo`

The handle `startAsyncAgent()` and `EmbeddedAgentConfig.start()` return.

| Member          | Type      | Description                                                                                                                                       |
|:----------------|:----------|:--------------------------------------------------------------------------------------------------------------------------------------------------|
| `launchId`      | `String`  | Unique ID for this agent launch, new each time an agent starts                                                                                    |
| `agentName`     | `String`  | Name of the agent: `agent.name`, or `Unnamed-<hostname>` when none is set                                                                         |
| `isConnected`   | `Boolean` | Whether the agent is connected to a proxy and has registered its paths there, so Prometheus can scrape through it now; read live, for a health check |
| `proxyEndpoint` | `String?` | The proxy it's connected to, as `host:port`, or `null` when it isn't                                                                             |
| `shutdown()`    | `Unit`    | Stops the agent and blocks until it has stopped, up to the builder's `stopTimeout`, or, without one, 30 seconds, after which it throws an `IllegalStateException`, with the `TimeoutException` as its cause; calling it again, or on a failed agent, does nothing |
| `close()`       | `Unit`    | Since 4.7.0, does what `shutdown()` does: the handle is `AutoCloseable`, for Kotlin's `use` and Java's try-with-resources                         |

`isConnected` is false before the agent's first connection, while it reconnects after losing one, and once it has
stopped. A host's health check can report it, though without failing the app's own health: a lost proxy shouldn't
take the app down.

Call `shutdown()` wherever your application stops its own services, or from a shutdown hook of its own. It closes the
connection to the proxy, drains the scrapes in flight, and releases its admin, metrics, and dashboard ports, so the
agent's connection and background work don't outlive your application. It waits up to the builder's `stopTimeout` (since
4.7.1): once that's up, `shutdown()` returns, with a warning in the log, and the agent goes on stopping on a daemon
thread of its own, so your application's shutdown fits the window its platform gives it, such as the 30 seconds
Kubernetes gives a pod, or Cloud Run's 10. A stop that fails within it throws. Without a `stopTimeout`, it waits 30
seconds at most, then throws an `IllegalStateException`, whose cause is a `java.util.concurrent.TimeoutException`, and
the agent goes on stopping. An interrupt ends neither wait. Since 4.7.0, the agent registers no JVM shutdown hook, so it stops when your application stops it,
after the work that needs it, not at the same time as your application's own hooks; the [Ktor Plugin](ktor-plugin.md)
and the [Spring Boot Starter](spring-boot-starter.md) stop it with the app.

??? note "Through 4.7.0"

    Without a `stopTimeout`, `shutdown()` threw the `TimeoutException` itself, which Java code couldn't catch.

Since 4.7.0, too, the agent runs on a daemon thread, so it doesn't keep the JVM running: your application's own threads
do. An application that fails after starting the agent, without stopping it, still exits. So does a program whose
`main()` starts the agent and returns, at once, ending the agent without draining its scrapes: keep `main()` running
for as long as the agent is needed, as `startIfConfigured().use { worker.run() }` does. With its admin or metrics port
on, the agent also runs those ports' servers, whose threads do keep the JVM running until the agent stops.

On SIGTERM, the JVM runs its shutdown hooks and exits without returning from `main()`. So a program that runs until
it's stopped, as a queue consumer does, adds a hook of its own that ends its work and waits for `main()` to return, by
which time `use` has stopped the agent, as the [Plain Kotlin Demo](plain-kotlin-demo.md)'s ovens do. The hook waits 25
seconds at most, so the JVM still exits on its own within the 30 seconds Kubernetes gives a pod to stop, and the agent's
`stopTimeout` of 20 seconds keeps its stop inside the hook's wait:

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentShutdownKotlinExample.kt:embedded-kotlin-shutdown"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentShutdownJavaExample.java:embedded-java-shutdown"
    ```

Without the hook, the agent of a `use` block ends with the JVM, without draining the scrapes in flight. Once the hook
is added, don't call `exitProcess()` or `System.exit()` in `main()`, as on a fatal error: it waits for the shutdown
hooks, and this one waits for `main()`, so the JVM hangs until the hook gives up. To end with an error, throw from
`main()` instead: the JVM then exits with status 1.

## `Agent.startSyncAgent()`

Starts an agent from command-line arguments, as the standalone agent's `main()` does, and returns once the agent is
running. It returns no handle: the agent runs on its own non-daemon thread, so a JVM whose `main()` calls this keeps
running until the agent stops. Use `startAsyncAgent()` to embed an agent you stop yourself.

| Parameter             | Type            | Description                                                                                                                                  |
|:----------------------|:----------------|:---------------------------------------------------------------------------------------------------------------------------------------------|
| `args`                | `Array<String>` | The agent's [CLI options](cli-reference.md#agent-options), such as `--config agent.conf`                                                     |
| `exitOnMissingConfig` | `Boolean`       | `true` behaves as the standalone agent does, exiting the JVM on a config error. `false` throws `ConfigLoadException` instead, and with no `--config` falls back to the built-in defaults |

**Throws:** `ConfigLoadException`, when `exitOnMissingConfig` is `false`, in the cases
[`startAsyncAgent()`](#agentstartasyncagent) lists, and for `-v` or `-u`; `IllegalStateException` when the agent fails
to start, with the failure as its cause. It waits up to 30 seconds for the agent to start.
