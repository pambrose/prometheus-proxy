# Agent Dynamic Target Discovery — Design

- **Date:** 2026-07-16
- **Status:** Approved (design); implementation pending
- **Feature:** Feature 1 from `docs/FEATURE_PROPOSALS_JULY_2026.md`

## Context

`agent.pathConfigs` is a static HOCON list read once at startup. Adding or removing a target
behind the firewall requires editing the agent config and **restarting the agent**, and a restart
disrupts scraping for *all* of that agent's paths, not just the one that changed. This makes the
product a poor fit for dynamic environments (Kubernetes, Docker, autoscaling) where the target set
churns continuously.

The gRPC plumbing for dynamic membership already exists — `registerPath` / `unregisterPath` are
first-class RPCs, and `AgentPathManager.registerPath` / `unregisterPath` already drive them under a
mutex. Only the *trigger* is missing on the agent side.

This design adds a **dynamic target discovery** layer: the agent reconciles its registered paths
against a watched local file, without a restart.

## Goals

- Add and remove scrape targets at runtime with no agent restart and no disruption to unaffected paths.
- Keep the change small, self-contained, and backward compatible (off by default).
- Build a clean `PathDiscoverySource` seam so Kubernetes/Docker sources can be added later without rework.

## Non-Goals (out of scope for this MVP)

- Kubernetes or Docker discovery sources.
- Filesystem-watch (`WatchService`) or `SIGHUP` triggers (polling only).
- Per-path metric filtering (that is Feature 4).
- Hot-reloading any agent setting other than the discovered path set (ports, TLS, etc. still require a restart).

## Decisions

Confirmed during brainstorming:

1. **Source of truth: a dedicated discovery file.** A separate file (declared via
   `agent.discovery.file.path`) holds a list of `{ name, path, url, labels }` entries — *not* the
   main agent config. This separates volatile targets from stable config, avoids re-validating
   unrelated settings, and is exactly what a future K8s/Docker source would generate.
2. **Change detection: interval polling.** A per-connection task re-reads the file every
   `reconcileIntervalSecs` and reconciles the diff. Chosen over `WatchService` because the target
   environments (K8s ConfigMap symlink swaps, bind mounts) frequently do not emit reliable inotify
   events, and polling doubles as a periodic full-resync safety net.
3. **Ownership model: additive union, static wins.** Static `pathConfigs` are an always-registered
   baseline the reconciler never touches. The discovery file adds and removes *additional* paths on
   top. If a discovered path collides with a static path, the static entry wins and the collision is
   logged. "Discovery-only" usage is supported by simply leaving `pathConfigs` empty.

## Architecture

Three cleanly-bounded units plus a minimal addition to `AgentPathManager`.

### `PathDiscoverySource` (interface) + `FileDiscoverySource` (MVP impl)

```
interface PathDiscoverySource { fun read(): List<DiscoveredPath> }
```

- `DiscoveredPath` mirrors the existing path shape: `name`, `path`, `url`, `labels`.
- `FileDiscoverySource` parses the file at `agent.discovery.file.path` with Typesafe Config, so the
  file may be **HOCON or JSON** (HOCON is a JSON superset). It returns the parsed list. A **valid but
  empty** file returns an empty list (the reconciler treats this as "remove all discovered"); a
  **missing, unreadable, or malformed** file **throws**, so the caller can tell failure apart from
  emptiness and preserve the last-known-good set rather than tearing everything down.
- This is the seam. `KubernetesDiscoverySource` / `DockerDiscoverySource` implement the same
  interface later; nothing else changes.

**What it does:** turns a source into a desired list of discovered paths.
**How it's used:** called once per poll tick by `PathDiscoveryService`.
**Depends on:** the discovery config (file path); Typesafe Config for parsing.

### `AgentPathManager` additions

- Tag each registered path with a **source**: `STATIC` (from `pathConfigs`, via the existing
  `registerPaths()`) or `DISCOVERED` (added by reconcile).
- New `suspend fun reconcileDiscoveredPaths(desired: List<DiscoveredPath>)`:
  - Runs under the existing `pathMutex`, so the whole diff-and-apply is atomic with respect to any
    other `registerPath` / `unregisterPath` / `clear` call (no TOCTOU window).
  - Registers any desired path not currently registered.
  - Unregisters any `DISCOVERED` path no longer desired.
  - Re-registers (unregister + register) a `DISCOVERED` path whose `url` or `labels` changed.
  - **Skips (and logs) any desired path that collides with a `STATIC` path** — static wins.
  - Never adds, removes, or modifies `STATIC` paths.
  - Idempotent: a path that fails to register (e.g. the proxy rejects it) is left out and retried on
    the next tick.

**What it does:** owns the live path map and makes the `DISCOVERED` subset match the desired set.
**How it's used:** `reconcileDiscoveredPaths` is called each poll tick; `registerPaths()` (unchanged)
still seeds the `STATIC` baseline on connect.
**Depends on:** the gRPC service (existing), `pathMutex` (existing).

### `PathDiscoveryService`

- Installed only when `agent.discovery.enabled` is true.
- Runs as one more `launchConnectionTask(connectionContext, "reconcilePaths") { ... }` inside the
  per-connection `coroutineScope` in `Agent.run()`.
