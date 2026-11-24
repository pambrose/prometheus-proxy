---
# The page has two sections, which a scroll finds; its Try It agent command, one line of which is mostly a long URL,
# takes the table of contents' width to fit
hide:
  - toc
---

# Prometheus Proxy

Prometheus scrapes endpoints behind a firewall, still by pull, with no inbound firewall rule.
{ .pp-lede }

<!-- The social card's flow line (social-card/card.html), the firewall drawn as the accent's wall between the two
     zones; stylesheets/extra.css stacks the zones, the wall between them, where the line doesn't fit across. -->
<div class="pp-flow">
  <div class="pp-flow__diagram" role="img" aria-label="Prometheus, outside the firewall, scrapes the proxy. The agent, inside the firewall, connects out to the proxy over gRPC and fetches each endpoint's /metrics.">
    <div class="pp-flow__side">
      <span class="pp-flow__zone">outside the firewall</span>
      <span class="pp-flow__line"><b>Prometheus</b> ──▶ <b>proxy</b> ◀── gRPC ──</span>
    </div>
    <div class="pp-flow__wall"></div>
    <div class="pp-flow__side">
      <span class="pp-flow__zone">inside the firewall</span>
      <span class="pp-flow__line">── <b>agent</b> ──▶ <b>/metrics</b></span>
    </div>
  </div>
</div>

Prometheus pulls, so it can't reach an endpoint on the far side of a firewall. Prometheus Proxy turns the connection
around and leaves the pull alone: an agent inside the firewall connects out to a proxy beside Prometheus, on port
50051, and Prometheus scrapes the proxy on port 8080 as it would any target. The proxy hands each scrape to the agent,
which fetches the endpoint and sends the result back over its connection.

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
