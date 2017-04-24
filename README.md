# Prometheus Proxy

[![Build Status](https://travis-ci.org/pambrose/prometheus-proxy.svg?branch=master)](https://travis-ci.org/pambrose/prometheus-proxy)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/prometheus-proxy/badge.svg?branch=master)](https://coveralls.io/github/pambrose/prometheus-proxy?branch=master)
[![Code Climate](https://codeclimate.com/github/pambrose/prometheus-proxy/badges/gpa.svg)](https://codeclimate.com/github/pambrose/prometheus-proxy)


[Prometheus](https://prometheus.io) is a great systems monitoring and alertig toolkit. It uses a pull model for 
colleting metrics, which is probelmatic when a Prometheus server and its scrape endpoints are separated by a firewall. 
The [Prometheus Proxy](https://github.com/pambrose/prometheus-proxy) solves this problem. 

Scrape endpoints running behind a firewall require an Agent to be run locally. An Agent can be run 
as a stand-alone server or embedded. Agents connect to a Proxy and register the paths for which they 
will provide data. A Proxy can support one or many Agents.


## Usage

```bash
$ java -jar proxy.jar
```

```bash
$ java -jar agent.jar
```

Note: If you want to customize the logging, include: `-Dlogback.configurationFile=/path/to/logback.xml`

```bash
$ docker run --rm -p 8082:8082 -p 50051:50051 -p 8080:8080 pambrose/prometheus-proxy:1.0.0
```


```bash
$ docker run --rm -p 8083:8083 \
        -v agent.conf:/prometheus-proxy/agent.conf \
        pambrose/prometheus-agent:1.0.0
```

## Configuration

Proxy and Agent configuration can take place via CLI options, environment vars, and/or config files.
 [Typesafe Config](https://github.com/typesafehub/config) 


### Proxy

| CLI Options         | EnvVar          | Property               |Default | Description                            |
|:-------------------:|:----------------|:-----------------------|:-------|:---------------------------------------|
| -c --config         | PROXY_CONFIG    |                        |        | Agent config file or url               |
| -p --port           | PROXY_PORT      | proxy.http.port        | 8080   | Proxy listen port                      |
| -a --agent_port     | AGENT_PORT      | proxy.grpc.port        | 50051  | Grpc listen port                       |
| -e --metrics        | ENABLE_METRICS  | proxy.metrics.enabled  | false  | Enable proxy metrics                   |
| -m --metrics_port   | METRICS_PORT    | proxy.metrics.port     | 8082   | Proxy metrics listen port              |
| -v --version        |                 |                        |        | Print version info and exit            |
| -u --usage          |                 |                        |        | Print usage message and exit           |


### Agent

| CLI Options         | EnvVar          | Property               |Default | Description                            |
|:-------------------:|:----------------|:-----------------------|:-------|:---------------------------------------|
| -c --config         | AGENT_CONFIG    |                        |        | Agent config file or url (required)    |
| -p --proxy          | PROXY_HOSTNAME  | agent.grpc.hostname    |        | Proxy hostname (can include :port)     |
| -n --name           | AGENT_NAME      | agent.name             |        | Agent name                             |
| -e --metrics        | ENABLE_METRICS  | agent.metrics.enabled  | false  | Enable agent metrics                   |
| -m --metrics_port   | METRICS_PORT    | agent.metrics.port     | 8083   | Agent metrics listen port              |
| -v --version        |                 |                        |        | Print version info and exit            |
| -u --usage          |                 |                        |        | Print usage message and exit           |

