# Design — Proxy HA with Agent-Side Failover (Feature 2, Phase 1)

Date: 2026-07-20
Status: Approved, not yet implemented
Feature: #2 of `docs/FEATURE_PROPOSALS_JULY_2026.md`

## Problem

The proxy is a single point of failure for the entire metrics plane. If it goes down, every target
behind every agent disappears from Prometheus at once. The agent already reconnects to a *replacement*
proxy at the same address (`ContainersReconnectTest`), but it has no notion of a second endpoint, so
operators cannot run redundant proxies without external machinery — DNS failover, or a TCP load
balancer with its own single-point-of-failure characteristics.

Phase 1 delivers the headline capability — **run two proxies, survive losing one** — as a small change
to a reconnect loop that already exists.

## How it works today

One endpoint, resolved once, never changed.

`AgentOptions.assignConfigVals()` (`AgentOptions.kt:282-289`) folds `agent.proxy.hostname` and
`agent.proxy.port` into a single `"host:port"` string, with CLI `-p`/`--proxy` winning via an
empty-string sentinel and `PROXY_HOSTNAME` overriding the config-derived default.

`AgentGrpcService.init` (`AgentGrpcService.kt:133-145`) strips any `http(s)://` prefix
case-insensitively, calls `Utils.parseHostPort(…, DEFAULT_GRPC_PORT)`, and assigns the **immutable**
`val agentHostName` / `val agentPort`. `resetGrpcStubs()` (`:208-261`) is the single place a
`ManagedChannel` is built and reads those two fields.

The reconnect loop is `Agent.run()` (`Agent.kt:334-349`), paced by a Guava `RateLimiter` built once
from `reconnectPauseSecs` (`Agent.kt:178-179`, default 3s).

**Everything a new proxy needs is already re-established per connection** — `pathManager.clear()`,
backlog reset, `registerAgent`, `registerPaths()`, a fresh `AgentConnectionContext`
(`Agent.kt:258-278`). That is why Phase 1 is genuinely small.

## The load-bearing constraint

A gRPC `ManagedChannel` is bound to its target address, so switching endpoints **requires rebuilding
the channel**. Two consequences:

1. `connectToProxy()` rebuilds stubs **only when `agentId.isNotEmpty()`** (`Agent.kt:252-257`) — i.e.
   only after a previously *successful* connection. A failed `connectAgent()` returns `false` without
   throwing (`AgentGrpcService.kt:264-287`), leaving `agentId` empty. So a run of consecutive failures
   reuses the same channel against the same address. **A rotation cursor added without changing this
   guard would be inert — it would advance an index nothing reads.**
2. Exactly **one** channel rebuild per retry iteration. Adding a rotate-and-reset alongside the
   existing guard would rebuild twice.

## Decisions

### 1. Config: `agent.proxy.endpoints` list, plus comma-accepting `--proxy` / `PROXY_HOSTNAME`

```hocon
agent {
  proxy {
    hostname = "localhost"     // legacy, still honored when endpoints is empty
    port = 50051               // per-entry default port for bare hostnames
    endpoints = [ "proxy-a:50051", "proxy-b:50051" ]
  }
}
```

```
--proxy proxy-a:50051,proxy-b:50051
PROXY_HOSTNAME=proxy-a:50051,proxy-b:50051
```

An earlier draft of this design claimed a HOCON list forced a config-file-only option, citing the
constraint that made `proxy.auth` and `agent.filters` config-only. **That was wrong.** The constraint
applies to lists of *objects*, whose records cannot be expressed as one CLI string. A list of plain
strings generates cleanly — `ConfigVals.java:147` already declares
`public final java.util.List<java.lang.String> metricNameAllow;`. CLI/env parity was always available.

Overloading `--proxy` rather than minting `--proxies` avoids inventing a precedence rule between two
flags. Commas are illegal in DNS names, so the overload is unambiguous, and a single value collapses to
exactly today's behavior.

**Precedence:** resolve to one ordered list. Config `endpoints`, if non-empty, is the base; otherwise
the base is the one-element list from today's `hostname:port` collapse. `PROXY_HOSTNAME` then `--proxy`
each override *wholesale*, split on comma. `hostname` is never implicitly merged into `endpoints` —
silent prepending would strand anyone who set `agent.proxy.port` and expected it to apply. Every entry
is normalized through `parseHostPort(entry, agent.proxy.port)`, so `endpoints = ["a", "b"]` with
`port = 50052` works as expected.

