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
The token is never logged. If a token or per-agent identities are configured without TLS, the proxy and the
agent each log a startup warning that tokens are sent in cleartext.

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
token can register **any** path, including one already served by another agent — silently taking over
its metrics. Nothing fails and nothing alerts; the dashboard keeps drawing a line, it is just another
agent's numbers. The shared secret also makes revocation all-or-nothing (rotating it means
reconfiguring every agent at once) and leaves the proxy unable to say *who* registered a path.

Define named identities under `proxy.auth`, each with its own token and a list of allowed path glob
patterns, to scope what each agent may register. This closes the takeover hole and makes a shared
proxy safe for **multiple teams**, none of which can step on another's paths:

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
agent's identity permits the shared path. A consolidated path belongs to the identity that registered
it, though: another identity whose patterns match is refused while agents still serve the path, and
joins only once they are gone.

A non-consolidated path is protected the same way. While a live agent serves it, only an agent of the
same identity may take it over, which keeps redeploys working; an agent of another identity is refused
with a reason, logged at WARN, until the serving agent is gone. With no agent authentication, or only
the legacy shared token, every agent has the same identity, so takeovers behave as they always have. The
legacy token and a `proxy.auth` identity cannot take over each other's live paths.

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
2. Move each agent onto its own identity token, one at a time, stopping the old agent before starting
   its replacement. While the old agent still serves a path, the proxy rejects the new identity's
   registration of it; the new agent retries a rejected static path, first after
   `agent.internal.rejectedPathRetrySecs` and then on a backoff of up to
   `agent.internal.rejectedPathRetryMaxSecs`, and registers it once the old agent is gone.
3. Once every agent presents an identity token, remove `proxy.agentToken` to close the shared
   allow-all path.

Step 3 is the one that actually secures anything: until the shared token is removed, anyone holding
it still has allow-all access — the per-agent entries constrain only the agents that use them.

!!! note "Current limits"

    Identities are read at startup, so revoking one requires a **proxy restart** — there is no hot
    reload. Identity derives from the presented token, not from an mTLS client certificate, and
    tokens live in the proxy's config file (no env-var or file-based token source). Compare tokens'
    operational weight against [mutual TLS](tls.md) when choosing a posture; the two can be combined.

## Isolation Between Agents

Authenticated agents share one proxy, so the proxy keeps each agent to its own traffic:

- **Scrape results come only from the agent the scrape was sent to.** Scrape IDs come from one
  counter shared by every agent, so the proxy checks each result, failure, and chunked-transfer message
  against the agent that owns the scrape. A message for another agent's scrape is dropped and logged
  at WARN, and the rest of the stream is still processed.
- **Heartbeats are bound to their connection.** A heartbeat naming another agent is refused and does
  not keep that agent from being evicted as stale.
- **Behind a reverse proxy, calls are bound to an identity.** With
  [`transportFilterDisabled`](../configuration/proxy.md#transport-filter) there is no connection to tie a
  call to, so the proxy records the auth identity an agent connected with and refuses a call naming that
  agent under a different identity.

!!! warning "Identity binding is only as fine-grained as the identities"

    Calls are bound only when agent authentication is configured (`proxy.auth` or `proxy.agentToken`).
    Agents that share one identity cannot be told apart when the transport filter is disabled, and
    that includes every agent on the legacy `proxy.agentToken`, which is a single allow-all identity.
    Behind a reverse proxy, give each agent its own `proxy.auth` token. With the transport filter
    enabled, calls are bound by connection and this limit does not apply.

## Credentials in URLs

A target URL can carry credentials, such as `http://user:pass@host/metrics?api_key=...`. The agent
redacts the user info and query values everywhere a target URL is sent, logged, or displayed: in its
logs, in what it sends the proxy, and so on the dashboard and `/debug` pages. It also redacts them
inside HTTP client error messages, which embed the request URL. The proxy redacts target URLs from
older agents as well. A config URL that fails to load (`--config http://user:pass@host/agent.conf`) is
redacted in the error log and in the `ConfigLoadException` an embedding application catches.

## Scrape Port Responses

The proxy passes a target's `Content-Type` through only for the Prometheus exposition formats
(`text/plain`, `application/openmetrics-text`, and the protobuf format); anything else is served as
`text/plain`. Every scrape-port response also carries `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: sandbox`. A compromised target therefore can't serve a page that runs as
script on the proxy's origin in an operator's browser.

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

## Redirects from Scrape Targets

The agent follows a redirect only to the target's own origin — the same scheme, host, and port.
A redirect to any other origin is not followed: the scrape reports the 3xx status and the agent logs
a warning. This keeps a target, or an open redirect reached through forwarded query parameters, from
sending the agent to another host that would receive the target's basic-auth credentials, or to an
internal address whose response would come back through the proxy. Point the path's `url` at the
final location if a target redirects elsewhere.

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

## Reporting a Vulnerability

Report a security problem privately, not in a public issue, pull request, or discussion: use GitHub's
[private vulnerability reporting](https://github.com/pambrose/prometheus-proxy/security/advisories/new). The report
stays visible only to you and the maintainer until an advisory is published with the fix. Security fixes go into
the latest release only, so upgrade to receive them.

[`SECURITY.md`](https://github.com/pambrose/prometheus-proxy/blob/master/SECURITY.md) lists what a useful report
includes, and which documented behavior is by design rather than a vulnerability: the agent port accepting any
agent without a token, identities, or mutual TLS, and the admin, metrics, and dashboard ports having no
authentication.
