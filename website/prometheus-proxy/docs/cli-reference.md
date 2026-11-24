---
icon: lucide/terminal
# The page's tables of flags, variables, and keys take the table of contents' width to fit. A row's first cell holds
# its option over its variable, so its description has room, and an anchor named by its key (#agent.scrapeTimeoutSecs),
# which the configuration pages link to.
hide:
  - toc
---

# CLI Reference

Each row is one setting: its command-line option over its environment variable, its config key, and its default. An
option overrides the variable, which overrides the config file (see [Configuration](configuration/index.md)).

## Proxy Options

### Proxy Startup

| CLI Option / Env Var                                                | Property         | Default | Description                  |
|:--------------------------------------------------------------------|:-----------------|:--------|:-----------------------------|
| `--config, --conf, -c`<br>`PROXY_CONFIG`                            |                  |         | Config file path or URL      |
| <span id="proxy.logLevel"></span>`--log_level`<br>`PROXY_LOG_LEVEL` | `proxy.logLevel` | "info"  | Log level                    |
| `--version, -v`                                                     |                  |         | Print version info and exit  |
| `--usage, -u`                                                       |                  |         | Print usage message and exit |
| `-D`                                                                |                  |         | Dynamic property assignment  |

### Proxy Ports and Agents

| CLI Option / Env Var                                                                                          | Property                        | Default | Description                 |
|:--------------------------------------------------------------------------------------------------------------|:--------------------------------|:--------|:----------------------------|
| <span id="proxy.http.port"></span>`--port, -p`<br>`PROXY_PORT`                                                | `proxy.http.port`               | 8080    | HTTP listen port            |
| <span id="proxy.agent.port"></span>`--agent_port, -a`<br>`AGENT_PORT`                                         | `proxy.agent.port`              | 50051   | gRPC listen port for agents |
| <span id="proxy.agentToken"></span>`--agent_token`<br>`AGENT_TOKEN`                                           | `proxy.agentToken`              | ""      | Pre-shared agent auth token |
| <span id="proxy.transportFilterDisabled"></span>`--tf-disabled, --tf_disabled`<br>`TRANSPORT_FILTER_DISABLED` | `proxy.transportFilterDisabled` | false   | Disable transport filter    |
| <span id="proxy.reflectionDisabled"></span>`--ref-disabled, --ref_disabled`<br>`REFLECTION_DISABLED`          | `proxy.reflectionDisabled`      | true    | Disable gRPC reflection     |

### Proxy Admin and Metrics

| CLI Option / Env Var                                                         | Property                   | Default | Description               |
|:-----------------------------------------------------------------------------|:---------------------------|:--------|:--------------------------|
| <span id="proxy.admin.enabled"></span>`--admin, -r`<br>`ADMIN_ENABLED`       | `proxy.admin.enabled`      | false   | Enable admin endpoints    |
| <span id="proxy.admin.port"></span>`--admin_port, -i`<br>`ADMIN_PORT`        | `proxy.admin.port`         | 8092    | Admin listen port         |
| <span id="proxy.admin.debugEnabled"></span>`--debug, -b`<br>`DEBUG_ENABLED`  | `proxy.admin.debugEnabled` | false   | Enable debug servlet      |
| <span id="proxy.metrics.enabled"></span>`--metrics, -e`<br>`METRICS_ENABLED` | `proxy.metrics.enabled`    | false   | Enable metrics collection |
| <span id="proxy.metrics.port"></span>`--metrics_port, -m`<br>`METRICS_PORT`  | `proxy.metrics.port`       | 8082    | Metrics listen port       |

### Proxy Service Discovery

