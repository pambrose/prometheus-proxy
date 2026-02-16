# Bug Fixes: ChunkedContext Input Validation & Stale Agent Eviction TOCTOU Race

## Fix 1: Missing input validation in `ChunkedContext.applyChunk`

**File:** `src/main/kotlin/io/prometheus/proxy/ChunkedContext.kt`

**Problem:** `applyChunk` used `chunkByteCount` (from the protobuf message) as the
length parameter for `checksum.update(data, 0, chunkByteCount)` and
`baos.write(data, 0, chunkByteCount)` without validating it against `data.size`.
If a buggy or malicious agent sent a `chunkByteCount` larger than the actual data
array, this would throw `ArrayIndexOutOfBoundsException`. In `ProxyServiceImpl`,
only `ChunkValidationException` is caught -- the uncaught exception would propagate
up and terminate the chunked response stream for that agent.

**Fix:** Added a validation guard at the top of `applyChunk` that throws
`ChunkValidationException` when `chunkByteCount` is negative or exceeds `data.size`.
This ensures the error is caught by the existing `ChunkValidationException` handler
in `ProxyServiceImpl.writeChunkedResponsesToProxy`, which logs the error, discards
the context, and fails the individual scrape request -- without disrupting other
in-flight transfers.

**Tests added:**

- `applyChunk should throw ChunkValidationException when chunkByteCount exceeds data size`
- `applyChunk should throw ChunkValidationException when chunkByteCount is negative`
- `applyChunk should succeed when chunkByteCount equals data size`
- `applyChunk should succeed when chunkByteCount is less than data size`

## Fix 2: TOCTOU race in `AgentContextCleanupService`

**File:** `src/main/kotlin/io/prometheus/proxy/AgentContextCleanupService.kt`

**Problem:** `findStaleAgents` returned a snapshot of agents whose inactivity exceeded
the threshold. The eviction loop then unconditionally called `removeAgentContext` for
each one. Between the snapshot and the eviction, an agent could have sent a heartbeat
(updating its activity time), making it no longer stale. The unconditional eviction
would incorrectly remove an active agent, forcing it to reconnect.

**Fix:** Added a re-check of `agentContext.inactivityDuration > maxAgentInactivityTime`
inside the eviction loop, immediately before calling `removeAgentContext`. If the agent
has become active since the snapshot, eviction is skipped with a debug log message.

**Tests added:**

- `cleanup should skip eviction when agent becomes active between check and evict`
  -- uses `returnsMany` to simulate an agent that is stale during `findStaleAgents`
  but active during the re-check
- `cleanup should still evict agent that remains stale at re-check`
  -- confirms that persistently stale agents are still evicted correctly
