# Bug Audit Report - February 13, 2026 (Session B)

This report summarizes additional bugs identified and fixed in the Prometheus Proxy codebase during the second audit
session.

## 1. Agent Scrape Backlog Counter Drift (Fixed)

**Files:** `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`, `src/main/kotlin/io/prometheus/Agent.kt`

**Description:**
The `scrapeRequestBacklogSize` counter in the Agent was incremented before submitting a scrape task to the internal
processing channel. If the submission failed (e.g., because the connection was closing and the channel was cancelled),
the counter was never decremented. This led to a permanent upward drift in the backlog metric, eventually making the
Agent appear "unhealthy" to health checks.

**Solution:**

1. **Try-Catch Protection:** Wrapped `sendScrapeRequestAction` in a try-catch block in `AgentGrpcService.kt` to ensure
   the counter is decremented if the task cannot be queued.
2. **Connection Reset:** Added an explicit reset of `scrapeRequestBacklogSize` to `0` at the start of `connectToProxy`
   to ensure a clean state for every new connection attempt.

**Status:** Fixed. Verified with `AgentBacklogDriftTest.kt`.

## 2. Orphaned Consolidated Paths in Proxy (Fixed)

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt`

**Description:**
When an agent disconnected, `removeFromPathManager` correctly removed the agent from any consolidated paths it was part
of. However, if that agent was the *last* one for a specific path, the path entry itself remained in the `pathMap` as an
empty list, rather than being removed. This caused the Proxy to continue claiming ownership of paths it could no longer
service.

**Solution:**
Updated `removeFromPathManager` to check if a consolidated path's agent list is empty after removal and delete the path
from the map if so.

**Status:** Fixed. Verified by code review and passing regression tests.

## 3. Missing Zipped Content Size Limit in Proxy (Fixed)

**Files:** `src/main/kotlin/io/prometheus/proxy/ChunkedContext.kt`,
`src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt`, `config/config.conf`

**Description:**
While the Proxy had a limit for *unzipped* content, it lacked a limit for the raw *zipped* data accumulated during gRPC
chunked transfers. A malicious or misconfigured agent could send massive amounts of compressed data, exhausting Proxy
memory before decompression even began.

**Solution:**

1. **Configurable Limit:** Added `proxy.internal.maxZippedContentSizeMBytes` (default 5MB) to the configuration.
2. **Enforcement:** Updated `ChunkedContext.applyChunk()` to throw a `ChunkValidationException` if the accumulated byte
   count exceeds this limit.

**Status:** Fixed. Verified with `ChunkedContextTest.kt`.

## 4. Missing Scrape Response Size Limit in Agent (Fixed)

**Files:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt`,
`src/main/kotlin/io/prometheus/agent/AgentOptions.kt`, `config/config.conf`

**Description:**
The Agent would read the entire body of an HTTP scrape response into memory without checking its size. If a monitored
service returned a massive payload (e.g., a multi-gigabyte log file accidentally served on the metrics port), the Agent
would crash with an `OutOfMemoryError`.

**Solution:**

1. **Configurable Limit:** Added `agent.http.maxContentLengthMBytes` (default 10MB) to the configuration.
2. **Proactive Check:** Added a check for the `Content-Length` header before reading the body, and a secondary check on
   the actual body length for cases where the header is missing or incorrect.

**Status:** Fixed. Verified with `AgentHttpServiceTest.kt`.

## 5. Unvalidated ScrapeId in Chunked Headers (Fixed)

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt`

**Description:**
The Proxy's gRPC service created a new `ChunkedContext` immediately upon receiving a `HEADER` message from an agent,
without verifying if the Proxy had actually requested that `scrapeId`. This allowed an agent to force the Proxy to
allocate memory for arbitrary transfers.

**Solution:**
Added a validation check in `writeChunkedResponsesToProxy` to ensure the `scrapeId` exists in the `scrapeRequestManager`
before allocating a `ChunkedContext`.

**Status:** Fixed. Verified with `ProxyServiceImplTest.kt`.
