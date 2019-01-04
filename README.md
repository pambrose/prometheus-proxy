# Prometheus Proxy

[![Build Status](https://travis-ci.org/pambrose/prometheus-proxy.svg?branch=master)](https://travis-ci.org/pambrose/prometheus-proxy)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/prometheus-proxy/badge.svg?branch=master)](https://coveralls.io/github/pambrose/prometheus-proxy?branch=master)
[![codebeat badge](https://codebeat.co/badges/8dbe1dc6-628e-44a4-99f9-d468831ff0cc)](https://codebeat.co/projects/github-com-pambrose-prometheus-proxy-master)
[![Code Climate](https://codeclimate.com/github/pambrose/prometheus-proxy/badges/gpa.svg)](https://codeclimate.com/github/pambrose/prometheus-proxy)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)

[Prometheus](https://prometheus.io) is an excellent systems monitoring and alerting toolkit, which uses a pull model for 
collecting metrics. The pull model is problematic when a Prometheus server and its metrics endpoints are separated by a 
firewall. [Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) enables Prometheus to reach metrics endpoints 
running behind a firewall and preserves the pull model. 

Endpoints running behind a firewall require a Prometheus Agent to be run inside the firewall. 
An Agent can run as a stand-alone server, embedded in another java server or as a java agent. 
Agents connect to a Proxy and register the paths for which they will provide data. A Proxy can work one or many Agents.

## CLI Usage

Download the proxy and agent uber-jars from [here](https://github.com/pambrose/prometheus-proxy/releases).

Start a proxy with:

```bash
$ java -jar prometheus-proxy.jar
```

Start an agent with: 

```bash
$ java -jar prometheus-agent.jar -Dagent.proxy.hostname=proxy.local --config https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf
```

If prometheus-proxy were running on a machine named *proxy.local* and the 
`agent.pathConfigs` value in the [myapps.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/myapps.conf) 
config file had these values:

```hocon
agent {
  pathConfigs: [
    {
      name: myApp1
      path: myapp1_metrics
      url: "http://myapp1.local:8080/metrics"
    },
    {
      name: myApp2
      path: myapp2_metrics
      url: "http://myapp2.local:8080/metrics"
    },
    {
      name: myApp3
      path: myapp3_metrics
      url: "http://myapp3.local:8080/metrics"
    }
  ]
}
```

then the *prometheus.yml* scrape_config would target the three apps at:

* http://proxy.local:8080/myapp1_metrics
* http://proxy.local:8080/myapp2_metrics
* http://proxy.local:8080/myapp3_metrics

The `prometheus.yml` file would include:

```yaml
scrape_configs:
  - job_name: 'myapp1'
    metrics_path: '/myapp1_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
  - job_name: 'myapp2'
    metrics_path: '/myapp2_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
  - job_name: 'myapp3'
    metrics_path: '/myapp3_metrics'
    static_configs:
      - targets: ['proxy.local:8080']
```

## Docker Usage

The docker images are available via:
```bash
$ docker pull pambrose/prometheus-proxy:1.3.9
$ docker pull pambrose/prometheus-agent:1.3.9
```

Start the proxy and an agent in separate shells on your local machine:

```bash
$ docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
        -e HOSTNAME=${HOSTNAME} \
        -e METRICS_ENABLED=true \
        pambrose/prometheus-proxy:1.3.9
```

```bash
$ docker run --rm -p 8083:8083 -p 8093:8093 \
        -e HOSTNAME=${HOSTNAME} \
        -e AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
        pambrose/prometheus-agent:1.3.9
```

Using the config file [simple.conf](https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf),
the proxy and the agent metrics would be available from the proxy on *localhost* at:
* http://localohost:8080/proxy_metrics
* http://localohost:8080/agent_metrics

## Configuration

The Proxy and Agent use the [Typesafe Config](https://github.com/typesafehub/config) library for configuration.
Highlights include:
* supports files in three formats: Java properties, JSON, and a human-friendly JSON superset ([HOCON](https://github.com/typesafehub/config#using-hocon-the-json-superset))
* config files can be files or urls
* config values can come from CLI options, environment vars, Java system properties, and/or config files.
* config files can reference environment variables
  
The Proxy and Agent properties are described [here](https://github.com/pambrose/prometheus-proxy/blob/master/etc/config/config.conf).
The only required argument is an Agent config value, which should have an `agent.pathConfigs` value.


### Proxy CLI Options

| Options             | Env Var         | Property               |Default | Description                            |
|:--------------------|:----------------|:-----------------------|:-------|:---------------------------------------|
| -c --config         | PROXY_CONFIG    |                        |        | Agent config file or url               |
| -p --port           | PROXY_PORT      | proxy.http.port        | 8080   | Proxy listen port                      |
| -a --agent_port     | AGENT_PORT      | proxy.agent.port       | 50051  | gRPC listen port for Agents            |
| -r --admin          | ADMIN_ENABLED   | proxy.admin.enabled    | false  | Enable admin servlets                  |
| -i --admin_port     | ADMIN_PORT      | proxy.admin.port       | 8092   | Admin servlets port                    |
| -e --metrics        | METRICS_ENABLED | proxy.metrics.enabled  | false  | Enable proxy metrics                   |
| -m --metrics_port   | METRICS_PORT    | proxy.metrics.port     | 8082   | Proxy metrics listen port              |
| -v --version        |                 |                        |        | Print version info and exit            |
| -u --usage          |                 |                        |        | Print usage message and exit           |
| -D                  |                 |                        |        | Dynamic property assignment            |


### Agent CLI Options

| Options             | Env Var         | Property               |Default | Description                            |
|:--------------------|:----------------|:-----------------------|:-------|:---------------------------------------|
| -c --config         | AGENT_CONFIG    |                        |        | Agent config file or url (required)    |
| -p --proxy          | PROXY_HOSTNAME  | agent.proxy.hostname   |        | Proxy hostname (can include :port)     |
| -n --name           | AGENT_NAME      | agent.name             |        | Agent name                             |
| -r --admin          | ADMIN_ENABLED   | agent.admin.enabled    | false  | Enable admin servlets                  |
| -i --admin_port     | ADMIN_PORT      | agent.admin.port       | 8093   | Admin servlets port                    |
| -e --metrics        | METRICS_ENABLED | agent.metrics.enabled  | false  | Enable agent metrics                   |
| -m --metrics_port   | METRICS_PORT    | agent.metrics.port     | 8083   | Agent metrics listen port              |
| -v --version        |                 |                        |        | Print version info and exit            |
| -u --usage          |                 |                        |        | Print usage message and exit           |
| -D                  |                 |                        |        | Dynamic property assignment            |

Misc notes:
* If you want to customize the logging, include the java arg `-Dlogback.configurationFile=/path/to/logback.xml`
* JSON config files must have a *.json* suffix
* Java Properties config files must have a *.properties*  or *.prop* suffix
* HOCON config files must have a *.conf* suffix
* Option values are evaluated in the order: CLI, enviroment vars, and finally config file vals
* Property values can be set as a java -D arg to  or as a proxy or agent jar -D arg.

### Admin Servlets

Three admin servlets are available when the `proxy.admin.enabled` or `agent.admin.enabled` properties are enabled:
 * /ping 
 * /threaddump
 * /healthcheck
 * /version

Descriptions of the servlets are [here](http://metrics.dropwizard.io/3.2.2/manual/servlets.html).
The path names can be changed in the configuration file. To disable an admin servlet, assign its path to "".

## Grafana 

[Grafana](https://grafana.com) dashboards for the Proxy and Agent are [here](https://github.com/pambrose/prometheus-proxy/tree/master/grafana).

## Related Links

* [Prometheus.io](http://prometheus.io)
* [gRPC](http://grpc.io)
* [Typesafe Config](https://github.com/typesafehub/config)
* [Zipkin]()

## Zipkin 

* Run a Zipkin server with: `docker run -d -p 9411:9411 openzipkin/zipkin`
* View Zipkin info at http://localhost:9411

Details on the Zipkin container are [here](https://github.com/openzipkin/docker-zipkin).