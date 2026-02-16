# Release Notes — Prometheus Proxy 3.0.0

**Version bump: 2.4.0 → 3.0.0**

---

## Bug Fixes

### Data Integrity & Correctness

- Fix integer overflow in `ChunkedContext.totalByteCount` (Int → Long) that could silently bypass size limits on large
  payloads
- Fix chunk checksum calculation to use actual byte count instead of full buffer size
- Fix `toScrapeResponseHeader` to propagate the actual `srZipped` value (was hardcoded to `true`)
- Fix `applySummary` to propagate the `headerZipped` value from chunked response headers
- Fix `IOException` error code from `NotFound` (404) to `ServiceUnavailable` (503) — semantically correct for
  unreachable targets
- Fix catch-all HTTP exception handler from `NotFound` (404) to `InternalServerError` (500)
- Fix `errorCode()` to walk the exception cause chain for wrapped timeout exceptions
- Fix OpenMetrics `# EOF` marker handling in consolidated responses — intermediate `# EOF` markers are now stripped
- Fix `parseHostPort` to strip brackets from IPv6 addresses in `HostPort` — `[::1]:50051` now yields host `::1` instead
  of `[::1]`

### Concurrency & Resource Management

- Fix TOCTOU race in `AgentContextCleanupService` — agents are now re-checked for staleness before eviction
- Fix negative `scrapeRequestBacklogSize` with atomic CAS-loop decrement clamped at zero
- Fix `ConcurrentModificationException` in `ProxyPathManager.removePathsForAgentId` and `recentReqs` access
- Fix `HttpClientCache.close()` deadlock — coroutine scope cancelled before acquiring mutex
- Fix HTTP client close calls moved outside mutex to avoid blocking cache operations during slow I/O
- Fix idle HTTP clients now closed on eviction (previously only marked for close)
- Fix `AgentHttpService` now properly closed during agent shutdown (resource leak)
- Fix path registration concurrency by moving gRPC calls outside the mutex
- Fix `AgentClientInterceptor` to use the `next` channel parameter instead of bypassing the interceptor chain
- Fix synchronized `agentId` assignment in `AgentClientInterceptor` to prevent race condition
- Fix `ScrapeRequestWrapper.markComplete()` is now idempotent via `AtomicBoolean.compareAndSet`
- Fix `runCatching` replaced with `runCatchingCancellable` throughout to avoid swallowing `CancellationException`
- Fix agent context added after ID validation to prevent orphaned contexts

### Error Handling & Cleanup

- Fix orphaned `ChunkedContext` cleanup on stream failure — associated scrape requests are now explicitly failed
- Fix chunk validation errors now throw `ChunkValidationException` instead of crashing the gRPC stream
- Fix `readRequestsFromProxy` throws `StatusException(NOT_FOUND)` when agent context is missing (was silently no-op)
- Fix `connectAgent`/`connectAgentWithTransportFilterDisabled` throw `StatusException(FAILED_PRECONDITION)` instead of
  `RequestFailureException`
- Fix `sendHeartBeat` re-throws `NOT_FOUND` status to trigger agent reconnection (was zombie state)
- Fix agent invalidation now drains pending scrape requests and unblocks HTTP handlers immediately
- Fix `handleConnectionFailure` re-throws JVM `Error` subclasses instead of retrying in a corrupted state
- Fix stream cleanup for `transportFilterDisabled` mode in `readRequestsFromProxy` finally block

### Security

- Fix credential leak in `HttpClientCache` logs — `ClientKey.toString()` now masks credentials
- Fix password `CharArray` zeroed after use in `SslSettings.getKeyStore`
- Fix `FileInputStream` resource leak in `SslSettings` — now uses try-with-resources
- Fix URL sanitization in agent logs to strip credentials before logging

### Misc

- Fix gzip compression for small responses — enforced `minimumSize(1024)` in `ProxyHttpConfig`
- Fix redundant `response.status()` call in `ProxyUtils.respondWith`
- Fix service discovery and metrics paths now ensure leading `/`
- Fix dynamic parameter handling to correctly set system properties
- Fix `registerPath`/`registerAgent`/`sendHeartBeat` responses only set `reason` field when `valid` is false
- Fix typo: "Overide" → "Override" in config and ConfigVals

---

## New Features

- **Content size limits** — New configurable limits to prevent zip bombs and unbounded memory:
  - `proxy.internal.maxZippedContentSizeMBytes` (default 5 MB)
  - `proxy.internal.maxUnzippedContentSizeMBytes` (default 10 MB)
  - `agent.http.maxContentLengthMBytes` / `AGENT_MAX_CONTENT_LENGTH_MBYTES` (default 10 MB)
- **Unary RPC deadline** — `agent.grpc.unaryDeadlineSecs` / `UNARY_DEADLINE_SECS` (default 30s) prevents unary gRPC
  calls from hanging indefinitely
- **Graceful scrape request failure** — Orphaned scrape requests are failed with proper status on agent disconnect,
  stream termination, chunk validation failure, and proxy shutdown