| CLI Option / Env Var                                                                              | Property                               | Default          | Description                  |
|:--------------------------------------------------------------------------------------------------|:---------------------------------------|:-----------------|:-----------------------------|
| <span id="proxy.service.discovery.enabled"></span>`--sd_enabled`<br>`SD_ENABLED`                  | `proxy.service.discovery.enabled`      | false            | Enable service discovery     |
| <span id="proxy.service.discovery.path"></span>`--sd_path`<br>`SD_PATH`                           | `proxy.service.discovery.path`         | "discovery"      | SD endpoint path             |
| <span id="proxy.service.discovery.targetPrefix"></span>`--sd_target_prefix`<br>`SD_TARGET_PREFIX` | `proxy.service.discovery.targetPrefix` | "localhost:8080" | SD target (host:port or URL) |

### Proxy Dashboard

| CLI Option / Env Var                                                           | Property                   | Default     | Description                  |
|:-------------------------------------------------------------------------------|:---------------------------|:------------|:-----------------------------|
| <span id="proxy.dashboard.enabled"></span>`--dashboard`<br>`DASHBOARD_ENABLED` | `proxy.dashboard.enabled`  | false       | Enable operational dashboard |
| <span id="proxy.dashboard.port"></span>`--dashboard_port`<br>`DASHBOARD_PORT`  | `proxy.dashboard.port`     | 8094        | Dashboard listen port        |
| <span id="proxy.dashboard.path"></span>`--dashboard_path`<br>`DASHBOARD_PATH`  | `proxy.dashboard.path`     | "dashboard" | Dashboard base path          |
| <span id="proxy.dashboard.host"></span>`--dashboard_host`<br>`DASHBOARD_HOST`  | `proxy.dashboard.host`     | "0.0.0.0"   | Dashboard listen address     |
| <span id="proxy.dashboard.username"></span>`DASHBOARD_USERNAME`                | `proxy.dashboard.username` | "admin"     | Dashboard login's username   |
| <span id="proxy.dashboard.password"></span>`DASHBOARD_PASSWORD`                | `proxy.dashboard.password` |             | Dashboard login's password   |

