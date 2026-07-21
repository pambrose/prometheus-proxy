# Feature Proposals — July 2026

This document describes five proposed features for prometheus-proxy, numbered for easy
reference (Feature 1 through Feature 5). Each section covers the problem being solved, the
proposed design, the components affected, a suggested MVP scope, and open questions.

A suggested implementation order appears at the end of the document.

---

## Feature 1: Dynamic Target Discovery on the Agent

> **Status: Implemented (MVP — file source).** A polling reconcile loop keeps the agent's registered
> paths in sync with a HOCON/JSON file (`agent.discovery`), diffing desired-vs-actual and tagging each
> path `STATIC` or `DISCOVERED` so static `pathConfigs` are never disturbed. Still deferred: the
> **Kubernetes** and **Docker** discovery sources, and change-triggered (rather than polled) file
> reloads. See **Implementation Notes (As Built)** immediately below for the shipped design and where
> it diverged from the original proposal.

### Implementation Notes (As Built)

Shipped as the file-source MVP. The proposal below is preserved as the original design; these notes
record what landed and the places it diverged.

**Config (diverged — flattened, file-only).** A single `agent.discovery` block with a flat
`file.path`, not the nested `file { } / kubernetes { } / docker { }` structure:

```hocon
agent {
  discovery {
    enabled = false
    file.path = ""              // HOCON/JSON file listing discovered paths
    reconcileIntervalSecs = 30  // Poll-and-reconcile / full-resync interval
  }
}
```

**Trigger (diverged — polling, not a watcher).** `PathDiscoveryService` re-reads the source every
`reconcileIntervalSecs` (the interval wait is sliced into short polls so shutdown stays prompt). The
proposal's `WatchService` + `SIGHUP` change-triggering was **not** built; the safety-net poll is the
sole mechanism.

**Reconciler.** `AgentPathManager.reconcileDiscoveredPaths(desired: List<DiscoveredPath>)` holds the
path lock across a desired-vs-actual diff and issues the `registerPath` / `unregisterPath` deltas.
Each path carries a `PathSource` (`STATIC` vs `DISCOVERED`): static `pathConfigs` are never added,
updated, or removed by reconciliation — resolving the source-tagging open question affirmatively.

**Files.** New `agent/discovery/` package: `PathDiscoverySource` (a `fun interface` with
`read(): List<DiscoveredPath>`, so the Kubernetes/Docker sources plug in later without other changes),
`FileDiscoverySource`, `PathDiscoveryService`. Modified: `AgentPathManager` (reconcile API +
`PathSource`), `Agent` (lifecycle wiring alongside the heartbeat service), `AgentOptions` / `ConfigVals`.

**Tests.** `FileDiscoverySourceTest`, `PathDiscoveryServiceTest` (+ shared `DiscoveryTestSupport`),
the Netty-transport harness `AgentDiscoveryTest`, and container `ContainersDiscoveryTest`.

### Motivation

Without dynamic discovery, prometheus-proxy is effectively limited to static fleets: every
target change is a manual config edit plus an agent restart, and restarting the agent
disrupts scraping for *all* of its paths, not just the one that changed. That makes the
product a poor fit for exactly the environments where it is most useful — Kubernetes,
Docker, and autoscaling groups, where the target set churns continuously. This is the
most-requested day-to-day operational relief and it widens the range of environments the
product can serve, all on top of registration plumbing (`registerPath` / `unregisterPath`)
that already exists.

### Problem

`agent.pathConfigs` is a static HOCON list read once at startup. Every new service behind
the firewall requires editing the agent config and restarting the agent. In dynamic
environments (Kubernetes, Docker, autoscaling groups) this is the single largest
operational friction point: the set of scrape targets changes constantly, but the agent's
view of them is frozen at boot.

The gRPC plumbing for dynamic membership already exists — `registerPath` and
`unregisterPath` are first-class RPCs in `proxy_service.proto` — only the *trigger* is
missing on the agent side.

### Proposed Design

Add a pluggable discovery layer to the agent that watches a local source of truth and
reconciles the agent's registered paths against it:

```hocon
agent {
  discovery {
    enabled = false
    reconcileIntervalSecs = 30        // Full resync interval (safety net)

    file {
      enabled = false
      path = ""                       // JSON/YAML file of pathConfigs; watched for changes
    }

    kubernetes {
      enabled = false
      namespace = ""                  // Empty = all namespaces visible to the service account
      annotationPrefix = "prometheus-proxy.io/"   // e.g. .../path, .../port, .../scrape
    }

    docker {
      enabled = false
      labelPrefix = "prometheus-proxy."           // e.g. prometheus-proxy.path
    }
  }
}
```

Reconciliation loop: compute the desired path set from all enabled sources plus the static
`pathConfigs`, diff against the currently registered set in `AgentPathManager`, and issue
`registerPath` / `unregisterPath` calls for the delta. The loop must be idempotent and
resilient to proxy reconnects (on reconnect the agent already re-registers; discovery
simply feeds the same desired-state set).

### MVP

Config hot-reload, no external systems:

1. **File watcher** — watch the `file.path` source (or the main agent config file itself)
   with a `WatchService`, plus a `SIGHUP` handler as an explicit trigger.
2. On change, re-parse `pathConfigs` and reconcile.

This alone eliminates agent restarts for path changes and builds the reconciliation
machinery that the Kubernetes/Docker watchers plug into later.

### Affected Components

- `AgentPathManager` — becomes the reconciliation target (needs a "desired vs. actual" diff API)
- `Agent` — lifecycle wiring for the discovery service (start/stop alongside heartbeat service)
- `ConfigVals` — regenerate via `make tsconfig` after schema changes
- New: `agent/discovery/` package (`DiscoverySource` interface, `FileDiscoverySource`,
  later `KubernetesDiscoverySource`, `DockerDiscoverySource`)

### Testing

- Kotest unit tests for the reconciler (desired/actual diffing, flapping sources, duplicate paths)
- Harness test: register via file change, verify scrape works, remove, verify 404
- Container test: mount a config file into the agent container, mutate it, assert the
  proxy's `/discovery` output updates

