---
icon: lucide/settings
---

# Configuration Overview

Prometheus Proxy uses the [Typesafe Config](https://github.com/lightbend/config) library for
configuration. Values are evaluated in this precedence order (highest first):

```text
--8<-- "ConfigExamples.txt:precedence-example"
```

## Configuration Formats

Typesafe Config supports three formats:

| Format | Extension | Description |
|:-------|:----------|:------------|
| **HOCON** | `.conf` | Human-friendly JSON superset (recommended) |
| **JSON** | `.json` | Standard JSON format |
| **Properties** | `.properties` | Java properties format |

## Loading Configuration

### From a Local File

```bash
java -jar prometheus-agent.jar --config /etc/prometheus-proxy/agent.conf
java -jar prometheus-proxy.jar --config /etc/prometheus-proxy/proxy.conf
```

### From a URL

```bash
--8<-- "ConfigExamples.txt:config-from-url"
```

### Dynamic Properties

Override any configuration value using `-D` flags:

```bash
java -jar prometheus-proxy.jar -Dproxy.http.port=9090 -Dproxy.admin.enabled=true
java -jar prometheus-agent.jar -Dagent.proxy.hostname=myproxy.com --config agent.conf
```

## Environment Variables

Common environment variables:

```text
--8<-- "ConfigExamples.txt:env-var-reference"
```

See the [CLI Reference](../cli-reference.md) for the complete list.

## HOCON Basics

HOCON (Human-Optimized Config Object Notation) is a superset of JSON:

```hocon
# Comments start with # or //
agent {
  // Nested keys with dot notation
  proxy.hostname = "proxy-host.example.com"
  proxy.port = 50051

  // Arrays use brackets
  pathConfigs: [
    {
      name: "My App"
      path: my_app_metrics
      url: "http://localhost:9100/metrics"
    }
  ]

  // Reference environment variables
  name = ${?AGENT_NAME}
}
```

Key HOCON features:

- Comments with `#` or `//`
- Unquoted strings for simple values
- Environment variable substitution with `${?VAR_NAME}`
- Dot notation for nested paths (`proxy.hostname`)
- Merging and includes (`include "other.conf"`)

## Configuration Reference

For the complete configuration schema, see the reference file:
[`config/config.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf)

## Next Steps

- [Agent Configuration](agent.md) -- path configs, HTTP client, scrape settings
- [Proxy Configuration](proxy.md) -- HTTP service, gRPC, service discovery
- [CLI Reference](../cli-reference.md) -- all CLI options and environment variables
