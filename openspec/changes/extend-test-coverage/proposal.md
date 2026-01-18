# Change: Extend Test Coverage for Core Components

## Why

The current test suite has ~40% line coverage (2,170 test lines / 5,463 source lines) and focuses primarily on
integration/harness testing. While integration tests validate end-to-end flows, many core components lack direct unit
tests, making it difficult to:

- Test edge cases and error conditions in isolation
- Debug failures quickly without spinning up full harness infrastructure
- Refactor internal logic confidently
- Achieve comprehensive branch coverage for complex conditional logic

Critical gaps exist in gRPC services, HTTP handling, state management, path management, and SSL/TLS configuration - all
core to the proxy-agent architecture.

## What Changes

This proposal adds **comprehensive unit tests** for core untested/under-tested components:

**Phase 1 - Core Communication (High Priority):**

- gRPC service implementations (`ProxyServiceImpl`, `AgentGrpcService`)
- HTTP scraping logic (`AgentHttpService`)
- Path management (`ProxyPathManager`, `AgentPathManager`)
- Agent context lifecycle (`AgentContext`, `AgentContextManager`)

**Phase 2 - Configuration & Security (Medium Priority):**

- Complex option parsing (`AgentOptions`, `ProxyOptions`)
- SSL/TLS configuration (`SslSettings`, `TrustAllX509TrustManager`)
- Agent cleanup and eviction (`AgentContextCleanupService`)

**Phase 3 - Utilities & Metrics (Low Priority):**

- Utility functions (`Utils.kt`, `ScrapeResults.kt`)
- Metrics initialization (`ProxyMetrics`, `AgentMetrics`)
- HTTP configuration (`ProxyHttpConfig`)

**Testing Approach:**

- Use Kotest for test structure (as per existing tests)
- Use MockK for mocking dependencies (with relaxed mocks where appropriate)
- Use factory patterns for dependency injection (as established in recent test updates)
- Focus on behavior testing, edge cases, error conditions, and state transitions
- Maintain existing integration tests (no removal)

## Impact

- **Affected specs**: `testing` (new capability being added)
- **Affected code**: No production code changes - only new test files in `src/test/kotlin/`
- **Test files added**: ~15-20 new test files across three phases
- **Expected coverage increase**: From ~40% to ~70-80% line coverage
- **Build time**: May increase test execution time by 30-60 seconds
- **Maintenance**: Increased test surface area requiring updates when implementation changes
