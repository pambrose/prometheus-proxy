# Feature Proposals — July 2026

This document describes five proposed features for prometheus-proxy, numbered for easy
reference (Feature 1 through Feature 5). Each section covers the problem being solved, the
proposed design, the components affected, a suggested MVP scope, and open questions.

A suggested implementation order appears at the end of the document.

---

## Feature 1: Dynamic Target Discovery on the Agent

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

- Should discovered paths be visually distinguishable from static ones in the debug
  servlet / future UI (Feature 5)? (Recommended: yes, tag with a `source` field.)
- Kubernetes watcher: in-cluster config only, or also kubeconfig for out-of-cluster agents?

---

## Feature 2: Proxy High Availability with Agent-Side Failover

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
with identical target lists; the inactive proxy returns 503 for unregistered paths, which
Prometheus treats as a failed scrape of a duplicate target — standard HA-pair behavior.

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

**Interceptor (diverged).** `AgentTokenServerInterceptor` was **replaced** by
`AgentAuthServerInterceptor`, backed by a new `AgentAuthManager` that holds the identities, resolves
tokens (constant-time SHA-256 digest compare), and compiles the globs. The single-token path is now
simply one allow-all identity.

**Files.** New: `proxy/AgentAuthManager.kt`, `proxy/AgentAuthServerInterceptor.kt`. Modified:
`config/config.conf` (+ regenerated `ConfigVals.java`), `src/main/resources/reference.conf`
(`proxy.auth = []` backward-compat fallback — the generated `c.getList("auth")` is required-present),
`Proxy.kt`, `ProxyGrpcService.kt`, `ProxyServiceImpl.registerPath`. Removed:
`AgentTokenServerInterceptor.kt`. `ProxyOptions` / `EnvVars` were **not** touched — the identity list
is config-file-only (a list of objects), like `agent.pathConfigs`.

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

---

## Suggested Implementation Order

| Order | Feature | Rationale |
|-------|---------|-----------|
| 1st ✅ | **Feature 3** — Per-agent identity & path authorization | **Implemented (MVP)** — closes a known security gap; security blockers stall adoption more than missing features |
| 2nd | **Feature 1** — Dynamic target discovery | Biggest day-to-day operational pain relief; MVP (file hot-reload) is small |
| 3rd | **Feature 4** — Agent-side metric filtering | Best bang-for-buck: strengthens the core value proposition (efficient transport across network boundaries) |
| 4th | **Feature 2** — Proxy HA / agent failover | Phase 1 is cheap and unlocks redundant deployments |
| 5th | **Feature 5** — Operational web UI | Highest value once Features 1 and 3 add state worth visualizing (identities, discovery sources) |

Cross-cutting notes for every feature:

- Config schema changes require regenerating `ConfigVals` (`make tsconfig`) and updating
  `config/config.conf`, the docs site, and `EnvVars` where CLI/env parity is expected.
- New tests follow the house style: Kotest `StringSpec`, MockK where appropriate, shared
  port constants from `TestPorts`, container specs gated on `RUN_CONTAINER_TESTS`.
- Anything promoted to the public API surface needs a `docs/packages.md` cross-reference
  per the Dokka policy.
