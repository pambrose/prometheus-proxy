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

## API Reference

### `Agent.startAsyncAgent()`

Starts the agent in background threads/coroutines.

| Parameter             | Type      | Description                              |
|:----------------------|:----------|:-----------------------------------------|
| `configFilename`      | `String`  | Path to the HOCON config file            |
| `exitOnMissingConfig` | `Boolean` | Exit the JVM if config file is not found |
| `logBanner`           | `Boolean` | Log the startup banner (default: `true`) |

**Returns:** `EmbeddedAgentInfo`

**Throws:** `ConfigLoadException` when the config cannot be loaded and `exitOnMissingConfig` is `false`;
`IllegalStateException` when the agent fails to start (for example, its admin or metrics port is already in
use), with the startup failure as its cause. A failed start releases everything the agent had opened.

### `EmbeddedAgentInfo`

| Property    | Type     | Description                     |
|:------------|:---------|:--------------------------------|
| `launchId`  | `String` | Unique ID for this agent launch |
| `agentName` | `String` | Name of the agent               |

## How It Works

When you call `startAsyncAgent()`:

1. The agent reads the configuration file
2. Starts its admin and metrics servers, if enabled, and waits for them to come up
3. Returns with agent metadata, without waiting for a proxy connection
4. Connects to the proxy and registers paths in the background
5. Keeps running in the background, processing scrape requests

Your application code runs normally while the agent handles metrics scraping in the background.

The embedded agent's metrics register in the Prometheus Java client 1.x default registry
(`io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry`). A host that serves its own metrics
from that registry exposes the agent's too; a host still on the 0.x client (`io.prometheus:simpleclient`) does
not see them.

!!! tip "Config file location"

    The config file path is relative to your application's working directory.
    Use an absolute path if needed.

## Upgrading from 4.1.x

- The agent moved from the Prometheus Java client 0.x (`io.prometheus:simpleclient` 0.16.0) to 1.x
  (`io.prometheus:prometheus-metrics-core` 1.9.0), and its metrics now register in the 1.x default registry. A
  host that serves the 0.x `CollectorRegistry` no longer exposes them. To serve both from one endpoint, serve the
  1.x registry and bridge your 0.x metrics into it: add `io.prometheus:prometheus-metrics-simpleclient-bridge`
  1.9.0 and call `SimpleclientCollector.builder().register()` once at startup.
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