**`reference.conf` is mandatory.** Generated list fields carry no `hasPathOrNull` guard, so
`agent { proxy {} }` must become `agent { proxy { endpoints = [] } }` or `c.getList("endpoints")`
throws `ConfigException.Missing` against every existing config. This follows the established pattern
already visible there (`pathConfigs: []`, `filters: []`, `proxy.auth = []`) and gets its own
regression test.

### 2. Rotation: advance on connect failure, reset to head on a dropped connection

The cursor advances only when a connect attempt fails, and resets to index 0 whenever a connection that
had succeeded then drops.

This gives failback for free — no health prober, no timer, no per-endpoint state — and makes the list a
real priority order rather than a set. The two failure signals converge without special-case code: a
mid-stream drop restarts the sweep, re-probing the primary, and if the primary is genuinely gone the
next connect failure advances one iteration later.

Rejected: **sticky round-robin**, whose failure mode is that a single transient blip pins the entire
fleet to the standby until it dies during maintenance on the supposed primary. Rejected: **restart the
sweep every cycle**, which retries a hard-down primary on every reconnect. Rejected: **timed drain-back**,
which kills a healthy connection and drops in-flight scrapes.

Cost of the chosen policy is one extra failed connect (about one `reconnectPauseSecs`) per reconnect
while the primary is down — well inside `maxAgentInactivitySecs` of 60s.

### 3. Pacing: unchanged

No `failoverPauseSecs`. One endpoint per outer loop iteration, paced by the existing limiter. For a
two-proxy pair the worst case is one extra 3s pause. Sweeping the whole list inside one iteration would
turn a total outage into every agent hammering every proxy back-to-back — precisely the thundering herd
HA exists to avoid.

### 4. App-level cursor, not gRPC-native multi-address resolution

A custom `NameResolver` or `round_robin` LB policy would **silently break in the fat JAR**:
`src/shadow/resources/META-INF/services/` pins the provider list, so a new provider would pass every
unit and harness test and fail only in the container suite, which is gated behind `RUN_CONTAINER_TESTS`
and skipped on most PRs. Separately, `round_robin` spreads load across both proxies simultaneously —
that is Phase 2 multi-homing, not Phase 1's ordered first-wins.

### 5. Observability: logs only

No metric change. Adding an endpoint label to `agent_connect_count` would change its series identity
and break existing dashboards. `Agent.proxyHost` (`Agent.kt:416`) already reports the current endpoint
in connect/disconnect logs, `/debug`, and `toString()`.

**Written down deliberately:** `proxyHost` stops meaning "the configured proxy" and starts meaning "the
currently selected endpoint."

**Include the endpoint in every connection failure log line.** No connect failure is terminal except
`java.lang.Error`, so with rotation a bad token or wrong cert on endpoint B is indistinguishable from B
being down — the agent would cycle forever without surfacing a config error. An operator seeing
`A: UNAVAILABLE / B: UNAUTHENTICATED` can diagnose it. Status-code classification is out of scope.

## Verified constraints

- **A standby proxy returns 404, not 503**, for a path no agent has registered. `ProxyHttpRoutes.kt:131`
  routes a null agent context to `ProxyUtils.invalidPathResponse` → `HttpStatusCode.NotFound`
  (`ProxyUtils.kt:94`). The 503s are for `proxy_stopped` / `no_agents` / `agent_disconnected` /
  `timed_out`, none of which a standby hits. Prometheus scores both as `up=0`, so the HA story holds,
  but `FEATURE_PROPOSALS_JULY_2026.md:195-196` states 503 and must be corrected.
- **The HA recipe must mandate `static_config`.** A standby returns `[]` from `/discovery`; under
  `http_sd_config` Prometheus *deletes* those targets rather than marking them down — no `up=0`, no
  alert, series silently vanish. The feature's own detection story fails without this.
- **Homogeneous endpoints only.** One `tlsContext` built once (`AgentGrpcService.kt:145-155`) and one
  global `overrideAuthority` serve every endpoint. Different CAs or SANs fail with an opaque handshake
  error rather than a clear config error. Hard Phase 1 constraint; document it.
- **`@Volatile` is required** once `agentHostName`/`agentPort` become mutable: `Agent.proxyHost` reads
  them unsynchronized from log lambdas on the run loop while `resetGrpcStubs` writes under `grpcLock`.
