---
icon: lucide/play
---

# Quick Start

**Requirements:** Java 17 or newer

## Installation

=== "Download JARs"

    Download the latest proxy and agent JAR files from the
    [GitHub Releases](https://github.com/pambrose/prometheus-proxy/releases) page.

=== "Build from Source"

    ```bash
    git clone https://github.com/pambrose/prometheus-proxy.git
    cd prometheus-proxy
    ./gradlew shadowJar
    ```

    JARs are generated in `build/libs/`:

    - `build/libs/prometheus-proxy.jar`
    - `build/libs/prometheus-agent.jar`

=== "Docker"

    Multi-platform images (amd64, arm64, s390x) are available on Docker Hub:

    ```bash
    docker pull pambrose/prometheus-proxy:3.1.0
    docker pull pambrose/prometheus-agent:3.1.0
    ```

## Start the Proxy

The proxy runs outside the firewall alongside your Prometheus server.

=== "CLI"

    ```bash
    java -jar prometheus-proxy.jar
    ```

    The proxy starts with default settings:

    - HTTP scrape port: **8080**
    - gRPC agent port: **50051**

=== "Docker"

    ```bash
    docker run --rm -p 8080:8080 -p 50051:50051 \
      pambrose/prometheus-proxy:3.1.0
    ```

## Start the Agent

The agent runs inside the firewall with your monitored services. It needs a configuration file
that specifies which metrics endpoints to expose.

### Create an Agent Config

Create a file called `agent.conf`:

```hocon
agent {
  proxy.hostname = "proxy-host.example.com"

  pathConfigs: [
    {
      name: "My App metrics"
      path: my_app_metrics
      url: "http://localhost:9100/metrics"
    }
  ]
}
```

Each entry in `pathConfigs` maps:

- `path` -- the URL path on the proxy that Prometheus will scrape
- `url` -- the actual metrics endpoint the agent will fetch from

### Start the Agent

=== "CLI"

    ```bash
    java -jar prometheus-agent.jar --config agent.conf
    ```

    Or specify the proxy hostname on the command line:

    ```bash
    java -jar prometheus-agent.jar \
      --proxy proxy-host.example.com \
      --config agent.conf
    ```

=== "Docker"

    ```bash
    docker run --rm \
      --mount type=bind,source="$(pwd)"/agent.conf,target=/app/agent.conf \
      --env AGENT_CONFIG=agent.conf \
      --env PROXY_HOSTNAME=proxy-host.example.com \
      pambrose/prometheus-agent:3.1.0
    ```

=== "Remote Config"

    The agent can load configuration from a URL:

    ```bash
    java -jar prometheus-agent.jar \
      --proxy proxy-host.example.com \
      --config https://example.com/configs/agent.conf
    ```

## Configure Prometheus

Add scrape targets pointing to the proxy:

```yaml
scrape_configs:
  - job_name: 'my-app'
    metrics_path: '/my_app_metrics'
    static_configs:
      - targets: ['proxy-host.example.com:8080']
```

The `metrics_path` must match the `path` value in the agent's `pathConfigs`.

## Verify

Check that metrics are flowing through the proxy:

```bash
curl -s http://proxy-host.example.com:8080/my_app_metrics | head
```

## Multiple Endpoints

An agent can serve multiple metrics endpoints. Each gets its own path on the proxy:

```hocon
--8<-- "ConfigExamples.txt:path-config-multi"
```

The corresponding Prometheus configuration:

```yaml
--8<-- "PrometheusConfigs.txt:multi-target-scrape"
```

## Authentication

When Prometheus scrape configs include `basic_auth` or `bearer_token`, the proxy forwards the
`Authorization` header to the agent, which includes it when fetching from the target endpoint.

```yaml
--8<-- "PrometheusConfigs.txt:auth-scrape-config"
```

!!! warning "Enable TLS for auth forwarding"

    Without TLS, authorization headers are transmitted in plaintext between proxy and agent.
    See [Security](security/index.md) for TLS setup instructions.

## Next Steps

<div class="grid cards" markdown>

-   :material-robot:{ .lg .middle } __Agent Configuration__

    ---

    All agent settings including path configs, HTTP client, and scrape options

    [:octicons-arrow-right-24: Agent Configuration](configuration/agent.md)

-   :material-server:{ .lg .middle } __Proxy Configuration__

    ---

    All proxy settings including HTTP service, gRPC, and service discovery

    [:octicons-arrow-right-24: Proxy Configuration](configuration/proxy.md)

-   :material-docker:{ .lg .middle } __Docker Usage__

    ---

    Production Docker setups and docker-compose examples

    [:octicons-arrow-right-24: Docker](docker.md)

-   :material-shield-lock:{ .lg .middle } __Security & TLS__

    ---

    Secure the proxy-agent connection with TLS and mutual authentication

    [:octicons-arrow-right-24: Security](security/index.md)

</div>