- **Consolidated/non-consolidated mismatch rejection** — `addPath` now rejects mismatched agent types on the same path
  with a descriptive error
- **Authorization header TLS warning** — One-time warning logged when auth headers are sent over non-TLS connections
- **HTTP request lifecycle** — `cancelCallOnClose = true` cancels HTTP requests when clients disconnect
- **Bounded scrape request channel** — Agent-side channel now has configurable backpressure instead of unlimited
  capacity
- **Outer scrape timeout** — `withTimeout` wrapper in `fetchContent()` as safety net beyond Ktor client timeout
- **Strict env var parsing** — Boolean env vars only accept `"true"`/`"false"`; integer/long env vars throw descriptive
  errors on invalid values
- **"all" log level** — `setLogLevel` now accepts "all" as a valid level
- **Input validation** — `parseHostPort` validates blank strings; `parsePort` validates port ranges
- **TLS config validation** — Requires both certificate and key for TLS; warns on disabled X.509 verification

---

## Refactoring

- `ScrapeResults` fields changed from `var` to `val` (fully immutable construction)
- `ResponseResults` and `ScrapeRequestResponse` converted to immutable data classes
- `updateMsg: String` → `updateMsgs: List<String>` in `ResponseResults`
- `ProxyUtils` response functions now return values instead of mutating a passed-in object
- `AgentContextManager` maps made private with accessor methods and read-only views
- `ScrapeRequestManager.scrapeRequestMap` made private with read-only view
- `ProxyPathManager` changed from `ConcurrentMap` to `HashMap` with explicit `synchronized` blocks
- `AgentPathManager` uses `ConcurrentHashMap` and `Mutex` for thread-safe registration
- `AgentGrpcService` uses `ReentrantLock` for thread-safe shutdown and stub creation
- gRPC metadata constants consolidated into `GrpcConstants`
- Config file moved: `etc/config/config.conf` → `config/config.conf`
- Detekt config moved: `config/detekt/` → `etc/detekt/`
- `SslSettings` return types changed from nullable to non-nullable
- Scrape request queue changed from `Channel` to `ConcurrentLinkedQueue` with notifier
- Scrape request polling loop replaced with event-driven `awaitCompleted()` suspension
- Proto: reserved field 5 in `RegisterAgentRequest`; added `header_zipped` field 8 to `HeaderData`

---

## Dependency Updates

| Dependency     | Old    | New    |
|----------------|--------|--------|
| Kotlin         | 2.2.20 | 2.3.10 |
| Gradle wrapper | 8.x    | 9.2.0  |
| Ktor           | 3.2.3  | 3.4.0  |
| gRPC           | 1.75.0 | 1.79.0 |
| Protoc         | 4.32.0 | 4.33.5 |
| JCommander     | 2.0    | 3.0    |
| Kotest         | 6.0.3  | 6.1.3  |
| Logback        | 1.5.18 | 1.5.31 |
| MockK          | (new)  | 1.14.9 |
| tcnative       | 2.0.73 | 2.0.74 |
| utils          | 2.4.5  | 2.5.3  |
| config plugin  | 5.6.8  | 6.0.7  |
| kotlinter      | 5.2.0  | 5.4.2  |
| kover          | 0.9.1  | 0.9.7  |
| dropwizard     | 4.2.36 | 4.2.38 |
| gengrpc        | 1.4.3  | 1.5.0  |
| serialization  | 1.9.0  | 1.10.0 |
| slf4j          | 2.0.13 | 2.0.17 |
| typesafe       | 1.4.4  | 1.4.5  |

---

## CI/CD

- Added GitHub Actions CI workflow for building the project on push/PR to `master`
- Added GitHub Actions workflow for deploying Dokka API documentation to GitHub Pages
- Removed Travis CI configuration (`.travis.yml`)

---

## Documentation

- Integrated Dokka for HTML API documentation generation (`./gradlew dokkaHtml`)
- Added KDoc documentation across agent, proxy, and common packages
- Added module and package documentation (`docs/packages.md`)
- Added improvements roadmap document (`docs/improvements.md`)

---

## Testing

- ~26,000+ lines of new unit tests added
- Tests reorganized into `io.prometheus.agent/`, `io.prometheus.proxy/`, `io.prometheus.common/`, `io.prometheus.misc/`
- Added MockK for mocking support
- Compiler option `-Xreturn-value-checker=check` enabled

---

## Breaking Changes

### High Impact — Will affect most users monitoring scrape responses

| # | Change                                       | Detail                                                                                                                                                                                                                           |
|---|----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Default scrape failure status: 404 → 503** | `ScrapeResults.srStatusCode` default changed from `NotFound` (404) to `ServiceUnavailable` (503). Any monitoring/alerting keyed on status codes from failed scrapes will see different codes.                                    |
| 2 | **IOException scrape error: 404 → 503**      | When the agent can't reach the scrape target (connection refused, DNS failure, etc.), the status returned to Prometheus changed from 404 to 503.                                                                                 |
| 3 | **Catch-all exception: 404 → 500**           | Unexpected server errors in the proxy now return `InternalServerError` (500) instead of `NotFound` (404).                                                                                                                        |
| 4 | **New HTTP status codes**                    | New responses that didn't exist before: `413 Payload Too Large` (zip bomb), `502 Bad Gateway` (invalid gzip / chunk validation), `503 ServiceUnavailable` (agent disconnected mid-scrape, no agents available, missing results). |

