---
icon: lucide/server
---

# Proxy Configuration

The proxy runs outside the firewall alongside Prometheus. It accepts scrape requests from
Prometheus on HTTP and communicates with agents via gRPC.

## HTTP Service

Configure the HTTP service that Prometheus scrapes:

```hocon
--8<-- "ConfigExamples.txt:proxy-http-config"
```

## gRPC Service

Configure the gRPC service that agents connect to:

```hocon
--8<-- "ConfigExamples.txt:proxy-grpc-config"
```

## Service Discovery

Enable Prometheus HTTP service discovery:

```hocon
--8<-- "ServiceDiscoveryExamples.txt:sd-enable-config"
```

See [Service Discovery](../service-discovery.md) for complete details.

## Admin Endpoints

Enable admin endpoints for health checks and debugging:

```hocon
proxy {
  admin {
    enabled = true
    port = 8092
    pingPath = "ping"
    versionPath = "version"
    healthCheckPath = "healthcheck"
    threadDumpPath = "threaddump"
    debugEnabled = false
  }
}
```

When enabled, the following endpoints are available:

| Endpoint | Description |
|:---------|:------------|
| `GET /ping` | Returns "pong" (liveness check) |
| `GET /healthcheck` | Returns health status JSON |
| `GET /version` | Returns version information |
| `GET /threaddump` | Returns JVM thread dump |
| `GET /debug` | Proxy debug info (requires `debugEnabled = true`) |

## Metrics

Enable internal metrics collection:

```hocon
--8<-- "MonitoringExamples.txt:enable-metrics-proxy"
```

See [Monitoring](../monitoring.md) for the complete metrics reference.

## Internal Settings

Configure agent cleanup and scrape request management:

```hocon
--8<-- "AdvancedExamples.txt:stale-agent-config"
```

| Setting | Default | Description |
|:--------|:--------|:------------|
| `staleAgentCheckEnabled` | true | Enable periodic stale agent cleanup |
| `maxAgentInactivitySecs` | 60 | Seconds of inactivity before agent is evicted |
| `staleAgentCheckPauseSecs` | 10 | Interval between cleanup checks |
| `scrapeRequestTimeoutSecs` | 90 | Timeout for individual scrape requests |

## Content Size Limits

```hocon
proxy.internal {
  maxZippedContentSizeMBytes = 5      // Max zipped content size
  maxUnzippedContentSizeMBytes = 10   // Max unzipped content size
}
```

## Transport Filter

The transport filter detects agent disconnections immediately. Disable it when using a
reverse proxy like Nginx:

```hocon
proxy.transportFilterDisabled = true
```

!!! warning

    With `transportFilterDisabled`, agent disconnections are not immediately detected.
    Agent contexts on the proxy are removed after the inactivity timeout
    (default: 60 seconds).

## gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is enabled by default for debugging
and tooling. Disable it in production if desired:

```hocon
proxy.reflectionDisabled = true
```

## Log Level

```hocon
--8<-- "ConfigExamples.txt:log-level-config"
```

## Full Reference

See the complete configuration schema:
[`config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf)
