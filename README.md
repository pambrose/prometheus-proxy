# Prometheus Proxy

[![GitHub release (latest by date)](https://img.shields.io/github/v/release/pambrose/prometheus-proxy)](https://github.com/pambrose/prometheus-proxy/releases)
[![Maven Central](https://img.shields.io/maven-central/v/com.pambrose/prometheus-proxy)](https://central.sonatype.com/artifact/com.pambrose/prometheus-proxy)
[![Kotlin version](https://img.shields.io/badge/kotlin-2.4.0-red?logo=kotlin)](http://kotlinlang.org)
[![codecov](https://codecov.io/gh/pambrose/prometheus-proxy/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/prometheus-proxy)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://app.codacy.com/gh/pambrose/prometheus-proxy/dashboard)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

![prometheus-agent Stats](https://dockerhub-readme-stats.vercel.app/api?image=pambrose/prometheus-agent&label=Docker%20Stats&hide=stars)
![prometheus-proxy Stats](https://dockerhub-readme-stats.vercel.app/api?image=pambrose/prometheus-proxy&label=Docker%20Stats&hide=stars)


[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for
collecting metrics. The pull model is problematic when a firewall separates a Prometheus server and its metrics
endpoints.

[Prometheus Proxy](https://pambrose.github.io/prometheus-proxy/) enables Prometheus to scrape metrics endpoints running
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
- [Documentation](#-documentation)
- [License](#-license)

## 🏗️ Architecture

> 📖 **Docs site:
** [Architecture](https://pambrose.github.io/prometheus-proxy/architecture/) for the full component breakdown and request flow, and the [Glossary](https://pambrose.github.io/prometheus-proxy/glossary/) for the core terms used throughout the docs.

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
- ✅ **Bandwidth-conscious** - Optional per-path metric filtering drops unwanted families at the agent
- ✅ **Zero changes** to existing Prometheus configuration patterns

## 🚀 Quick Start

> 📖 **Docs site:
** [Quick Start guide](https://pambrose.github.io/prometheus-proxy/getting-started/) walks through these steps with more detail.

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
   ./gradlew agentJar proxyJar
   ```

   `agentJar` and `proxyJar` are dedicated `ShadowJar` tasks; the default `shadowJar` task is disabled so it cannot produce a third redundant fat jar.

3. The JARs will be available in `build/libs/`:
  - `build/libs/prometheus-proxy.jar`
  - `build/libs/prometheus-agent.jar`

### Docker Quick Start

```bash
# Start proxy
docker run --rm -p 8080:8080 -p 50051:50051 pambrose/prometheus-proxy:3.2.0

# Start agent
docker run --rm \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  pambrose/prometheus-agent:3.2.0
```

## 📋 Configuration Examples

> 📖 **Docs site:
** [Agent configuration](https://pambrose.github.io/prometheus-proxy/configuration/agent/) and [Proxy configuration](https://pambrose.github.io/prometheus-proxy/configuration/proxy/) document every config key in depth.

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

> 📖 **Docs site:
** [Docker deployment guide](https://pambrose.github.io/prometheus-proxy/docker/) covers compose files, bind mounts, and container configuration. Running on Kubernetes? See the [Kubernetes guide](https://pambrose.github.io/prometheus-proxy/kubernetes/).

### Multi-Platform Images

The docker images support multiple architectures (amd64, arm64, s390x, ppc64le):

```bash
docker pull pambrose/prometheus-proxy:3.2.0
docker pull pambrose/prometheus-agent:3.2.0
```

### Production Docker Setup

Start a proxy container with:

```bash
# Proxy with admin and metrics enabled
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        --restart unless-stopped \
        pambrose/prometheus-proxy:3.2.0
```

Start an agent container with:

```bash
# Agent with remote config file
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        --restart unless-stopped \
        pambrose/prometheus-agent:3.2.0
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
    pambrose/prometheus-agent:3.2.0
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

## ⚙️ Advanced Features

### Embedded Agent

If you are running a JVM-based program, you can run with the agent embedded directly in your app and not use an external
agent. This approach eliminates the need for a separate agent process when your application already runs on the JVM.

> 📖 **Docs site:
** [Embedded Agent guide](https://pambrose.github.io/prometheus-proxy/embedded-agent/) for the full API surface and lifecycle details.

  ```Java
  // Start embedded agent
  EmbeddedAgentInfo agentInfo = startAsyncAgent("configFile.conf", true);

  // Your application code runs here
  // The agent runs in the background and does not block your application

  // Shutdown the agent when the application terminates
  agentInfo.shutdown();
  ```

If the configuration cannot be loaded (a parse error, an unreachable config URL, or a missing file), the embedded
`startAsyncAgent` / `startSyncAgent` entry points throw `io.prometheus.common.ConfigLoadException` for your application
to catch, rather than terminating the host JVM via `exitProcess`. Stand-alone agents (run from the CLI) still exit on a
missing or unreadable config.

### Service Discovery

Enable Prometheus service discovery support:

```bash
java -jar prometheus-proxy.jar \
  --sd_enabled \
  --sd_path discovery \
  --sd_target_prefix http://proxy-host:8080/
```

Access discovery endpoint at: `http://proxy-host:8080/discovery`

> 📖 **Docs site:
** [Service Discovery guide](https://pambrose.github.io/prometheus-proxy/service-discovery/) explains the discovery payload format and the Prometheus
`http_sd_configs` wiring.

### Dynamic Target Discovery (Agent)

By default the agent's scrape targets come from the static `agent.pathConfigs` list, read once at
startup — adding or removing a target means editing the config and restarting the agent. Enable
dynamic discovery to have the agent reconcile its registered paths against a **watched file** at
runtime, with no restart:

```hocon
agent {
  discovery {
    enabled = true
    file.path = "/etc/prometheus-proxy/targets.conf"   // HOCON or JSON list of paths
    reconcileIntervalSecs = 30                          // Poll and full-resync interval
  }
}
```

The discovery file is a `paths` list of the same `{ name, path, url, labels }` entries as
`pathConfigs`:

```hocon
paths = [
  { name = "app1", path = "app1_metrics", url = "http://app1:9090/metrics" }
  { name = "app2", path = "app2_metrics", url = "http://app2:9090/metrics" }
]
```

Every `reconcileIntervalSecs` the agent reads the file and registers new paths, unregisters ones no
longer listed, and re-registers a path whose URL or labels changed — without disturbing the paths
that did not change. Behavior worth knowing:

- **Additive to the static baseline.** Discovered paths add to `agent.pathConfigs` (which is never
  touched); on a path collision the static entry wins. Leave `pathConfigs` empty to run
  discovery-only.
- **Fail-safe reads.** A missing/unreadable/malformed file keeps the last-known-good set; a
  *valid-but-empty* file removes all discovered paths.
- **Polling, not inotify.** Reliable under Kubernetes ConfigMap updates and bind mounts, where file
  watches often miss changes.
- **Config-file only.** `discovery.file.path` points at a list, so there is no CLI/env equivalent
  (though `enabled`, the path, and the interval are scalars you can also set via `-D`).

> This is distinct from the [Prometheus Service Discovery](#service-discovery) above: that exposes a
> discovery endpoint so *Prometheus* can find proxied targets, whereas this lets the *agent* pick up
> target changes behind the firewall without a restart.

### Metric Filtering (Agent)

By default the agent forwards every metric a target exposes, so chatty or high-cardinality endpoints
pay full bandwidth across exactly the network boundary this product exists to bridge. An optional
per-path filter drops whole metric families **at the agent**, before the payload is gzipped and
chunked, cutting WAN bandwidth and downstream Prometheus cardinality at the source:

```hocon
agent {
  pathConfigs: [
    { name: "app1", path: "app1_metrics", url: "http://app1:9090/metrics" }
  ]

  filters: [
    {
      path: "app1_metrics"          // The pathConfigs (or discovered) path this applies to
      metricNameAllow: []           // Fully-anchored regexes; empty allows every family
      metricNameDeny: [ "go_gc_.*", "go_memstats_.*" ]
    }
  ]
}
```

Both list fields are required on every element — write `metricNameAllow: []` for a deny-only filter.
The default is `filters: []`, so an existing config with no `filters` key loads unchanged. Behavior
worth knowing:

- **Regexes are fully anchored**, matching Prometheus `relabel_config` semantics: `"go_"` matches
  nothing, `"go_.*"` is required to match `go_goroutines`. `metricNameDeny` is applied after
  `metricNameAllow`, so deny wins on overlap.
- **Matching is per metric family, not per series.** A `# HELP`/`# TYPE` line opens a family and the
  verdict is computed once, so a histogram's `_bucket`, `_sum`, and `_count` series are always kept
  or dropped together with their metadata lines — a filter can never deliver a histogram with some of
  its series missing.
- **Fails open.** A non-text `Content-Type` (protobuf, an HTML error body) or a body that is not
  valid UTF-8 passes through unfiltered and byte-exact. The worst case is bandwidth not saved, never
  a corrupted payload.
- **Applies to discovered paths too.** Filters are matched by path, so they cover both
  `pathConfigs` entries and paths added by [Dynamic Target Discovery](#dynamic-target-discovery-agent).
- **Observable.** `agent_filter_lines_dropped` and `agent_filter_bytes_saved`, labeled by
  `launch_id` and `path`, report what each filter is actually removing.
- **Config-file only.** `filters` is a list, so there is no CLI/env equivalent.

An invalid regex fails agent startup rather than surfacing later on a scrape. Not implemented:
`dropLabels`, metric renaming/relabeling, and an agent-global filter — every filter is per-path.

### Performance Tuning

Configure concurrent scraping:

```bash
java -jar prometheus-agent.jar \
  --max_concurrent_clients 5 \
  --client_timeout_secs 30 \
  --config myconfig.conf
```

## 📊 Monitoring & Observability

> 📖 **Docs site:
** [Monitoring guide](https://pambrose.github.io/prometheus-proxy/monitoring/) for the full metrics reference and admin endpoints, plus [Grafana & Alerting](https://pambrose.github.io/prometheus-proxy/grafana/) for ready-to-import dashboards and alert rules.

### Built-in Metrics

Both proxy and agent expose their own metrics:

- **Proxy metrics:** `http://proxy-host:8082/proxy_metrics`
- **Agent metrics:** `http://agent-host:8083/agent_metrics`
- **Admin endpoints:** `http://host:admin-port/ping`, `/healthcheck`, `/version`

## 🔧 Configuration Options

> 📖 **Docs site:
** [Configuration overview](https://pambrose.github.io/prometheus-proxy/configuration/) and the [CLI Reference](https://pambrose.github.io/prometheus-proxy/cli-reference/) document every option and environment variable.

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
| **Both**  | `--agent_token`    | `AGENT_TOKEN`                   | Pre-shared agent auth token; both sides must match (Default: disabled). For per-agent identities see `proxy.auth` |
| **Proxy** | `--port, -p`       | `PROXY_PORT`                    | Port for Prometheus to scrape (Default: 8080)        |
| **Proxy** | `--agent_port, -a` | `AGENT_PORT`                    | Port for Agents to connect via gRPC (Default: 50051) |
| **Agent** | `--proxy, -p`      | `PROXY_HOSTNAME`                | Hostname/IP of the Proxy                             |

### Configuration Notes

* **Formats:** Supports HOCON (`.conf`), JSON (`.json`), and Java Properties (`.properties`).
* **Logging:** Customize with `-Dlogback.configurationFile=/path/to/logback.xml`.
* **Dynamic Props:** Use `-Dproperty.name=value` for any scalar configuration key (parsed as Java properties, so list/object values like `proxy.auth` must be set in a config file).
* **Per-Agent Auth:** `proxy.auth` (per-agent identities with path authorization) is a list of objects, so it is config-file-only — see [Per-Agent Identities and Path Authorization](#per-agent-identities-and-path-authorization).
* **Keepalives:** See the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/) for tuning details.

---

## 📝 Examples & Use Cases

> 📖 **Docs site:
** [Example Configs guide](https://pambrose.github.io/prometheus-proxy/examples/) describes each ready-to-run config under
`examples/`.

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

> 📖 **Docs site:
** [Advanced Topics guide](https://pambrose.github.io/prometheus-proxy/advanced/) covers the Nginx reverse-proxy setup, federation, and other advanced configurations.

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

**⚠️ Security Note:** Reflection is **unauthenticated** and exposes the full gRPC API surface to anyone who can reach the
agent port (default `50051`). A client such as `grpcurl` can enumerate every service, method, and message type without
credentials, making it trivial for an attacker who reaches the port to discover and craft calls (for example, to attempt
agent or path registration). The optional [agent token](#agent-token-authentication) does **not** protect reflection — the
token interceptor guards only the `ProxyService` RPCs, not the separate reflection service. Leave reflection on only for
local debugging on a trusted network; **disable it in production** and anywhere the agent port is reachable beyond trusted
agents. Disabling reflection only hides the API shape — it is not a substitute for authentication, so still pair the agent
port with the pre-shared agent token, mutual TLS, and/or network segmentation.

## 🔐 Security & TLS

> 📖 **Docs site:
** [Security overview](https://pambrose.github.io/prometheus-proxy/security/) and the [TLS Setup guide](https://pambrose.github.io/prometheus-proxy/security/tls/) cover agent authentication, mutual TLS, and certificate management in depth.

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
    pambrose/prometheus-proxy:3.2.0

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:3.2.0
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

### Agent Token Authentication

The proxy accepts agent gRPC connections with no application-level authentication by default — any process that can reach
the agent port (default `50051`) can register as an agent. Set a shared **pre-shared token** so the proxy rejects agents
that do not present it:

* Proxy: `--agent_token`, `AGENT_TOKEN`, or `proxy.agentToken`
* Agent: `--agent_token`, `AGENT_TOKEN`, or `agent.agentToken`

Both sides must use the **same** value. When set, the agent attaches the token as a gRPC metadata header on every call and
the proxy rejects any call with a missing or mismatched token (`UNAUTHENTICATED`). When the token is empty (the default),
the open behavior is preserved and the proxy logs a startup warning — unless mutual TLS is configured, which already
authenticates agents.

```bash
# Proxy requiring a token
java -jar prometheus-proxy.jar --agent_token "$AGENT_TOKEN"

# Agent presenting the token
java -jar prometheus-agent.jar --config myconfig.conf --agent_token "$AGENT_TOKEN"
```

The token is a lightweight, app-level control. For production, prefer mutual TLS (and/or restrict the agent port to
trusted networks); the token complements, but does not replace, those controls. The value is never written to logs.

### Per-Agent Identities and Path Authorization

A single shared `agentToken` authenticates agents but does not distinguish between them: any agent holding the token can
register **any** path, including one already served by another agent. To scope agents, define named identities under
`proxy.auth`, each with its own token and a list of allowed path glob patterns:

```hocon
proxy {
  auth = [
    { name = team-a, token = "team-a-token", paths = ["team_a_*"] }
    { name = team-b, token = "team-b-token", paths = ["team_b_*"] }
    { name = infra,  token = "infra-token",  paths = [] }          # empty paths = may register any path
  ]
}
```

Each agent presents its identity's token the usual way (`--agent_token`, `AGENT_TOKEN`, or `agent.agentToken`); no
agent-side change is needed. The proxy resolves the token to an identity and enforces, on every `registerPath`, that the
requested path matches one of the identity's `paths` patterns. Patterns are single-segment globs where `*` matches any run
of characters and `?` matches one (e.g. `team_a_*`); an **empty** `paths` list authorizes every path. An unknown token is
rejected with `UNAUTHENTICATED`; a path outside the identity's patterns fails registration with a "not authorized" reason.
Because authorization is per-identity-per-path, consolidated mode (multiple agents serving one path) still works as long as
each agent's identity permits the shared path.

`proxy.auth` is a list of objects, so it is set in the config file only — there is no equivalent CLI flag or environment
variable. Identity names must be unique and tokens must be non-empty; the proxy fails fast at startup otherwise.

#### Migrating from a shared token

Setting `proxy.auth` does **not** disable a legacy `proxy.agentToken`. When both are present, the shared token is honored
as an additional **allow-all** identity (and the proxy logs a warning that it is active), so you can adopt per-agent
identities incrementally:

1. Add a `proxy.auth` entry per agent while leaving `proxy.agentToken` in place — existing agents keep connecting with the
   shared token.
2. Move each agent onto its own identity token one at a time.
3. Once every agent presents an identity token, remove `proxy.agentToken` to close the shared allow-all path.

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

For HTTPS scrape targets signed by a custom or private CA (e.g. an internal corporate CA), point the agent at
a trust store that contains that CA so certificates are **still validated**:

```bash
java -jar prometheus-agent.jar \
  --https_truststore /etc/agent/truststore.jks \
  --https_truststore_password changeit \
  --config myconfig.conf
```

This is also configurable via the `HTTPS_TRUST_STORE_PATH` / `HTTPS_TRUST_STORE_PASSWORD` environment vars or
the `agent.http.trustStorePath` / `agent.http.trustStorePassword` properties. An empty path uses the JDK
default trust store. The trust store is process-wide — it applies to every HTTPS target the agent scrapes.

As a last resort you can disable certificate verification entirely with the `TRUST_ALL_X509_CERTIFICATES`
environment var, the `--trust_all_x509` CLI option, or the `agent.http.enableTrustAllX509Certificates`
property:

```bash
java -jar prometheus-agent.jar --trust_all_x509 --config myconfig.conf
```

**⚠️ Security Note:** Only use `--trust_all_x509` in development/testing environments. Its scope is
process-global and all-or-nothing: it disables certificate validation for **every** HTTPS target the agent
scrapes, and it takes precedence over `--https_truststore`. Prefer a custom trust store whenever possible.

## 🔧 Troubleshooting

> 📖 **Docs site:
** [Troubleshooting guide](https://pambrose.github.io/prometheus-proxy/troubleshooting/) is a symptom-driven reference covering more failure modes and fixes.

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

## 📖 Documentation

Full documentation is available at the [Prometheus Proxy Documentation](https://pambrose.github.io/prometheus-proxy/) site.

KDoc API documentation is published
at [pambrose.github.io/prometheus-proxy/kdocs](https://pambrose.github.io/prometheus-proxy/kdocs/).

**Note:** Running on Kubernetes? See the
[Kubernetes deployment guide](https://pambrose.github.io/prometheus-proxy/kubernetes/) for ready-to-use proxy and
agent manifests, standalone and sidecar agent patterns, gRPC exposure for remote agents, and Prometheus Operator
(`ServiceMonitor`) integration.

**Note:** Deploying to production? The
[Running in Production guide](https://pambrose.github.io/prometheus-proxy/production/) pulls the security, reliability,
and tuning settings into a single operational checklist.

### Maven Central

The library is published to Maven Central under `com.pambrose:prometheus-proxy`:

```kotlin
// build.gradle.kts
repositories {
  mavenCentral()
}

dependencies {
  implementation("com.pambrose:prometheus-proxy:3.2.0")
}
```

## 📄 License

This project is licensed under the Apache License 2.0 - see [License.txt](LICENSE.txt) for details.

## Related Links

* [Prometheus.io](http://prometheus.io)
* [gRPC](http://grpc.io)
* [Typesafe Config](https://github.com/carueda/tscfg)
