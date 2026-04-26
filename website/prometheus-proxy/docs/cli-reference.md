---
icon: lucide/terminal
---

# CLI Reference

## Proxy Options

| CLI Option           | Env Var                           | Property                                | Default                  | Description                  |
|:---------------------|:----------------------------------|:----------------------------------------|:-------------------------|:-----------------------------|
| `--config, -c`       | `PROXY_CONFIG`                    |                                         |                          | Config file path or URL      |
| `--port, -p`         | `PROXY_PORT`                      | `proxy.http.port`                       | 8080                     | HTTP listen port             |
| `--agent_port, -a`   | `AGENT_PORT`                      | `proxy.agent.port`                      | 50051                    | gRPC listen port for agents  |
| `--admin, -r`        | `ADMIN_ENABLED`                   | `proxy.admin.enabled`                   | false                    | Enable admin endpoints       |
| `--admin_port, -i`   | `ADMIN_PORT`                      | `proxy.admin.port`                      | 8092                     | Admin listen port            |
| `--debug, -b`        | `DEBUG_ENABLED`                   | `proxy.admin.debugEnabled`              | false                    | Enable debug servlet         |
| `--metrics, -e`      | `METRICS_ENABLED`                 | `proxy.metrics.enabled`                 | false                    | Enable metrics collection    |
| `--metrics_port, -m` | `METRICS_PORT`                    | `proxy.metrics.port`                    | 8082                     | Metrics listen port          |
| `--sd_enabled`       | `SD_ENABLED`                      | `proxy.service.discovery.enabled`       | false                    | Enable service discovery     |
| `--sd_path`          | `SD_PATH`                         | `proxy.service.discovery.path`          | "discovery"              | SD endpoint path             |
| `--sd_target_prefix` | `SD_TARGET_PREFIX`                | `proxy.service.discovery.targetPrefix`  | "http://localhost:8080/" | SD target prefix             |
| `--tf_disabled`      | `TRANSPORT_FILTER_DISABLED`       | `proxy.transportFilterDisabled`         | false                    | Disable transport filter     |
| `--ref_disabled`     | `REFLECTION_DISABLED`             | `proxy.reflectionDisabled`              | false                    | Disable gRPC reflection      |
| `--log_level`        | `PROXY_LOG_LEVEL`                 | `proxy.logLevel`                        | "info"                   | Log level                    |
| `--cert, -t`         | `CERT_CHAIN_FILE_PATH`            | `proxy.tls.certChainFilePath`           |                          | TLS cert chain file          |
| `--key, -k`          | `PRIVATE_KEY_FILE_PATH`           | `proxy.tls.privateKeyFilePath`          |                          | TLS private key file         |
| `--trust, -s`        | `TRUST_CERT_COLLECTION_FILE_PATH` | `proxy.tls.trustCertCollectionFilePath` |                          | TLS trust cert file          |
| `--version, -v`      |                                   |                                         |                          | Print version info and exit  |
| `--usage, -u`        |                                   |                                         |                          | Print usage message and exit |
| `-D`                 |                                   |                                         |                          | Dynamic property assignment  |

### Proxy gRPC Options

| CLI Option                         | Env Var                          | Property                                 | Default | Description                                 |
|:-----------------------------------|:---------------------------------|:-----------------------------------------|:--------|:--------------------------------------------|
| `--handshake_timeout_secs`         | `HANDSHAKE_TIMEOUT_SECS`         | `proxy.grpc.handshakeTimeoutSecs`        | 120     | Handshake timeout (seconds)                 |
| `--keepalive_time_secs`            | `KEEPALIVE_TIME_SECS`            | `proxy.grpc.keepAliveTimeSecs`           | 7200    | Interval between PING frames (seconds)      |
| `--keepalive_timeout_secs`         | `KEEPALIVE_TIMEOUT_SECS`         | `proxy.grpc.keepAliveTimeoutSecs`        | 20      | Timeout for PING acknowledgment (seconds)   |
| `--permit_keepalive_without_calls` | `PERMIT_KEEPALIVE_WITHOUT_CALLS` | `proxy.grpc.permitKeepAliveWithoutCalls` | false   | Allow keepalive without active streams      |
| `--permit_keepalive_time_secs`     | `PERMIT_KEEPALIVE_TIME_SECS`     | `proxy.grpc.permitKeepAliveTimeSecs`     | 300     | Min interval between client PINGs (seconds) |
| `--max_connection_idle_secs`       | `MAX_CONNECTION_IDLE_SECS`       | `proxy.grpc.maxConnectionIdleSecs`       | INT_MAX | Max idle time for a channel (seconds)       |
| `--max_connection_age_secs`        | `MAX_CONNECTION_AGE_SECS`        | `proxy.grpc.maxConnectionAgeSecs`        | INT_MAX | Max lifetime for a channel (seconds)        |
| `--max_connection_age_grace_secs`  | `MAX_CONNECTION_AGE_GRACE_SECS`  | `proxy.grpc.maxConnectionAgeGraceSecs`   | INT_MAX | Grace period after max age (seconds)        |

## Agent Options

