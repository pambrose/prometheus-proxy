[![JitPack](https://jitpack.io/v/pambrose/prometheus-proxy.svg)](https://jitpack.io/#pambrose/prometheus-proxy)
[![Build Status](https://app.travis-ci.com/pambrose/prometheus-proxy.svg?branch=master)](https://app.travis-ci.com/pambrose/prometheus-proxy)
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
- [Configuration Examples](#-configuration-examples)
- [Docker Usage](#-docker-usage)
- [Advanced Features](#%EF%B8%8F-advanced-features)
- [Monitoring & Observability](#-monitoring--observability)
- [Configuration Options](#-configuration-options)
- [Examples & Use Cases](#-examples--use-cases)
- [Advanced Configuration](#-advanced-configuration)
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

### Docker Quick Start

```bash
# Start proxy
docker run --rm -p 8080:8080 -p 50051:50051 pambrose/prometheus-proxy:2.4.1

# Start agent
docker run --rm \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  pambrose/prometheus-agent:2.4.1
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
docker pull pambrose/prometheus-proxy:2.4.1
docker pull pambrose/prometheus-agent:2.4.1
```

### Production Docker Setup

Start a proxy container with:

```bash
# Proxy with admin and metrics enabled
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        --restart unless-stopped \
        pambrose/prometheus-proxy:2.4.1
```

Start an agent container with:

```bash
# Agent with remote config file
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        --restart unless-stopped \
        pambrose/prometheus-agent:2.4.1
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
    pambrose/prometheus-agent:2.4.1
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
  agentInfo.

close();
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

The proxy and agent use the [Typesafe Config](https://github.com/typesafehub/config) library for configuration.
Highlights include:

* support for files in three formats: Java properties, JSON, and a human-friendly JSON
  superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment variables, Java system properties, and/or config files.
* config files can reference environment variables

All the proxy and agent properties are
described [here](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf). The only required
argument is an agent config value, which should have an `agent.pathConfigs` value.

Configuration values are evaluated in order: CLI options → environment variables → config file values.

**📖 Complete configuration reference:** [
`/etc/config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf)

### Proxy CLI Options

| CLI Option<br>ENV VAR<br>Property                                                                                | Default                  | Description                                                                                                             |
|:-----------------------------------------------------------------------------------------------------------------|:-------------------------|:------------------------------------------------------------------------------------------------------------------------|
| --config, -c                    <br> PROXY_CONFIG                                                                |                          | Agent config file or url                                                                                                |
| --port, -p                      <br> PROXY_PORT                      <br> proxy.http.port                        | 8080                     | Proxy listen port                                                                                                       |
| --agent_port, -a                <br> AGENT_PORT                      <br> proxy.agent.port                       | 50051                    | gRPC listen port for agents                                                                                             |
| --admin, -r                     <br> ADMIN_ENABLED                   <br> proxy.admin.enabled                    | false                    | Enable admin servlets                                                                                                   |
| --admin_port, -i                <br> ADMIN_PORT                      <br> proxy.admin.port                       | 8092                     | Admin servlets port                                                                                                     |
| --debug, -b                     <br> DEBUG_ENABLED                   <br> proxy.admin.debugEnabled               | false                    | Enable proxy debug servlet<br>on admin port                                                                             |
| --metrics, -e                   <br> METRICS_ENABLED                 <br> proxy.metrics.enabled                  | false                    | Enable proxy metrics                                                                                                    |
| --metrics_port, -m              <br> METRICS_PORT                    <br> proxy.metrics.port                     | 8082                     | Proxy metrics listen port                                                                                               |
| --sd_enabled                    <br> SD_ENABLED                      <br> proxy.service.discovery.enabled        | false                    | Service discovery endpoint enabled                                                                                      |
| --sd_path                       <br> SD_PATH                         <br> proxy.service.discovery.path           | "discovery"              | Service discovery endpoint path                                                                                         |
| --sd_target_prefix              <br> SD_TARGET_PREFIX                <br> proxy.service.discovery.targetPrefix   | "http://localhost:8080/" | Service discovery target prefix                                                                                         |
| --tf_disabled                   <br> TRANSPORT_FILTER_DISABLED       <br> proxy.transportFilterDisabled          | false                    | Transport filter disabled                                                                                               |
| --ref_disabled                  <br> REFLECTION_DISABLED             <br> proxy.reflectionDisabled               | false                    | gRPC Reflection disabled                                                                                                |
| --handshake_timeout_secs        <br> HANDSHAKE_TIMEOUT_SECS          <br> proxy.grpc.handshakeTimeoutSecs        | 120                      | gRPC Handshake timeout (seconds)                                                                                        |
| --keepalive_time_secs           <br> KEEPALIVE_TIME_SECS             <br> proxy.grpc.keepAliveTimeSecs           | 7200                     | The interval between gRPC PING frames (seconds)                                                                         |
| --keepalive_timeout_secs        <br> KEEPALIVE_TIMEOUT_SECS          <br> proxy.grpc.keepAliveTimeoutSecs        | 20                       | The timeout for a gRPC PING frame to be acknowledged (seconds)                                                          |
| --permit_keepalive_without_calls<br> PERMIT_KEEPALIVE_WITHOUT_CALLS  <br> proxy.grpc.permitKeepAliveWithoutCalls | false                    | Is it permissible to send gRPC keepalive pings from the client without any outstanding streams                          |
| --permit_keepalive_time_secs    <br> PERMIT_KEEPALIVE_TIME_SECS      <br> proxy.grpc.permitKeepAliveTimeSecs     | 300                      | Min allowed time between a gRPC server receiving successive PING frames without sending any DATA/HEADER frame (seconds) |
| --max_connection_idle_secs      <br> MAX_CONNECTION_IDLE_SECS        <br> proxy.grpc.maxConnectionIdleSecs       | INT_MAX                  | Max time that a gRPC channel may have no outstanding rpcs (seconds)                                                     |
| --max_connection_age_secs       <br> MAX_CONNECTION_AGE_SECS         <br> proxy.grpc.maxConnectionAgeSecs        | INT_MAX                  | Max time that a gRPC channel may exist (seconds)                                                                        |
| --max_connection_age_grace_secs <br> MAX_CONNECTION_AGE_GRACE_SECS   <br> proxy.grpc.maxConnectionAgeGraceSecs   | INT_MAX                  | Grace period after the gRPC channel reaches its max age (seconds)                                                       |
| --log_level                     <br> PROXY_LOG_LEVEL                 <br> proxy.logLevel                         | "info"                   | Log level ("trace", "debug", "info", "warn", "error", "off")                                                            |
| --cert, -t                      <br> CERT_CHAIN_FILE_PATH            <br> proxy.tls.certChainFilePath            |                          | Certificate chain file path                                                                                             |
| --key, -k                       <br> PRIVATE_KEY_FILE_PATH           <br> proxy.tls.privateKeyFilePath           |                          | Private key file path                                                                                                   |
| --trust, -s                     <br> TRUST_CERT_COLLECTION_FILE_PATH <br> proxy.tls.trustCertCollectionFilePath  |                          | Trust certificate collection file path                                                                                  |
| --version, -v                                                                                                    |                          | Print version info and exit                                                                                             |
| --usage, -u                                                                                                      |                          | Print usage message and exit                                                                                            |
| -D                                                                                                               |                          | Dynamic property assignment                                                                                             |

### Agent CLI Options

| CLI Option<br> ENV VAR<br>Property                                                                                    | Default | Description                                                                                    |
|:----------------------------------------------------------------------------------------------------------------------|:--------|:-----------------------------------------------------------------------------------------------|
| --config, -c                  <br> AGENT_CONFIG                                                                       |         | Agent config file or url (required)                                                            |
| --proxy, -p                   <br> PROXY_HOSTNAME                     <br> agent.proxy.hostname                       |         | Proxy hostname (can include :port)                                                             |
| --name, -n                    <br> AGENT_NAME                         <br> agent.name                                 |         | Agent name                                                                                     |
| --admin, -r                   <br> ADMIN_ENABLED                      <br> agent.admin.enabled                        | false   | Enable admin servlets                                                                          |
| --admin_port, -i              <br> ADMIN_PORT                         <br> agent.admin.port                           | 8093    | Admin servlets port                                                                            |
| --debug, -b                   <br> DEBUG_ENABLED                      <br> agent.admin.debugEnabled                   | false   | Enable agent debug servlet<br>on admin port                                                    |
| --metrics, -e                 <br> METRICS_ENABLED                    <br> agent.metrics.enabled                      | false   | Enable agent metrics                                                                           |
| --metrics_port, -m            <br> METRICS_PORT                       <br> agent.metrics.port                         | 8083    | Agent metrics listen port                                                                      |
| --consolidated, -o            <br> CONSOLIDATED                       <br> agent.consolidated                         | false   | Enable multiple agents per registered path                                                     |
| --timeout                     <br> SCRAPE_TIMEOUT_SECS                <br> agent.scrapeTimeoutSecs                    | 15      | Scrape timeout time (seconds)                                                                  |
| --max_retries                 <br> SCRAPE_MAX_RETRIES                 <br> agent.scrapeMaxRetries                     | 0       | Scrape maximum retries (0 disables scrape retries)                                             |
| --chunk                       <br> CHUNK_CONTENT_SIZE_KBS             <br> agent.chunkContentSizeKbs                  | 32      | Threshold for chunking data to Proxy and buffer size (KBs)                                     |
| --gzip                        <br> MIN_GZIP_SIZE_BYTES                <br> agent.minGzipSizeBytes                     | 1024    | Minimum size for content to be gzipped (bytes)                                                 |
| --tf_disabled                 <br> TRANSPORT_FILTER_DISABLED          <br> agent.transportFilterDisabled              | false   | Transport filter disabled                                                                      |
| --trust_all_x509              <br> TRUST_ALL_X509_CERTIFICATES        <br> agent.http.enableTrustAllX509Certificates  | false   | Disable SSL verification for agent https endpoints                                             |
| --max_concurrent_clients      <br> MAX_CONCURRENT_CLIENTS             <br> agent.http.maxConcurrentClients            | 1       | Maximum number of concurrent HTTP clients                                                      |
| --client_timeout_secs         <br> CLIENT_TIMEOUT_SECS                <br> agent.http.clientTimeoutSecs               | 90      | HTTP client timeout (seconds)                                                                  |
| --max_content_length_mbytes   <br> AGENT_MAX_CONTENT_LENGTH_MBYTES    <br> agent.http.maxContentLengthMBytes          | 10      | Maximum allowed size of scrape response (megabytes)                                            |
| --max_cache_size              <br> MAX_CLIENT_CACHE_SIZE              <br> agent.http.clientCache.maxSize             | 100     | Maximum number of HTTP clients to cache                                                        |
| --max_cache_age_mins          <br> MAX_CLIENT_CACHE_AGE_MINS          <br> agent.http.clientCache.maxAgeMins          | 30      | Maximum age of cached HTTP clients (minutes)                                                   |
| --max_cache_idle_mins         <br> MAX_CLIENT_CACHE_IDLE_MINS         <br> agent.http.clientCache.maxIdleMins         | 10      | Maximum idle time before HTTP client is evicted (minutes)                                      |
| --cache_cleanup_interval_mins <br> CLIENT_CACHE_CLEANUP_INTERVAL_MINS <br> agent.http.clientCache.cleanupIntervalMins | 5       | Interval between HTTP client cache cleanup runs (minutes)                                      |
| --keepalive_time_secs         <br> KEEPALIVE_TIME_SECS                <br> agent.grpc.keepAliveTimeSecs               | INT_MAX | The interval between gRPC PING frames (seconds)                                                |
| --keepalive_timeout_secs      <br> KEEPALIVE_TIMEOUT_SECS             <br> agent.grpc.keepAliveTimeoutSecs            | 20      | The timeout for a PING gRPC frame to be acknowledged (seconds)                                 |
| --keepalive_without_calls     <br> KEEPALIVE_WITHOUT_CALLS            <br> agent.grpc.keepAliveWithoutCalls           | false   | Is it permissible to send gRPC keepalive pings from the client without any outstanding streams |
| --unary_deadline_secs         <br> UNARY_DEADLINE_SECS                <br> agent.grpc.unaryDeadlineSecs               | 30      | The timeout for gRPC unary calls (seconds)                                                     |
| --log_level                   <br> AGENT_LOG_LEVEL                    <br> agent.logLevel                             | "info"  | Log level ("trace", "debug", "info", "warn", "error", "off")                                   |
| --cert, -t                    <br> CERT_CHAIN_FILE_PATH               <br> agent.tls.certChainFilePath                |         | Certificate chain file path                                                                    |
| --key, -k                     <br> PRIVATE_KEY_FILE_PATH              <br> agent.tls.privateKeyFilePath               |         | Private key file path                                                                          |
| --trust, -s                   <br> TRUST_CERT_COLLECTION_FILE_PATH    <br> agent.tls.trustCertCollectionFilePath      |         | Trust certificate collection file path                                                         |
| --override                    <br> OVERRIDE_AUTHORITY                 <br> agent.tls.overrideAuthority                |         | Override authority (for testing)                                                               |
| --version, -v                                                                                                         |         | Print version info and exit                                                                    |
| --usage, -u                                                                                                           |         | Print usage message and exit                                                                   |
| -D                                                                                                                    |         | Dynamic property assignment                                                                    |

### Configuration Notes

* If you want to customize the logging, include the java arg `-Dlogback.configurationFile=/path/to/logback.xml`
* JSON config files must have a *.json* suffix
* Java Properties config files must have a *.properties*  or *.prop* suffix
* HOCON config files must have a *.conf* suffix
* Option values are evaluated in the order: CLI, environment variables, and finally config file vals
* Property values can be set as a java -D arg to or as a proxy or agent jar -D arg
* For more information about the proxy service discovery options, see
  the [Prometheus HTTP SD docs](https://prometheus.io/docs/prometheus/latest/http_sd/)
* A pathConfig `labels` value is a quote-escaped JSON string with key/value pairs. It is used to add additional service
  discovery context to a target.
* The gRPC keepalive values are described in the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/).

### Logging Configuration

Control logging levels with:

- **Environment vars:** `PROXY_LOG_LEVEL`, `AGENT_LOG_LEVEL`
- **CLI options:** `--log_level`
- **Properties:** `proxy.logLevel`, `agent.logLevel`

**Available levels:** `trace`, `debug`, `info`, `warn`, `error`, `off`

**Custom logging:** Use `-Dlogback.configurationFile=/path/to/logback.xml`

### Agent HTTP Client Cache Tuning

The agent caches HTTP clients for performance. The cache can be tuned with the following options:

- **`maxCacheSize`** (100): Maximum clients to cache
- **`maxCacheAgeMins`** (30): Maximum age before eviction
- **`maxCacheIdleMins`** (10): Maximum idle time before eviction
- **`cacheCleanupIntervalMins`** (5): Cleanup interval

The cache entries are keyed by the username and password used to access the agent. They are evicted when they reach the
`maxCacheAgeMins` or when they have been idle for `maxCacheIdleMins`. The cache is cleaned up every
`cacheCleanupIntervalMins`. They are reused when the same username and password are used to access the agent.

URLs without authentication share the same cache entry.

### Agent HTTP Client Usage

The agent uses HTTP clients to scrape metrics endpoints. The maximum number of HTTP clients used concurrently is
controlled by the `--max_concurrent_clients` CLI option, the `MAX_CONCURRENT_CLIENTS` environment var,
or the `agent.http.maxConcurrentClients` property. The default value is 1.

The HTTP client timeout is controlled by the `--client_timeout_secs` CLI option, the `CLIENT_TIMEOUT_SECS` environment
variable, or the `agent.http.clientTimeoutSecs` property. The default value is 90 seconds. This value replaces the
`agent.configVals.agent.internal.cioTimeoutSecs` value.

The maximum scrape response size is controlled by the `--max_content_length_mbytes` CLI option,
the `AGENT_MAX_CONTENT_LENGTH_MBYTES` environment variable, or the `agent.http.maxContentLengthMBytes` property.
The default value is 10 megabytes. Responses exceeding this size will be rejected with an `HTTP 413 Payload Too Large`
error to prevent memory exhaustion.

### Admin Servlets

These admin servlets are available when the admin servlet is enabled:

- **`/ping`** - Simple health check endpoint
- **`/threaddump`** - JVM thread dump for debugging
- **`/healthcheck`** - Comprehensive health status
- **`/version`** - Application version information
- **`/debug`** - Debug information (requires debug mode enabled)

Enable the admin servlets with `--admin` CLI option, `ADMIN_ENABLED` environment variable, or
`proxy/agent.admin.enabled` property.

Enable the debug servlet with the `DEBUG_ENABLED` environment var, the `--debug` CLI option, or with the
`proxy/agent.admin.debugEnabled` properties. The debug servlet also requires that the admin servlets
are enabled.

Descriptions of the servlets are [here](http://metrics.dropwizard.io/3.2.2/manual/servlets.html). The path names can be
changed in the configuration file. To disable an admin servlet, assign its property path to "".

## 📝 Examples & Use Cases

### Common Scenarios

- **🏢 Enterprise environments** - Scrape metrics across firewall boundaries
- **☁️ Multi-cloud deployments** - Bridge different network segments
- **🔒 Secure environments** - Monitor internal services without opening inbound ports
- **🌐 Federation** - Scrape existing Prometheus instances via `/federate` endpoint
- **🚀 Kubernetes** - Monitor services across clusters or namespaces

### Example Configurations

| Use Case                | Configuration                                                                                                                       |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Basic setup             | [`examples/simple.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/simple.conf)                             |
| Multiple applications   | [`examples/myapps.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/myapps.conf)                             |
| TLS without mutual auth | [`examples/tls-no-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-no-mutual-auth.conf)     |
| TLS with mutual auth    | [`examples/tls-with-mutual-auth.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/tls-with-mutual-auth.conf) |
| Prometheus federation   | [`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf)                         |
| Nginx reverse proxy     | [`nginx/nginx-proxy.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/nginx-proxy.conf)                         |

## 🔐 Advanced Configuration

### Prometheus Federation

It's possible to scrape an existing prometheus server using the `/federate` endpoint.
This enables using the existing service discovery features already built into Prometheus.

```hocon
agent {
  pathConfigs: [
    {
      name: "Federated Prometheus"
      path: "federated_metrics"
      url: "http://prometheus-server:9090/federate?match[]={__name__=~\"job:.*\"}"
    }
  ]
}
```

Another example config can be found in
[federate.conf](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf).

### Nginx Reverse Proxy Support

To use the Prometheus Proxy with nginx as a reverse proxy, disable the transport filter with the
`TRANSPORT_FILTER_DISABLED` environment var, the `--tf_disabled` CLI option, or the `agent.transportFilterDisabled`/
`proxy.transportFilterDisabled` properties. Agents and the Proxy must run with the same `transportFilterDisabled` value.

An example nginx conf file is [here](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf),
and an example agent/proxy conf file
is [here](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/nginx-proxy.conf)

```bash
# Both proxy and agent must use the same transport filter settings
java -jar prometheus-proxy.jar --tf_disabled

java -jar prometheus-agent.jar --tf_disabled --config myconfig.conf
```

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
    pambrose/prometheus-proxy:2.4.1

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:2.4.1
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
* [Typesafe Config](https://github.com/typesafehub/config)
