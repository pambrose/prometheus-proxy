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

## Adding the Dependency

=== "Gradle (Kotlin DSL)"

    ```kotlin
    --8<-- "EmbeddedAgentExamples.txt:gradle-dependency"
    ```

=== "Maven"

    ```xml
    --8<-- "EmbeddedAgentExamples.txt:maven-dependency"
    ```

## Usage

=== "Java"

    ```java
    --8<-- "EmbeddedAgentJavaExample.java:embedded-java"
    ```

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentKotlinExample.kt:embedded-kotlin"
    ```

## Agent Configuration

The embedded agent uses the same HOCON configuration as the standalone agent:

```hocon
--8<-- "EmbeddedAgentExamples.txt:embedded-agent-config"
```

## Configuring in Code

Since 4.6.0, an application that holds the agent's settings itself, such as a token, the proxy's certificate, or
credentials for its own `/metrics`, can pass them in memory, as a Typesafe `Config`, so that no config file holds them
on disk. The
tree is the one a config file holds, rooted at `agent`, and the certificate is given as PEM text
([`agent.tls.trustCertCollectionPem`](security/tls.md#certificates-as-environment-variables)):

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentConfigKotlinExample.kt:embedded-kotlin-config"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentConfigJavaExample.java:embedded-java-config"
    ```

`ConfigFactory.parseString` and `ConfigFactory.parseMap` build one too. The config falls back to the built-in defaults
and is resolved, as a config file is, so a `${?ENV_VAR}` substitution in it works. `AGENT_CONFIG` isn't read, but the
environment variables of single settings, such as `AGENT_TOKEN` or `PROXY_HOSTNAME`, still take precedence over it, as
they do over a config file: give your application's own settings other names, unless you mean the agent to read them.

[Cloud Platforms](cloud-platforms.md) builds on this to run an embedded agent in an app on ECS on Fargate, Cloud Run,
Azure Container Apps, or another platform, with the proxy on AWS, GCP, Azure, DigitalOcean, or Railway, and the
[Embedded Demo](embedded-demo.md) runs it on one machine, in a Ktor app.

## API Reference

### `Agent.startAsyncAgent()`

Starts the agent in background threads/coroutines.

| Parameter             | Type      | Description                                                                                                                              |
|:----------------------|:----------|:-----------------------------------------------------------------------------------------------------------------------------------------|
| `configFilename`      | `String`  | Path to the HOCON config file                                                                                                            |
| `exitOnMissingConfig` | `Boolean` | Pass `false` to embed: a config error or invalid option throws `ConfigLoadException`. `true` exits the JVM, as the standalone agent does |
| `logBanner`           | `Boolean` | Log the startup banner (default: `true`, which Java callers get by leaving it out too)                                                   |