### Open Questions

- ~~Should discovered paths be visually distinguishable from static ones in the debug
  servlet / future UI (Feature 5)?~~ **Partly done:** paths now carry an internal `PathSource`
  (`STATIC` / `DISCOVERED`) that scopes reconciliation; surfacing it in the debug servlet / future
  UI (Feature 5) remains open.
- Kubernetes watcher: in-cluster config only, or also kubeconfig for out-of-cluster agents? (Still
  open — the Kubernetes source is not yet built.)

---

## Feature 2: Proxy High Availability with Agent-Side Failover

> **Status: Implemented (Phase 1 — agent-side endpoint failover).** The agent takes an ordered list of
> proxy endpoints, connects to the first that answers, advances on a failed connect, and returns to the
> head of the list when a working connection drops. Still deferred: **Phase 2** (simultaneous
> multi-homing) and **Phase 3** (proxy clustering). See **Implementation Notes (As Built)** immediately
> below for the shipped design and where it diverged.

### Implementation Notes (As Built)

**Config (diverged — no new `endpoints` CLI flag).** The proposal assumed a separate `--proxies` flag
alongside `agent.proxy.endpoints`. As built, the config key exists as proposed, but `--proxy` and
`PROXY_HOSTNAME` simply accept a **comma-separated** value rather than a second flag being minted —
commas are illegal in DNS names, so the overload is unambiguous, and it avoids inventing a
`--proxy` vs `--proxies` precedence rule. `endpoints` is the base when non-empty; env then CLI override
it *wholesale* (never merged, since silently prepending `hostname` would strand anyone who set
`agent.proxy.port` expecting it to apply). A single value resolves to a one-element list and behaves
exactly as before.

An earlier draft of this note claimed a HOCON list forced a config-file-only option, citing the
constraint behind `proxy.auth` and `agent.filters`. That was wrong: the constraint applies to lists of
*objects*, not strings — `ConfigVals` already generates a clean `List<String>` for `metricNameAllow`.

**`reference.conf` is load-bearing.** tscfg emits an unguarded `c.getList("endpoints")` for a list-typed
key, unlike the `hasPathOrNull` check it generates for the sibling `hostname`/`port` scalars, so
`agent.proxy.endpoints = []` had to be added or every pre-existing config would fail to load. Two
`ConfigValsTest` cases pin this.

**Rotation (diverged — no `failoverPauseSecs`).** The proposal sketched a separate failover pause. Not
built: rotation happens inside the existing retry loop, so the existing `RateLimiter` already paces it,
and a second timing knob would have to compose with a fixed-rate limiter constructed once at startup.

Rotation is driven by one bit already present in the loop: `agentId` is non-empty at the top of an
iteration only if the previous attempt actually connected. A connection that came up and then dropped
resets to the head of the list — that is the entire failback mechanism, with no prober, timer, or
per-endpoint state, and it resolves the proposal's open question about failback affirmatively (contrary
to its "no automatic failback" recommendation). An attempt that never connected advances instead.

The channel rebuild is the load-bearing part. A `ManagedChannel` is bound to its target address, and
`connectToProxy()` previously rebuilt only after a *successful* connection, so a run of failures reused
one channel against one address. Without changing that guard, a cursor would advance an index nothing
reads. The rebuild on the advance path is conditional on the endpoint actually changing, so a
single-endpoint agent keeps reusing its channel and leaning on gRPC's own backoff.

**Not built: gRPC-native multi-address resolution.** A custom `NameResolver` or `round_robin` policy
would break silently in the fat JAR, since `src/shadow/resources/META-INF/services/` pins the provider
list — it would pass every unit and harness test and fail only in the container suite.

**Constraints.** All endpoints share one TLS context and one `overrideAuthority`, so heterogeneous CAs
or SANs fail with an opaque handshake error. `launchId` stays process-scoped, so one metric series spans
both proxies' identity spaces after a switch. No connect failure is terminal, so a bad token on endpoint
B is indistinguishable from B being down — mitigated by naming the current endpoint in every failure log
line.

**Files.** New `Utils.parseEndpointList` and `HostPort.spec` (a render that round-trips back through
`parseHostPort` — the naive `"$host:$port"` form drops the brackets an IPv6 literal needs, so endpoints
were silently mangled into a bare-IPv6-with-no-port). Modified: `AgentGrpcService` (endpoint list,
`@Volatile` cursor, `advanceEndpoint`/`resetEndpoint`, single-read `currentEndpoint`), `Agent`
(rotation wiring, `proxyHost`), `AgentOptions` (resolution + fail-fast validation), `config/config.conf`,
`reference.conf`, and `ContainerTestSupport.proxyContainer` (which hardcoded one network alias, so two
proxies round-robined behind one name).

**Tests.** `parseEndpointList` and `HostPort.spec` round-trip cases in `UtilsTest`; rotation and
shutdown-latch cases in `AgentGrpcServiceTest`; endpoint resolution in `AgentOptionsTest` via a dedicated
config (that whole block was previously unreachable, since every existing case passes `--proxy`);
`reference.conf` regression cases in `ConfigValsTest`; the Netty harness spec `AgentProxyFailoverTest`;
and the two-proxy `ContainersProxyFailoverTest`. Both end-to-end specs were verified to fail when
`advanceEndpoint()` is forced to return false — an in-process harness spec cannot cover rotation at all,
because `GrpcDsl` ignores host and port when an in-process server name is supplied.

### Motivation

A monitoring system needs to be more available than the things it monitors, yet today a
single proxy outage blinds Prometheus to every target behind every agent simultaneously.
For production adoption that is disqualifying: operators cannot bet their observability on a
component with no redundancy story. High availability is table stakes for serious
deployments, and the cheapest phase (an agent-side failover list) delivers the headline
capability — "run two proxies, survive losing one" — with a small change to a reconnect
loop the agent already has.

### Problem

