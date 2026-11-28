---
icon: lucide/package
---

# Embedded Agent

If your application runs on the JVM, you can embed the prometheus-agent directly in your app
instead of running a separate agent process.

## When to Use

- Your application already runs on the JVM (Java, Kotlin, Scala, etc.)
- You want to avoid managing a separate agent process
- You need tight integration between your app and the agent lifecycle

## Ways to Run It

Each of these runs the same agent from the same settings, the tree a config file holds under `agent` (see
[Agent Configuration](configuration/agent.md)):

| Your application                        | Start the agent with                                                                        | Its settings come from                                                                                  |
|:----------------------------------------|:--------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------|
| A Spring Boot app                       | The [Spring Boot Starter](spring-boot-starter.md), from a dependency                        | The `prometheus-proxy.agent.*` properties, and a customizer bean                                        |
| A Ktor app                              | The [Ktor Plugin](ktor-plugin.md), `install(PrometheusProxyAgent)`                          | `prometheus-proxy.agent` in `application.conf` or its YAML, and the install block                       |
| Any JVM program, its settings in code   | [`embeddedAgent { }`](#configuring-in-code), or `EmbeddedAgentConfig.builder()` from Java   | The builder's methods, [`fromEnvironment()`](#settings-from-the-environment), and `setting()`           |
| Any JVM program, its settings in a file | [`Agent.startAsyncAgent(file, false)`](embedded-api.md#agentstartasyncagent)                               | A HOCON config file or URL, as the standalone agent reads one                                           |
| Any JVM program, a `Config` it builds   | [`Agent.startAsyncAgent(config)`](#a-config-of-your-own)                                    | A Typesafe `Config`, rooted at `agent`                                                                  |

The starter and the plugin start the agent with the app and stop it as the app stops; with the others, the app stops
it itself (see [`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)). The starter and the plugin set the agent's settings through
the builder, and [Every Setting, Every Way](#every-setting-every-way) lists each setting's name in all of them.

## Adding the Dependency

=== "Gradle (Kotlin DSL)"

    ```kotlin
    --8<-- "EmbeddedAgentExamples.txt:gradle-dependency"
    ```

=== "Maven"

    ```xml
    --8<-- "EmbeddedAgentExamples.txt:maven-dependency"
    ```

## Configuring in Code

Since 4.6.0, an application that holds the agent's settings itself, such as a token, the proxy's certificate, or
credentials for its own `/metrics`, can build them in code, so that no config file holds them on disk.
[`EmbeddedAgentConfig`](embedded-api.md#embeddedagentconfig) has a typed method for each setting: build it with `embeddedAgent { }`
from Kotlin, or `EmbeddedAgentConfig.builder()` from Java, and start it. Since 4.7.0,
[`fromEnvironment()`](#settings-from-the-environment) reads the proxy, the token, and the certificate from the app's
environment, and [`startIfConfigured()`](#starting-where-theres-a-proxy) starts the agent only where there's a proxy,
and keeps the app running if it can't:

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentConfigKotlinExample.kt:embedded-kotlin-config"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentConfigJavaExample.java:embedded-java-config"
    ```

- **The certificate is PEM text,** or its base64
  ([`agent.tls.trustCertCollectionPem`](security/tls.md#certificates-as-environment-variables)).
- **`scrapeSelf` points the agent at the app's own metrics** on `127.0.0.1`. It percent-encodes a username and password
  into the scrape URL, whatever their characters; the agent sends them as Basic Auth and leaves them out of its logs.
  An app with a metrics registry needn't serve it at all: the agent reads it
  [in-process](#metrics-served-in-process).
- **`stopTimeout`, since 4.7.1, caps how long the agent's stop holds up the app's shutdown,** inside the window the
  platform gives the app after `SIGTERM`; once it's up, `shutdown()` returns, and the agent goes on stopping on its own
  ([`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)).
- **The settings fall back to the built-in defaults,** as a config file's do, and a setting the agent can't use throws
  `ConfigLoadException` from `start()`; `startIfConfigured()` logs it and returns `null`.
- **The environment variables of single settings still take precedence,** such as `AGENT_TOKEN` or `PROXY_HOSTNAME`,
  as they do over a config file: give your application's own settings other names, as the `PROMETHEUS_` ones
  `fromEnvironment()` reads are, unless you mean the agent to read them. `AGENT_CONFIG` isn't read.
  [Every Setting, Every Way](#every-setting-every-way) lists each setting's variable.

### Metrics Served In-Process

Since 4.7.0, an app with a metrics registry can hand it to the agent instead of serving it at a URL:
`source(path, registry)` takes the Prometheus Java client's `PrometheusRegistry`, which Micrometer's
`PrometheusMeterRegistry` gives with `getPrometheusRegistry()`, and the agent reads it on each scrape, in-process. The
app then needs no metrics endpoint, port, or credentials for the agent, and the agent opens no connection to it. A
program with no HTTP server at all, such as a worker that consumes a queue, is scraped as a web app is, as the
[Plain Kotlin Demo](plain-kotlin-demo.md)'s ovens are:

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentSourceKotlinExample.kt:embedded-kotlin-source"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentSourceJavaExample.java:embedded-java-source"
    ```

- **The agent writes the format Prometheus asks for:** the Prometheus text format or OpenMetrics, as Prometheus's
  `Accept` header allows, without the protobuf it can't carry, and only the metric families Prometheus names with
  `name[]` parameters, if it names any. It brings the Prometheus client's text formats itself, so the app declares
  nothing more.
- **It's read as a target's body is,** on a thread it can block: up to `agent.http.maxContentLengthMBytes`, past which
  the scrape is refused, and through the path's [metric filter](configuration/agent.md#metric-filtering).
- **The agent's own metrics aren't among the app's.** With its metrics on, the agent keeps them in the default
  registry, `PrometheusRegistry.defaultRegistry`, which a plain program's own metrics are often in too; a source of it
  leaves them out, so each series is served once. [`agentMetricsAt`](#the-agents-own-metrics) serves them at a path of
  their own.
- **A source's trouble stays in its scrape.** At the scrape timeout, the agent answers without waiting for it, and
  whatever reading the registry throws, short of a `VirtualMachineError`, fails the scrape, as a target that can't be
  reached does, and the agent goes on.
- **The path's target shows as `in-process`** on both dashboards and in the agent's log, where a scraped path shows its
  URL, and Scrape now on the agent's dashboard reads the registry.
- **The path is checked as a `pathConfigs` entry is:** one that another path also names, or that has a character the
  proxy refuses (whitespace, a control character, `?`, or `#`), fails `start()`. A source is code, so `toConfig()`
  leaves it out: start the agent with the builder's `start()` or `startIfConfigured()`.

For metrics in another form, `source(path, source)` takes a `MetricsSource`, which the agent asks for the app's metrics
on each scrape: it writes them, in a format the scrape's `Accept` header allows, to the stream it's given, and returns
the content type it wrote, an empty one meaning the text format. Calls can overlap, as scrapes do, so it must be safe to
call from several threads. At the scrape timeout, the agent interrupts the call; one that ignores the interrupt, such as
one blocked on a socket read, keeps its thread, and the source isn't called again until every such call has returned.
Whatever it throws, an `Error` included, fails only its scrape; only a `VirtualMachineError`, such as
`OutOfMemoryError`, isn't kept to it.
`MetricsSource.of(registry)` is the source `source(path, registry)` gives a registry.

### The Agent's Own Metrics

The [Agents dashboard](grafana.md#dashboards) the project ships reads the agent's own metrics, `agent_connected`, its
scrapes, their latency, and its backlog among them. An embedded agent's metrics port would be no more reachable from
outside than the app's, so since 4.7.0, `agentMetricsAt(path)` has the agent serve them through the proxy, at a path of
their own, read in-process as the app's registry is, with no port opened for them. The example above serves them at
`/worker_agent_metrics`.

`agentMetricsAt(path, labels)` sets `agent.metrics.proxyPath` and `agent.metrics.proxyPathLabels`, the settings a
standalone agent serves its metrics through the proxy with, so `toConfig()` holds them, and a config file, a `Config`,
the starter's and the plugin's `metrics-proxy-path`, or `METRICS_PROXY_PATH` in the app's environment sets the same:

- **They're the `agent_*` series, this agent's alone:** another agent in the same JVM serves its own, and the JVM's
  metrics stay with the app's path, which the dashboard doesn't read them from. A standalone agent's path serves its
  JVM's metrics too, since its JVM is its own, and so does an `Agent` an app constructs and starts itself: only one
  started with `startAsyncAgent()` or this builder, as the starter and the plugin start theirs, counts as embedded.
- **They're kept from the start,** whether or not the agent's metrics port is on (`agentMetrics(port)`,
  `METRICS_ENABLED`), and a source of the default registry leaves them out. The
  [agent's dashboard](agent-dashboard.md) shows them as on in its Settings view, with their path, and their port when
  it's on too.
- **The path is checked as a `pathConfigs` entry is:** one another path also names, or that has a character the proxy
  refuses, fails `start()`, and the error names `agent.metrics.proxyPath`.
- **Give each of the app's replicas a path of its own,** as the [Plain Kotlin Demo](plain-kotlin-demo.md) names its
  replicas' after their host names: replicas sharing one would have the proxy serve only one of them there.
- **Give the path a Prometheus job of its own,** since the dashboard tells agents apart by `job`:

```yaml
scrape_configs:
  - job_name: worker-agent
    metrics_path: /worker_agent_metrics
    static_configs:
      - targets: [ 'proxy-host:8080' ]
```

The [Ktor Demo](ktor-demo.md) serves its coffee shop's agent's metrics this way, and its Grafana links to the
dashboard.

The metrics port, `agentMetrics(port)`, is for a scraper on the app's own network, such as a Prometheus inside the
cluster, which can read the agent while it can't reach the proxy, when nothing reaches its path. The port serves
everything in the JVM's default registry, the app's metrics too when they're kept there, and the JVM metric groups the
agent's `agent.metrics` settings turn on. Without such a scraper, the path is all the agent needs (see
[Port or Proxy Path](monitoring.md#port-or-proxy-path)).

### Settings from the Environment

Since 4.7.0, `fromEnvironment()` reads the proxy, and how to reach it, from the app's environment, under names apart from
the agent's own, which the [Ktor Demo](ktor-demo.md), the [Spring Boot Demo](spring-boot-demo.md), and
[Cloud Platforms](cloud-platforms.md) use:

| Variable                     | Sets                                                                                                                              |
|:-----------------------------|:----------------------------------------------------------------------------------------------------------------------------------|
| `PROMETHEUS_PROXY`           | `proxy`: the proxy's agent port, as `host:port`, or several, separated by commas                                                  |
| `PROMETHEUS_AGENT_TOKEN`     | `token`: the token the agent presents to the proxy                                                                                |
| `PROMETHEUS_PROXY_CERT`      | `trustCertificatePem`: the certificate the agent trusts the proxy's by, as PEM or its base64, which turns TLS on                    |
| `PROMETHEUS_PROXY_AUTHORITY` | The certificate's authority, the name the proxy's certificate was issued to; `prometheus-proxy` when unset. Read only with the certificate |

- **The authority defaults to `prometheus-proxy`,** the name the certificate [Securing the Link](cloud-platforms.md#securing-the-link)
  makes is issued to. To check the proxy's certificate against the host the agent connects to, as for a certificate
  from a public CA, set `PROMETHEUS_PROXY_AUTHORITY` to that host.
- **A variable that's unset, or blank, leaves its setting alone,** so a builder method called after `fromEnvironment()`
  takes precedence, and one called before it holds where the variable isn't set. A `setting()` of the same key takes
  precedence whatever its order, as it does over any builder method, and the variable's value then isn't checked.
- **Only `fromEnvironment()` reads these:** the agent doesn't, so an app that never calls it can use the names for
  anything.
- **A value the agent refuses names its variable.** A `PROMETHEUS_PROXY` endpoint the agent can't parse, or a
  `PROMETHEUS_PROXY_CERT` it can't read as a certificate, fails `start()`, or has `startIfConfigured()` log, with the
  variable, the setting, and the agent's reason: `Invalid configuration: PROMETHEUS_PROXY, read by fromEnvironment() for
  agent.proxy.endpoints: Invalid port in 'proxy:abc': 'abc' is not a valid integer`. An endpoint it can't parse is
  quoted, as here, but never a certificate, and no token is checked as the agent starts. The agent
  takes any token or authority as it starts: a token the proxy refuses is logged once the agent connects
  ([Troubleshooting](troubleshooting.md#unauthenticated-missing-or-invalid-agent-token)), and a wrong authority fails
  the TLS handshake.

### Starting Where There's a Proxy

`start()` throws when the agent can't start, which suits an app that can't run without it. Since 4.7.0,
`startIfConfigured()` suits one that can, as most can, since their metrics aren't worth an outage:

- **Without a proxy,** from the builder's `proxy`, a `setting()` of the proxy's endpoints or host name, or the agent's
  own `PROXY_HOSTNAME`, as on a developer's machine, it logs one INFO line,
  `No proxy is set, so the Prometheus agent stays off`, and returns `null`. `namesProxy()` tells whether the settings
  name one, for an app that decides for itself, as the [Ktor Plugin](ktor-plugin.md) and the
  [Spring Boot Starter](spring-boot-starter.md) do.
- **A start that fails,** for a setting the agent rejects or for anything else, such as its admin port being in use or
  its start taking over 30 seconds, is logged at ERROR, `The Prometheus agent could not start, so it stays off:` and
  why, which holds no token, password, or PEM, and it returns `null`, so the app runs on. Since 4.7.1, to report the
  failure elsewhere, as to a chat channel, add a handler with the builder's `onFailure`, which is given why, as the log
  line has it, and what was thrown. Each handler added runs, in turn, and one that throws is logged, without failing the
  start, so a [Ktor](ktor-plugin.md) `agent { }` block or a [Spring Boot](spring-boot-starter.md) customizer can add one
  beside the app's own. `startIfConfigured(onFailure)` still takes one too, which runs after them. `start()` tells none
  of them: it throws, for its caller to handle. A host that makes the settings from its own configuration before the
  app's code adds its handlers, as those two do, tells them of a value there that a builder method rejects with the
  builder's `reportFailure(cause)`, which logs it as a failed start is logged.
- **The handle is `AutoCloseable`,** and Kotlin's `use` and Java's try-with-resources both take a `null` one, so a
  program that runs while its agent does reads as `startIfConfigured().use { worker.run() }`: the worker runs whether
  or not the agent started, and the agent stops after it. Write `.use`, not `?.use`, which skips the block for `null`.
  One that runs until it's stopped needs a shutdown hook too (see [`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo)).

=== "Kotlin"

    ```kotlin
    val agent =
      embeddedAgent {
        // The agent's other settings
        fromEnvironment()
        onFailure { reason, _ -> alerts.send("No metrics from this app: $reason") }
      }.startIfConfigured(logBanner = false)
    ```

=== "Java"

    ```java
    EmbeddedAgentInfo agent =
        EmbeddedAgentConfig.builder()
            // The agent's other settings
            .fromEnvironment()
            .onFailure((reason, e) -> alerts.send("No metrics from this app: " + reason))
            .build()
            .startIfConfigured(false);
    ```

A wrong token isn't a failure to start: the agent starts, and the proxy refuses it once it connects. The agent logs that
at ERROR, naming the setting, and keeps retrying (see [Troubleshooting](troubleshooting.md#unauthenticated-missing-or-invalid-agent-token)).

### A `Config` of Your Own

For a setting the builder has no method for, `setting(key, value)` sets it by its key, relative to `agent`, such as
`setting("scrapeTimeoutSecs", 5)`. Or build the whole tree as a Typesafe `Config` rooted at `agent`, the tree a config
file holds, and start it with `Agent.startAsyncAgent(config)`. `ConfigFactory.parseString`, `ConfigFactory.parseMap`,
and `ConfigValueFactory.fromMap` build one, and a builder's `toConfig()` gives the one it holds. It's resolved, as a
config file is, so a `${?ENV_VAR}` substitution in it works.

[Cloud Platforms](cloud-platforms.md) builds on this to run an embedded agent in an app on ECS on Fargate, Cloud Run,
Azure Container Apps, or another platform, with the proxy on AWS, GCP, Azure, DigitalOcean, or Railway, and the
[Ktor Demo](ktor-demo.md) runs an embedded agent on one machine, in a Ktor app, with the
[Ktor Plugin](ktor-plugin.md). The [Plain Kotlin Demo](plain-kotlin-demo.md) runs one in each of a plain Kotlin
program's replicas, with no framework or HTTP server, scaled out and in.

## Configuring with a File { #usage }

An app that keeps the agent's settings in a file, as the standalone agent does, starts it with
[`Agent.startAsyncAgent()`](embedded-api.md#agentstartasyncagent), which reads the file or a URL:

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentKotlinExample.kt:embedded-kotlin"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentJavaExample.java:embedded-java"
    ```

The file holds the same HOCON settings as the standalone agent's (see
[Agent Configuration](configuration/agent.md)):

```hocon
--8<-- "EmbeddedAgentExamples.txt:embedded-agent-config"
```

## Every Setting, Every Way

A setting has a name in each way of giving it. The builder's methods, the starter's properties, and the plugin's keys
all set the agent's config keys, the ones a config file holds, and the agent reads each key's environment variable
itself, embedded or not:

| Setting                         | Builder                                             | Config key                                                                 | Starter property, plugin key                       | Environment variable                                   |
|:--------------------------------|:----------------------------------------------------|:---------------------------------------------------------------------------|:---------------------------------------------------|:-------------------------------------------------------|
| The proxy                       | `proxy(endpoints…)`                                 | `agent.proxy.endpoints`                                                    | `proxy`                                            | `PROXY_HOSTNAME`                                       |
| The token                       | `token(token)`                                      | `agent.agentToken`                                                         | `token`                                            | `AGENT_TOKEN`                                          |
| The proxy's certificate         | `trustCertificatePem(pem, authority)`               | `agent.tls.trustCertCollectionPem`, `agent.tls.overrideAuthority`          | `trust-certificate`, `authority`                   | `TRUST_CERT_COLLECTION_PEM`, `OVERRIDE_AUTHORITY`      |
| The agent's certificate and key | `clientCertificatePem(certChainPem, privateKeyPem)` | `agent.tls.certChainPem`, `agent.tls.privateKeyPem`                        | `client-certificate`, `client-key`                 | `CERT_CHAIN_PEM`, `PRIVATE_KEY_PEM`                    |
| The agent's name                | `name(name)`                                        | `agent.name`                                                               | `name`                                             | `AGENT_NAME`                                           |
| Consolidated mode               | `consolidated(consolidated)`                        | `agent.consolidated`                                                       | `consolidated`                                     | `CONSOLIDATED`                                         |
| Its metrics, on a port          | `agentMetrics(port)`                                | `agent.metrics.enabled`, `agent.metrics.port`                              | `metrics-port`                                     | `METRICS_ENABLED`, `METRICS_PORT`                      |
| Its metrics, through the proxy  | `agentMetricsAt(path, labels)`                      | `agent.metrics.proxyPath`, `agent.metrics.proxyPathLabels`                 | `metrics-proxy-path`, `metrics-proxy-path-labels`  | `METRICS_PROXY_PATH`                                   |
| Its dashboard                   | `dashboard(port, host)`                             | `agent.dashboard.enabled`, `agent.dashboard.port`, `agent.dashboard.host`  | `dashboard.port`, `dashboard.host`                 | `DASHBOARD_ENABLED`, `DASHBOARD_PORT`, `DASHBOARD_HOST` |
| Its dashboard's login           | `dashboardLogin(password, username)`                | `agent.dashboard.password`, `agent.dashboard.username`                     | `dashboard.password`, `dashboard.username`         | `DASHBOARD_PASSWORD`, `DASHBOARD_USERNAME`             |
| Its dashboard's framing         | `setting("dashboard.framingEnabled", false)`        | `agent.dashboard.framingEnabled`                                           | `settings.dashboard.framingEnabled`                |                                                        |
| A target it scrapes             | `path(…)`, `scrapeSelf(…)`                          | `agent.pathConfigs`                                                        | `paths`                                            |                                                        |
| The app's own registry          | `source(path, registry)`                            | none: it's code                                                            | `path` and `labels`, with the app's registry       |                                                        |
| Its stop's timeout              | `stopTimeout(timeout)`                              | none: it's the handle's                                                    | `stop-timeout`                                     |                                                        |
| Anything else                   | `setting(key, value)`                               | the key                                                                    | `settings.<key>`                                   | the key's, if it has one                               |

The starter serves the app's `PrometheusRegistry` bean at `path` unless its `source` is `none`, and the plugin serves
the registry its install block gives. `fromEnvironment()`'s `PROMETHEUS_` variables are the app's, which only it reads
([Settings From the Environment](#settings-from-the-environment)); the variables above are the agent's own, which it
reads whether or not the app asks, so keep their names out of the app's environment unless you mean the agent to use
them.

Where one setting is given more than one way, the first of these that gives it wins:

1. **The agent's own environment variable,** such as `AGENT_TOKEN`, read as the agent starts.
2. **`setting(key, value)`,** and the starter's and the plugin's `settings.<key>`, which call it.
3. **A builder method,** its last call; the starter's customizers and the plugin's install block run after the
   properties or the file, so their calls replace what those set.
4. **The built-in default** (see [Agent Configuration](configuration/agent.md)).

A config file or a `Config` takes the place of the second and third: its keys, then the defaults.

### Settings without a Builder Method

`setting(key, value)` sets any other setting by its key under `agent`, as the starter's and the plugin's
`settings.<key>` do. These are the ones an embedded agent most often needs:

| Config key                             | Default | What it sets                                                                                                           |
|:---------------------------------------|:--------|:-----------------------------------------------------------------------------------------------------------------------|
| `agent.scrapeTimeoutSecs`              | 15      | How long the agent waits for a target's answer, in seconds                                                             |
| `agent.scrapeMaxRetries`               | 0       | How many times it retries a failed scrape                                                                              |
| `agent.http.maxConcurrentClients`      | 1       | How many scrapes it runs at once; raise it when it scrapes several slow targets                                        |
| `agent.http.clientTimeoutSecs`         | 90      | Its HTTP client's timeout, in seconds                                                                                  |
| `agent.http.maxContentLengthMBytes`    | 10      | The largest scrape it takes, in megabytes                                                                              |
| `agent.http.trustStorePath`            | ""      | A trust store for HTTPS targets signed by a private CA, with `agent.http.trustStorePassword`                           |
| `agent.transportFilterDisabled`        | false   | Set it, as on the proxy, when [nginx fronts the proxy](advanced.md#nginx-reverse-proxy)                                |
| `agent.admin.enabled`                  | false   | The admin endpoints, on `agent.admin.port`, and `/debug` with `agent.admin.debugEnabled` ([Admin and Debug Endpoints](embedded-debugging.md#admin-and-debug-endpoints)) |
| `agent.logLevel`                       | ""      | The level of the agent's `io.prometheus` loggers, and no others; unset, they follow your application's logging ([Logging](embedded-debugging.md#logging)) |
| `agent.metrics.standardExportsEnabled` | false   | The process metrics, one of the [JVM metric groups](monitoring.md#jvm-metrics), which the agent's metrics port serves |

An embedded agent's JVM metrics are usually its app's, which its own registry, such as Micrometer's, keeps and serves:
the agent's JVM metric groups add only to what its metrics port serves, and do nothing with the port off. A list or an
object, such as `filters` or `discovery`, is clearer in a config file or a `Config` (see
[A `Config` of Your Own](#a-config-of-your-own)); [Agent Configuration](configuration/agent.md) describes every
setting.

## In Ktor

The [Ktor Plugin](ktor-plugin.md) does this for a Ktor app: `install(PrometheusProxyAgent)` with the app's registry
starts the agent once the app's modules load, stops it as the server stops, and serves the registry in-process, with
its settings from the install block or `application.conf`.

## In Spring Boot

The [Spring Boot Starter](spring-boot-starter.md) does all of this for a Spring Boot app, from a dependency and a few
properties, reading the app's metrics in-process, as the [Spring Boot Demo](spring-boot-demo.md)'s roastery shows. To
wire the agent yourself, run it from a bean, so it starts and stops with the app, with each of these:

- **A `SmartLifecycle` bean** in the last phase, the default, starts the agent once the web server is serving, so the
  agent's first scrape finds the app's metrics, and stops it first on shutdown, before the web server drains: the
  agent disconnects, the proxy drops its paths, and no scrape reaches a server that's going away.
- **Actuator's metrics:** with `micrometer-registry-prometheus` on the classpath, and `prometheus` in
  `management.endpoints.web.exposure.include`, which exposes only `health` by default, Actuator serves them at
  `/actuator/prometheus`, so `scrapeSelf` takes `"actuator/prometheus"` as its `metricsPath`, with the credentials the
  app's Spring Security asks for. Or the agent reads the registry in-process, with `source` and the app's
  `PrometheusRegistry` bean ([Metrics Served In-Process](#metrics-served-in-process)), and `/actuator/prometheus` needn't
  be exposed at all.
- **The settings:** a `@ConfigurationProperties` record can hold them, with `application.yml` reading each from the
  environment, as `${PROMETHEUS_AGENT_TOKEN:}` does. Give it a `toString` that leaves the secrets out, so the app can
  log it.
- **Spring Boot's dependency management** sets the versions of the libraries it knows, some of the agent's among them,
  and some of those run older than the agent's own. The agent runs with Spring Boot 4.2's, and the demo's roastery
  runs it at those versions on every pull request
  ([Spring Boot's versions](spring-boot-demo.md#spring-boots-versions-of-the-agents-libraries)). Spring Boot 4.0 and
  4.1 set kotlinx-coroutines and the Prometheus Java client below the versions the agent runs on: set
  `kotlin-coroutines.version` to `1.11.0` and `prometheus-client.version` to `1.10.0`, or the agent doesn't start, and
  says so. Were a Spring Boot release to break it, the app's pom sets that library to the agent's version with the
  property Spring Boot names it by, such as `grpc-java.version` or `netty.version`, which Spring Boot's
  [Version Properties](https://docs.spring.io/spring-boot/appendix/dependency-versions/properties.html) lists for every
  library it manages. netty-tcnative has none, since Netty's BOM sets it: pin `netty-tcnative-classes` and
  `netty-tcnative-boringssl-static`, with each native's classifier, in the pom's own `dependencyManagement`.
- **A health indicator** can report the agent in Actuator's `/actuator/health` from `EmbeddedAgentInfo.isConnected`
  and `proxyEndpoint`: UP while it's connected, and UNKNOWN, not DOWN, when it's off or reconnecting, so a lost proxy
  leaves the app's health UP.
- **Logging:** see [Logging](embedded-debugging.md#logging) for the line Spring Boot's `application.yml` takes.

## API Reference

[Embedded Agent API](embedded-api.md) lists the methods and types:
[`Agent.startAsyncAgent()`](embedded-api.md#agentstartasyncagent),
[`EmbeddedAgentConfig`](embedded-api.md#embeddedagentconfig),
[`EmbeddedAgentInfo`](embedded-api.md#embeddedagentinfo), and
[`Agent.startSyncAgent()`](embedded-api.md#agentstartsyncagent).

## How It Works

When you call `startAsyncAgent()`:

1. The agent reads the configuration file, or the config you passed
2. Starts its admin, metrics, and dashboard servers, if enabled, and waits for them to come up
3. Returns with agent metadata, without waiting for a proxy connection
4. Connects to the proxy and registers paths in the background
5. Keeps running in the background, processing scrape requests

Your application code runs normally while the agent handles metrics scraping in the background.

??? info "The agent's metrics in your application's registry"

    With its metrics on, on their port (`agentMetrics(port)`, `agent.metrics.enabled`) or at a proxy path
    (`agentMetricsAt(path)`, `agent.metrics.proxyPath`), the embedded agent's metrics register in the Prometheus Java
    client 1.x default registry (`io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry`). A host
    that serves its own metrics from that registry over HTTP exposes the agent's too, though the agent's
    `source(path, registry)` of it leaves them out; a host still on the 0.x client (`io.prometheus:simpleclient`) does
    not see them. Every agent in the JVM, two at once or one started again after `shutdown()`, shares one set of agent
    metrics, and each series carries the agent's `launch_id`. An agent's series go when it shuts down. A host metric
    with one of the agent metrics' names makes `startAsyncAgent()` throw.

!!! tip "Config file location"

    The config file path is relative to your application's working directory.
    Use an absolute path if needed.

## Logging

Your application configures the agent's logging, which goes through SLF4J. [Logging](embedded-debugging.md#logging) says
how it goes with Logback, with another provider, and with none.

## Debugging

[Debugging an Embedded Agent](embedded-debugging.md) shows how to see what the agent is doing: its admin and debug
endpoints, its dashboard, its log, and the proxy's view of it.

## Upgrading

What each release since 4.0 changed for code that embeds the agent, and what to do about it, is on
[Upgrading an Embedded Agent](embedded-upgrading.md).
