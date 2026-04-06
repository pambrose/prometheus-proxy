---
icon: lucide/layers
---

# Architecture

## Components

### Proxy

The Proxy runs outside the firewall alongside the Prometheus server. It provides:

- **HTTP Service** (default port 8080) -- serves metrics endpoints that Prometheus scrapes
- **gRPC Service** (default port 50051) -- accepts persistent connections from agents
- **Service Discovery** -- optional HTTP SD endpoint for Prometheus auto-discovery
- **Admin/Metrics** -- optional health checks and operational metrics

### Agent

The Agent runs inside the firewall with the monitored services. It provides:

- **gRPC Client** -- establishes and maintains an *outbound* connection to the proxy
- **HTTP Client** -- scrapes actual metrics endpoints on behalf of the proxy
- **Path Registration** -- registers configured endpoints with the proxy
- **Heartbeat** -- sends periodic keep-alive messages to maintain the connection

## Request Flow

``` mermaid
sequenceDiagram
    participant P as Prometheus
    participant Proxy as Proxy :8080
    participant gRPC as Proxy gRPC :50051
    participant Agent as Agent
    participant App as App :9100/metrics

    Note over Agent, gRPC: Agent connects on startup (outbound)
    Agent->>gRPC: connectAgent()
    Agent->>gRPC: registerAgent()
    Agent->>gRPC: registerPath("/app_metrics")

    Note over P, Proxy: Prometheus scrapes as normal
    P->>Proxy: GET /app_metrics
    Proxy->>gRPC: Lookup agent for path
    gRPC->>Agent: ScrapeRequest (via stream)
    Agent->>App: GET /metrics
    App-->>Agent: Metrics response
    Agent-->>gRPC: ScrapeResponse (via stream)
    gRPC-->>Proxy: Route response
    Proxy-->>P: Return metrics
```

## gRPC Protocol

The communication between Proxy and Agent uses a bidirectional gRPC protocol defined in
`proxy_service.proto`. Key RPCs:

| RPC | Type | Description |
|:----|:-----|:------------|
| `connectAgent` | Unary | Agent announces its presence |
| `registerAgent` | Unary | Register agent identity and metadata |
| `registerPath` | Unary | Register a scrape path with optional labels |
| `unregisterPath` | Unary | Remove a previously registered path |
| `readRequestsFromProxy` | Server-streaming | Proxy sends scrape requests to agent |
| `writeResponsesToProxy` | Client-streaming | Agent sends scrape responses back |
| `writeChunkedResponsesToProxy` | Client-streaming | Agent sends chunked responses for large payloads |
| `sendHeartBeat` | Unary | Keep-alive during periods of inactivity |

## Chunking

Large metric responses are automatically split into chunks to stay within gRPC message size limits.

- The agent checks if a response exceeds `chunkContentSizeKbs` (default: 32 KB)
- If so, the response is split into `ChunkedScrapeResponse` messages containing:
    - A **header** with metadata (status, content type, URL)
    - One or more **chunks** with payload bytes and CRC32 checksums
    - A **summary** with total byte count and final checksum for validation
- The proxy reassembles chunks and validates integrity before responding to Prometheus

Configure the chunk threshold:

```hocon
agent.chunkContentSizeKbs = 64   // Larger chunks for big payloads
```

## Compression

Responses exceeding `minGzipSizeBytes` (default: 512 bytes) are gzip-compressed before transmission
over gRPC, reducing bandwidth between agent and proxy.

```hocon
agent.minGzipSizeBytes = 256     // Compress more aggressively
```

## Heartbeat

The agent sends periodic heartbeat messages to maintain the gRPC connection during periods of
inactivity. This prevents load balancers and firewalls from closing idle connections.

```hocon
agent.internal {
  heartbeatEnabled = true
  heartbeatCheckPauseMillis = 500
  heartbeatMaxInactivitySecs = 5   // Send heartbeat after 5s of inactivity
}
```

## Connection Lifecycle

1. **Startup** -- Agent reads configuration and initializes services
2. **Connection** -- Agent establishes outbound gRPC connection to proxy
3. **Registration** -- Agent registers its identity and configured paths
4. **Operation** -- Agent processes scrape requests and sends heartbeats
5. **Reconnection** -- On disconnect, agent automatically reconnects with rate limiting
6. **Shutdown** -- Graceful cleanup of connections and resources

## Stale Agent Cleanup

The proxy runs a background service that evicts agents that have been inactive for too long:

```hocon
proxy.internal {
  staleAgentCheckEnabled = true
  maxAgentInactivitySecs = 60       // Evict after 60s of inactivity
  staleAgentCheckPauseSecs = 10     // Check every 10s
}
```

## Consolidated Mode

By default, each path is owned by a single agent. In **consolidated mode**, multiple agents can
register the same path for redundancy. When a scrape request arrives for a consolidated path,
the proxy routes the request to one of the available agents.

```hocon
agent.consolidated = true
```

See [Advanced Topics](advanced.md) for more details.