### Medium Impact — Affects users with specific configurations

| #  | Change                                              | Detail                                                                                                                                                                                                                                              |
|----|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 5  | **Content size limits enforced by default**         | Scrape responses >10 MB (agent-side), zipped chunks >5 MB, or unzipped content >10 MB will now be rejected. Users with large metric endpoints must raise `maxContentLengthMBytes`, `maxZippedContentSizeMBytes`, or `maxUnzippedContentSizeMBytes`. |
| 6  | **Unary RPC deadline enforced (30s)**               | gRPC unary calls (register, heartbeat, etc.) now fail after 30 seconds. May affect users with very slow/distant proxy connections. Configurable via `agent.grpc.unaryDeadlineSecs`.                                                                 |
| 7  | **Retry policy: only 5xx**                          | Agent retries now only on server errors (500-599). Previously retried on any non-success status except 404. Users relying on retries for specific 4xx errors will no longer get retries.                                                            |
| 8  | **Strict boolean env vars**                         | `EnvVars.getEnv(Boolean)` now only accepts `"true"` or `"false"` (case-insensitive). Values like `"1"`, `"yes"`, `"TRUE "` (with whitespace) will throw `IllegalArgumentException`.                                                                 |
| 9  | **Consolidated/non-consolidated mismatch rejected** | `addPath` now returns an error when agent types conflict on the same path. Previously silently accepted.                                                                                                                                            |
| 10 | **Compression preference: Deflate → Gzip**          | Gzip priority raised from 1.0 to 10.0. Clients that previously received Deflate-compressed responses will now receive Gzip.                                                                                                                         |
| 11 | **JCommander 2.0 → 3.0**                            | May have CLI parsing behavior changes.                                                                                                                                                                                                              |

### Low Impact — Affects developers / embedded API users

| #  | Change                                                                      | Detail                                                                                                                                                                                                            |
|----|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 12 | **Config file path moved**                                                  | `etc/config/config.conf` → `config/config.conf`. Docker volumes or scripts referencing the old path need updating.                                                                                                |
| 13 | **`GrpcConstants` consolidation**                                           | `META_AGENT_ID_KEY` and `AGENT_ID` moved from `ProxyServerInterceptor`/`ProxyServerTransportFilter` companions to `io.prometheus.common.GrpcConstants`. Compile-time break for code importing from old locations. |
| 14 | **`SslSettings` non-nullable returns**                                      | `getTrustManagerFactory()` and `getSslContext()` now return non-nullable types. Callers that null-checked will get compile errors.                                                                                |
| 15 | **`HttpClientCache.getCacheStats()` is now `suspend`**                      | Requires coroutine context to call.                                                                                                                                                                               |
| 16 | **`AgentConnectionContext.close()` returns `Int`**                          | No longer implements `Closeable`. Returns drained request count.                                                                                                                                                  |
| 17 | **`ScrapeRequestWrapper.scrapeResults` is now nullable**                    | Changed from `nonNullableReference()` delegate to `@Volatile var scrapeResults: ScrapeResults? = null`.                                                                                                           |
| 18 | **`ProxyOptions.proxyHttpPort` renamed to `proxyPort`**                     | CLI flags unchanged, but programmatic access needs updating.                                                                                                                                                      |
| 19 | **`AgentOptions.chunkContentSizeKbs` renamed to `chunkContentSizeBytes`**   | CLI flag (`--chunk`) and config key (`agent.chunkContentSizeKbs`) unchanged, but the internal Kotlin field name changed. Affects embedded agent API users.                                                        |
| 20 | **`ScrapeResults` fields are now immutable (`val`)**                        | All fields changed from `var` to `val`. The `setDebugInfo()` method was removed. `scrapeCounterMsg` changed from `AtomicReference<String>` to plain `val String`.                                                 |
| 21 | **`PathManager.addPath()` return type changed**                             | Returns `String?` (null on success, failure reason on failure) instead of `Unit`.                                                                                                                                 |
| 22 | **`AgentContextManager` and `ScrapeRequestManager` maps made private**      | Access through getter methods and read-only views only.                                                                                                                                                           |
| 23 | **`ScrapeRequestWrapper.suspendUntilComplete` renamed to `awaitCompleted`** | Polling loop replaced with single event-driven suspension.                                                                                                                                                        |
| 24 | **Kotlin 2.3.10 / Gradle 9.2.0**                                            | Users building from source need compatible toolchains.                                                                                                                                                            |
| 25 | **Strict integer/long env var parsing**                                     | `EnvVars.getEnv(Int)` and `getEnv(Long)` now throw `IllegalArgumentException` with descriptive messages for invalid values (previously threw raw `NumberFormatException`).                                        |

