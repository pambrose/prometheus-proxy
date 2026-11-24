# Demos

Seven self-contained demos run the proxy, its agents, and Prometheus on your laptop from the released images. Each
starts with `make up` in its directory under `demos/`, and a guided tour walks you through it. All but the Kubernetes
Demo need only Docker. Start with the Basics Demo.

## The Proxy and Its Agents

| Demo                                          | What it shows                                                                                                               | Needs                         |
|:----------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------|:------------------------------|
| [Basics Demo](../basics-demo.md)              | The whole system on one machine: the proxy and its dashboard, three agents behind a simulated firewall, Prometheus, Grafana | Docker                        |
| [Network Demo](../network-demo.md)            | A simulated WAN between the agents and the proxies: what a far, thin, dead, flaky, or missing link does, and how they cope  | Docker                        |
| [nginx Demo](../nginx-demo.md)                | nginx, a reverse proxy, between the agents and the proxy: its stream timeouts, its reloads, and TLS ended on one port        | Docker                        |
| [Kubernetes Demo](../kubernetes-demo.md)      | The Helm charts on two kind clusters, with Kubernetes doing the operating and the target cluster exposing nothing           | Docker, kind, `kubectl`, Helm |

## The Agent Embedded in an App

| Demo                                          | What it shows                                                                                                    | Needs  |
|:----------------------------------------------|:-----------------------------------------------------------------------------------------------------------------|:-------|
| [Ktor Demo](../ktor-demo.md)                  | A coffee shop, a Ktor app that runs the agent in its own JVM through the [Ktor plugin](../ktor-plugin.md)         | Docker |
| [Spring Boot Demo](../spring-boot-demo.md)    | A roastery, a Spring Boot app in Java whose agent the [starter](../spring-boot-starter.md) starts from properties | Docker |
| [Plain Kotlin Demo](../plain-kotlin-demo.md)  | A bakery's ovens, workers with no framework or HTTP server, each reading its own metrics in-process              | Docker |
