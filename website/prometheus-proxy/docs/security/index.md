---
icon: lucide/shield
---

# Security

## Security Model

Prometheus Proxy is designed to be firewall-friendly:

- The **agent** initiates an *outbound* gRPC connection to the proxy
- **No inbound ports** need to be opened on the firewall
- Agent connections can be authenticated with an optional
  [pre-shared token](#agent-authentication-pre-shared-token) — optionally scoped to
  [per-agent identities](#per-agent-identities-and-path-authorization) — and/or [mutual TLS](tls.md)
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

## Per-Agent Identities and Path Authorization

A single shared `agentToken` authenticates agents but cannot tell them apart: any agent holding the
token can register **any** path, including one already served by another agent. Define named
identities under `proxy.auth`, each with its own token and a list of allowed path glob patterns, to
scope what each agent may register:

```hocon
proxy {
  auth = [
    { name = team-a, token = "team-a-token", paths = ["team_a_*"] }
    { name = team-b, token = "team-b-token", paths = ["team_b_*"] }
    { name = infra,  token = "infra-token",  paths = [] }          // empty paths = may register any path
  ]
}
```

Each agent presents its identity's token the usual way (`--agent_token`, `AGENT_TOKEN`, or
`agent.agentToken`) — no agent-side change is needed. The proxy resolves the token to an identity and
enforces, on every path registration, that the requested path matches one of the identity's patterns.

| Condition                | Result                                             |
|:-------------------------|:---------------------------------------------------|
| Unknown token            | Connection rejected with `UNAUTHENTICATED`         |
| Path matches a pattern   | Registration succeeds                              |
| Path matches no pattern  | Registration fails with a "not authorized" reason  |
| Empty `paths` list       | Identity may register **any** path (allow-all)     |

Patterns are single-segment globs: `*` matches any run of characters and `?` matches exactly one
(e.g. `team_a_*`). Because authorization is per-identity-per-path,
[consolidated mode](../advanced.md#consolidated-mode) still works as long as each participating
agent's identity permits the shared path.

!!! note "Config-file only"

    `proxy.auth` is a list of objects, so it can only be set in a config file — there is no
    equivalent CLI flag or environment variable, and the `-D` property override (parsed as Java
    properties) cannot express a list. Identity names must be unique and tokens non-empty; the proxy
    fails fast at startup otherwise.

### Migrating from a shared token

Setting `proxy.auth` does **not** disable a legacy `proxy.agentToken`. When both are present, the
shared token is honored as an additional **allow-all** identity (the proxy logs a warning that it is
active), so you can adopt per-agent identities incrementally:

1. Add a `proxy.auth` entry per agent while leaving `proxy.agentToken` in place — existing agents
   keep connecting with the shared token.
2. Move each agent onto its own identity token, one at a time.
3. Once every agent presents an identity token, remove `proxy.agentToken` to close the shared
   allow-all path.

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