The proxy is a single point of failure for the entire metrics plane. If it goes down, all
targets behind all agents disappear from Prometheus at once. The agent already handles
reconnecting to a *replacement* proxy at the same address (covered by
`ContainersReconnectTest`), but there is no support for multiple proxy endpoints, so
operators cannot run redundant proxies without external tricks (DNS failover, a TCP load
balancer with its own SPOF characteristics).

### Proposed Design

Phased, cheapest-first:

**Phase 1 — Failover list.** Allow the agent to accept an ordered list of proxy endpoints:

```hocon
agent {
  proxy {
    // Existing single hostname/port retained for backward compatibility
    hostname = "localhost"
    port = 50051

    // New: tried in order; first successful connection wins
    endpoints = [ "proxy1.example.com:50051", "proxy2.example.com:50051" ]
    failoverPauseSecs = 3            // Pause before trying the next endpoint
  }
}
```

`AgentGrpcService`'s existing reconnect loop (`reconnectPauseSecs`) rotates through the
list instead of hammering one address. Prometheus is configured to scrape *both* proxies
with identical target lists; the inactive proxy returns ~~503~~ **404** for unregistered
paths (`ProxyHttpRoutes` routes a null agent context to `ProxyUtils.invalidPathResponse`,
which returns `NotFound`; the 503s are for `proxy_stopped` / `no_agents` /
`agent_disconnected` / `timed_out`, none of which a standby hits). Either way Prometheus
scores it `up=0`, so the HA-pair story is unaffected — but the status code was wrong here
and would have propagated into alert rules.

**Phase 2 — Active-active multi-homing.** The agent maintains simultaneous connections to
N proxies and registers its paths on all of them. Every proxy can serve every path;
Prometheus deduplicates via its usual HA story (or via `honor_labels` + external labels).
This removes the failover gap entirely.

**Phase 3 (exploratory) — Shared state.** True clustering with shared agent-context state
between proxies. Deliberately out of scope until Phases 1–2 prove insufficient; the
stateless-per-proxy model of Phase 2 is operationally much simpler.

### MVP

Phase 1 only. It is a small, low-risk change to the reconnect loop plus config surface,
and it delivers the headline capability ("run two proxies, survive losing one").

### Affected Components

- `AgentGrpcService` — endpoint rotation in the connect/retry loop
- `AgentOptions` / `ConfigVals` / `EnvVars` — new `endpoints` option (CLI: `--proxies`,
  env: `PROXY_ENDPOINTS`)
- Docs: an HA deployment recipe in the Kubernetes guide and README

### Testing

- Unit tests for endpoint rotation order and failover pause behavior
- Container test: two proxies, kill the active one, assert the agent lands on the second
  and scrapes resume (extends the existing `ContainersReconnectTest` pattern)

### Open Questions

- Phase 2 interaction with `consolidated` mode: multi-homed agents registering the same
  path on multiple proxies is fine, but the semantics should be documented carefully.
- Should the agent prefer to fail back to the primary endpoint when it recovers?
  (Recommended: no automatic failback in Phase 1; keep it simple and sticky.)

---

## Feature 3: Per-Agent Identity and Path Authorization

