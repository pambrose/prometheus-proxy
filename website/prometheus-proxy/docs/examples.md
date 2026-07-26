---
icon: lucide/file-code
---

# Example Configs

The repository ships a set of ready-to-run config files under
[`examples/`](https://github.com/pambrose/prometheus-proxy/tree/master/examples). Each is
shown below verbatim. For the full set of options see
[Agent Configuration](configuration/agent.md) and [Proxy Configuration](configuration/proxy.md).

!!! note "Precedence"

    Config files are the lowest-precedence source: CLI args → env vars → config file →
    built-in defaults. See the [CLI Reference](cli-reference.md).

## Self-monitoring — `simple.conf`

Enables admin and metrics on both components and points the agent at the proxy's and agent's
own `/metrics` endpoints, so the proxy and agent monitor themselves. A good first run on a
single host.

```hocon
--8<-- "examples/simple.conf"
```

Run it with:

```bash
java -jar prometheus-proxy.jar --config examples/simple.conf
java -jar prometheus-agent.jar  --config examples/simple.conf
```

## Multiple application endpoints — `myapps.conf`

Three application endpoints, each on its own proxy path and tagged with custom `labels`. This
is the typical shape of a real agent config.

```hocon
--8<-- "examples/myapps.conf"
```

Prometheus then scrapes `/app1_metrics`, `/app2_metrics`, and `/app3_metrics` from the proxy.
See the [Quick Start](getting-started.md#multiple-endpoints).

## Metric filtering — `agent-filters.conf`

Drops whole metric families at the agent, before the payload is gzipped, chunked, and sent across
the WAN. Filters live in a top-level `agent.filters` list keyed by `path` rather than nested inside
`pathConfigs`, so a filter applies to that path whether it was registered statically or by dynamic
discovery.

```hocon
--8<-- "examples/agent-filters.conf"
```

Regexes are fully anchored, matching Prometheus `relabel_config` semantics: `go_` matches nothing,
while `go_.*` matches `go_gc_duration_seconds`. See
[Metric Filtering](configuration/agent.md#metric-filtering).

## Dynamic discovery targets — `discovery-targets.conf`

Unlike every other file on this page, this one is **not** passed to `--config`. It is the targets
file an agent watches when
[dynamic target discovery](configuration/agent.md#dynamic-target-discovery) is enabled, so it has a
top-level `paths` list and no `agent { }` wrapper. The agent re-reads it every
`reconcileIntervalSecs` and registers, unregisters, or re-registers paths to match — no restart, and
paths that did not change keep scraping.

```hocon
--8<-- "examples/discovery-targets.conf"
```

An agent points at it from its own config:

```hocon
agent {
  discovery {
    enabled = true
    file.path = "examples/discovery-targets.conf"
    reconcileIntervalSecs = 30
  }
}
```

## Prometheus federation — `federate.conf`

Pulls metrics from an existing Prometheus server through its `/federate` endpoint, exposing
them on a proxy path. See [Prometheus Federation](advanced.md#prometheus-federation).

```hocon
--8<-- "examples/federate.conf"
```

## Server-only TLS — `tls-no-mutual-auth.conf`

Encrypts the gRPC channel with a server certificate; the agent verifies the proxy but does not
present a client certificate. Note the non-default agent port `50440` and the
`overrideAuthority` used to match the test fixture's SAN.

```hocon
--8<-- "examples/tls-no-mutual-auth.conf"
```

## Mutual TLS — `tls-with-mutual-auth.conf`

Both sides present certificates: the proxy trusts the client CA and the agent supplies its own
`certChainFilePath` / `privateKeyFilePath`. This is the recommended production posture.

```hocon
--8<-- "examples/tls-with-mutual-auth.conf"
```

See [TLS Setup](security/tls.md) for generating certificates and the full TLS option set.