Or, to start the agent from a config built in memory (see [Configuring in Code](#configuring-in-code)):

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
- an HTTPS trust store (`agent.http.trustStorePath`) or TLS certificate file that is missing or malformed, or a TLS
  certificate or key given as PEM that can't be read, or given as both a file and PEM.

Credentials in a config URL are redacted from its message and from every message in its cause chain. The cause is the
exception that rejected the setting, usually an `IllegalArgumentException`. When that exception's message held a URL
with credentials, as a Typesafe `ConfigException`'s does for a value read from a config URL, the cause is a stand-in
`ConfigLoadException` with the redacted message and the original stack trace.

`IllegalStateException` when the agent fails to start (for example, its admin or metrics port is already in
use), with the startup failure as its cause. A failed start releases everything the agent had opened.

### `EmbeddedAgentInfo`

The handle `startAsyncAgent()` returns.

| Member       | Type     | Description                                                                                   |
|:-------------|:---------|:----------------------------------------------------------------------------------------------|
| `launchId`   | `String` | Unique ID for this agent launch, new each time an agent starts                                |
| `agentName`  | `String` | Name of the agent: `agent.name`, or `Unnamed-<hostname>` when none is set                     |
| `shutdown()` | `Unit`   | Stops the agent and blocks until it has stopped; calling it again, or on a failed agent, does nothing |

Call `shutdown()` from your application's shutdown hook, or wherever it stops its own services. It closes the
connection to the proxy, drains the scrapes in flight, and releases the admin and metrics ports, so the agent's
connection and background work don't outlive your application.

### `Agent.startSyncAgent()`

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

## How It Works

When you call `startAsyncAgent()`:

1. The agent reads the configuration file, or the config you passed
2. Starts its admin and metrics servers, if enabled, and waits for them to come up
3. Returns with agent metadata, without waiting for a proxy connection
4. Connects to the proxy and registers paths in the background
5. Keeps running in the background, processing scrape requests

Your application code runs normally while the agent handles metrics scraping in the background.

The embedded agent's metrics register in the Prometheus Java client 1.x default registry
(`io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry`). A host that serves its own metrics
from that registry exposes the agent's too; a host still on the 0.x client (`io.prometheus:simpleclient`) does
not see them. Every agent in the JVM, two at once or one started again after `shutdown()`, shares one set of agent
metrics, and each series carries the agent's `launch_id`. An agent's series go when it shuts down. A host metric
with one of the agent metrics' names makes `startAsyncAgent()` throw.

The agent's `logLevel` (`agent.logLevel`, `AGENT_LOG_LEVEL`, or `--log_level`) sets the level of the `io.prometheus`
loggers only, so it doesn't change your application's own logging. A standalone agent sets the root logger's level.

!!! tip "Config file location"

    The config file path is relative to your application's working directory.
    Use an absolute path if needed.

## Logging

Your application configures the agent's logging. The agent logs through SLF4J to Logback, and the library jar carries
no Logback configuration, so your own `logback.xml` (or, in Spring Boot, `logback-spring.xml` and the `logging.level`
properties) applies to the agent's loggers as it does to the rest. With no configuration at all, Logback logs
everything at DEBUG, gRPC and Netty included.

The standalone agent's configuration sets `io.grpc.internal` to WARN, so that only gRPC's internal warnings and errors
reach its log; you may want the same:

```xml
<logger name="io.grpc.internal" level="WARN"/>
```

In Spring Boot, set `logging.level.io.grpc.internal=WARN` instead.

## Upgrading from 4.4.x

- `startAsyncAgent()` throws `ConfigLoadException` for a config that loads but holds a setting the agent can't use,
  as listed under [`Agent.startAsyncAgent()`](#agentstartasyncagent). Through 4.4.2 those escaped as an
  `IllegalArgumentException` or a Typesafe `ConfigException`, and a `ConfigException`'s message began with the config
  URL, credentials included. Code that caught either should catch `ConfigLoadException`; the
  `IllegalArgumentException` is now its cause.
- The library jar no longer carries a `logback.xml`. Through 4.4.2 it did, and Logback, and Spring Boot ahead of
  your `logback-spring.xml`, took it for your application's configuration. An application with no Logback
  configuration of its own now gets Logback's default, which logs everything at DEBUG, so add one (see
  [Logging](#logging)); one whose own configuration was being replaced gets it back.
- Maven builds now get Jackson 2.23.0 and netty-tcnative 2.0.86.Final, natives included, through the agent, where
  they got Jackson 2.12.7 and the 2.0.84.Final natives. A Jackson your build declares itself still wins.
- The agent's generated gRPC classes need protobuf-java 4.36.2 or later, which Maven and Gradle builds now resolve
  through it, where they got 3.25.9. Your own code generated by protoc 3.x keeps working on it. A build that pins
  protobuf-java to 3.x must move the pin to 4.36.2 or later, or the agent fails when it connects, with a
  `NoClassDefFoundError` for `com.google.protobuf.RuntimeVersion`.

## Upgrading from 4.1.x

- The agent moved from the Prometheus Java client 0.x (`io.prometheus:simpleclient` 0.16.0) to 1.x
  (`io.prometheus:prometheus-metrics-core` 1.9.0), and its metrics now register in the 1.x default registry. A
  host that serves the 0.x `CollectorRegistry` no longer exposes them. To serve both from one endpoint, serve the
  1.x registry and bridge your 0.x metrics into it: add `io.prometheus:prometheus-metrics-simpleclient-bridge`,
  at the same version as the `prometheus-metrics-core` the agent brings, and call
  `SimpleclientCollector.builder().register()` once at startup.
- `simpleclient` no longer arrives through the agent. If your application relied on getting it that way,
  declare it yourself.
- With the agent's metrics endpoint on, the `_created` series of its counters and histograms are gone (set
  `IO_PROMETHEUS_EXPORTER_INCLUDE_CREATED_TIMESTAMPS=true` to bring them back), and with the JVM exports on, the
  JVM memory metrics are renamed (see [JVM Metrics](monitoring.md#jvm-metrics)).

## Upgrading from 4.0.x

- `grpc-netty-shaded` no longer arrives through the agent, which uses the unshaded `grpc-netty` transport.
  If your application relied on getting it that way, declare it yourself.
- `startAsyncAgent()` now waits for the agent to start and throws if it fails, where it used to return a
  handle to an agent that had failed (see [`Agent.startAsyncAgent()`](#agentstartasyncagent)).
- When a config URL's error message has to be redacted, the `ConfigLoadException`'s cause is a stand-in
  exception with the redacted message and the original stack trace, not Typesafe's `ConfigException`, so
  code that checks `cause is ConfigException` no longer matches it.
