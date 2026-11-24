---
icon: lucide/house
# The page has five sections, which a scroll finds; its Try It agent command, one line of which is mostly a long URL,
# takes the table of contents' width to fit
hide:
  - toc
---

# Prometheus Proxy

**Enable Prometheus to scrape metrics endpoints behind firewalls.**

[Prometheus](https://prometheus.io) uses a pull model for collecting metrics. This is problematic when a firewall
separates the Prometheus server from its metrics endpoints. **Prometheus Proxy** solves this by using a persistent
gRPC connection initiated from inside the firewall, preserving Prometheus's native pull-based architecture.

## How It Works

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph TB
  accTitle: How Prometheus Proxy works
  accDescr {
    Outside the firewall, Prometheus scrapes the proxy over HTTP. Inside the firewall, the agent connects out to the
    proxy over a gRPC stream, and fetches metrics over HTTP from three apps, each at :9100/metrics.
  }
  P[Prometheus] -->|HTTP scrape| Proxy
  %% The agent opens the gRPC stream, so its arrow points up, into the proxy. Mermaid has no reverse arrow, and an
  %% Agent --> Proxy edge pinned under the proxy by an invisible link drew a second edge beside it, which bent this
  %% one and set it off the boxes' centers. A two-headed edge keeps the proxy above the agent on one straight line,
  %% and the linkStyle below (this is edge 1, counting from 0) drops its arrowhead at the agent's end.
  Proxy <-->|gRPC stream| Agent
  %% Mermaid draws the first of these edges in the middle, the second to its right, and the third to its left, so
  %% App 2's comes first and the apps read 1, 2, 3.
  Agent -->|HTTP fetch| E2[App 2 :9100/metrics]
  Agent -->|HTTP fetch| E3[App 3 :9100/metrics]
  Agent -->|HTTP fetch| E1[App 1 :9100/metrics]

  %% Each zone's title starts with a line break, which grows the zone to fit it, so the title stands clear of the
  %% zone's top edge. Mermaid's subGraphTitleMargin moved the titles down without growing the zones, and moved the
  %% edges' labels onto their arrowheads.
  subgraph Outside ["<br>Outside Firewall"]
    P
    Proxy
  end

  %% The gRPC edge runs up the middle, where Mermaid centers a zone's title, so trailing em spaces set this title
  %% to the left of the edge rather than under it.
  subgraph Inside ["<br>Inside Firewall#8195;#8195;#8195;#8195;#8195;#8195;#8195;#8195;#8195;#8195;"]
    Agent
    E1
    E2
    E3
  end

  linkStyle 1 marker-end:none
```

The system comprises two components:

- **Proxy** — runs outside the firewall alongside Prometheus. Accepts scrape requests from Prometheus
  on HTTP (port 8080) and communicates with agents via gRPC (port 50051).
- **Agent** — runs inside the firewall with monitored services. Initiates an *outbound* gRPC
  connection to the proxy and responds to scrape requests by fetching metrics from local endpoints.

## Benefits

- **Firewall-friendly** — only requires an outbound connection from the agent
- **Preserves pull model** — Prometheus continues to pull metrics as normal
- **High performance** — built with Kotlin coroutines and gRPC streaming
- **Secure** — optional TLS with mutual authentication, plus
  [per-agent identities](security/index.md#per-agent-identities-and-path-authorization) that scope
  each agent to the paths it may register
- **Scalable** — one proxy supports many agents, each serving multiple paths
- **Highly available** — agents [fail over](production.md#high-availability) across an ordered list
  of proxies, and return to a recovered primary at their next reconnect
- **Dynamic** — agents pick up target changes from a
  [watched file](configuration/agent.md#dynamic-target-discovery) at runtime, no restart needed
- **Bandwidth-conscious** — optional per-path
  [metric filtering](configuration/agent.md#metric-filtering) drops unwanted families at the agent,
  before they cross the WAN
- **Observable** — a [live dashboard](web-dashboard.md) shows every agent, path, and recent
  scrape in one place, and scrapes a path on demand
- **Embeddable** — the agent can [run inside a JVM app](embedded-agent.md), with its settings
  built in code, so an app on a [cloud platform](cloud-platforms.md) needs no secret files
- **Zero changes** to existing Prometheus configuration patterns

## Use Cases

| Scenario                    | Description                                                   |
|:----------------------------|:--------------------------------------------------------------|
| **Enterprise environments** | Scrape metrics across corporate firewall boundaries           |
| **Multi-cloud deployments** | Bridge different network segments                             |
| **Secure environments**     | Monitor internal services without opening inbound ports       |
| **Federation**              | Scrape existing Prometheus instances via `/federate` endpoint |
| **Kubernetes**              | Monitor services across clusters or namespaces                |
| **Multi-team proxies**      | Share one proxy across teams, each scoped to its own paths    |
| **Dynamic fleets**          | Reconcile churning targets from a generated file, no restarts |

## Try It

On one machine, with Docker, in three terminals. The agent serves two paths through the proxy, the proxy's metrics and
its own, so there's nothing else to set up.

```bash title="Terminal 1: the proxy"
# A network for the two containers, so the agent reaches the proxy by its name
docker network create prometheus-proxy-net

docker run --rm --name prometheus-proxy --network prometheus-proxy-net \
  -p 8080:8080 -p 50051:50051 --env METRICS_ENABLED=true \
  pambrose/prometheus-proxy:4.7.0
```

```bash title="Terminal 2: the agent"
docker run --rm --network prometheus-proxy-net \
  --env PROXY_HOSTNAME=prometheus-proxy \
  --env AGENT_CONFIG=https://raw.githubusercontent.com/pambrose/prometheus-proxy/4.7.0/examples/simple.conf \
  pambrose/prometheus-agent:4.7.0
```

```bash title="Terminal 3: scrape through the proxy"
curl http://localhost:8080/proxy_metrics
curl http://localhost:8080/agent_metrics
```

Each `curl` prints metrics in Prometheus's text format, which the agent fetched and sent back through the proxy.
Ctrl+C stops each container, and `docker network rm prometheus-proxy-net` then removes the network.
{ .pp-result }

## Next Steps

<div class="pp-ways" markdown>

- [Try a demo](basics-demo.md)

    The Basics Demo runs the proxy, three agents behind a simulated firewall, Prometheus, and Grafana with one
    `make up`, and a guided tour walks you through it. The Demos page lists six more: a simulated WAN, nginx,
    Kubernetes, and three apps that embed the agent.

- [Install it](getting-started.md)

    Run the proxy beside Prometheus and an agent beside your services, from the JAR, Homebrew, Docker, or Helm, and
    point Prometheus at the proxy.

- [Onboard a team](security/index.md#onboarding-a-team)

    Give each team's agents an identity of their own, scoped to the paths they may register, so one proxy serves every
    team.

</div>
