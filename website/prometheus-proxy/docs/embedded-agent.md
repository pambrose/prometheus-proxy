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

!!! tip "Config file location"

    The config file path is relative to your application's working directory.
    Use an absolute path if needed.
