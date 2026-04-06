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
    --8<-- "EmbeddedAgentExamples.txt:embedded-java"
    ```

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentExamples.txt:embedded-kotlin"
    ```

## Agent Configuration

The embedded agent uses the same HOCON configuration as the standalone agent:

```hocon
--8<-- "EmbeddedAgentExamples.txt:embedded-agent-config"
```

## API Reference

### `Agent.startAsyncAgent()`

Starts the agent in background threads/coroutines.

| Parameter | Type | Description |
|:----------|:-----|:------------|
| `configFilename` | `String` | Path to the HOCON config file |
| `exitOnMissingConfig` | `Boolean` | Exit the JVM if config file is not found |
| `logBanner` | `Boolean` | Log the startup banner (default: `true`) |

**Returns:** `EmbeddedAgentInfo`

### `EmbeddedAgentInfo`

| Property | Type | Description |
|:---------|:-----|:------------|
| `launchId` | `String` | Unique ID for this agent launch |
| `agentName` | `String` | Name of the agent |

## How It Works

When you call `startAsyncAgent()`:

1. The agent reads the configuration file
2. Starts background coroutines for gRPC communication
3. Connects to the proxy and registers paths
4. Returns immediately with agent metadata
5. The agent continues running in the background, processing scrape requests

Your application code runs normally while the agent handles metrics scraping in the background.

!!! tip "Config file location"

    The config file path is relative to your application's working directory.
    Use an absolute path if needed.
