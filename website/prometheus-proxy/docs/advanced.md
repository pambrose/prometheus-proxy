---
icon: lucide/wrench
---

# Advanced Topics

## Nginx Reverse Proxy

To use Nginx as a reverse proxy in front of the gRPC service, disable the transport filter
on both proxy and agent:

```text
--8<-- "AdvancedExamples.txt:nginx-cli"
```

!!! warning "Delayed disconnect detection"

    With `transportFilterDisabled`, agent disconnections are not immediately detected.
    Agent contexts on the proxy are removed after the inactivity timeout
    (default: 60 seconds, controlled by `proxy.internal.maxAgentInactivitySecs`).

Example Nginx and proxy configuration files are available in the repository:

- [`nginx/docker/nginx.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf)
- [`nginx/nginx-proxy.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/nginx-proxy.conf)

## Prometheus Federation

Scrape an existing Prometheus instance via the `/federate` endpoint:

```hocon
--8<-- "AdvancedExamples.txt:federation-config"
```

This leverages Prometheus's built-in federation support, allowing you to pull metrics from
another Prometheus server through the proxy.

A complete federation config is available at:
[`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf)

## Consolidated Mode

By default, each scrape path is owned by a single agent. If a second agent tries to register
the same path, it displaces the first agent.

In **consolidated mode**, multiple agents can register the same path for redundancy:

```hocon
agent.consolidated = true
```

When a scrape request arrives for a consolidated path, the proxy selects one of the available
agents. If one agent disconnects, the remaining agents continue serving the path.

Use cases:

- **High availability** -- multiple agents serving the same endpoints
- **Load distribution** -- spread scrape load across agents
- **Rolling upgrades** -- new agent registers before old one deregisters

## gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is enabled by default, allowing
tools like [grpcurl](https://github.com/fullstorydev/grpcurl) to inspect the service:

```text
--8<-- "AdvancedExamples.txt:grpc-reflection-list"
```

```text
--8<-- "AdvancedExamples.txt:grpc-reflection-describe"
```

!!! note

    When using grpcurl with the `-plaintext` option, ensure the proxy is running
    without TLS. When TLS is enabled, provide the appropriate certificate flags.

## Performance Tuning

### Concurrent Scraping

Increase the number of parallel scrapes for high-throughput scenarios:

=== "CLI"

    ```bash
    --8<-- "AdvancedExamples.txt:performance-tuning-cli"
    ```

=== "Config File"

    ```hocon
    --8<-- "AdvancedExamples.txt:performance-tuning-config"
    ```

### Key Tuning Parameters

| Parameter              | Default | Guidance                                          |
|:-----------------------|:--------|:--------------------------------------------------|
| `maxConcurrentClients` | 1       | Increase for many endpoints or slow targets       |
| `clientTimeoutSecs`    | 90      | Lower for fast-failing scrapes                    |
| `chunkContentSizeKbs`  | 32      | Increase for large payloads to reduce chunk count |
| `minGzipSizeBytes`     | 512     | Lower to compress more aggressively               |
| `scrapeTimeoutSecs`    | 15      | Increase for slow targets                         |
| `clientCache.maxSize`  | 100     | Increase if many unique auth credentials are used |

## gRPC Keepalive Tuning

Fine-tune gRPC keepalive behavior for specific network environments:

```hocon
--8<-- "AdvancedExamples.txt:grpc-keepalive-config"
```

See the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/) for detailed tuning advice.

## Stale Agent Cleanup

The proxy periodically checks for inactive agents and evicts them:

```hocon
--8<-- "AdvancedExamples.txt:stale-agent-config"
```

!!! info

    When `transportFilterDisabled` is `true`, stale agent cleanup is automatically
    force-enabled, regardless of the `staleAgentCheckEnabled` setting. This ensures
    leaked agent contexts are eventually cleaned up.

## Zipkin Tracing

Both proxy and agent support distributed tracing via Zipkin:

```hocon
proxy.internal.zipkin {
  enabled = true
  hostname = "zipkin.example.com"
  port = 9411
  path = "api/v2/spans"
  serviceName = "prometheus-proxy"
}

agent.internal.zipkin {
  enabled = true
  hostname = "zipkin.example.com"
  port = 9411
  path = "api/v2/spans"
  serviceName = "prometheus-agent"
}
```
