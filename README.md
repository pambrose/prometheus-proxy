[![JitPack](https://jitpack.io/v/pambrose/prometheus-proxy.svg)](https://jitpack.io/#pambrose/prometheus-proxy)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://app.codacy.com/gh/pambrose/prometheus-proxy/dashboard)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)
[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081)](https://pinterest.github.io/ktlint/)

# Prometheus Proxy

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for
collecting metrics. The pull model is problematic when a firewall separates a Prometheus server and its metrics
endpoints.

[Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) enables Prometheus to scrape metrics endpoints running
behind a firewall and preserves the native pull-based model architecture.

## Table of Contents

- [Architecture](#-architecture)
- [Quick Start](#-quick-start)
- [Building from Source](#-building-from-source)
- [Configuration Examples](#-configuration-examples)
- [Docker Usage](#-docker-usage)
- [Advanced Features](#-advanced-features)
- [Monitoring & Observability](#-monitoring--observability)
- [Configuration Options](#-configuration-options)
- [Security & TLS](#-security--tls)
- [Troubleshooting](#-troubleshooting)
- [License](#-license)

## 🏗️ Architecture

The `prometheus-proxy` runtime comprises two services:

* `proxy`: runs in the same network domain as Prometheus server (outside the firewall) and proxies calls from Prometheus
  to the `agent` behind the firewall.
* `agent`: runs in the same network domain as all the monitored hosts/services/apps (inside the firewall). It maps the
  scraping queries coming from the `proxy` to the actual `/metrics` scraping endpoints of the hosts/services/apps.

Prometheus Proxy solves the firewall problem by using a persistent gRPC connection initiated from inside the firewall.
Here's a simplified network diagram of how the deployed `proxy` and `agent` work:

![Architecture Diagram](https://github.com/pambrose/prometheus-proxy/raw/master/docs/prometheus-proxy.png)

Endpoints running behind a firewall require a `prometheus-agent` (the agent) to be run inside the firewall. An agent can
run as a stand-alone server, embedded in another java server, or as a java agent. Agents connect to
a `prometheus-proxy` (the proxy) and register the paths for which they will provide data. One proxy can work with one or
many
agents.

### Components

- **🌐 Proxy** - Runs outside the firewall alongside Prometheus
  - HTTP server (port 8080) - Serves metrics to Prometheus
  - gRPC server (port 50051) - Accepts agent connections
  - Service discovery support for dynamic targets

- **🔗 Agent** - Runs inside the firewall with monitored services
  - Connects to proxy via gRPC (outbound connection only)
  - Scrapes local metrics endpoints
  - Registers available paths with proxy

### Key Benefits

- ✅ **Firewall-friendly** - Only requires outbound connection from agent
- ✅ **Preserves pull model** - Prometheus continues to pull as normal
- ✅ **High performance** - Built with Kotlin coroutines and gRPC
- ✅ **Secure** - Optional TLS with mutual authentication
- ✅ **Scalable** - One proxy supports many agents
- ✅ **Zero changes** to existing Prometheus configuration patterns

## 🚀 Quick Start

**Requirements:** Java 17 or newer

### CLI Quick Start

1. Download the latest proxy and agent JAR files from [releases](https://github.com/pambrose/prometheus-proxy/releases)

2. Start the **proxy** (runs outside the firewall with Prometheus):
   ```bash
   java -jar prometheus-proxy.jar
   ```

3. Start the **agent** (runs inside the firewall with your services):
   ```bash
   java -jar prometheus-agent.jar \
     -Dagent.proxy.hostname=mymachine.local \
     --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf

   # or use --proxy option
   java -jar prometheus-agent.jar \
     --proxy mymachine.local \
     --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf
   ```

4. Configure Prometheus to scrape from the proxy at `http://mymachine.local:8080`

5. Verify it works with:
   ```bash
   curl -s http://mymachine.local:8080/app1_metrics | head

   # or, if SD is enabled
   curl -s http://mymachine.local:8080/discovery | jq '.'
   ```

### 🛠️ Building from Source

If you prefer to build the project from source:

1. Clone the repository:
   ```bash
   git clone https://github.com/pambrose/prometheus-proxy.git
   cd prometheus-proxy
   ```

2. Build the fat JARs:
   ```bash
   ./gradlew shadowJar
   ```

3. The JARs will be available in `build/libs/`:
  - `build/libs/prometheus-proxy.jar`
  - `build/libs/prometheus-agent.jar`

### Docker Quick Start

```bash
# Start proxy
docker run --rm -p 8080:8080 -p 50051:50051 pambrose/prometheus-proxy:3.0.0

# Start agent
docker run --rm \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  pambrose/prometheus-agent:3.0.0
```

## 📋 Configuration Examples

### Agent Configuration

If the prometheus-proxy were running on a machine named *mymachine.local* and the
`agent.pathConfigs` value in
the [myapps.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf)
config file had the contents:

```hocon
agent {
  pathConfigs: [
    {
      name: "App1 metrics"
      path: app1_metrics
      labels: "{\"key1\": \"value1\", \"key2\": 2}"
      url: "http://app1.local:9100/metrics"
    },
    {
      name: "App2 metrics"
      path: app2_metrics
      labels: "{\"key3\": \"value3\", \"key4\": 4}"
      url: "http://app2.local:9100/metrics"
    },
    {
      name: "App3 metrics"
      path: app3_metrics
      labels: "{\"key5\": \"value5\", \"key6\": 6}"
      url: "http://app3.local:9100/metrics"
    }
  ]
}
```

then the *prometheus.yml* scrape_config would target the three apps with:

* http://mymachine.local:8080/app1_metrics
* http://mymachine.local:8080/app2_metrics
* http://mymachine.local:8080/app3_metrics

If the endpoints were restricted with basic auth/bearer authentication, you could either include the basic-auth
credentials in the URL with: `http://user:pass@hostname/metrics` or they could be configured with `basic_auth`/
`bearer_token` in the scrape-config.

### Corresponding Prometheus Configuration

The `prometheus.yml` file would include:

```yaml
scrape_configs:
  - job_name: 'app1 metrics'
    metrics_path: '/app1_metrics'
    bearer_token: 'eyJ....hH9rloA'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
  - job_name: 'app2 metrics'
    metrics_path: '/app2_metrics'
    basic_auth:
      username: 'user'
      password: 's3cr3t'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
  - job_name: 'app3 metrics'
    metrics_path: '/app3_metrics'
    static_configs:
      - targets: [ 'mymachine.local:8080' ]
```

## 🐳 Docker Usage

### Multi-Platform Images

The docker images support multiple architectures (amd64, arm64, s390x):

```bash
docker pull pambrose/prometheus-proxy:3.0.0
docker pull pambrose/prometheus-agent:3.0.0
```

### Production Docker Setup

Start a proxy container with:

```bash
# Proxy with admin and metrics enabled
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        --restart unless-stopped \
        pambrose/prometheus-proxy:3.0.0
```

Start an agent container with:

```bash
# Agent with remote config file
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        --restart unless-stopped \
        pambrose/prometheus-agent:3.0.0
```

Or use docker-compose: see `etc/compose/proxy.yml` for a working example.

Using the config
file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf), the proxy
and the agent metrics would be available from the proxy on *localhost* at:

* http://localhost:8082/proxy_metrics
* http://localhost:8083/agent_metrics

If you want to use a local config file with a docker container (instead of the above HTTP-served config file), use the
docker [mount](https://docs.docker.com/storage/bind-mounts/) option. Assuming the config file `prom-agent.conf`
is in your current directory, run an agent container with:

```bash
# Agent with local config file
docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/prom-agent.conf,target=/app/prom-agent.conf \
    --env AGENT_CONFIG=prom-agent.conf \
    pambrose/prometheus-agent:3.0.0
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

## ⚙️ Advanced Features

### Embedded Agent

If you are running a JVM-based program, you can run with the agent embedded directly in your app and not use an external
agent. This approach eliminates the need for a separate agent process when your application already runs on the JVM.

  ```Java
  // Start embedded agent
  EmbeddedAgentInfo agentInfo = startAsyncAgent("configFile.conf", true);

  // Your application code runs here
  // The agent runs in the background and does not block your application

  // Shutdown the agent when the application terminates
  agentInfo.close();
  ```

### Service Discovery

Enable Prometheus service discovery support:

```bash
java -jar prometheus-proxy.jar \
  --sd_enabled \
  --sd_path discovery \
  --sd_target_prefix http://proxy-host:8080/
```

Access discovery endpoint at: `http://proxy-host:8080/discovery`

### Performance Tuning

Configure concurrent scraping:

```bash
java -jar prometheus-agent.jar \
  --max_concurrent_clients 5 \
  --client_timeout_secs 30 \
  --config myconfig.conf
```

## 📊 Monitoring & Observability

### Built-in Metrics

Both proxy and agent expose their own metrics:

- **Proxy metrics:** `http://proxy-host:8082/proxy_metrics`
- **Agent metrics:** `http://agent-host:8083/agent_metrics`
- **Admin endpoints:** `http://host:admin-port/ping`, `/healthcheck`, `/version`

## 🔧 Configuration Options

The proxy and agent use the [Typesafe Config](https://github.com/typesafehub/config) library. Configuration values are
evaluated in order: **CLI options → environment variables → config file values**.

Typesafe Config highlights include:

* support for files in three formats: Java properties, JSON, and a human-friendly JSON
  superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment variables, Java system properties, and/or config files.
* config files can reference environment variables

**📖 Complete configuration reference:** [**CLI Options & Environment Variables Reference**](docs/cli-args.md)

### Common Options Summary

| Component | Option             | Env Var                         | Description                                          |
|:----------|:-------------------|:--------------------------------|:-----------------------------------------------------|
| **Both**  | `--config, -c`     | `PROXY_CONFIG` / `AGENT_CONFIG` | Path or URL to config file                           |
| **Both**  | `--admin, -r`      | `ADMIN_ENABLED`                 | Enable admin/health-check endpoints                  |
| **Both**  | `--metrics, -e`    | `METRICS_ENABLED`               | Enable internal metrics collection                   |
| **Proxy** | `--port, -p`       | `PROXY_PORT`                    | Port for Prometheus to scrape (Default: 8080)        |
| **Proxy** | `--agent_port, -a` | `AGENT_PORT`                    | Port for Agents to connect via gRPC (Default: 50051) |
| **Agent** | `--proxy, -p`      | `PROXY_HOSTNAME`                | Hostname/IP of the Proxy                             |

### Configuration Notes

* **Formats:** Supports HOCON (`.conf`), JSON (`.json`), and Java Properties (`.properties`).
* **Logging:** Customize with `-Dlogback.configurationFile=/path/to/logback.xml`.
* **Dynamic Props:** Use `-Dproperty.name=value` for any configuration key.
* **Keepalives:** See the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/) for tuning details.

---

## 📝 Examples & Use Cases

### Common Scenarios

- **🏢 Enterprise environments** - Scrape metrics across firewall boundaries
- **☁️ Multi-cloud deployments** - Bridge different network segments
- **🔒 Secure environments** - Monitor internal services without opening inbound ports
- **🌐 Federation** - Scrape existing Prometheus instances via `/federate` endpoint
- **🚀 Kubernetes** - Monitor services across clusters or namespaces

### Example Configurations

| Use Case                   | Configuration                                                              |
|:---------------------------|:---------------------------------------------------------------------------|
| **Basic setup**            | [`examples/simple.conf`](examples/simple.conf)                             |
| **Multiple apps**          | [`examples/myapps.conf`](examples/myapps.conf)                             |
| **TLS (no mutual auth)**   | [`examples/tls-no-mutual-auth.conf`](examples/tls-no-mutual-auth.conf)     |
| **TLS (with mutual auth)** | [`examples/tls-with-mutual-auth.conf`](examples/tls-with-mutual-auth.conf) |
| **Prometheus federation**  | [`examples/federate.conf`](examples/federate.conf)                         |
| **Nginx reverse proxy**    | [`nginx/nginx-proxy.conf`](nginx/nginx-proxy.conf)                         |

### Advanced Use Cases

#### Prometheus Federation

Scrape an existing Prometheus instance via the `/federate` endpoint:
```hocon
agent.pathConfigs: [{
  name: "Federated Prometheus"
  path: "federated_metrics"
  url: "http://prometheus-server:9090/federate?match[]={__name__=~\"job:.*\"}"
}]
```

This leverages the existing service discovery features already built into Prometheus.

Another service discovery example config can be found in
[federate.conf](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf).

#### Nginx Reverse Proxy

To use with Nginx as a reverse proxy, disable the transport filter on both Proxy and Agent:
```bash
java -jar prometheus-proxy.jar --tf_disabled
java -jar prometheus-agent.jar --tf_disabled --config myconfig.conf
```

An example nginx conf file is [here](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf),
and an example agent/proxy conf file
is [here](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/nginx-proxy.conf)

**⚠️ Note:** With `transportFilterDisabled`, agent disconnections aren't immediately detected. Agent contexts on the
proxy
are removed after inactivity timeout (default: 1 minute, controlled by `proxy.internal.maxAgentInactivitySecs`).

### gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is enabled by default for debugging and tooling.

**Test with [grpcurl](https://github.com/fullstorydev/grpcurl):**

```bash
# List available services
grpcurl -plaintext localhost:50051 list

# Describe a service
grpcurl -plaintext localhost:50051 describe io.prometheus.ProxyService
```

If you use grpcurl `-plaintext` option, make sure that you run the proxy in plaintext
mode, i.e., do not define any TLS properties.

**Disable reflection:** Use `--ref_disabled`, `REFLECTION_DISABLED`, or `proxy.reflectionDisabled=true`.

## 🔐 Security & TLS

### TLS Configuration Details

Agents connect to a proxy using [gRPC](https://grpc.io). gRPC supports TLS with or without mutual authentication. The
necessary certificate and key file paths can be specified via CLI args, environment variables, and configuration file
settings.

The gRPC docs describe [how to set up TLS](https://github.com/grpc/grpc-java/tree/master/examples/example-tls).
The [repo](https://github.com/pambrose/prometheus-proxy/tree/master/testing/certs) includes the certificates and keys
necessary to test TLS support.

Running TLS without mutual authentication requires these settings:

* `certChainFilePath` and `privateKeyFilePath` on the proxy
* `trustCertCollectionFilePath` on the agent

Running TLS with mutual authentication requires these settings:

* `certChainFilePath`, `privateKeyFilePath` and `trustCertCollectionFilePath` on the proxy
* `certChainFilePath`, `privateKeyFilePath` and `trustCertCollectionFilePath` on the agent

Run a proxy and an agent with TLS (with mutual authentication) with:

```bash
# Proxy with TLS
java -jar prometheus-proxy.jar \
  --cert /path/to/server.crt \
  --key /path/to/server.key \
  --trust /path/to/ca.crt

# Agent with TLS
java -jar prometheus-agent.jar \
  --config myconfig.conf \
  --trust /path/to/ca.crt \
  --cert /path/to/client.crt \
  --key /path/to/client.key
```

Run a proxy and an agent with TLS (no mutual authentication) using the included testing certs and keys with:

```bash
java -jar prometheus-proxy.jar --config examples/tls-no-mutual-auth.conf

java -jar prometheus-agent.jar --config examples/tls-no-mutual-auth.conf
```

Run a proxy and an agent docker container with TLS (no mutual authentication) using the included testing certs and keys
with:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50440:50440 -p 8080:8080 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env PROXY_CONFIG=tls-no-mutual-auth.conf \
    --env ADMIN_ENABLED=true \
    --env METRICS_ENABLED=true \
    pambrose/prometheus-proxy:3.0.0

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:3.0.0
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

### Auth Header Forwarding

When Prometheus scrape configurations include `basic_auth` or `bearer_token`, the proxy forwards the
`Authorization` header to the agent over the gRPC channel. If TLS is not configured, these credentials
are transmitted in plaintext and could be intercepted on the network between the proxy and agent.

**Enable TLS when forwarding auth headers:**

```bash
# Proxy with TLS to protect forwarded credentials
java -jar prometheus-proxy.jar \
  --cert /path/to/server.crt \
  --key /path/to/server.key

# Agent with TLS
java -jar prometheus-agent.jar \
  --config myconfig.conf \
  --trust /path/to/ca.crt
```

The proxy logs a warning on the first request that includes an `Authorization` header when TLS is not enabled.

### Scraping HTTPS Endpoints

Disable SSL verification for agent https endpoints with the `TRUST_ALL_X509_CERTIFICATES` environment var,
the `--trust_all_x509` CLI option, or the `agent.http.enableTrustAllX509Certificates` property.

To scrape HTTPS endpoints with a self-signed certificate:

```bash
java -jar prometheus-agent.jar --trust_all_x509 --config myconfig.conf
```

**⚠️ Security Note:** Only use `--trust_all_x509` in development/testing environments.

## 🔧 Troubleshooting

### Common Issues

**Agent can't connect to proxy:**

- Verify proxy hostname and port
- Check firewall rules (agent needs outbound access to proxy port)
- Ensure proxy is running and listening

**TLS connection failures:**

- Verify certificate paths and file permissions
- Check certificate validity and chain
- Ensure clock synchronization between proxy and agent

**Metrics not appearing:**

- Verify agent path configuration matches Prometheus scrape paths
- Check agent logs for scraping errors
- Confirm target endpoints are accessible from agent

**Performance issues:**

- Increase `max_concurrent_clients` for high-throughput scenarios
- Tune HTTP client cache settings
- Consider running multiple agents for load distribution

## 📄 License

This project is licensed under the Apache License 2.0 - see [License.txt](License.txt) for details.

## Related Links

* [Prometheus.io](http://prometheus.io)
* [gRPC](http://grpc.io)
* [Typesafe Config](https://github.com/carueda/tscfg)
