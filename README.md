# Prometheus Proxy

[![JitPack](https://jitpack.io/v/pambrose/prometheus-proxy.svg)](https://jitpack.io/#pambrose/prometheus-proxy)
[![Build Status](https://app.travis-ci.com/pambrose/prometheus-proxy.svg?branch=master)](https://app.travis-ci.com/pambrose/prometheus-proxy)
[![codebeat badge](https://codebeat.co/badges/8dbe1dc6-628e-44a4-99f9-d468831ff0cc)](https://codebeat.co/projects/github-com-pambrose-prometheus-proxy-master)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://app.codacy.com/gh/pambrose/prometheus-proxy/dashboard)
[![codecov](https://codecov.io/gh/pambrose/prometheus-proxy/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/prometheus-proxy)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/prometheus-proxy/badge.svg?branch=master)](https://coveralls.io/github/pambrose/prometheus-proxy?branch=master)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)
[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081)](https://pinterest.github.io/ktlint/)

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for
collecting metrics. The pull model is problematic when a firewall separates a Prometheus server and its metrics
endpoints.
[Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) enables Prometheus to reach metrics endpoints running
behind a firewall and preserves the pull model.

The `prometheus-proxy` runtime comprises two services:

* `proxy`: runs in the same network domain as Prometheus server (outside the firewall) and proxies calls from Prometheus
  to the `agent` behind the firewall.
* `agent`: runs in the same network domain as all the monitored hosts/services/apps (inside the firewall). It maps the
  scraping queries coming from the `proxy` to the actual `/metrics` scraping endpoints of the hosts/services/apps.

Here's a simplified network diagram of how the deployed `proxy` and `agent` work:

![network diagram](https://github.com/pambrose/prometheus-proxy/raw/master/docs/prometheus-proxy.png)

Endpoints running behind a firewall require a `prometheus-agent` (the agent) to be run inside the firewall. An agent can
run as a stand-alone server, embedded in another java server, or as a java agent. Agents connect to
a `prometheus-proxy` (the proxy) and register the paths for which they will provide data. One proxy can work one or many
agents.

## Requirements

Requires Java 11 or newer.

## CLI Usage

Download the proxy and agent uber-jars from [here](https://github.com/pambrose/prometheus-proxy/releases).

Start a `proxy` with:

```bash
java -jar prometheus-proxy.jar
```

Start an `agent` with:

```bash
java -jar prometheus-agent.jar -Dagent.proxy.hostname=mymachine.local --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf
```

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

## Docker Usage

The docker images are available via:

```bash
docker pull pambrose/prometheus-proxy:2.2.1-beta3
docker pull pambrose/prometheus-agent:2.2.1-beta3
```

Start a proxy container with:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        pambrose/prometheus-proxy:2.2.1-beta3
```

Start an agent container with:

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:2.2.1-beta3
```

Using the config
file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf), the proxy
and the agent metrics would be available from the proxy on *localhost* at:

* http://localhost:8082/proxy_metrics
* http://localhost:8083/agent_metrics

If you want to use a local config file with a docker container (instead of the above HTTP-served config file), use the
docker [mount](https://docs.docker.com/storage/bind-mounts/) option. Assuming the config file `prom-agent.conf`
is in your current directory, run an agent container with:

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/prom-agent.conf,target=/app/prom-agent.conf \
    --env AGENT_CONFIG=prom-agent.conf \
    pambrose/prometheus-agent:2.2.1-beta3
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

## Embedded Agent Usage

If you are running a JVM-based program, you can run with the agent embedded directly in your app and not use an external
agent:

```Java
EmbeddedAgentInfo agentInfo = startAsyncAgent("configFile.conf", true);
```

## Configuration

The proxy and agent use the [Typesafe Config](https://github.com/typesafehub/config) library for configuration.
Highlights include:

* support for files in three formats: Java properties, JSON, and a human-friendly JSON
  superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment vars, Java system properties, and/or config files.
* config files can reference environment variables

All the proxy and agent properties are
described [here](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf). The only required
argument is an agent config value, which should have an `agent.pathConfigs` value.

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
| --max_cache_size              <br> MAX_CLIENT_CACHE_SIZE              <br> agent.http.clientCache.maxSize             | 100     | Maximum number of HTTP clients to cache                                                        |
| --max_cache_age_mins          <br> MAX_CLIENT_CACHE_AGE_MINS          <br> agent.http.clientCache.maxAgeMins          | 30      | Maximum age of cached HTTP clients (minutes)                                                   |
| --max_cache_idle_mins         <br> MAX_CLIENT_CACHE_IDLE_MINS         <br> agent.http.clientCache.maxIdleMins         | 10      | Maximum idle time before HTTP client is evicted (minutes)                                      |
| --cache_cleanup_interval_mins <br> CLIENT_CACHE_CLEANUP_INTERVAL_MINS <br> agent.http.clientCache.cleanupIntervalMins | 5       | Interval between HTTP client cache cleanup runs (minutes)                                      |
| --keepalive_time_secs         <br> KEEPALIVE_TIME_SECS                <br> agent.grpc.keepAliveTimeSecs               | INT_MAX | The interval between gRPC PING frames (seconds)                                                |
| --keepalive_timeout_secs      <br> KEEPALIVE_TIMEOUT_SECS             <br> agent.grpc.keepAliveTimeoutSecs            | 20      | The timeout for a PING gRPC frame to be acknowledged (seconds)                                 |
| --keepalive_without_calls     <br> KEEPALIVE_WITHOUT_CALLS            <br> agent.grpc.keepAliveWithoutCalls           | false   | Is it permissible to send gRPC keepalive pings from the client without any outstanding streams |
| --log_level                   <br> AGENT_LOG_LEVEL                    <br> agent.logLevel                             | "info"  | Log level ("trace", "debug", "info", "warn", "error", "off")                                   |
| --cert, -t                    <br> CERT_CHAIN_FILE_PATH               <br> agent.tls.certChainFilePath                |         | Certificate chain file path                                                                    |
| --key, -k                     <br> PRIVATE_KEY_FILE_PATH              <br> agent.tls.privateKeyFilePath               |         | Private key file path                                                                          |
| --trust, -s                   <br> TRUST_CERT_COLLECTION_FILE_PATH    <br> agent.tls.trustCertCollectionFilePath      |         | Trust certificate collection file path                                                         |
| --override                    <br> OVERRIDE_AUTHORITY                 <br> agent.tls.overrideAuthority                |         | Override authority (for testing)                                                               |
| --version, -v                                                                                                         |         | Print version info and exit                                                                    |
| --usage, -u                                                                                                           |         | Print usage message and exit                                                                   |
| -D                                                                                                                    |         | Dynamic property assignment                                                                    |

Misc notes:

* If you want to customize the logging, include the java arg `-Dlogback.configurationFile=/path/to/logback.xml`
* JSON config files must have a *.json* suffix
* Java Properties config files must have a *.properties*  or *.prop* suffix
* HOCON config files must have a *.conf* suffix
* Option values are evaluated in the order: CLI, environment variables and finally config file vals
* Property values can be set as a java -D arg to or as a proxy or agent jar -D arg
* For more information about the proxy service discovery options, see the
  Prometheus [documentation](https://prometheus.io/docs/prometheus/latest/http_sd/)
* A pathConfig `labels` value is a quote-escaped JSON string with key/value pairs. It is used to add additional service
  discovery context to a target.
* The gRPC keepalive values are described [here](https://grpc.io/docs/guides/keepalive/).

### Logging

The log level can be set with the `PROXY_LOG_LEVEL` and `AGENT_LOG_LEVEL` environment vars, the `--log_level` CLI
option,
or the `proxy.logLevel` and `agent.logLevel` properties. The log level can be one of: `trace`, `debug`, `info`, `warn`,
`error`, or `off`.

### Tuning the Agent HTTP Client Cache

The agent caches HTTP clients for reuse. The cache can be tuned with the following options:

* `--max_cache_size`, `MAX_CLIENT_CACHE_SIZE` or `agent.http.clientCache.maxSize`: Maximum number of HTTP clients to
  cache
* `--max_cache_age_mins`, `MAX_CLIENT_CACHE_AGE_MINS` or `agent.http.clientCache.maxAgeMins`: Maximum age of cached
  clients in minutes
* `--max_cache_idle_mins`, `MAX_CLIENT_CACHE_IDLE_MINS` or `agent.http.clientCache.maxIdleMins`: Maximum idle time
  before the client is evicted in
  minutes
* `--cache_cleanup_interval_mins` or `agent.http.clientCache.cleanupIntervalMins`: Interval between cache cleanup runs
  in minutes

The default values are:

* `maxCacheSize`: 100
* `maxCacheAgeMins`: 30
* `maxCacheIdleMins`: 10
* `cacheCleanupIntervalMins`: 5

The cache entries are keyed by the username and password used to access the agent. They are evicted when they reach the
`maxCacheAgeMins` or when they have been idle for `maxCacheIdleMins`. The cache is cleaned up every
`cacheCleanupIntervalMins`. They are reused when the same username and password are used to access the agent. All URLs
without authentication are considered to have the same username and password and are therefore also candidates for
reuse.

### Agent HTTP Client Usage

The agent uses HTTP clients to scrape metrics endpoints. The maximum number of HTTP clients used concurrently is
controlled by the `--max_concurrent_clients` CLI option, the `MAX_CONCURRENT_CLIENTS` environment var,
or the `agent.http.maxConcurrentClients` property. The default value is 1.

### Admin Servlets

These admin servlets are available when the admin servlet is enabled:

* /ping
* /threaddump
* /healthcheck
* /version

The admin servlets can be enabled with the `ADMIN_ENABLED` environment var, the `--admin` CLI option, or with the
`proxy.admin.enabled` and `agent.admin.enabled` properties.

The debug servlet can be enabled with the `DEBUG_ENABLED` environment var, the `--debug` CLI option, or with the
`proxy.admin.debugEnabled` and `agent.admin.debugEnabled` properties. The debug servlet requires that the admin servlets
are enabled. The debug servlet is at: `/debug` on the admin port.

Descriptions of the servlets are [here](http://metrics.dropwizard.io/3.2.2/manual/servlets.html). The path names can be
changed in the configuration file. To disable an admin servlet, assign its property path to "".

## Adding TLS to Agent-Proxy Connections

Agents connect to a proxy using [gRPC](https://grpc.io). gRPC supports TLS with or without mutual authentication. The
necessary certificate and key file paths can be specified via CLI args, environment variables and configuration file
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

### Running with TLS

Run a proxy and an agent with TLS (no mutual auth) using the included testing certs and keys with:

```bash
java -jar prometheus-proxy.jar --config examples/tls-no-mutual-auth.conf
java -jar prometheus-agent.jar --config examples/tls-no-mutual-auth.conf
```

Run a proxy and an agent docker container with TLS (no mutual auth) using the included testing certs and keys with:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50440:50440 -p 8080:8080 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env PROXY_CONFIG=tls-no-mutual-auth.conf \
    --env ADMIN_ENABLED=true \
    --env METRICS_ENABLED=true \
    pambrose/prometheus-proxy:2.2.1-beta3

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:2.2.1-beta3
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure to use `/app` as the base directory in the
target for `--mount` options.

## Scraping HTTPS Endpoints

Disable SSL verification for agent https endpoints with the `TRUST_ALL_X509_CERTIFICATES` environment var,
the `--trust_all_x509` CLI option, or the `agent.http.enableTrustAllX509Certificates` property.

## Scraping a Prometheus Instance

It's possible to scrape an existing prometheus server using the `/federate` endpoint.
This enables using the existing service discovery features already built into Prometheus.

An example config can be found in
[federate.conf](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf).

## Nginx Support

To use the prometheus_proxy with nginx as a reverse proxy, disable the transport filter with the
`TRANSPORT_FILTER_DISABLED` environment var, the `--tf-disabled` CLI option, or the `agent.transportFilterDisabled`/
`proxy.transportFilterDisabled` properties. Agents and the Proxy must run with the same `transporFilterDisabled` value.

When using `transporFilterDisabled`, you will not see agent contexts immediately removed
from the proxy when agents are terminated. Instead, agent contexts will be removed from the proxy
after they age out from inactivity. The maximum age is controlled by the `proxy.internal.maxAgentInactivitySecs` value.
The default value is 1 minute.

An example nginx conf file is [here](https://github.com/pambrose/prometheus-proxy/tree/master/nginx/docker/nginx.conf),
and an example agent/proxy conf file
is [here](https://github.com/pambrose/prometheus-proxy/tree/master/nginx/nginx-proxy.conf)

## gRPC Reflection

The [gRPC Reflection](https://grpc.io/docs/guides/reflection/) service is enabled by default.

To disable gRPC Reflection support, set the `REFLECTION_DISABLED` environment var,
the `--reflection_disabled` CLI option, or the `proxy.reflectionDisabled` property to true.

To use [grpcurl](https://github.com/fullstorydev/grpcurl) to test the reflection service, run:

```bash
grpcurl -plaintext localhost:50051 list
```

If you use the grpcurl `-plaintext` option, make sure that you run the proxy in plaintext
mode, i.e., do not define any TLS properties.

## Grafana

[Grafana](https://grafana.com) dashboards for the proxy and agent
are [here](https://github.com/pambrose/prometheus-proxy/tree/master/grafana).

## Related Links

* [Prometheus.io](http://prometheus.io)
* [gRPC](http://grpc.io)
* [Typesafe Config](https://github.com/typesafehub/config)