- Loop: every `reconcileIntervalSecs`, call `source.read()` then
  `pathManager.reconcileDiscoveredPaths(...)`.
- Because it lives in the connection scope, it starts fresh on each connect and dies on disconnect —
  reconnect re-syncs for free (the connect path already does `pathManager.clear()` +
  `registerPaths()` to restore the static baseline).

**What it does:** drives the poll-and-reconcile loop for the connection's lifetime.
**How it's used:** launched by `Agent.run()` alongside the other connection tasks.
**Depends on:** `PathDiscoverySource`, `AgentPathManager`, the discovery config.

## Configuration

New block, off by default. Regenerate `ConfigVals` with `make tsconfig` and add the `discovery`
defaults to `src/main/resources/reference.conf` (mirroring how `pathConfigs`/`auth` are backstopped)
so existing configs that omit it don't fail generation.

```hocon
agent {
  discovery {
    enabled = false                // Off by default
    file.path = ""                 // Path to a HOCON/JSON list of { name, path, url, labels }
    reconcileIntervalSecs = 30     // Poll + full-resync interval
  }
}
```

Discovery file format (HOCON shown; equivalent JSON accepted):

```hocon
paths = [
  { name = "app1", path = "app1_metrics", url = "http://app1:9090/metrics", labels = "{}" }
  { name = "app2", path = "app2_metrics", url = "http://app2:9090/metrics", labels = "{}" }
]
```

## Data Flow

```
connect
  → pathManager.clear()
  → registerAgent
  → pathManager.registerPaths()            // STATIC baseline (unchanged)
  → coroutineScope {
       launchConnectionTask("reconcilePaths") {
         every reconcileIntervalSecs:
           desired = source.read()                       // FileDiscoverySource
           pathManager.reconcileDiscoveredPaths(desired) // diff + apply DISCOVERED subset
       }
       ... existing tasks (readRequests, heartbeat, writeResponses, scrape processing) ...
     }
```

## Error Handling & Edge Cases

- **Missing / unreadable / malformed file (read *failure*):** `read()` throws; the service logs a
  warning and **skips the tick**, leaving the live set untouched (that live set *is* the
  last-known-good). The next successful read reconciles normally. This is deliberately distinct from
  the next point.
- **Valid but empty file:** this is *not* an error — it reconciles to zero discovered paths (removes
  them all). Only a read/parse *failure* preserves the previous set. Operators emptying the file
  intentionally get the removal they asked for; a transient bad write does not.
- **Duplicate `path` within the file:** last entry wins (log the shadowing), matching Typesafe
  Config's list semantics; the reconciler sees a de-duplicated desired set.
- **Discovered path collides with a STATIC path:** skip it, log at WARN. Static always wins.
- **A discovered registration is rejected by the proxy** (e.g. multi-segment path, or a path claimed
  by another agent in a non-consolidated setup): log and omit; retried next tick. Never crashes the
  loop.
- **URL/labels change for an existing discovered path:** treated as unregister + register so the
  local mapping and the proxy's stored labels stay correct.
- **Reconnect:** the discovery task dies with the connection; the next connect re-seeds STATIC and
  the new task's first tick re-establishes the discovered set.

## Testing

House style: Kotest `StringSpec` with `init {}`, MockK where useful, `TestPorts` constants,
container specs gated on `RUN_CONTAINER_TESTS`.

- **Reconcile unit tests** (`AgentPathManager`): add-only, remove-only, mixed add/remove, url/label
  change → re-register, empty desired → removes all discovered but keeps static, discovered/static
  collision → skipped, idempotent re-run is a no-op.
- **`FileDiscoverySource` unit tests:** valid HOCON, valid JSON, valid-but-empty → empty list,
  malformed → throws, missing file → throws, duplicate path handling.
- **`PathDiscoveryService` test:** a read *failure* skips the tick (live set unchanged); a successful
  *empty* read removes all discovered paths.
- **Harness test:** enable discovery pointing at a temp file, write one path, assert it scrapes
  through the proxy; rewrite the file adding a second and removing the first; assert the add appears
  and the removal 404s — all without restarting the agent.
- **Container test (optional, later):** mount a discovery file into the agent container, mutate it,
  assert the proxy's `/discovery` output updates. Can follow the MVP.

## Affected Components

- **New:** `agent/discovery/PathDiscoverySource.kt` (interface + `DiscoveredPath`),
  `agent/discovery/FileDiscoverySource.kt`, `agent/discovery/PathDiscoveryService.kt`.
- **Modified:** `AgentPathManager` (source tagging + `reconcileDiscoveredPaths`), `Agent.run()`
  (launch the reconcile task when enabled), `config/config.conf` + regenerated `ConfigVals.java`,
  `src/main/resources/reference.conf` (discovery defaults).
- **Unchanged:** the proxy, the `.proto`, the gRPC register/unregister RPCs.

## Future Work (deferred, enabled by this seam)

- `KubernetesDiscoverySource` (pod/service annotations) and `DockerDiscoverySource` (labels).
- `WatchService` / `SIGHUP` triggers for lower change latency.
- Surfacing the path `source` (static vs discovered) in the debug servlet and the future proxy UI
  (Feature 5).
