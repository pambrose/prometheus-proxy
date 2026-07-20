# Agent Metric Filtering — Design

- **Date:** 2026-07-19
- **Status:** Approved (design); implementation pending
- **Feature:** Feature 4 from `docs/FEATURE_PROPOSALS_JULY_2026.md` (MVP scope)

## Context

Every metric a target exposes crosses the WAN through the chunked gRPC stream before anything is
filtered. Filtering happens in Prometheus, *after* the payload has already paid full freight across
exactly the network boundary this product exists to bridge. The existing knobs
(`chunkContentSizeKbs`, gzip, `maxContentLengthMBytes`) manage payload *size* but cannot reduce the
*content*.

This design lets the agent drop whole metric families before the payload is compressed and chunked,
cutting WAN bandwidth and downstream Prometheus cardinality at the source.

## Goals

- Drop unwanted metric families at the agent, before gzip and chunking, so all existing size limits
  and bandwidth benefits compose naturally.
- Keep histogram and summary families intact — `foo`, `foo_bucket`, `foo_sum`, and `foo_count` are
  always kept or dropped together.
- Zero behavior and zero performance change for paths without a filter.
- Backward compatible: existing agent configs load unchanged.

## Non-Goals (out of scope for this MVP)

- `dropLabels` and label re-serialization (Feature 4's second phase).
- Full relabeling: renaming metrics, or injecting static labels beyond the existing `labels` field.
- An agent-global filter. The chosen config shape reduces this to a one-line extension (`path: "*"`),
  but it is not implemented here.
- Declaring filters inside Feature 1's discovery file. Filters are declared only in the agent config;
  see Decision 5 for how they nonetheless reach discovered paths.
- A full exposition-format parser. Filtering is deliberately line-oriented.

## Decisions

Confirmed during brainstorming:

1. **Per-path filters, not agent-global.** An agent-global filter is deferred; the config shape below
   makes it a trivial follow-up.
2. **Family-scoped matching via `# TYPE`.** A `# HELP` / `# TYPE` line opens a family, the regexes are
   matched once against the family name, and the verdict applies to every sample line until the next
   family opens. Sample lines with no open family fall back to literal-name matching. This keeps
   histograms and summaries intact without guessing at name suffixes, and avoids the
   `items_count` → family `items` over-match that blind suffix-stripping causes.
3. **Fully-anchored regexes.** `Regex.matches()`, matching Prometheus's own `relabel_config` and
   `metric_relabel_configs` semantics, so rules copied from a Prometheus config behave identically at
   the agent. `deny = ["go_"]` matches nothing; `deny = ["go_.*"]` is required.
4. **Filter text-ish payloads only, otherwise pass through.** A filter applies when `Content-Type` is
   absent, `text/plain*`, or `application/openmetrics-text*`. Any other type (protobuf, gzip, an HTML
   error body) passes through untouched with a once-per-path warning. This fails open: the worst case
   is bandwidth not saved, never a corrupted payload.
5. **Filters apply by path, regardless of registration source.** A filter on path `X` applies to `X`
   whether `X` was registered from static `pathConfigs` or from Feature 1's discovery file. The
   lookup lives in `doRegisterPath`, which both routes already share, so this touches no discovery
   code. The alternative — gating on `PathSource.STATIC` — would make filtering silently depend on a
   path's provenance and would exclude the churning, dynamic deployments the feature most targets.

### Decision 6: config shape (deviates from the proposal doc)

The proposal sketches a `filter { }` block nested inside each `agent.pathConfigs` entry. **That shape
cannot be expressed in this project's config pipeline.** Verified empirically against
`config/jars/tscfg-1.2.5.jar`:

- `metricNameAllow = []` — the proposal's literal sketch — crashes tscfg at generation time
  (`ModelBuilder.listType`).
- `metricNameAllow = [ String ]` generates an **unguarded** `c.getList("metricNameAllow")`. tscfg wraps
  the enclosing optional block in `hasPathOrNull(...) ? ... : new Filter(parseString("filter{}"))`, so
  a config omitting `filter` reaches that unguarded call against an empty config and throws
  `Missing: No configuration setting found for key 'metricNameAllow'`. Confirmed at runtime — this
  would break every existing agent config.
- Quoted forms (`"[string]?"`, `"[string] | []"`) silently degrade to a plain `String` default. tscfg
  1.2.5 has no optional or defaulted string list.
- `src/main/resources/reference.conf` cannot rescue it: Typesafe Config merges fallbacks by key, not
  element-wise into list entries, so it can default the whole `pathConfigs` list but cannot inject a
  default `filter` into a user-supplied element.

Filters are therefore declared in a **top-level `agent.filters` list keyed by path**, mirroring the
proven `proxy.auth` pattern — a list defaulted to `[]` in `reference.conf`, with required fields on
each element. This stays entirely inside the generated `ConfigVals` pipeline, needs no bespoke config
parsing, and does not require widening `BaseOptions.config` from `private`.

The cost is that the path string is repeated, so a typo silently no-ops. Mitigated by a startup
warning when a filter's `path` matches no configured `pathConfigs` entry. Per Decision 5 the warning
is scoped to static paths only — a filter may legitimately target a path that appears later via
discovery, and warning on those would fire spuriously.

## Configuration

`config/config.conf` (the tscfg spec), inside the `agent` block:

```hocon
  filters: [                          // Per-path metric filters; empty list disables filtering
    {
      path: String                    // pathConfigs path this filter applies to (required)
      metricNameAllow = [ String ]    // Fully-anchored regexes; empty list allows all families
      metricNameDeny = [ String ]     // Fully-anchored regexes; applied after allow
    }
  ]
```

`src/main/resources/reference.conf` gains `filters = []` in its `agent` block. This is the line that
keeps existing configs loading, since `config/config.conf` is a tscfg spec and is *not* a runtime
fallback. Regenerate with `make tsconfig`.

A user config then reads:

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

Both list fields are required on each element, so a deny-only filter writes `metricNameAllow: []`
explicitly — consistent with how `proxy.auth` elements must write `paths = []`.

## Architecture

### `MetricFilter`

New file `src/main/kotlin/io/prometheus/agent/filter/MetricFilter.kt`, following the `discovery/`
subpackage precedent for a self-contained concern.

```kotlin
internal class MetricFilter private constructor(allow: List<Regex>, deny: List<Regex>) {
  fun filterText(text: String): FilterResult
  companion object {
    fun createOrNull(allow: List<String>, deny: List<String>, path: String): MetricFilter?
  }
}

internal data class FilterResult(val text: String, val linesDropped: Int)
```

`createOrNull` returns `null` when both lists are empty, so "no filter" is a distinct state rather
than an identity transform — this is what guarantees the zero-overhead path for unfiltered scrapes.
Its `path` argument is used only to name the offending path in the invalid-regex message.

Regexes compile eagerly in the constructor. An invalid pattern fails agent startup with a message
naming the path and the offending pattern, never at scrape time.

`FilterResult` deliberately does **not** carry bytes saved. The filter works on `String`, so counting
bytes here would mean a second UTF-8 encode purely for a metric; `AgentHttpService` computes it
exactly from the raw and filtered byte arrays it already holds.

Verdict for a family name:

```kotlin
(allow.isEmpty() || allow.any { it.matches(family) }) && deny.none { it.matches(family) }
```

`matches` is fully anchored (Decision 3), and deny is evaluated after allow, so deny wins.

**Algorithm** — a single forward pass over the lines, carrying `currentFamily: String?` and
`currentVerdict: Boolean`:

| Line | Handling |
|------|----------|
| `# HELP <name> …` / `# TYPE <name> …` | Opens a family: `currentFamily = name`, compute the verdict once, emit the line iff kept |
| Other `#` comment (including `# EOF`) | Emitted unchanged |
| Blank line | Emitted unchanged |
| Sample line | Name is the text before the first `{` or whitespace. If it is `currentFamily` or `currentFamily` plus one of `_bucket`, `_sum`, `_count`, `_created`, `_total`, `_gsum`, `_gcount`, `_info`, it inherits `currentVerdict`. Otherwise the family closes (`currentFamily = null`) and the line is judged literally |

Handles both `\n` and `\r\n`, and preserves the input's trailing-newline convention.

### `AgentPathManager` additions

`PathContext` gains `val filter: MetricFilter?`.

At construction, `AgentPathManager` compiles `agentConfigVals.filters` into a
`Map<String, MetricFilter>` keyed by normalized path, and logs a warning for any filter whose `path`
matches no static `pathConfigs` entry.

`doRegisterPath` — already shared by static registration, dynamic registration, and
`reconcileDiscoveredPaths` — looks the filter up by path and stores it on the `PathContext`. Per
Decision 5 the lookup is unconditional; no `PathSource` check, and no change to any discovery code.

### `AgentHttpService` insertion

The filter is threaded `fetchContentFromUrl` → `fetchContent` → `buildScrapeResults`, with a `null`
default on `fetchContent` so its existing `internal` test callers keep compiling.

Ordering inside `buildScrapeResults` is load-bearing:

1. The `maxContentLength` guard runs against the **raw** payload and is left exactly as-is. It is a
   memory bound, and filtering must never become a way to bypass it.
2. The filter runs, gated on `Content-Type` per Decision 4.
3. The `zipped` decision and the emitted `ScrapeResults` are built from the **filtered** bytes, so
   gzip and chunking both see the reduced payload.

Content-type gating is case-insensitive and ignores parameters after `;`. A filtered path returning a
non-filterable type logs a once-per-path warning and passes the body through.

Paths with no filter never decode the response bytes, keeping today's byte path — and its
performance — untouched.

## Data Flow

```
ScrapeRequest
  → AgentHttpService.fetchScrapeUrl
  → pathManager[path] → PathContext(url, labels, filter)
  → HTTP GET target
  → raw bytes read (bounded by maxContentLength)
  → maxContentLength guard          [raw payload]
  → MetricFilter.filterText         [only if filter != null && content-type filterable]
  → gzip decision + ScrapeResults   [filtered payload]
  → gRPC stream to proxy
```

## Error Handling & Edge Cases

- **Invalid regex:** fails at agent startup, naming path and pattern. Never a scrape-time failure.
- **Non-filterable content type:** pass through, warn once per path. Fails open.
- **Filter path matching no static path:** startup warning; discovered paths excluded from the check.
- **Payload with no `# TYPE` lines:** each sample line is judged literally. An allow-list targeting a
  histogram family in such a payload will not pull in its `_bucket`/`_sum`/`_count` series; this is
  the documented fallback, not a defect.
- **Label values containing `}`, `#`, or escaped quotes:** name extraction reads only up to the first
  `{` or whitespace, so label content cannot influence matching.
- **Filter drops everything:** an empty body is returned. Valid — Prometheus sees a target with no
  series rather than an error.

## Observability

Two counters in `AgentMetrics`, following the existing label conventions plus a `path` label, guarded
by `isMetricsEnabled` like the existing timers:

- lines dropped per path
- bytes saved per path

Only filtered paths ever instantiate series, so cardinality is bounded by the number of filters.

## Testing

`MetricFilterTest` (Kotest `StringSpec`, MockK where it helps) carries the weight:

- deny drops a family including its `# HELP` / `# TYPE` lines and every suffixed series
- an allow-list on a histogram family keeps `_bucket` / `_sum` / `_count` together
- anchoring: `go_` matches nothing, `go_.*` matches `go_goroutines`
- deny applied after allow (deny wins on overlap)
- both lists empty → `createOrNull` returns `null`
- no-`TYPE` fallback judges each line literally
- label values containing `}`, `#`, and escaped quotes do not confuse name extraction
- `# EOF`, blank lines, and CRLF round-trip correctly
- invalid regex fails fast with path and pattern in the message
- `linesDropped` is accurate (bytes saved is asserted at the `AgentHttpService` layer, where the
  byte arrays exist)

Beyond the unit suite:

- `AgentHttpServiceTest` — content-type gate passes protobuf through; the `maxContentLength` guard
  still sees raw bytes while the gzip decision sees filtered bytes
- `AgentPathManagerTest` — filters attach by path for both static and discovered registration; an
  unmatched filter path warns
- Harness test — a filtered path returns a reduced payload end-to-end
- Optional: reuse the `ContainersLargePayloadTest` synthetic generator to confirm filtering cost is
  negligible against scrape + gzip

## Affected Components

| Component | Change |
|-----------|--------|
| `config/config.conf` | New `agent.filters` spec block |
| `src/main/resources/reference.conf` | `filters = []` default |
| `src/main/java/io/prometheus/common/ConfigVals.java` | Regenerated via `make tsconfig` |
| `agent/filter/MetricFilter.kt` | New — engine and `FilterResult` |
| `agent/AgentPathManager.kt` | Compile filters, `PathContext.filter`, unmatched-path warning |
| `agent/AgentHttpService.kt` | Thread filter, content-type gate, apply before gzip |
| `agent/AgentMetrics.kt` | Two counters |
| Tests | `MetricFilterTest` (new) plus additions to agent and harness suites |
| Docs | `config/config.conf` comments, an `examples/` config, website agent page |
| `docs/FEATURE_PROPOSALS_JULY_2026.md` | "Implementation Notes (As Built)" recording Decision 6 |

## Future Work (deferred, enabled by this design)

- **Agent-global filter:** `path: "*"` in `agent.filters`, matched as a glob in the lookup.
- **`dropLabels`:** Feature 4's second phase. It needs real label parsing and re-serialization, but
  slots into `MetricFilter.filterText` behind the same content-type gate.
- **Filters in the discovery file:** would add a field to `DiscoveredPath`, parsing in
  `FileDiscoverySource`, and filter equality in the reconciler's change detection so an edited filter
  forces re-registration.