> **Status: Implemented (MVP).** Per-agent identities (`proxy.auth`) with glob-based path
> authorization on `registerPath`, enforced via a gRPC context-propagated identity. The legacy
> `proxy.agentToken` is honored as an allow-all identity for migration; the change is additive and
> non-breaking. Still deferred: hot-reload revocation, mTLS-derived identity, env/file token
> sources, and safe-by-default enforcement (see [Backward Compatibility](#backward-compatibility)).
> User-facing docs live in the README and the docs site (security + proxy-configuration pages). See
> **Implementation Notes (As Built)** immediately below for the shipped design and where it diverged
> from the original proposal.

### Implementation Notes (As Built)

Shipped as an additive, non-breaking change. The proposal below is preserved as the original design;
these notes record what actually landed and the four places it diverged.

**Config schema (diverged).** A bare list, not an `enabled` / `agents` block, and the field is
`paths` (not `pathPatterns`):

```hocon
proxy {
  auth = [
    { name = team-a, token = "team-a-token", paths = ["team_a_*"] }
    { name = team-b, token = "team-b-token", paths = ["team_b_*"] }
    { name = infra,  token = "infra-token",  paths = [] }   // empty = allow all
  ]
}
```

Auth is enabled *implicitly* when the list is non-empty (or a legacy `proxy.agentToken` is set) —
there is no `enabled` flag. `paths` is **required within each identity** (write `paths = []` for
allow-all): tscfg cannot default an empty typed list, so the schema uses the type-name form
`paths = [ String ]`, which makes the field required-present per element.

**Identity propagation (diverged).** The resolved identity travels from the interceptor to the
handler via a gRPC `io.grpc.Context` key (`AgentAuthManager.AGENT_IDENTITY_KEY`), **not** stored on
`AgentContext` as the proposal suggested. grpc-kotlin propagates `io.grpc.Context` across coroutine
dispatch (`GrpcContextElement`), so `AGENT_IDENTITY_KEY.get()` is valid inside the `registerPath`
suspend handler. `AgentContext` was left unchanged.

**Denial signal (diverged).** An unauthorized path is rejected as a `registerPath` **failure
response** (`valid = false` + a "not authorized" reason), reusing the existing `String?` reason
contract — **not** a gRPC `PERMISSION_DENIED` status. The agent already surfaces this as
`RequestFailureException`. Consequently there is **no `.proto` change**. Authentication failures
(unknown token) still close the call with `UNAUTHENTICATED`, unchanged.

**Interceptor (diverged).** The original `AgentTokenServerInterceptor` was **replaced** by
`AgentAuthServerInterceptor`, backed by a new `AgentAuthManager` that holds the identities, resolves
tokens (constant-time SHA-256 digest compare), and compiles the globs. The single-token path is now
simply one allow-all identity.

**Files.** New: `proxy/AgentAuthManager.kt`, `proxy/AgentAuthServerInterceptor.kt`. Modified:
`config/config.conf` (+ regenerated `ConfigVals.java`), `src/main/resources/reference.conf`
(`proxy.auth = []` backward-compat fallback — the generated `c.getList("auth")` is required-present),
`Proxy.kt`, `ProxyGrpcService.kt`, `ProxyServiceImpl.registerPath`. Removed:
`AgentTokenServerInterceptor.kt` (the pre-rename interceptor). `ProxyOptions` / `EnvVars` were
**not** touched — the identity list is config-file-only (a list of objects), like `agent.pathConfigs`.

**Tests.** `AgentAuthManagerTest` (globs, token resolution, fail-fast validation),
`AgentAuthServerInterceptorTest` (replaces the old interceptor test; also verifies the identity is
readable from the context), allow/deny cases added to `ProxyServiceImplTest`, and a Netty-transport
`AgentPathAuthTest` harness test that drives an allowed registration and asserts
`RequestFailureException` on a cross-team path. Because config-only `proxy.auth` cannot be expressed
via `-D` (parsed as Java properties, so it cannot carry a list), the harness test loads a dedicated
`config/test-configs/path-auth.conf`. Full build passes; the
`ContainersAgentTokenAuthTest` two-identity extension was **deferred**.

**Verification.** `detekt` + `lintKotlinMain` + `lintKotlinTest` clean; full `./gradlew build`
green (all suites, including the legacy-single-token `AgentTokenAuthTest`, confirming backward
compatibility); coverage ~95.9%.

### Motivation

Everything here stems from one root cause: `agentToken` is a single shared secret with no
notion of *which* agent presents it. That single fact produces a security hole and two
capability limits:

- **Security.** Because `registerPath` performs no authorization, any token-holder can
  register any path — including one already backed by another agent — silently hijacking
  its metrics. This was the high-severity finding in the June 2026 security review, and it
  is unfixable while every agent looks identical on the wire.
- **Attribution.** A token that maps to a boolean rather than a named identity means the
  proxy cannot say *who* registered a path or opened a connection, in logs, the debug
  servlet, or a future UI.
- **Revocation.** Rotating the shared secret requires reconfiguring every agent at once;
  there is no way to revoke or rotate credentials for one decommissioned or misbehaving
  agent.

Giving each agent its own identity plus a path allowlist closes the hijack hole and, as a
bonus, unlocks **multi-tenancy** — different teams running agents against one shared proxy
without being able to step on each other's paths, which is unsafe today.

### Problem

`agentToken` is a single shared secret. Any agent that holds it can register **any** path —
including a path already owned by another agent, silently hijacking its metrics. This was
flagged as the high-severity finding in the June 2026 security review. The shared token
also makes revocation all-or-nothing: rotating it requires reconfiguring every agent at
once, and there is no way to identify *which* agent presented a token.

### Proposed Design

> **Original proposal — partly superseded.** The as-built design differs in specifics (config shape,
> identity propagation, and denial signal); see [Implementation Notes (As Built)](#implementation-notes-as-built)
> above. The intent below is unchanged.

Introduce named identities on the proxy, each with its own credential and an optional path
authorization pattern:

```hocon
proxy {
  auth {
    enabled = false
    agents = [
      {
        name = "team-a-agent"
        token = "..."                    // Or resolved from env/file
        pathPatterns = [ "team_a_*" ]    // Glob patterns this identity may register
      },
      {
        name = "team-b-agent"
        token = "..."
        pathPatterns = [ "team_b_*" ]
      }
    ]
    // Existing single agentToken remains supported as a fallback identity
  }
}
```

Enforcement points:

1. **Authentication** — the existing server interceptor maps the presented token to an
   identity instead of a boolean; the identity is attached to the `AgentContext`.
2. **Authorization** — `registerPath` validates the requested path against the identity's
   `pathPatterns` and returns `PERMISSION_DENIED` on mismatch.
3. **Revocation** — reloading the auth config (admin endpoint or file watch) drops the
   removed identity's connections and unregisters its paths, without a proxy restart.

A follow-on option is mTLS-based identity: when mutual TLS is configured (already
supported), derive the identity from the client certificate SAN instead of a token, so the
credential and the transport security are one mechanism.

### MVP

Per-agent tokens + `pathPatterns` enforcement on `registerPath`, static config only
(restart to change). Hot revocation and mTLS-derived identity follow.

### Affected Components

- Proxy server auth interceptor (token → identity resolution)
- `ProxyServiceImpl.registerPath` — authorization check
- `AgentContextManager` / `AgentContext` — carry the identity; expose it in the debug
  servlet and Feature 5's UI
- `ProxyOptions` / `ConfigVals` — new `auth` block
- Docs: security section rewrite; migration notes from single `agentToken`

### Testing

- Unit tests: token→identity mapping, pattern matching (globs, empty = allow-all)
- Harness tests: register allowed path (success), disallowed path (`PERMISSION_DENIED`),
  wrong token (existing `UNAUTHENTICATED` behavior preserved)
- Container test: extend `ContainersAgentTokenAuthTest` with a two-identity scenario
  including an attempted cross-team path registration

### Backward Compatibility

This feature is designed to be **additive and opt-in**, so adopting it is not a breaking
change. Three properties make that true against the current code:

1. **Config is additive.** The new `proxy.auth` block defaults to `enabled = false`. An
   existing config that sets only `proxy.agentToken` (or nothing) is unaffected, and the
   legacy single `agentToken` continues to work as a fallback identity.
2. **No wire/proto change.** Auth travels in the gRPC metadata header
   (`META_AGENT_TOKEN_KEY`), not in `proxy_service.proto`. Per-agent tokens reuse the same
   header — the proxy maps token → identity instead of token → boolean — so old agents talk
   to a new proxy and new agents talk to an old proxy without a protocol break.
3. **Enforcement only activates on opt-in.** `registerPath` currently performs no
   authorization at all; the new path check rejects a registration only when the resolved
   identity actually has `pathPatterns` configured. With no patterns set, behavior is
   identical to today.

**Design landmines that would accidentally break compatibility** (and must be avoided):

- **Empty `pathPatterns` must mean "allow all," not "deny all"** — matching the existing
  convention where an empty `agentToken` disables auth. Otherwise merely enabling `auth`
  would reject every existing registration.
- **Do not model a path as single-owner.** Consolidated mode is a supported feature in
  which multiple agents intentionally register the *same* path for redundancy. Per-identity
  pattern matching is compatible (each identity's patterns permit the path); a naive
  "one path, one owner" ownership model would silently break consolidated deployments.
- **Keep `agentToken` and `auth` co-existing**, not mutually exclusive. Erroring when both
  are set is acceptable (old configs set only `agentToken`); *requiring* removal of
  `agentToken` to adopt `auth` would force a migration.

**The one decision that makes it breaking.** Shipping enforcement opt-in leaves the June
2026 security finding (any token-holder can hijack any path) exploitable until each operator
configures patterns. Making the proxy reject cross-agent path claims *by default* — even
under the legacy shared token — closes the hole but changes behavior for anyone relying on
any agent re-registering any path. Recommended sequencing:

- **Opt-in, non-breaking** in a minor release (3.x): per-agent tokens + `pathPatterns`.
- **Safe-by-default** in the next major release (4.0): deny cross-agent path claims by
  default, gated behind the version boundary with migration notes.

### Open Questions

- ~~Token storage format: inline HOCON vs. a separate credentials file~~ **Decided:** inline
  `token` only for the MVP. `tokenEnvVar` / `tokenFilePath` variants are deferred.
- ~~Should `pathPatterns` also constrain `unregisterPath` and consolidated-mode joins?~~
  **Decided:** no separate check on `unregisterPath` — `ProxyPathManager.removePath` already scopes
  removal by `agentId`, and with static config an agent can never own a path outside its patterns.
  Consolidated-mode joins flow through the same `registerPath` check, so each participating agent's
  identity must permit the shared path.

---

## Feature 4: Metric Filtering and Relabeling at the Agent

> **Status: Implemented (MVP — `metricNameAllow` / `metricNameDeny`).** Per-path, family-scoped
> metric filtering applied before gzip and chunking. Still deferred: `dropLabels`, full relabeling,
> and an agent-global filter. See **Implementation Notes (As Built)** immediately below for the
> shipped design and where it diverged from the original proposal.

### Implementation Notes (As Built)

Shipped as line-oriented `metricNameAllow` / `metricNameDeny` filtering, family-scoped via
`# HELP`/`# TYPE`/`# UNIT` lines. The proposal below is preserved as the original design; these notes
record what landed and where it diverged.

**Config (diverged — top-level list, not nested).** The proposal's `pathConfigs[].filter { }` block,
shown below, could **not** be implemented as sketched. Verified empirically against
`config/jars/tscfg-1.2.5.jar`: the proposal's literal `metricNameAllow = []` crashes tscfg at
generation time (`ModelBuilder.listType`), and the workaround `metricNameAllow = [ String ]` instead
generates an **unguarded** `c.getList("metricNameAllow")` — because tscfg wraps the enclosing optional
`filter` block as `hasPathOrNull("filter") ? ... : new Filter(parseString("filter{}"))`, a config that
omits `filter` reaches that unguarded call against an empty synthesized object and throws `Missing: No
configuration setting found for key 'metricNameAllow'`, breaking every existing agent config that
doesn't declare a filter. Filters are therefore declared in a **top-level `agent.filters` list keyed by
path**, mirroring the `proxy.auth` pattern from Feature 3 — defaulted to `filters = []` in
`reference.conf`, with `path`, `metricNameAllow`, and `metricNameDeny` all required on each element
(an allow-all deny-only filter must still write `metricNameAllow: []`):

```hocon
agent {
  pathConfigs: [
    { name: "app1", path: "app1_metrics", url: "http://app1:9090/metrics" }
  ]
  filters: [
    { path: "app1_metrics", metricNameAllow: [], metricNameDeny: [ "go_gc_.*" ] }
  ]
}
```

See the [design doc](superpowers/specs/2026-07-19-agent-metric-filtering-design.md) (Decision 6) for
the full tscfg investigation, including the quoted-form (`"[string]?"`) and `reference.conf` fallback
approaches that were also ruled out.

**Matching (as designed, refined during review).** Fully-anchored regexes (`Regex.matches()`), so a
rule copied from a Prometheus `relabel_config` behaves identically at the agent. A `# HELP`, `# TYPE`,
**or `# UNIT`** comment line opens a metric family and the allow/deny verdict is computed once against
the family name, so a histogram's `_bucket`/`_sum`/`_count` series (and OpenMetrics's `_total` /
`_created` / `_gsum` / `_gcount` / `_info` suffixes) are kept or dropped as a unit. Deny is evaluated
after allow. A non-text payload (e.g. protobuf) or a body that fails strict UTF-8 decoding passes
through unfiltered and byte-exact rather than risk corrupting it — both fail open, each warning once
per filter instance, an additional guard added during review alongside the `# UNIT` handling above. A
fail-open scrape records no filter metrics at all, which is deliberately distinguishable from a filter
that ran and happened to drop nothing.

**Scope (unchanged from the MVP cut below).** `dropLabels`, full relabeling, and an agent-global filter
remain unimplemented, matching the proposal's original MVP boundary and Open Questions.

**Files.** New `agent/filter/MetricFilter.kt` — owns both the line filtering and the payload-level
policy wrapped around it (`filterBytes()`: the filterable-content-type gate, the strict UTF-8 decode,
the two fail-open branches, and their warn latches). Modified: `AgentPathManager` (compiles and
attaches a filter per path at registration time, keyed by path so it applies to both static
`pathConfigs` and Feature 1 discovered paths, and reports in the registration log whether one
attached), `AgentHttpService` (calls `filterBytes()` before the gzip/chunk decision and records the
counters), `AgentMetrics` (`agent_filter_lines_dropped` / `agent_filter_bytes_saved` counters),
`config/config.conf` and `reference.conf`.

**Post-merge cleanup (diverged from the first cut).** A quality pass after the feature landed moved
two things and deleted a third; the descriptions above reflect the result:

- **Filter policy moved behind the filter.** The content-type gate, decode, and fail-open handling
  were originally a ~50-line branch ladder inlined in `AgentHttpService.buildScrapeResults`. They now
  sit in `MetricFilter.filterBytes()`, so the exposition-format assumptions live in one file. The two
  warn-once latches moved with them: they were path-keyed `ConcurrentHashMap` sets in
  `AgentHttpService` that nothing ever removed from, so with Feature 1's reconcile loop churning paths
  they grew without bound. They are now `AtomicBoolean`s on the filter instance, which is already 1:1
  with a path and is reclaimed with it.
- **`filterText()` scans by index.** The first cut split the payload on `'\n'`, materializing one
  `String` per line plus the backing list before judging anything — on a multi-megabyte body, tens of
  MB of garbage per scrape, per filtered path. It now scans the source string by index and appends
  ranges directly; family membership is compared in place, so a sample line belonging to the open
  family allocates nothing, and a family's `HELP`/`TYPE`/`UNIT` lines no longer each re-run every
  regex.
- **The startup filter/path cross-check was deleted.** It warned when a configured filter path matched
  no `pathConfigs` entry, but `pathConfigs` is only the static baseline available at construction time
  — discovered and runtime-registered paths arrive later and were reported as unmatched, which is why
  it had to sit at debug behind a comment explaining when to ignore it. The signal now comes from
  `doRegisterPath`, where the filter for a path is actually resolved: the registration log says whether
  one attached. Correct for all three path sources, and visible at info.

**Tests.** `MetricFilterTest` (anchoring, family scoping, HELP/TYPE/UNIT handling), filter-compilation
and registration-log attachment cases in `AgentPathManagerTest` (including a discovered path — the case
no construction-time cross-check could reach), filter-ordering, fail-open, and
`isFilterableContentType` truth-table cases in `AgentHttpServiceTest`, and the Netty-transport harness
test `AgentMetricFilterTest` exercising per-path filtering end-to-end. The content-type cases still
live in `AgentHttpServiceTest` for history even though the function under test moved to `MetricFilter`;
relocating them to `MetricFilterTest` is a loose end.

### Motivation

The product's whole reason to exist is moving metrics efficiently across a network boundary,
yet today the full payload always crosses that boundary before anything is filtered — the
one place where dropping data is most valuable is the one place it cannot currently happen.
Filtering at the agent turns that around: it cuts WAN bandwidth and downstream Prometheus
cardinality (and the storage/cost that come with it) at the source, for chatty or
high-cardinality endpoints that today pay full freight across the expensive link. It
directly strengthens the core value proposition and composes with the existing gzip/chunking
limits rather than competing with them.

### Problem

All metric filtering today happens in Prometheus, *after* the full payload has crossed the
WAN through the chunked gRPC stream. High-cardinality or chatty endpoints pay full
bandwidth cost across exactly the network boundary this product exists to bridge. The
existing knobs (`chunkContentSizeKbs`, gzip, `maxContentLengthMBytes`) manage the symptom
(payload size) but cannot reduce the content itself.

### Proposed Design

Optional per-path filter rules applied by the agent between scraping the target and
building `ScrapeResults`:

```hocon
agent {
  pathConfigs: [
    {
      name: "app1"
      path: "app1_metrics"
      url: "http://app1:9090/metrics"
      labels = "{}"
      filter {
        metricNameAllow = []            // Regexes; empty = allow all
        metricNameDeny = [ "go_gc_.*" ] // Regexes; applied after allow
        dropLabels = [ "pod_template_hash" ]
      }
    }
  ]
}
```

Implementation is deliberately line-oriented rather than a full exposition-format parser:
Prometheus text format is line-based (`metric_name{labels} value [timestamp]`), so
allow/deny filtering needs only the metric-name prefix of each line, and `# HELP` / `# TYPE`
lines are kept or dropped together with their metric family. `dropLabels` requires label
re-serialization for matching lines and is the only part needing real label parsing.

Filtering happens in `AgentHttpService` before gzip/chunking, so all downstream size limits
and bandwidth benefits compose naturally.

### MVP

`metricNameAllow` / `metricNameDeny` only (pure line filtering, no label parsing).
`dropLabels` ships second. Full relabeling (rename, static label injection beyond the
existing `labels` field) is explicitly out of scope until demand is proven.

### Affected Components

- `AgentHttpService` — apply the filter to the scraped body
- New: `agent/MetricFilter` (compiled per path at registration time, not per scrape)
- `AgentPathManager` / `ConfigVals` — carry the filter config per path
- Agent metrics: counters for lines dropped / bytes saved per path, so operators can see
  the filter working

### Testing

- Kotest unit tests on `MetricFilter` with representative exposition-format fixtures
  (HELP/TYPE handling, histograms/summaries where `_bucket`/`_sum`/`_count` must be
  treated as one family, escaped label values)
- Harness test: filtered path returns reduced payload end-to-end
- Benchmark note: verify filtering cost is negligible vs. scrape+gzip on a large payload
  (reuse the `ContainersLargePayloadTest` synthetic generator)

### Open Questions

- Histogram/summary family integrity: filtering `foo` should consistently include/exclude
  `foo_bucket`, `foo_sum`, `foo_count`. (Recommended: match on family name.)
- Should there be an agent-global filter in addition to per-path? (Probably yes, later —
  e.g. drop `go_*` everywhere.)

---

## Feature 5: Operational Web UI on the Proxy Admin Port

> **Status: Implemented (MVP — master-detail).** A read-only dashboard on its own Ktor server and port,
> server-rendered with the Ktor HTML DSL, interactive via htmx, and live over a WebSocket. Off by
> default. Still deferred: a path-centric view, a dedicated Scrapes view, and any authentication.
> See **Implementation Notes (As Built)** immediately below — this feature departs from the proposal on
> two explicit points, and the title above is now wrong.

### Implementation Notes (As Built)

**Port (diverged — NOT the admin port, contrary to this section's own title).** The proposal specifies
"served from the existing admin port." That is not achievable. The admin port is a raw Jetty 12 `Server`
constructed inside `common-utils`' `ServletService`, whose only extension point is a
`path -> jakarta.servlet.Servlet` map; there is no way to attach Ktor routing, a Ktor `Application`, or
any non-servlet handler, and no way to reach the underlying `Server`. `LambdaServlet` is `GET`-only,
always-200, and returns a `String` with one fixed content type per instance.

A new `ProxyUiService : GenericIdleService` therefore runs its own Ktor CIO server on **port 8094**,
mirroring `ProxyHttpService` — a pattern already proven twice in `Proxy.kt`. This also turns out to be
the better security posture: Kubernetes liveness and readiness probes target `/ping` and `/healthcheck`
on the admin port, so a shared port could not be firewalled without taking the probes with it.

Rejected alternatives: switching `Proxy` to `common-utils`' `GenericKtorService` (would make the admin
port Ktor-native, but changes the superclass of both `Proxy` **and** `Agent`, and `KtorServletResponse`
throws on `setContentLength`/`setBufferSize`, an unverified risk to the dropwizard servlets); and adding
`/ui` to the scrape port (world-reachable, unauthenticated, plaintext, and the `get("/*")` catch-all
shadows it).

**Stack (diverged — the proposal's "no framework, no external assets" is not what shipped).** The
proposal calls for "a single static HTML page with inline CSS/JS … no build toolchain, no framework, no
external assets." As built: Kotlin/Ktor HTML DSL for rendering, **htmx** for interaction, and a
**WebSocket** for updates. There is still no build toolchain and no CDN, but htmx is a framework and it
is a third-party asset.

htmx arrives as a **WebJar** (`org.webjars.npm:htmx.org`, plus `htmx-ext-ws`, since htmx 2.x moved
WebSocket support out of core), served from the classpath by an explicit two-entry allowlist. That was
chosen over vendoring the minified files into the repo (no committed third-party JavaScript, versions
visible to normal dependency tooling) and over a CDN (a proxy bridging a firewall frequently has no
outbound network access, and a CDN-loaded UI would fail at page open rather than at deploy).
`ktor-htmx` supplies only Kotlin attribute constants — no JavaScript — so the client library had to come
from somewhere regardless.

**Layout (chosen from four).** Master-detail: agent list on the left, drill into one on the right. Picked
by reviewing all four candidates rendered side by side rather than described. It answers the motivating
question ("why isn't this target scraping?") by putting an agent's paths and its own recent scrapes on
one screen, and it is the natural home for Feature 3 identities and Feature 1 discovery sources later.

Its known weakness is the one htmx handles least gracefully — per-client selection state. Resolved by
keeping selection in the **URL** (`hx-push-url`), so it is bookmarkable and reload-safe, with a small
inline script reading it back and telling the server over the socket. That script is the only
hand-written JavaScript on the page; everything else is htmx attributes.

**Live updates: an event bus *and* a timer.** A `MutableSharedFlow<ProxyEvent>` was added to the proxy,
which previously had **zero** observability — every collection was a plain `ConcurrentHashMap` or a
`synchronized` `HashMap` with no listeners. `tryEmit` with `DROP_OLDEST` never suspends or blocks, which
is what makes it safe inside `synchronized(pathMap)` and on gRPC transport threads.

The bus alone is not sufficient. It covers *discrete* transitions; backlog depth, map sizes and eviction
countdowns drift with no moment to emit from, so a ~2s timer covers those. One shared push loop serves
all sessions — conflated, so a reconnecting fleet collapses into a single snapshot collect.

**Data changes.** `AgentContext.remoteAddr` and `.launchId` became readable (both were private and leaked
only via `toString()`), and a wall-clock `connectTime` was added because every other timing field is a
`Monotonic` `TimeMark` that cannot render as a time of day. A parallel `EvictingQueue<ScrapeRecord>` was
added alongside the `/debug` servlet's text queue, which carries no agent attribution — per-agent scrape
history could not be built from it without parsing display strings apart.

**Not built — and one shared root cause.** Two things operators will ask for are invisible to the UI for
the *same* structural reason: they are agent-side facts the proxy has no channel to learn.

- **Path source** (static vs discovered, Feature 1). `PathSource` lives only in the agent's
  `AgentPathManager`; `RegisterPathRequest` carries `agent_id`, `path` and `labels` and nothing else.
- **Failover state** (Feature 2). The configured endpoint list, the rotation cursor, and whether an agent
  has failed over all live in `AgentGrpcService` and `Agent.proxyHost`. `RegisterAgentRequest` carries
  only `agent_id`, `launch_id`, `agent_name`, `host_name` and `consolidated`, so none of it crosses the
  wire.

The operational consequence of the second is worth stating plainly: in an HA pair each proxy's UI shows
only *its own* agents, so during a failover an agent silently disappears from one dashboard and appears
on another with no explanation — at exactly the moment someone is watching. There is a partial thread to
pull: `launchId` is process-scoped and therefore survives a failover, while `agentId` is proxy-assigned
from a per-proxy counter and does not. The UI renders both, so an operator *can* correlate the same agent
process across two proxies by eye. That is a workaround, not a feature.

Closing either properly needs the same change — extending `RegisterPathRequest` / `RegisterAgentRequest`
so the agent reports these facts at registration. Worth doing once, for both, rather than twice.

Feature 3 identities are also still absent: they live in a gRPC `Context.Key` and are never stored on
`AgentContext`. That one needs no proto change, only somewhere to put them.

No authentication — see Security Posture below, which is unchanged and still applies.

**Files.** New `proxy/ui/` package: `ProxyUiService`, `ProxyUiHtml`, `ProxySnapshot`. New
`proxy/ProxyEventBus.kt` and `proxy/ScrapeRecord.kt`. Modified: `Proxy` (bus ownership, service
registration, scrape queue), `AgentContext`, `AgentContextManager`, `ProxyPathManager`,
`ProxyServiceImpl`, `ProxyHttpRoutes`, `ProxyOptions`, `EnvVars`, `config/config.conf`, `reference.conf`.

**Tests.** `ProxyUiHtmlTest` (fragment structure, out-of-band ids, the disconnected-agent state, and
selection-message parsing hardened against arbitrary browser input); `ProxyEventBusTest` (ordering,
non-blocking emit, drop-oldest, per-site emission); `ProxySnapshotTest`; the Netty harness spec
`ProxyWebUiTest`, which connects an agent **after** the socket is open so the asserted fragment can only
have come from the push path; and `ContainersWebUiTest`, which exists specifically to catch a fat-JAR
packaging regression that drops the WebJar resources — verified to fail when the asset path is broken.

### Motivation

"Why isn't this target scraping?" is the most common operational question this system
raises, and answering it today means log-diving on both the proxy and the agent or curling a
JSON debug servlet — slow, expert-only, and a recurring support burden. The proxy already
holds all the state needed to answer it instantly; it simply has no human-readable surface.
A read-only dashboard makes that state legible at a glance, shrinking mean-time-to-diagnose
and making the product approachable for operators who are not steeped in its internals. Its
value compounds once Features 1 and 3 add discovery sources and per-agent identities worth
visualizing.

### Problem

Answering "why isn't this target scraping?" currently requires log-diving on both the
proxy and the agent, or curling the JSON debug servlet. The proxy already holds all the
interesting state in memory (`AgentContextManager`, `ProxyPathManager`,
`ScrapeRequestManager`, the debug servlet's recent-requests queue) — it just has no
human-readable surface.

### Proposed Design

A read-only, dependency-free dashboard served from the existing admin port:

- **Agents view** — connected agents: name, identity (Feature 3), remote address, launch
  ID, connect time, last activity, and time-until-eviction relative to
  `maxAgentInactivitySecs`
- **Paths view** — registered paths: which agent(s) back each (including consolidated-mode
  fan-out), source (static vs. discovered, per Feature 1), target URL, labels
- **Scrapes view** — recent scrape activity from the debug queue: path, agent, duration,
  status code, payload size, chunked/gzipped flags
- **Health view** — backlog sizes vs. their `*UnhealthySize` thresholds, chunk-context map
  size, JVM basics

Implementation: a handful of JSON endpoints under the admin port (e.g.
`/ui/api/agents`, `/ui/api/paths`, `/ui/api/scrapes`) plus a single static HTML page with
inline CSS/JS that polls them. No build toolchain, no framework, no external assets — the
page ships as a classpath resource, keeping the fat-JAR story and the Shadow JAR
workaround untouched.

```hocon
proxy {
  admin {
    uiEnabled = false      // Off by default, same posture as debugEnabled
    uiPath = "ui"
  }
}
```

### MVP

Agents + Paths views (the two questions operators actually ask: "is my agent connected?"
and "is my path registered, and by whom?"). Scrapes and Health views follow.

### Affected Components

- Proxy admin service — new routes and JSON serialization of existing manager state
- `AgentContextManager` / `ProxyPathManager` — small read-only snapshot accessors
- Static resource: `src/main/resources/ui/index.html`
- Docs: screenshot + section in README and the docs site

### Security Posture

Disabled by default; read-only; served only on the admin port (which operators already
treat as internal). Document clearly that the admin port must not be exposed publicly.
If Feature 3 lands first, the UI displays identities but never tokens.

### Testing

- Harness tests asserting the JSON endpoints' shape against a running proxy+agent pair
- Container test: fetch `/ui/api/agents` and `/ui/api/paths` in `ContainersProxyHttpTest`
  alongside the existing admin servlet checks

### Open Questions

- Auth on the UI itself (basic auth?) or rely purely on network isolation of the admin
  port? (Recommended: network isolation for v1, matching the existing admin servlets.)

- **Should the proxy learn agent-side state it currently cannot see?** Path source (Feature 1) and
  failover endpoint/rotation state (Feature 2) are both invisible to the UI because neither crosses the
  registration RPC. Surfacing either means extending `RegisterPathRequest` / `RegisterAgentRequest`.
  Doing it once for both is cheaper than twice, but it widens the wire contract for what is currently a
  display-only benefit — worth confirming demand before spending the proto change.
- **Should an HA pair's UI acknowledge the other proxy at all?** Today each instance renders only its own
  agents, which is correct but reads as agents vanishing during a failover. Even a static "this is
  proxy-a of the pair" banner, or surfacing `launchId` more prominently as the identifier that survives
  a move, would help without any protocol change.
---

## Suggested Implementation Order

| Order | Feature | Rationale |
|-------|---------|-----------|
| 1st ✅ | **Feature 3** — Per-agent identity & path authorization | **Implemented (MVP)** — closes a known security gap; security blockers stall adoption more than missing features |
| 2nd ✅ | **Feature 1** — Dynamic target discovery | **Implemented (file-source MVP)** — biggest day-to-day operational relief; Kubernetes/Docker sources still deferred |
| 3rd ✅ | **Feature 4** — Agent-side metric filtering | **Implemented (MVP — `metricNameAllow` / `metricNameDeny`)** — strengthens the core value proposition (efficient transport across network boundaries); `dropLabels`, relabeling, and agent-global filters still deferred |
| 4th ✅ | **Feature 2** — Proxy HA / agent failover | **Implemented (Phase 1 — agent-side endpoint failover)** — unlocks redundant proxy deployments; Phase 2 multi-homing and Phase 3 clustering still deferred |
| 5th ✅ | **Feature 5** — Operational web UI | **Implemented (MVP — master-detail)** — on its own Ktor port rather than the admin port, which cannot host Ktor; server-rendered with htmx and WebSockets |

Cross-cutting notes for every feature:

- Config schema changes require regenerating `ConfigVals` (`make tsconfig`) and updating
  `config/config.conf`, the docs site, and `EnvVars` where CLI/env parity is expected.
- New tests follow the house style: Kotest `StringSpec`, MockK where appropriate, shared
  port constants from `TestPorts`, container specs gated on `RUN_CONTAINER_TESTS`.
- Anything promoted to the public API surface needs a `docs/packages.md` cross-reference
  per the Dokka policy.
