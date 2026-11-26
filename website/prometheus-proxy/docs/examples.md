---
icon: lucide/file-code
---

# Example Configs

The repository ships a set of ready-to-run config files under
[`examples/`](https://github.com/pambrose/prometheus-proxy/tree/master/examples). Each is
shown below verbatim, except `examples/cloud-proxy`, which
[Cloud Platforms](cloud-platforms.md#where-to-run-the-proxy) shows. For the full set of options see
[Agent Configuration](configuration/agent.md) and [Proxy Configuration](configuration/proxy.md).

!!! note "Precedence"

    Config files rank below every other source but the built-in defaults: CLI args → env vars →
    `-D` properties → config file → built-in defaults. See the [CLI Reference](cli-reference.md).

## Self-Monitoring — `simple.conf`

Turns on admin on both components and the proxy's metrics port, points the agent at the proxy's `/metrics`
endpoint, and has the agent serve its own metrics at `/agent_metrics`, in-process, with no port
(`agent.metrics.proxyPath`), so the proxy and agent monitor themselves. A good first run on a single host.

```hocon
--8<-- "examples/simple.conf"
```

Run it with:

```bash
java -jar prometheus-proxy.jar --config examples/simple.conf
java -jar prometheus-agent.jar  --config examples/simple.conf
```

In Docker, the agent's `localhost` is its own container, so give it `PROXY_HOSTNAME`, the proxy's host name without a
port: the agent connects to the proxy there, and fetches the proxy's metrics from there too. The
[Docker quick start](docker.md#basic-usage) runs it that way.

## Debugging a Local Run — `prom-agent.conf`

The same self-monitoring setup as `simple.conf`, turned up for troubleshooting: the proxy's debug
servlet is enabled on the admin port, the `labels` are dropped so the output stays readable, and the
agent's metrics port stays on beside its `/agent_metrics` path, so you can `curl localhost:8083/metrics`
directly. Two toggles are left commented out for when you need them — `transportFilterDisabled` for
running behind an nginx reverse proxy, and `scrapeTimeoutSecs` for endpoints slower than the 15-second
default.

```hocon
--8<-- "examples/prom-agent.conf"
```

This is the config behind the bundled *Agent (no auth)* IntelliJ run configuration in `.run/`.

## Multiple Application Endpoints — `myapps.conf`

Three application endpoints, each on its own proxy path and tagged with custom `labels`. This
is the typical shape of a real agent config.

```hocon
--8<-- "examples/myapps.conf"
```

Prometheus then scrapes `/app1_metrics`, `/app2_metrics`, and `/app3_metrics` from the proxy.
See the [Quick Start](getting-started.md#multiple-endpoints).

## Metric Filtering — `agent-filters.conf`

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

## Dynamic Discovery Targets — `discovery-targets.conf`

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

## Prometheus Federation — `federate.conf`

Pulls metrics from an existing Prometheus server through its `/federate` endpoint, exposing
them on a proxy path, and serves the agent's own metrics at `/agent_metrics`, in-process, with no port.
See [Prometheus Federation](advanced.md#prometheus-federation).

```hocon
--8<-- "examples/federate.conf"
```

## Server-Only TLS — `tls-no-mutual-auth.conf`

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

See [TLS Setup](security/tls.md) for [creating certificates](security/tls.md#creating-certificates) and the full
TLS option set.