| CLI Option                    | Env Var                           | Property                                    | Default | Description                        |
|:------------------------------|:----------------------------------|:--------------------------------------------|:--------|:-----------------------------------|
| `--config, -c`                | `AGENT_CONFIG`                    |                                             |         | Config file path or URL (required) |
| `--proxy, -p`                 | `PROXY_HOSTNAME`                  | `agent.proxy.hostname`                      |         | Proxy hostname (can include :port) |
| `--name, -n`                  | `AGENT_NAME`                      | `agent.name`                                |         | Agent name                         |
| `--admin, -r`                 | `ADMIN_ENABLED`                   | `agent.admin.enabled`                       | false   | Enable admin endpoints             |
| `--admin_port, -i`            | `ADMIN_PORT`                      | `agent.admin.port`                          | 8093    | Admin listen port                  |
| `--debug, -b`                 | `DEBUG_ENABLED`                   | `agent.admin.debugEnabled`                  | false   | Enable debug servlet               |
| `--metrics, -e`               | `METRICS_ENABLED`                 | `agent.metrics.enabled`                     | false   | Enable metrics collection          |
| `--metrics_port, -m`          | `METRICS_PORT`                    | `agent.metrics.port`                        | 8083    | Metrics listen port                |
| `--consolidated, -o`          | `CONSOLIDATED`                    | `agent.consolidated`                        | false   | Allow multiple agents per path     |
| `--timeout`                   | `SCRAPE_TIMEOUT_SECS`             | `agent.scrapeTimeoutSecs`                   | 15      | Scrape timeout (seconds)           |
| `--max_retries`               | `SCRAPE_MAX_RETRIES`              | `agent.scrapeMaxRetries`                    | 0       | Max scrape retries (0 = disabled)  |
| `--chunk`                     | `CHUNK_CONTENT_SIZE_KBS`          | `agent.chunkContentSizeKbs`                 | 32      | Chunking threshold (KB)            |
| `--gzip`                      | `MIN_GZIP_SIZE_BYTES`             | `agent.minGzipSizeBytes`                    | 1024    | Min size for gzip (bytes)          |
| `--tf_disabled`               | `TRANSPORT_FILTER_DISABLED`       | `agent.transportFilterDisabled`             | false   | Disable transport filter           |
| `--trust_all_x509`            | `TRUST_ALL_X509_CERTIFICATES`     | `agent.http.enableTrustAllX509Certificates` | false   | Disable SSL verification           |
| `--max_concurrent_clients`    | `MAX_CONCURRENT_CLIENTS`          | `agent.http.maxConcurrentClients`           | 1       | Max parallel scrapes               |
| `--client_timeout_secs`       | `CLIENT_TIMEOUT_SECS`             | `agent.http.clientTimeoutSecs`              | 90      | HTTP client timeout (seconds)      |
| `--max_content_length_mbytes` | `AGENT_MAX_CONTENT_LENGTH_MBYTES` | `agent.http.maxContentLengthMBytes`         | 10      | Max response size (MB)             |
| `--log_level`                 | `AGENT_LOG_LEVEL`                 | `agent.logLevel`                            | "info"  | Log level                          |
| `--cert, -t`                  | `CERT_CHAIN_FILE_PATH`            | `agent.tls.certChainFilePath`               |         | TLS cert chain file                |
| `--key, -k`                   | `PRIVATE_KEY_FILE_PATH`           | `agent.tls.privateKeyFilePath`              |         | TLS private key file               |
| `--trust, -s`                 | `TRUST_CERT_COLLECTION_FILE_PATH` | `agent.tls.trustCertCollectionFilePath`     |         | TLS trust cert file                |
| `--override`                  | `OVERRIDE_AUTHORITY`              | `agent.tls.overrideAuthority`               |         | TLS authority override             |
| `--version, -v`               |                                   |                                             |         | Print version info and exit        |
| `--usage, -u`                 |                                   |                                             |         | Print usage message and exit       |
| `-D`                          |                                   |                                             |         | Dynamic property assignment        |

### Agent HTTP Client Cache Options

| CLI Option                      | Env Var                              | Property                                     | Default | Description                             |
|:--------------------------------|:-------------------------------------|:---------------------------------------------|:--------|:----------------------------------------|
| `--max_cache_size`              | `MAX_CLIENT_CACHE_SIZE`              | `agent.http.clientCache.maxSize`             | 100     | Max cached HTTP clients                 |
| `--max_cache_age_mins`          | `MAX_CLIENT_CACHE_AGE_MINS`          | `agent.http.clientCache.maxAgeMins`          | 30      | Max age of cached clients (minutes)     |
| `--max_cache_idle_mins`         | `MAX_CLIENT_CACHE_IDLE_MINS`         | `agent.http.clientCache.maxIdleMins`         | 10      | Max idle time before eviction (minutes) |
| `--cache_cleanup_interval_mins` | `CLIENT_CACHE_CLEANUP_INTERVAL_MINS` | `agent.http.clientCache.cleanupIntervalMins` | 5       | Cleanup interval (minutes)              |

### Agent gRPC Options

| CLI Option                  | Env Var                   | Property                           | Default | Description                               |
|:----------------------------|:--------------------------|:-----------------------------------|:--------|:------------------------------------------|
| `--keepalive_time_secs`     | `KEEPALIVE_TIME_SECS`     | `agent.grpc.keepAliveTimeSecs`     | INT_MAX | Interval between PING frames (seconds)    |
| `--keepalive_timeout_secs`  | `KEEPALIVE_TIMEOUT_SECS`  | `agent.grpc.keepAliveTimeoutSecs`  | 20      | Timeout for PING acknowledgment (seconds) |
| `--keepalive_without_calls` | `KEEPALIVE_WITHOUT_CALLS` | `agent.grpc.keepAliveWithoutCalls` | false   | Allow keepalive without active streams    |
| `--unary_deadline_secs`     | `UNARY_DEADLINE_SECS`     | `agent.grpc.unaryDeadlineSecs`     | 30      | Timeout for unary RPCs (seconds)          |

## Log Levels

Available log levels for both proxy and agent:

`all`, `trace`, `debug`, `info`, `warn`, `error`, `off`

## Dynamic Properties

Use `-D` to override any configuration value:

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