The dashboard's login settings have no command-line option, so a password never shows in a process listing: a
password turns the login on, with the username `admin` unless set (see [Login](web-dashboard.md#login)).

### Proxy TLS

| CLI Option / Env Var                                                                                       | Property                                | Default | Description             |
|:-----------------------------------------------------------------------------------------------------------|:----------------------------------------|:--------|:------------------------|
| <span id="proxy.tls.certChainFilePath"></span>`--cert, -t`<br>`CERT_CHAIN_FILE_PATH`                       | `proxy.tls.certChainFilePath`           |         | TLS cert chain file     |
| <span id="proxy.tls.privateKeyFilePath"></span>`--key, -k`<br>`PRIVATE_KEY_FILE_PATH`                      | `proxy.tls.privateKeyFilePath`          |         | TLS private key file    |
| <span id="proxy.tls.trustCertCollectionFilePath"></span>`--trust, -s`<br>`TRUST_CERT_COLLECTION_FILE_PATH` | `proxy.tls.trustCertCollectionFilePath` |         | TLS trust cert file     |
| <span id="proxy.tls.certChainPem"></span>`CERT_CHAIN_PEM`                                                  | `proxy.tls.certChainPem`                |         | TLS cert chain, as PEM  |
| <span id="proxy.tls.privateKeyPem"></span>`PRIVATE_KEY_PEM`                                                | `proxy.tls.privateKeyPem`               |         | TLS private key, as PEM |
| <span id="proxy.tls.trustCertCollectionPem"></span>`TRUST_CERT_COLLECTION_PEM`                             | `proxy.tls.trustCertCollectionPem`      |         | TLS trust certs, as PEM |

The `*_PEM` settings take a certificate or key itself, as PEM text or its base64, in place of the matching file, for
a platform that passes secrets as environment variables (see
[TLS Setup](security/tls.md#certificates-as-environment-variables)). They have no command-line option, so a key never
shows in a process listing.

### Proxy gRPC { #proxy-grpc-options }

| CLI Option / Env Var                                                                                                            | Property                                 | Default | Description                                 |
|:--------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------|:--------|:--------------------------------------------|
| <span id="proxy.grpc.handshakeTimeoutSecs"></span>`--handshake_timeout_secs`<br>`HANDSHAKE_TIMEOUT_SECS`                        | `proxy.grpc.handshakeTimeoutSecs`        | 120     | Handshake timeout (seconds)                 |
| <span id="proxy.grpc.keepAliveTimeSecs"></span>`--keepalive_time_secs`<br>`KEEPALIVE_TIME_SECS`                                 | `proxy.grpc.keepAliveTimeSecs`           | 7200    | Interval between PING frames (seconds)      |
| <span id="proxy.grpc.keepAliveTimeoutSecs"></span>`--keepalive_timeout_secs`<br>`KEEPALIVE_TIMEOUT_SECS`                        | `proxy.grpc.keepAliveTimeoutSecs`        | 20      | Timeout for PING acknowledgment (seconds)   |
| <span id="proxy.grpc.permitKeepAliveWithoutCalls"></span>`--permit_keepalive_without_calls`<br>`PERMIT_KEEPALIVE_WITHOUT_CALLS` | `proxy.grpc.permitKeepAliveWithoutCalls` | false   | Allow keepalive without active streams      |
| <span id="proxy.grpc.permitKeepAliveTimeSecs"></span>`--permit_keepalive_time_secs`<br>`PERMIT_KEEPALIVE_TIME_SECS`             | `proxy.grpc.permitKeepAliveTimeSecs`     | 300     | Min interval between client PINGs (seconds) |
| <span id="proxy.grpc.maxConnectionIdleSecs"></span>`--max_connection_idle_secs`<br>`MAX_CONNECTION_IDLE_SECS`                   | `proxy.grpc.maxConnectionIdleSecs`       | INT_MAX | Max idle time for a channel (seconds)       |
| <span id="proxy.grpc.maxConnectionAgeSecs"></span>`--max_connection_age_secs`<br>`MAX_CONNECTION_AGE_SECS`                      | `proxy.grpc.maxConnectionAgeSecs`        | INT_MAX | Max lifetime for a channel (seconds)        |
| <span id="proxy.grpc.maxConnectionAgeGraceSecs"></span>`--max_connection_age_grace_secs`<br>`MAX_CONNECTION_AGE_GRACE_SECS`     | `proxy.grpc.maxConnectionAgeGraceSecs`   | INT_MAX | Grace period after max age (seconds)        |

## Agent Options

### Agent Startup

| CLI Option / Env Var                                                | Property         | Default | Description                        |
|:--------------------------------------------------------------------|:-----------------|:--------|:-----------------------------------|
| `--config, --conf, -c`<br>`AGENT_CONFIG`                            |                  |         | Config file path or URL (required) |
| <span id="agent.name"></span>`--name, -n`<br>`AGENT_NAME`           | `agent.name`     |         | Agent name                         |
| <span id="agent.logLevel"></span>`--log_level`<br>`AGENT_LOG_LEVEL` | `agent.logLevel` | "info"  | Log level                          |
| `--version, -v`                                                     |                  |         | Print version info and exit        |
| `--usage, -u`                                                       |                  |         | Print usage message and exit       |
| `-D`                                                                |                  |         | Dynamic property assignment        |

### Agent Proxy Connection

| CLI Option / Env Var                                                                                          | Property                        | Default     | Description                                         |
|:--------------------------------------------------------------------------------------------------------------|:--------------------------------|:------------|:----------------------------------------------------|
| <span id="agent.proxy.hostname"></span>`--proxy, -p`<br>`PROXY_HOSTNAME`                                      | `agent.proxy.hostname`          | "localhost" | Proxy host[:port], or comma-separated failover list |
| <span id="agent.agentToken"></span>`--agent_token`<br>`AGENT_TOKEN`                                           | `agent.agentToken`              | ""          | Pre-shared token presented to proxy                 |
| <span id="agent.transportFilterDisabled"></span>`--tf-disabled, --tf_disabled`<br>`TRANSPORT_FILTER_DISABLED` | `agent.transportFilterDisabled` | false       | Disable transport filter                            |
| <span id="agent.consolidated"></span>`--consolidated, -o`<br>`CONSOLIDATED`                                   | `agent.consolidated`            | false       | Allow multiple agents per path                      |

`--proxy` and `PROXY_HOSTNAME` replace `agent.proxy.endpoints`; with neither set, the agent reads that list, and
reads `agent.proxy.hostname` only when it's empty (see [Proxy failover](configuration/agent.md#proxy-failover)).

### Agent Admin and Metrics

| CLI Option / Env Var                                                                     | Property                   | Default | Description                                     |
|:-----------------------------------------------------------------------------------------|:---------------------------|:--------|:------------------------------------------------|
| <span id="agent.admin.enabled"></span>`--admin, -r`<br>`ADMIN_ENABLED`                   | `agent.admin.enabled`      | false   | Enable admin endpoints                          |
| <span id="agent.admin.port"></span>`--admin_port, -i`<br>`ADMIN_PORT`                    | `agent.admin.port`         | 8093    | Admin listen port                               |
| <span id="agent.admin.debugEnabled"></span>`--debug, -b`<br>`DEBUG_ENABLED`              | `agent.admin.debugEnabled` | false   | Enable debug servlet                            |
| <span id="agent.metrics.enabled"></span>`--metrics, -e`<br>`METRICS_ENABLED`             | `agent.metrics.enabled`    | false   | Enable metrics collection                       |
| <span id="agent.metrics.port"></span>`--metrics_port, -m`<br>`METRICS_PORT`              | `agent.metrics.port`       | 8083    | Metrics listen port                             |
| <span id="agent.metrics.proxyPath"></span>`--metrics_proxy_path`<br>`METRICS_PROXY_PATH` | `agent.metrics.proxyPath`  | ""      | Proxy path for the agent's own metrics, no port |

`--metrics_proxy_path` (since 4.7.0) has the agent serve its own metrics through the proxy at that path, read
in-process, with or without the metrics port; its labels, `agent.metrics.proxyPathLabels`, have no command-line option
or variable (see [Monitoring](monitoring.md#port-or-proxy-path)).

### Agent Dashboard

| CLI Option / Env Var                                                           | Property                   | Default     | Description                      |
|:-------------------------------------------------------------------------------|:---------------------------|:------------|:---------------------------------|
| <span id="agent.dashboard.enabled"></span>`--dashboard`<br>`DASHBOARD_ENABLED` | `agent.dashboard.enabled`  | false       | Enable the agent's dashboard     |
| <span id="agent.dashboard.port"></span>`--dashboard_port`<br>`DASHBOARD_PORT`  | `agent.dashboard.port`     | 8095        | Agent dashboard listen port      |
| <span id="agent.dashboard.path"></span>`--dashboard_path`<br>`DASHBOARD_PATH`  | `agent.dashboard.path`     | "dashboard" | Agent dashboard base path        |
| <span id="agent.dashboard.host"></span>`--dashboard_host`<br>`DASHBOARD_HOST`  | `agent.dashboard.host`     | "0.0.0.0"   | Agent dashboard listen address   |
| <span id="agent.dashboard.username"></span>`DASHBOARD_USERNAME`                | `agent.dashboard.username` | "admin"     | Agent dashboard login's username |
| <span id="agent.dashboard.password"></span>`DASHBOARD_PASSWORD`                | `agent.dashboard.password` |             | Agent dashboard login's password |

As for the proxy, the dashboard's login settings have no command-line option (see
[Agent Dashboard](agent-dashboard.md)). The `DASHBOARD_*` variables have the same names as the proxy's, and each
process reads them for its own dashboard, as with `ADMIN_*` and `METRICS_*`.

### Agent Scraping

| CLI Option / Env Var                                                              | Property                    | Default | Description                            |
|:----------------------------------------------------------------------------------|:----------------------------|:--------|:---------------------------------------|
| <span id="agent.scrapeTimeoutSecs"></span>`--timeout`<br>`SCRAPE_TIMEOUT_SECS`    | `agent.scrapeTimeoutSecs`   | 15      | Scrape timeout (seconds)               |
| <span id="agent.scrapeMaxRetries"></span>`--max_retries`<br>`SCRAPE_MAX_RETRIES`  | `agent.scrapeMaxRetries`    | 0       | Max scrape retries (0 = disabled)      |
| <span id="agent.chunkContentSizeKbs"></span>`--chunk`<br>`CHUNK_CONTENT_SIZE_KBS` | `agent.chunkContentSizeKbs` | 32      | Chunking threshold (KB, max 4032)      |
| <span id="agent.minGzipSizeBytes"></span>`--gzip`<br>`MIN_GZIP_SIZE_BYTES`        | `agent.minGzipSizeBytes`    | 512     | Min size for gzip (bytes, max 4128768) |

### Agent HTTP Client

| CLI Option / Env Var                                                                                            | Property                                    | Default | Description                   |
|:----------------------------------------------------------------------------------------------------------------|:--------------------------------------------|:--------|:------------------------------|
| <span id="agent.http.enableTrustAllX509Certificates"></span>`--trust_all_x509`<br>`TRUST_ALL_X509_CERTIFICATES` | `agent.http.enableTrustAllX509Certificates` | false   | Disable SSL verification      |
| <span id="agent.http.trustStorePath"></span>`--https_truststore`<br>`HTTPS_TRUST_STORE_PATH`                    | `agent.http.trustStorePath`                 | ""      | Trust store for HTTPS targets |
| <span id="agent.http.trustStorePassword"></span>`--https_truststore_password`<br>`HTTPS_TRUST_STORE_PASSWORD`   | `agent.http.trustStorePassword`             | ""      | Password for the trust store  |
| <span id="agent.http.maxConcurrentClients"></span>`--max_concurrent_clients`<br>`MAX_CONCURRENT_CLIENTS`        | `agent.http.maxConcurrentClients`           | 1       | Max parallel scrapes          |
| <span id="agent.http.clientTimeoutSecs"></span>`--client_timeout_secs`<br>`CLIENT_TIMEOUT_SECS`                 | `agent.http.clientTimeoutSecs`              | 90      | HTTP client timeout (seconds) |
| <span id="agent.http.maxContentLengthMBytes"></span>`--max_content_length_mbytes`                               | `agent.http.maxContentLengthMBytes`         | 10      | Max response size (MB)        |

### Agent HTTP Client Cache { #agent-http-client-cache-options }

| CLI Option / Env Var                                                                                                                 | Property                                     | Default | Description                             |
|:-------------------------------------------------------------------------------------------------------------------------------------|:---------------------------------------------|:--------|:----------------------------------------|
| <span id="agent.http.clientCache.maxSize"></span>`--max_cache_size`<br>`MAX_CLIENT_CACHE_SIZE`                                       | `agent.http.clientCache.maxSize`             | 100     | Max cached HTTP clients                 |
| <span id="agent.http.clientCache.maxAgeMins"></span>`--max_cache_age_mins`<br>`MAX_CLIENT_CACHE_AGE_MINS`                            | `agent.http.clientCache.maxAgeMins`          | 30      | Max age of cached clients (minutes)     |
| <span id="agent.http.clientCache.maxIdleMins"></span>`--max_cache_idle_mins`<br>`MAX_CLIENT_CACHE_IDLE_MINS`                         | `agent.http.clientCache.maxIdleMins`         | 10      | Max idle time before eviction (minutes) |
| <span id="agent.http.clientCache.cleanupIntervalMins"></span>`--cache_cleanup_interval_mins`<br>`CLIENT_CACHE_CLEANUP_INTERVAL_MINS` | `agent.http.clientCache.cleanupIntervalMins` | 5       | Cleanup interval (minutes)              |

### Agent TLS

| CLI Option / Env Var                                                                                       | Property                                | Default | Description             |
|:-----------------------------------------------------------------------------------------------------------|:----------------------------------------|:--------|:------------------------|
| <span id="agent.tls.certChainFilePath"></span>`--cert, -t`<br>`CERT_CHAIN_FILE_PATH`                       | `agent.tls.certChainFilePath`           |         | TLS cert chain file     |
| <span id="agent.tls.privateKeyFilePath"></span>`--key, -k`<br>`PRIVATE_KEY_FILE_PATH`                      | `agent.tls.privateKeyFilePath`          |         | TLS private key file    |
| <span id="agent.tls.trustCertCollectionFilePath"></span>`--trust, -s`<br>`TRUST_CERT_COLLECTION_FILE_PATH` | `agent.tls.trustCertCollectionFilePath` |         | TLS trust cert file     |
| <span id="agent.tls.certChainPem"></span>`CERT_CHAIN_PEM`                                                  | `agent.tls.certChainPem`                |         | TLS cert chain, as PEM  |
| <span id="agent.tls.privateKeyPem"></span>`PRIVATE_KEY_PEM`                                                | `agent.tls.privateKeyPem`               |         | TLS private key, as PEM |
| <span id="agent.tls.trustCertCollectionPem"></span>`TRUST_CERT_COLLECTION_PEM`                             | `agent.tls.trustCertCollectionPem`      |         | TLS trust certs, as PEM |
| <span id="agent.tls.overrideAuthority"></span>`--override, --over`<br>`OVERRIDE_AUTHORITY`                 | `agent.tls.overrideAuthority`           |         | TLS authority override  |

As for the proxy, the `*_PEM` settings take a certificate or key as PEM text or its base64, and have no
command-line option.

### Agent gRPC { #agent-grpc-options }

| CLI Option / Env Var                                                                                        | Property                           | Default | Description                               |
|:------------------------------------------------------------------------------------------------------------|:-----------------------------------|:--------|:------------------------------------------|
| <span id="agent.grpc.keepAliveTimeSecs"></span>`--keepalive_time_secs`<br>`KEEPALIVE_TIME_SECS`             | `agent.grpc.keepAliveTimeSecs`     | INT_MAX | Interval between PING frames (seconds)    |
| <span id="agent.grpc.keepAliveTimeoutSecs"></span>`--keepalive_timeout_secs`<br>`KEEPALIVE_TIMEOUT_SECS`    | `agent.grpc.keepAliveTimeoutSecs`  | 20      | Timeout for PING acknowledgment (seconds) |
| <span id="agent.grpc.keepAliveWithoutCalls"></span>`--keepalive_without_calls`<br>`KEEPALIVE_WITHOUT_CALLS` | `agent.grpc.keepAliveWithoutCalls` | false   | Allow keepalive without active streams    |
| <span id="agent.grpc.unaryDeadlineSecs"></span>`--unary_deadline_secs`<br>`UNARY_DEADLINE_SECS`             | `agent.grpc.unaryDeadlineSecs`     | 30      | Timeout for unary RPCs (seconds)          |

## Log Levels

Available log levels for both proxy and agent:

`trace`, `debug`, `info`, `warn`, `error`, `off`

The deprecated `all` level was removed in 3.2.0 — use `trace` for the most verbose output.

## Validation

Options are validated at startup and an invalid value fails fast with a clear message rather than an
opaque bind or builder error:

- **Ports** — `--port` / `--agent_port` (proxy), `--admin_port`, and `--metrics_port` must be in `1..65535`.
- **Agent scrape timeout** — `--timeout` / `agent.scrapeTimeoutSecs` must be `> 0`.
- **gRPC keepalive timeouts** — must be `-1` (use the gRPC default) or `> 0`.

## Dynamic Properties

Use `-D` to set any scalar configuration value. It overrides the config file, but a CLI option or environment variable
for the same setting overrides it:

```bash
java -jar prometheus-proxy.jar \
  -Dproxy.http.port=9090 \
  -Dproxy.admin.enabled=true \
  -Dproxy.metrics.enabled=true

java -jar prometheus-agent.jar \
  -Dagent.proxy.hostname=myproxy.com \
  -Dagent.http.maxConcurrentClients=5 \
  --config agent.conf
```
