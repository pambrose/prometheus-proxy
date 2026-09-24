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

=== "Homebrew"

    On macOS and Linux, the proxy and the agent are available from Homebrew. Each formula installs
    the Java it runs on (`openjdk@25`) and puts its command on your `PATH`:

    ```bash
    brew install pambrose/tap/prometheus-proxy
    brew install pambrose/tap/prometheus-agent
    ```

=== "Docker"

    Multi-platform images (amd64, arm64, s390x) are available on Docker Hub:

    ```bash
    docker pull pambrose/prometheus-proxy:4.1.0
    docker pull pambrose/prometheus-agent:4.1.0
    ```

## Start the Proxy

The proxy runs outside the firewall alongside your Prometheus server.

=== "CLI"

    **In the foreground**, the proxy runs in your terminal and logs there until you stop it with
    Ctrl+C:

    ```bash
    java -jar prometheus-proxy.jar
    ```

    The proxy starts with default settings:

    - HTTP scrape port: **8080**
    - gRPC agent port: **50051**

    **In the background**, `nohup` keeps the proxy running after you close the terminal. Send its
    output to a log file and save its process ID so you can stop it later:

    ```bash
    nohup java -jar prometheus-proxy.jar > proxy.log 2>&1 &
    echo $! > proxy.pid

    tail -f proxy.log            # follow its log
    kill "$(cat proxy.pid)"      # stop it
    ```

    Nothing restarts it if it exits or the machine reboots. For that, use the Homebrew or Docker
    tab, or run it under your system's service manager, as in
    [Running as a service](production.md#running-as-a-service).

=== "Homebrew"

    **In the foreground**, the proxy runs in your terminal and logs there until you stop it with
    Ctrl+C. With no arguments it uses the default ports above:

    ```bash
    prometheus-proxy
    ```

    To try the config the background service uses, pass it with `--config`:

    ```bash
    prometheus-proxy --config "$(brew --prefix)/etc/prometheus-proxy.conf"
    ```

    **In the background**, `brew services` runs the proxy with
    `$(brew --prefix)/etc/prometheus-proxy.conf`, which the formula installs as a starting point, and
    starts it again at login. Set the ports and agent authentication there before starting it:

    ```bash
    brew services start prometheus-proxy     # start now and at every login
    brew services info prometheus-proxy      # check that it is running
    brew services restart prometheus-proxy   # pick up an edited config
    brew services stop prometheus-proxy      # stop, and no longer start at login
    ```

    The service logs to `$(brew --prefix)/var/log/prometheus-proxy.log`:

    ```bash
    tail -f "$(brew --prefix)/var/log/prometheus-proxy.log"
    ```

=== "Docker"

    **In the foreground**, the container runs attached to your terminal and logs there until you stop
    it with Ctrl+C; `--rm` then removes it:

    ```bash
    docker run --rm -p 8080:8080 -p 50051:50051 \
      pambrose/prometheus-proxy:4.1.0
    ```

    **In the background**, `--detach` starts the container and returns, and
    `--restart unless-stopped` starts it again if it exits or Docker restarts, until you stop it.
    Docker doesn't allow `--rm` with `--restart`, so the stopped container stays until you remove it:

    ```bash
    docker run --detach --name prometheus-proxy --restart unless-stopped \
      -p 8080:8080 -p 50051:50051 \
      pambrose/prometheus-proxy:4.1.0

    docker logs --follow prometheus-proxy   # follow its log
    docker restart prometheus-proxy         # restart it
    docker stop prometheus-proxy            # stop it, and no longer restart it
    docker rm prometheus-proxy              # remove the stopped container
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

    **In the foreground**, the agent runs in your terminal and logs there until you stop it with
    Ctrl+C:

    ```bash
    java -jar prometheus-agent.jar --config agent.conf
    ```

    Or specify the proxy hostname on the command line:

    ```bash
    java -jar prometheus-agent.jar \
      --proxy proxy-host.example.com \
      --config agent.conf
    ```

    **In the background**, `nohup` keeps the agent running after you close the terminal. Send its
    output to a log file and save its process ID so you can stop it later:

    ```bash
    nohup java -jar prometheus-agent.jar --config agent.conf > agent.log 2>&1 &
    echo $! > agent.pid

    tail -f agent.log            # follow its log
    kill "$(cat agent.pid)"      # stop it
    ```

    To pick up an edited `agent.conf`, stop the agent and start it again. Nothing restarts it if it
    exits or the machine reboots. For that, use the Homebrew or Docker tab, or run it under your
    system's service manager, as in [Running as a service](production.md#running-as-a-service).

=== "Homebrew"

    **In the foreground**, the agent runs in your terminal and logs there until you stop it with
    Ctrl+C:

    ```bash
    prometheus-agent --config agent.conf
    ```

    Or specify the proxy hostname on the command line:

    ```bash
    prometheus-agent --proxy proxy-host.example.com --config agent.conf
    ```

    **In the background**, `brew services` runs the agent with
    `$(brew --prefix)/etc/prometheus-agent.conf` and starts it again at login. The formula installs
    a starting point there with no paths, so copy your `agent.conf` over it first. The service passes
    no `--proxy`, so the file's `proxy.hostname` decides which proxy the agent connects to:

    ```bash
    cp agent.conf "$(brew --prefix)/etc/prometheus-agent.conf"

    brew services start prometheus-agent     # start now and at every login
    brew services info prometheus-agent      # check that it is running
    brew services restart prometheus-agent   # pick up an edited config
    brew services stop prometheus-agent      # stop, and no longer start at login
    ```

    The service logs to `$(brew --prefix)/var/log/prometheus-agent.log`:

    ```bash
    tail -f "$(brew --prefix)/var/log/prometheus-agent.log"
    ```

=== "Docker"

    **In the foreground**, the container runs attached to your terminal and logs there until you stop
    it with Ctrl+C; `--rm` then removes it:

    ```bash
    docker run --rm \
      --mount type=bind,source="$(pwd)"/agent.conf,target=/app/agent.conf \
      --env AGENT_CONFIG=agent.conf \
      --env PROXY_HOSTNAME=proxy-host.example.com \
      pambrose/prometheus-agent:4.1.0
    ```

    **In the background**, `--detach` starts the container and returns, and
    `--restart unless-stopped` starts it again if it exits or Docker restarts, until you stop it.
    Docker doesn't allow `--rm` with `--restart`, so the stopped container stays until you remove it:

    ```bash
    docker run --detach --name prometheus-agent --restart unless-stopped \
      --mount type=bind,source="$(pwd)"/agent.conf,target=/app/agent.conf \
      --env AGENT_CONFIG=agent.conf \
      --env PROXY_HOSTNAME=proxy-host.example.com \
      pambrose/prometheus-agent:4.1.0

    docker logs --follow prometheus-agent   # follow its log
    docker restart prometheus-agent         # pick up an edited agent.conf
    docker stop prometheus-agent            # stop it, and no longer restart it
    docker rm prometheus-agent              # remove the stopped container
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
