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
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph LR
  P[Prometheus] -->|HTTP scrape| Proxy
  %% The agent initiates the outbound gRPC connection, so the arrow points back to the proxy. The
  %% invisible link pins Proxy left of Agent -- mermaid has no single reverse arrow to do it directly,
  %% and curve:linear (above) keeps the resulting back-edge as straight as it can be drawn.
  Proxy ~~~ Agent
  Agent -->|gRPC stream| Proxy
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
- **Secure** -- optional TLS with mutual authentication, plus
  [per-agent identities](security/index.md#per-agent-identities-and-path-authorization) that scope
  each agent to the paths it may register
- **Scalable** -- one proxy supports many agents, each serving multiple paths
- **Highly available** -- agents [fail over](production.md#high-availability) across an ordered list
  of proxies, and return to the primary when it recovers
- **Dynamic** -- agents pick up target changes from a
  [watched file](configuration/agent.md#dynamic-target-discovery) at runtime, no restart needed
- **Bandwidth-conscious** -- optional per-path
  [metric filtering](configuration/agent.md#metric-filtering) drops unwanted families at the agent,
  before they cross the WAN
- **Observable** -- a read-only [live dashboard](web-dashboard.md) shows every agent, path, and
  recent scrape in one place
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
      pambrose/prometheus-proxy:4.0.0

    # Start the agent
    docker run --rm \
      --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
      pambrose/prometheus-agent:4.0.0
    ```

See the [Quick Start Guide](getting-started.md) for detailed instructions.

## Common Use Cases

| Scenario                    | Description                                                   |
|:----------------------------|:--------------------------------------------------------------|
| **Enterprise environments** | Scrape metrics across corporate firewall boundaries           |
| **Multi-cloud deployments** | Bridge different network segments                             |
| **Secure environments**     | Monitor internal services without opening inbound ports       |
| **Federation**              | Scrape existing Prometheus instances via `/federate` endpoint |
| **Kubernetes**              | Monitor services across clusters or namespaces                |
| **Multi-team proxies**      | Share one proxy across teams, each scoped to its own paths    |
| **Dynamic fleets**          | Reconcile churning targets from a generated file, no restarts |

## API Reference

Full API documentation (KDocs) is available at [KDocs](kdocs/).

## Next Steps

<div class="grid cards" markdown>

-   :material-sitemap:{ .lg .middle } __Architecture__

    ---

    Understand the proxy/agent components, gRPC protocol, and request flow

    [:octicons-arrow-right-24: Architecture](architecture.md)

-   :material-cog:{ .lg .middle } __Configuration__

    ---

    Configure the application with HOCON and environment variables

    [:octicons-arrow-right-24: Configuration](configuration/index.md)

-   :material-shield-lock:{ .lg .middle } __Security & TLS__

    ---

    Set up TLS encryption and mutual authentication

    [:octicons-arrow-right-24: Security](security/index.md)

-   :material-chart-line:{ .lg .middle } __Monitoring__

    ---

    Built-in metrics and Grafana dashboards

    [:octicons-arrow-right-24: Monitoring](monitoring.md)

</div>
