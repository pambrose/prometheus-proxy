---
icon: lucide/cable
---

# Agent Configuration

The agent runs inside the firewall and scrapes local metrics endpoints on behalf of the proxy.

## Path Configs

The `pathConfigs` array defines which metrics endpoints the agent exposes through the proxy:

### Basic Configuration

```hocon
--8<-- "ConfigExamples.txt:path-config-basic"
```

### Multiple Endpoints

```hocon
--8<-- "ConfigExamples.txt:path-config-multi"
```

### With Labels

Labels are included in service discovery responses and can be used for filtering:

```hocon
--8<-- "ConfigExamples.txt:path-config-with-labels"
```

Each path config entry has these fields:

| Field | Required | Description |
|:------|:---------|:------------|
| `name` | Yes | Human-readable endpoint name (for logs and debugging) |
| `path` | Yes | URL path on the proxy that Prometheus will scrape |
| `url` | Yes | Actual metrics endpoint the agent fetches from |
| `labels` | No | JSON string of labels for service discovery (default: `"{}"`) |

## Proxy Connection

```hocon
agent {
  proxy {
    hostname = "proxy-host.example.com"   // Proxy hostname
    port = 50051                          // Proxy gRPC port
  }
}
```

Or specify on the command line:

```bash
java -jar prometheus-agent.jar --proxy proxy-host.example.com:50051 --config agent.conf
```

## HTTP Client Settings

Configure how the agent makes HTTP requests to scrape endpoints:

```hocon
--8<-- "ConfigExamples.txt:agent-http-config"
```

### HTTP Client Cache

The agent caches HTTP clients keyed by authentication credentials (for basic auth / bearer token
scenarios). Configure cache behavior:

```hocon
--8<-- "ConfigExamples.txt:agent-cache-config"
```

## Scrape Settings

```hocon
--8<-- "ConfigExamples.txt:agent-scrape-config"
```

| Setting | Default | Description |
|:--------|:--------|:------------|
| `scrapeTimeoutSecs` | 15 | Total time allowed for a scrape including retries |
| `scrapeMaxRetries` | 0 | Maximum retries; 0 disables retries |
| `chunkContentSizeKbs` | 32 | Responses larger than this are chunked |
| `minGzipSizeBytes` | 512 | Responses larger than this are gzip-compressed |

## Consolidated Mode

By default, each path is owned by a single agent. Enable consolidated mode to allow multiple
agents to register the same path:

```hocon
--8<-- "ConfigExamples.txt:consolidated-mode"
```

This is useful for redundancy -- if one agent goes down, another can serve the same path.

## Agent Naming

Give agents descriptive names for easier identification in logs and metrics:

```bash
java -jar prometheus-agent.jar --name production-agent-01 --config agent.conf
```

Or in the config file:

```hocon
agent.name = "production-agent-01"
```

If no name is provided, the agent uses `Unnamed-<hostname>`.

## Full Example

Here is a real-world config from the `examples/` directory:

```hocon
--8<-- "examples/simple.conf"
```

See also:

- [`examples/myapps.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/myapps.conf) -- multiple endpoints
- [`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf) -- Prometheus federation
