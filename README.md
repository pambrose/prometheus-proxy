# Prometheus Proxy

[![JitPack](https://jitpack.io/v/pambrose/prometheus-proxy.svg)](https://jitpack.io/#pambrose/prometheus-proxy)
[![Build Status](https://travis-ci.org/pambrose/prometheus-proxy.svg?branch=master)](https://travis-ci.org/pambrose/prometheus-proxy)
[![codebeat badge](https://codebeat.co/badges/8dbe1dc6-628e-44a4-99f9-d468831ff0cc)](https://codebeat.co/projects/github-com-pambrose-prometheus-proxy-master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://www.codacy.com/app/pambrose/prometheus-proxy?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/prometheus-proxy&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/pambrose/prometheus-proxy/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/prometheus-proxy)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/prometheus-proxy/badge.svg?branch=master)](https://coveralls.io/github/pambrose/prometheus-proxy?branch=master)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for 
collecting metrics. The pull model is problematic when a firewall separates a Prometheus server and its metrics endpoints. 
[Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) enables Prometheus to reach metrics endpoints 
running behind a firewall and preserves the pull model.

The `prometheus-proxy` runtime comprises 2 services:

* `proxy`: runs in the same network domain as Prometheus server (outside the firewall) and proxies calls from Prometheus to the `agent` behind the firewall.
* `agent`: runs in the same network domain as all the monitored hosts/services/apps (inside the firewall). It maps the scraping queries coming from the `proxy` to the actual `/metrics` scraping endpoints of the hosts/services/apps.

Here's a simplified network diagram of how the deployed `proxy` and `agent` work:

![network diagram](https://github.com/pambrose/prometheus-proxy/raw/master/docs/prometheus-proxy.png)

Endpoints running behind a firewall require a `prometheus-agent` (the agent) to be run inside the firewall.
An agent can run as a stand-alone server, embedded in another java server, or as a java agent.
Agents connect to a `prometheus-proxy` (the proxy) and register the paths for which they will provide data. 
One proxy can work one or many agents.

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
`agent.pathConfigs` value in the [myapps.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf) 
config file had the contents:

```hocon
agent {
  pathConfigs: [
    {
      name: "App1 metrics"
      path: app1_metrics
      url: "http://app1.local:9100/metrics"
    },
    {
      name: "App2 metrics"
      path: app2_metrics
      url: "http://app2.local:9100/metrics"
    },
    {
      name: "App3 metrics"
      path: app3_metrics
      url: "http://app3.local:9100/metrics"
    }
  ]
}
```

then the *prometheus.yml* scrape_config would target the three apps with:

* http://mymachine.local:8080/app1_metrics
* http://mymachine.local:8080/app2_metrics
* http://mymachine.local:8080/app3_metrics

The `prometheus.yml` file would include:

```yaml
scrape_configs:
  - job_name: 'app1 metrics'
    metrics_path: '/app1_metrics'
    static_configs:
      - targets: ['mymachine.local:8080']
  - job_name: 'app2 metrics'
    metrics_path: '/app2_metrics'
    static_configs:
      - targets: ['mymachine.local:8080']
  - job_name: 'app3 metrics'
    metrics_path: '/app3_metrics'
    static_configs:
      - targets: ['mymachine.local:8080']
```

## Docker Usage

The docker images are available via:
```bash
docker pull pambrose/prometheus-proxy:1.8.3
docker pull pambrose/prometheus-agent:1.8.3
```

Start a proxy container with:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        pambrose/prometheus-proxy:1.8.3
```

Start an agent container with:

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:1.8.3
```

Using the config file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf),
the proxy and the agent metrics would be available from the proxy on *localhost* at:
* http://localhost:8082/proxy_metrics
* http://localhost:8083/agent_metrics

If you want to use a local config file with a docker container (instead of the above HTTP-served config file), 
use the docker [mount](https://docs.docker.com/storage/bind-mounts/) option. Assuming the config file `prom-agent.conf` 
is in your current directory, run an agent container with:

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/prom-agent.conf,target=/app/prom-agent.conf \
    --env AGENT_CONFIG=prom-agent.conf \
    pambrose/prometheus-agent:1.8.3
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure 
to use `/app` as the base directory in the target for `--mount` options.

## Configuration

The proxy and agent use the [Typesafe Config](https://github.com/typesafehub/config) library for configuration.
Highlights include:
* support for files in three formats: Java properties, JSON, and a human-friendly JSON superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment vars, Java system properties, and/or config files.
* config files can reference environment variables
  
All the proxy and agent properties are described [here](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf).
The only required argument is an agent config value, which should have an `agent.pathConfigs` value.

### Proxy CLI Options

| Options               | ENV VAR<br>Property                             |Default | Description                         |
|-----------------------|-------------------------------------------------|--------|-------------------------------------|
| --config, -c          | PROXY_CONFIG                                    |        | Agent config file or url              |
| --port, -p            | PROXY_PORT      <br> proxy.http.port            | 8080   | Proxy listen port                   |
| --agent_port, -a      | AGENT_PORT      <br> proxy.agent.port           | 50051  | gRPC listen port for agents         |
| --admin, -r           | ADMIN_ENABLED   <br> proxy.admin.enabled        | false  | Enable admin servlets               |
| --admin_port, -i      | ADMIN_PORT      <br> proxy.admin.port           | 8092   | Admin servlets port                 |
| --debug, -b           | DEBUG_ENABLED   <br> proxy.admin.debugEnabled   | false  | Enable proxy debug servlet<br>on admin port|
| --metrics, -e         | METRICS_ENABLED <br> proxy.metrics.enabled      | false  | Enable proxy metrics                |
| --metrics_port, -m    | METRICS_PORT    <br> proxy.metrics.port         | 8082   | Proxy metrics listen port           |
| --cert, -t            | CERT_CHAIN_FILE_PATH <br> proxy.tls.certChainFilePath   |  | Certificate chain file path       |
| --key, -k             | PRIVATE_KEY_FILE_PATH <br> proxy.tls.privateKeyFilePath |  | Private key file path            |
| --trust, -s           | TRUST_CERT_COLLECTION_FILE_PATH <br> proxy.tls.trustCertCollectionFilePath |  | Trust certificate collection file path |
| --version, -v         |                                                 |        | Print version info and exit         |
| --usage, -u           |                                                 |        | Print usage message and exit        |
| -D                    |                                                 |        | Dynamic property assignment         |


### Agent CLI Options

| Options               | ENV VAR<br>Property                             |Default | Description                         |
|:----------------------|:------------------------------------------------|:-------|:------------------------------------|
| --config, -c          | AGENT_CONFIG                                    |        | Agent config file or url (required)   |
| --proxy, -p           | PROXY_HOSTNAME  <br> agent.proxy.hostname       |        | Proxy hostname (can include :port)  |
| --name, -n            | AGENT_NAME      <br> agent.name                 |        | Agent name                          |
| --admin, -r           | ADMIN_ENABLED   <br> agent.admin.enabled        | false  | Enable admin servlets               |
| --admin_port, -i      | ADMIN_PORT      <br> agent.admin.port           | 8093   | Admin servlets port                 |
| --debug, -b           | DEBUG_ENABLED   <br> agent.admin.debugEnabled   | false  | Enable agent debug servlet<br>on admin port|
| --metrics, -e         | METRICS_ENABLED <br> agent.metrics.enabled      | false  | Enable agent metrics                |
| --metrics_port, -m    | METRICS_PORT    <br> agent.metrics.port         | 8083   | Agent metrics listen port           |
| --consolidated, -o    | CONSOLIDATED <br> agent.consolidated            | false  | Enable multiple agents per registered path |
| --timeout             | SCRAPE_TIMEOUT_SECS <br> agent.scrapeTimeoutSecs  | 15  | Scrape timeout time (seconds) |
| --chunk               | CHUNK_CONTENT_SIZE_KBS <br> agent.chunkContentSizeKbs   | 32   | Threshold for chunking data to Proxy and buffer size (KBs) |
| --gzip                | MIN_GZIP_SIZE_BYTES <br> agent.minGzipSizeBytes         | 1024 | Minimum size for content to be gzipped (bytes) |
| --cert, -t            | CERT_CHAIN_FILE_PATH <br> agent.tls.certChainFilePath   |      | Certificate chain file path         |
| --key, -k             | PRIVATE_KEY_FILE_PATH <br> agent.tls.privateKeyFilePath |      | Private key file path            |
| --trust, -s           | TRUST_CERT_COLLECTION_FILE_PATH <br> agent.tls.trustCertCollectionFilePath |  | Trust certificate collection file path |
| --override            | OVERRIDE_AUTHORITY <br> agent.tls.overrideAuthority     |      | Override authority (for testing)    |
| --version, -v         |                                                 |        | Print version info and exit            |
| --usage, -u           |                                                 |        | Print usage message and exit           |
| -D                    |                                                 |        | Dynamic property assignment            |

Misc notes:
* If you want to customize the logging, include the java arg `-Dlogback.configurationFile=/path/to/logback.xml`
* JSON config files must have a *.json* suffix
* Java Properties config files must have a *.properties*  or *.prop* suffix
* HOCON config files must have a *.conf* suffix
* Option values are evaluated in the order: CLI, environment vars, and finally config file vals
* Property values can be set as a java -D arg to  or as a proxy or agent jar -D arg.

### Admin Servlets

These admin servlets are available when the admin servlet is enabled:
* /ping 
* /threaddump
* /healthcheck
* /version

The admin servlets can be enabled with the `ADMIN_ENABLED` environment var, the `--admin` CLI option, or with the 
`proxy.admin.enabled` and `agent.admin.enabled` properties.

The debug servlet can be enabled with the `DEBUG_ENABLED` env var, `--debug` CLI option , or with the 
`proxy.admin.debugEnabled` and `agent.admin.debugEnabled` properties. The debug servlet requires that the
admin servlets are enabled. The debug servlet is at: `/debug` on the admin port.

Descriptions of the servlets are [here](http://metrics.dropwizard.io/3.2.2/manual/servlets.html).
The path names can be changed in the configuration file. To disable an admin servlet, assign its property path to "".

## Adding TLS to Agent-Proxy connections

Agents connect to a proxy using [gRPC](https://grpc.io). gRPC supports TLS with or without mutual authentication. The
necessary certificate and key file paths can be specified via CLI args, environment variables and configuration file settings.

The gRPC docs describe [how to setup TLS](https://github.com/grpc/grpc-java/tree/master/examples/example-tls).
The [repo](https://github.com/pambrose/prometheus-proxy/tree/master/testing/certs) includes the
certificates and keys necessary to test TLS support. 

Running TLS without mutual authentication requires these settingss:
* certChainFilePath and privateKeyFilePath on the proxy
* trustCertCollectionFilePath on the agent

Running TLS with mutual authentication requires these settingss:
* certChainFilePath, privateKeyFilePath and trustCertCollectionFilePath on the proxy
* certChainFilePath, privateKeyFilePath and trustCertCollectionFilePath on the agent

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
    pambrose/prometheus-proxy:1.8.3

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:1.8.3
```

**Note:** The `WORKDIR` of the proxy and agent images is `/app`, so make sure 
to use `/app` as the base directory in the target for `--mount` options.

## Grafana 

[Grafana](https://grafana.com) dashboards for the proxy and agent are [here](https://github.com/pambrose/prometheus-proxy/tree/master/grafana).

## Related Links

* [Prometheus.io](http://prometheus.io)
* [gRPC](http://grpc.io)
* [Typesafe Config](https://github.com/typesafehub/config)