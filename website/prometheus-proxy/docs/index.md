---
icon: lucide/rocket
---

# Prometheus Proxy

**Enable Prometheus to scrape metrics endpoints behind firewalls.**

[Prometheus](https://prometheus.io) uses a pull model for collecting metrics. This is problematic when a firewall
separates the Prometheus server from its metrics endpoints. **Prometheus Proxy** solves this by using a persistent
gRPC connection initiated from inside the firewall, preserving Prometheus's native pull-based architecture.

## How It Works

``` mermaid
graph LR
  P[Prometheus] -->|HTTP scrape| Proxy
  Proxy -->|gRPC stream| Agent
  Agent -->|HTTP fetch| E1[App 1 :9100/metrics]
  Agent -->|HTTP fetch| E2[App 2 :9100/metrics]
  Agent -->|HTTP fetch| E3[App 3 :9100/metrics]

  subgraph Outside Firewall
    P
    Proxy
  end

  subgraph Inside Firewall
    Agent
    E1
    E2
    E3
  end
```

The system comprises two components:

- **Proxy** -- runs outside the firewall alongside Prometheus. Accepts scrape requests from Prometheus
  on HTTP (port 8080) and communicates with agents via gRPC (port 50051).
- **Agent** -- runs inside the firewall with monitored services. Initiates an *outbound* gRPC
  connection to the proxy and responds to scrape requests by fetching metrics from local endpoints.

## Key Benefits

- **Firewall-friendly** -- only requires an outbound connection from the agent
- **Preserves pull model** -- Prometheus continues to pull metrics as normal
- **High performance** -- built with Kotlin coroutines and gRPC streaming
- **Secure** -- optional TLS with mutual authentication
- **Scalable** -- one proxy supports many agents, each serving multiple paths
- **Zero changes** to existing Prometheus configuration patterns

## Quick Start

Get running in under a minute:

=== "CLI"

    ```bash
    # Start the proxy
    java -jar prometheus-proxy.jar

    # Start the agent
    java -jar prometheus-agent.jar \
      --proxy proxy-host.example.com \
      --config myapps.conf
    ```

=== "Docker"

    ```bash
    # Start the proxy
    docker run --rm -p 8080:8080 -p 50051:50051 \
      pambrose/prometheus-proxy:3.1.0

    # Start the agent
    docker run --rm \
      --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
      pambrose/prometheus-agent:3.1.0
    ```

See the [Quick Start Guide](getting-started.md) for detailed instructions.

## Common Use Cases

| Scenario | Description |
|:---------|:------------|
| **Enterprise environments** | Scrape metrics across corporate firewall boundaries |
| **Multi-cloud deployments** | Bridge different network segments |
| **Secure environments** | Monitor internal services without opening inbound ports |
| **Federation** | Scrape existing Prometheus instances via `/federate` endpoint |
| **Kubernetes** | Monitor services across clusters or namespaces |

## Learn More

- [Architecture](architecture.md) -- detailed component and protocol descriptions
- [Configuration](configuration/index.md) -- HOCON config format and options
- [Security & TLS](security/index.md) -- TLS setup and auth header forwarding
- [Monitoring](monitoring.md) -- built-in metrics and Grafana dashboards
- [KDoc API Reference](https://pambrose.github.io/prometheus-proxy/) -- generated API documentation
