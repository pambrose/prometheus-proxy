---
icon: lucide/shield
---

# Security

## Security Model

Prometheus Proxy is designed to be firewall-friendly:

- The **agent** initiates an *outbound* gRPC connection to the proxy
- **No inbound ports** need to be opened on the firewall
- Agent connections can be authenticated with an optional
  [pre-shared token](#agent-authentication-pre-shared-token) and/or [mutual TLS](tls.md)
- Stale agent connections are automatically cleaned up

!!! warning "The agent gRPC port is unauthenticated by default"

    With neither a pre-shared agent token nor mutual TLS configured, any process that can reach the
    agent port (default `50051`) can register as an agent. Set a token (below), require mutual TLS,
    and/or restrict the port to trusted networks. The proxy logs a startup warning when the agent
    port is left unauthenticated.

## TLS Encryption

Agents connect to the proxy using [gRPC](https://grpc.io), which supports TLS with or without
mutual authentication.

| Mode                  | Proxy Needs                 | Agent Needs                 |
|:----------------------|:----------------------------|:----------------------------|
| **No TLS**            | Nothing                     | Nothing                     |
| **TLS (server only)** | Server cert + key           | CA cert (trust store)       |
| **Mutual TLS**        | Server cert + key + CA cert | Client cert + key + CA cert |

See [TLS Setup](tls.md) for detailed configuration instructions.

## Agent Authentication (Pre-Shared Token)

By default the proxy accepts agent gRPC connections with **no application-level authentication**. Set
a shared **pre-shared token** so the proxy rejects agents that do not present it:

| Side  | CLI             | Env Var       | Config             |
|:------|:----------------|:--------------|:-------------------|
| Proxy | `--agent_token` | `AGENT_TOKEN` | `proxy.agentToken` |
| Agent | `--agent_token` | `AGENT_TOKEN` | `agent.agentToken` |

Both sides must use the **same** value. When set, the agent attaches the token as a gRPC metadata
header on every call and the proxy rejects any call with a missing or mismatched token
(`UNAUTHENTICATED`). When the token is empty (the default), the open behavior is preserved and the
proxy logs a startup warning — unless mutual TLS is configured, which already authenticates agents.
The token is never logged.

```bash
# Proxy requiring a token
java -jar prometheus-proxy.jar --agent_token "$AGENT_TOKEN"

# Agent presenting the token
java -jar prometheus-agent.jar --config myconfig.conf --agent_token "$AGENT_TOKEN"
```

!!! note "Token vs. mutual TLS"

    A pre-shared token is a lightweight, app-level control that authenticates *that* a peer may
    connect. [Mutual TLS](tls.md) additionally encrypts the channel and verifies a certificate
    identity. They can be combined; for production, prefer mutual TLS and/or restrict the agent port
    to trusted networks.

## Auth Header Forwarding

When Prometheus scrape configurations include `basic_auth` or `bearer_token`, the proxy forwards
the `Authorization` header to the agent over the gRPC channel. The agent then includes this
header when fetching metrics from the target endpoint.

```yaml
--8<-- "PrometheusConfigs.txt:auth-scrape-config"
```

!!! danger "Credentials transmitted in plaintext without TLS"

    Without TLS, the `Authorization` header is transmitted in plaintext between the proxy
    and agent. The proxy logs a warning on the first request that includes an
    `Authorization` header when TLS is not enabled.

    **Always enable TLS when forwarding authentication headers.**

```text
--8<-- "TlsExamples.txt:auth-header-tls"
```

## Scraping HTTPS Endpoints

For HTTPS scrape targets signed by a custom or private CA (e.g. an internal corporate CA),
point the agent at a trust store containing that CA so certificates are **still validated**:

```text
--8<-- "TlsExamples.txt:https-truststore"
```

An empty path uses the JDK default trust store. The trust store is process-wide — it applies
to every HTTPS target the agent scrapes, and it is ignored when `trust_all_x509` is enabled.

As a last resort, you can disable SSL verification entirely:

```text
--8<-- "TlsExamples.txt:trust-all-x509"
```

!!! warning "Development only"

    Only use `trust_all_x509` in development or testing environments: it disables certificate
    validation for **every** HTTPS target and takes precedence over the trust store. In
    production, configure a trust store (or properly trusted certificates) for your metrics
    endpoints instead.