- **Rotation must route through `resetGrpcStubs()`** so its `shutDownRequested` early-return
  (`AgentGrpcService.kt:214-217`) keeps winning. Bypassing it resurrects a live channel after a
  requested stop and reinstates the run-loop deadlock that latch was added to fix.
- **Channel churn increases an existing race.** Teardown is `shutdownNow()` + `awaitTermination(5s)`
  with the timeout only logged (`:161-170`); a straggler's late `onHeaders` can assign a stale agentId
  to the next connection. Across endpoints that becomes a cross-proxy identity bug.
- **`launchId` is process-scoped** (`Agent.kt:200`) and labels every metric, so one series spans two
  proxies' identity spaces after a switch. Accept and document; resetting it would fragment metrics
  worse.
- **`RegisterAgentResponse.proxy_url`** (`proxy_service.proto:21`) is declared and entirely dead. It
  looks like existing failover plumbing. Leave it alone.

## Testing

**Unit.** The `reference.conf` regression guard is the highest-value new test: build `ConfigVals` from a
config with `agent.proxy { hostname = "x" }` and no `endpoints`, and assert it loads. The `ConfigVals`
constructor eagerly builds both trees, so an unguarded agent-side `getList` throws even in a proxy-only
test. Plus `parseEndpointList` cases (comma splitting, whitespace, per-entry default port, scheme
prefixes, IPv6, malformed-entry fail-fast) and cursor tests (advance on failure, reset on drop,
wrap-around, and `shutDownRequested` still winning over rotation).

**Trap:** `AgentGrpcServiceTest.createMockAgent` is a relaxed mock used at ~45 construction sites. An
unstubbed list option returns an empty list, producing a service with zero endpoints that either passes
vacuously or fails opaquely far from the cause. Stub it in the helper.

**Harness (Netty only).** In-process cannot cover rotation at all — `GrpcDsl.channel` ignores
host/port/TLS entirely when `inProcessServerName` is set, so an in-process failover test would silently
assert nothing. Two in-JVM proxies, kill the active one, assert scrapes resume and a **new** `agentId`
is issued. Needs second-instance ports in `TestPorts.kt` and a `proxyPort` parameter on
`TestUtils.startProxy`.

**Container.** `ContainerTestSupport.proxyContainer` unconditionally calls
`withNetworkAliases(PROXY_ALIAS)` (`:105`), so two instances today land behind one alias and Docker
round-robins between them non-deterministically. It needs an `alias` parameter (mirroring
`agentContainer` and `metricsStub`), `PROXY_ALIAS` promoted to `const val`, and a parameterized log
consumer name so the two proxies' logs are distinguishable.

The spec must use **two distinct aliases** and **assert it reached proxy B specifically** — a test
copied from `ContainersReconnectTest` reuses one alias and therefore proves DNS re-resolution, not
endpoint selection, so it would pass even if rotation were never implemented. Agent config must be
generated at runtime via `configText`, since `-D` params are parsed with PROPERTIES syntax and cannot
carry a HOCON list.

All three levels are required: container specs are skipped on a plain `./gradlew test`, so a container
test alone leaves the feature untested on ordinary PRs.

## Scope boundary

**In:** an ordered endpoint list; one connection at a time; advance on connect failure; reset to head on
a dropped-but-established connection; homogeneous TLS; existing pacing; logs-only observability;
Prometheus scraping both proxies via `static_config`.

**Not in — Phase 2 (simultaneous multi-homing).** It requires changing agent identity from
process-scoped to connection-scoped. Four blockers: `Agent.agentId` is one scalar and
`AgentClientInterceptor` latches the first `agent-id` header it ever sees; `connectionMismatchReason`
then rejects mismatched RPCs, and that check exists for a real security reason (agentIds are guessable
sequential integers) so it must not simply be relaxed; `pathManager` is process-global and
`pathManager.clear()` on one reconnect would wipe state the other connection depends on; and each proxy
runs an independent cleanup service, so an agent heartbeating only its active connection is silently
evicted from the other — turning intended active-active into unnoticed active-passive.

**Not in — Phase 3 (clustering).** There is zero proxy-to-proxy awareness: all state is per-process
in-memory, with no persistence, peer config, or gossip.

**Also out:** per-endpoint TLS contexts or authority overrides; proxy identity labels in service
discovery; any change to consolidated-mode merge semantics.
