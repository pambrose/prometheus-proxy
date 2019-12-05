# Prometheus Proxy

[![JitPack](https://jitpack.io/v/pambrose/prometheus-proxy.svg)](https://jitpack.io/#pambrose/prometheus-proxy)
[![Build Status](https://travis-ci.org/pambrose/prometheus-proxy.svg?branch=master)](https://travis-ci.org/pambrose/prometheus-proxy)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/prometheus-proxy/badge.svg?branch=master)](https://coveralls.io/github/pambrose/prometheus-proxy?branch=master)
[![codebeat badge](https://codebeat.co/badges/8dbe1dc6-628e-44a4-99f9-d468831ff0cc)](https://codebeat.co/projects/github-com-pambrose-prometheus-proxy-master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/422df508473443df9fbd8ea00fdee973)](https://www.codacy.com/app/pambrose/prometheus-proxy?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/prometheus-proxy&amp;utm_campaign=Badge_Grade)
[![Code Climate](https://codeclimate.com/github/pambrose/prometheus-proxy/badges/gpa.svg)](https://codeclimate.com/github/pambrose/prometheus-proxy)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for 
collecting metrics. The pull model is problematic when a Prometheus server and its metrics endpoints are separated by a 
firewall. [Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) enables Prometheus to reach metrics endpoints 
running behind a firewall and preserves the pull model.

`prometheus-proxy` runtime is broken up into 2 microservices:

*   `proxy`: Runs in the same network domain as Prometheus server (outside the firewall) and proxies calls from Prometheus to the `agent` behind the firewall.
*   `agent`: Runs in the same network domain as all the monitored hosts/services/apps (inside the firewall). It maps the scraping queries coming from the `proxy` to the actual `/metrics` scraping endpoints of the hosts/services/apps.

Here's a simplified network diagram of how the deployed `proxy` and `agent` work:

![network diagram](https://github.com/pambrose/prometheus-proxy/raw/master/docs/prometheus-proxy.png)

Endpoints running behind a firewall require a `prometheus-agent` (the agent) to be run inside the firewall.
An agent can run as a stand-alone server, embedded in another java server, or as a java agent.
Agents connect to a `prometheus-proxy` (the proxy) and register the paths for which they will provide data. 
One proxy can work one or many agents.

## CLI Usage

Download the proxy and agent uber-jars from [here](https://github.com/pambrose/prometheus-proxy/releases).

Start a proxy with:

```bash
java -jar prometheus-proxy.jar
```

Start an agent with: 

```bash
java -jar prometheus-agent.jar -Dagent.proxy.hostname=proxy.local --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf
```

If prometheus-proxy were running on a machine named *proxy.local* and the
`agent.pathConfigs` value in the [myapps.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf) 
config file had these values:

```hocon
agent {
  pathConfigs: [
    {
      name: "app1 name"
      path: app1_metrics
      url: "http://app1.local:9100/metrics"
    },
    {
      name: "app2 name"
      path: app2_metrics
      url: "http://app2.local:9100/metrics"
    },
    {
      name: "app3 name"
      path: app3_metrics
      url: "http://app3.local:9100/metrics"
    }
  ]
}
```

then the *prometheus.yml* scrape_config would target the three apps at:

*   http://proxy.local:8080/app1_metrics
*   http://proxy.local:8080/app2_metrics
*   http://proxy.local:8080/app3_metrics

The `prometheus.yml` file would include:

```yaml
scrape_configs:
  - job_name: 'app1'
    metrics_path: '/app1_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
  - job_name: 'app2'
    metrics_path: '/app2_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
  - job_name: 'app3'
    metrics_path: '/app3_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
```

## Docker Usage

The docker images are available via:
```bash
docker pull pambrose/prometheus-proxy:1.4.6
docker pull pambrose/prometheus-agent:1.4.6
```

Start the proxy and an agent in separate shells on your local machine:

```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        --env ADMIN_ENABLED=true \
        --env METRICS_ENABLED=true \
        pambrose/prometheus-proxy:1.4.6
```

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
        --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:1.4.6
```

If you want to externalize your `agent` config file on your local machine (or VM) file system 
(instead of the above HTTP served config file), you'll need to use the Docker `mount` option:

```bash
docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/prom-agent.conf,target=/app/prom-agent.conf \
    --env AGENT_CONFIG=prom-agent.conf \
    pambrose/prometheus-agent:1.4.6
```

The above assumes that you have the file `prom-agent.conf` in the current directory from which you're 
running the `docker` command. The `WORKDIR` of the proxy and agent images is `/app`, so make sure 
to use /app as the base directory in the target for `--mount` options.

Using the config file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf),
the proxy and the agent metrics would be available from the proxy on *localhost* at:
*   http://localhost:8082/proxy_metrics
*   http://localhost:8083/agent_metrics

## Configuration

The Proxy and Agent use the [Typesafe Config](https://github.com/typesafehub/config) library for configuration.
Highlights include:
* supports files in three formats: Java properties, JSON, and a human-friendly JSON superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment vars, Java system properties, and/or config files.
* config files can reference environment variables
  
The Proxy and Agent properties are described [here](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf).
The only required argument is an Agent config value, which should have an `agent.pathConfigs` value.

## Adding TLS to Agent-Proxy connections

Agents connect to a Proxy using [gRPC](https://grpc.io). gRPC supports TLS with or without mutual authentication. The
necessary certificate amd key file paths can spcified via CLI args, environment variables and configuration file settings.

The gRPC docs describe how to setup TLS [here](https://github.com/grpc/grpc-java/tree/master/examples/example-tls).
The certificates and keys necessary to test TLS support are included in this 
[repo](https://github.com/pambrose/prometheus-proxy/tree/master/testing/certs).

To run TLS without mutual authentication the following must be defined:
* certChainFilePath and privateKeyFilePath on the Proxy
* trustCertCollectionFilePath on the Agent

To run TLS with mutual authentication the following must be defined:
* certChainFilePath, privateKeyFilePath and trustCertCollectionFilePath on the Proxy
* certChainFilePath, privateKeyFilePath and trustCertCollectionFilePath on the Agent

Run the Agent and Proxy using the testing certs with:
```bash
java -jar prometheus-proxy.jar --config examples/tls-no-mutual-auth.conf
java -jar prometheus-agent.jar --config examples/tls-no-mutual-auth.conf
```

The docker commands necessary to would be:
```bash
docker run --rm -p 8082:8082 -p 8092:8092 -p 50440:50440 -p 8080:8080 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env PROXY_CONFIG=tls-no-mutual-auth.conf \
    --env ADMIN_ENABLED=true \
    --env METRICS_ENABLED=true \
    pambrose/prometheus-proxy:1.4.6

and

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-no-mutual-auth.conf,target=/app/tls-no-mutual-auth.conf \
    --env AGENT_CONFIG=tls-no-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:1.4.6

docker run --rm -p 8082:8082 -p 8092:8092 -p 50440:50440 -p 8080:8080 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-with-mutual-auth.conf,target=/app/tls-with-mutual-auth.conf \
    --env PROXY_CONFIG=tls-with-mutual-auth.conf \
    --env ADMIN_ENABLED=true \
    --env METRICS_ENABLED=true \
    pambrose/prometheus-proxy:1.4.6

and

docker run --rm -p 8083:8083 -p 8093:8093 \
    --mount type=bind,source="$(pwd)"/testing/certs,target=/app/testing/certs \
    --mount type=bind,source="$(pwd)"/examples/tls-with-mutual-auth.conf,target=/app/tls-with-mutual-auth.conf \
    --env AGENT_CONFIG=tls-with-mutual-auth.conf \
    --env PROXY_HOSTNAME=mymachine.lan:50440 \
    --name docker-agent \
    pambrose/prometheus-agent:1.4.6

```

### Proxy CLI Options

| Options             | Env Var         | Property                   |Default | Description                            |
|:--------------------|:----------------|:---------------------------|:-------|:---------------------------------------|
| -c --config         | PROXY_CONFIG    |                            |        | Agent config file or url                 |
| -p --port           | PROXY_PORT      | proxy.http.port            | 8080   | Proxy listen port                      |
| -a --agent_port     | AGENT_PORT      | proxy.agent.port           | 50051  | gRPC listen port for Agents            |
| -r --admin          | ADMIN_ENABLED   | proxy.admin.enabled        | false  | Enable admin servlets                  |
| -i --admin_port     | ADMIN_PORT      | proxy.admin.port           | 8092   | Admin servlets port                    |
| -e --metrics        | METRICS_ENABLED | proxy.metrics.enabled      | false  | Enable proxy metrics                   |
| -m --metrics_port   | METRICS_PORT    | proxy.metrics.port         | 8082   | Proxy metrics listen port              |
| -b --debug          | DEBUG_ENABLED   | proxy.metrics.debugEnabled | false  | Enable proxy debug servlet on admin port|
| -t --cert           | CERT_CHAIN_FILE_PATH | proxy.tls.certChainFilePath | "" | Certificate chain file path              |
| -k --key            | PRIVATE_KEY_FILE_PATH | proxy.tls.privateKeyFilePath | "" | Private key file path              |
| -s --trust          | TRUST_CERT_COLLECTION_FILE_PATH | proxy.tls.trustCertCollectionFilePath | "" | Trust certificate collection file path |
| -v --version        |                 |                            |        | Print version info and exit            |
| -u --usage          |                 |                            |        | Print usage message and exit           |
| -D                  |                 |                            |        | Dynamic property assignment            |


### Agent CLI Options

| Options             | Env Var         | Property                   |Default | Description                            |
|:--------------------|:----------------|:---------------------------|:-------|:---------------------------------------|
| -c --config         | AGENT_CONFIG    |                            |        | Agent config file or url (required)      |
| -p --proxy          | PROXY_HOSTNAME  | agent.proxy.hostname       |        | Proxy hostname (can include :port)     |
| -n --name           | AGENT_NAME      | agent.name                 |        | Agent name                             |
| -r --admin          | ADMIN_ENABLED   | agent.admin.enabled        | false  | Enable admin servlets                  |
| -i --admin_port     | ADMIN_PORT      | agent.admin.port           | 8093   | Admin servlets port                    |
| -e --metrics        | METRICS_ENABLED | agent.metrics.enabled      | false  | Enable agent metrics                   |
| -m --metrics_port   | METRICS_PORT    | agent.metrics.port         | 8083   | Agent metrics listen port              |
| -b --debug          | DEBUG_ENABLED   | agent.metrics.debugEnabled | false  | Enable proxy debug servlet on admin port|
| -t --cert           | CERT_CHAIN_FILE_PATH | proxy.tls.certChainFilePath | "" | Certificate chain file path              |
| -k --key            | PRIVATE_KEY_FILE_PATH | proxy.tls.privateKeyFilePath | "" | Private key file path              |
| -s --trust          | TRUST_CERT_COLLECTION_FILE_PATH | proxy.tls.trustCertCollectionFilePath | "" | Trust certificate collection file path |
| --override          | OVERRIDE_AUTHORITY | proxy.tls.overrideAuthority | "" | Override authority (for testing) |
| -v --version        |                 |                            |        | Print version info and exit            |
| -u --usage          |                 |                            |        | Print usage message and exit           |
| -D                  |                 |                            |        | Dynamic property assignment            |

Misc notes:
*   If you want to customize the logging, include the java arg `-Dlogback.configurationFile=/path/to/logback.xml`
*   JSON config files must have a *.json* suffix
*   Java Properties config files must have a *.properties*  or *.prop* suffix
*   HOCON config files must have a *.conf* suffix
*   Option values are evaluated in the order: CLI, enviroment vars, and finally config file vals
*   Property values can be set as a java -D arg to  or as a proxy or agent jar -D arg.

### Admin Servlets

These admin servlets are available when the admin servlet is enabled:
*   /ping 
*   /threaddump
*   /healthcheck
*   /version

The admin servlets can be enabled with the ``ADMIN_ENABLED`` environment var, the ``--admin`` CLI option, or with the 
`proxy.admin.enabled` and `agent.admin.enabled` properties.

The debug servlet can be enabled with ``DEBUG_ENABLED`` environment var, ``--debug``CLI option , or with the 
proxy.admin.debugEnabled` and `agent.admin.debugEnabled` properties. The debug servlet requires that the
admin servlets are enabled. The debug servlet is at: /debug on the admin port.

Descriptions of the servlets are [here](http://metrics.dropwizard.io/3.2.2/manual/servlets.html).
The path names can be changed in the configuration file. To disable an admin servlet, assign its property path to "".

## Grafana 

[Grafana](https://grafana.com) dashboards for the Proxy and Agent are [here](https://github.com/pambrose/prometheus-proxy/tree/master/grafana).

## Related Links

*   [Prometheus.io](http://prometheus.io)
*   [gRPC](http://grpc.io)
*   [Typesafe Config](https://github.com/typesafehub/config)
*   [Zipkin]()

## Zipkin 

*   Run a Zipkin server with: `docker run -d -p 9411:9411 openzipkin/zipkin`
*   View Zipkin info at http://localhost:9411

Details on the Zipkin container are [here](https://github.com/openzipkin/docker-zipkin).