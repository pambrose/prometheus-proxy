# Change: Add missing unit tests to improve code coverage

## Why

The existing `testing` spec defines requirements for unit test coverage across gRPC services, HTTP services, path
management, state management, configuration, and security components. However, 11+ source files still lack
corresponding unit tests, including critical components like `AgentHttpService`, `ProxyGrpcService`, `AgentContext`,
`AgentContextManager`, and configuration options. Closing these gaps will catch regressions earlier and bring
coverage closer to the 70-80% target defined in the spec.

## What Changes

- Add unit tests for **agent module** gaps: `AgentHttpService`, `AgentOptions`, `AgentClientInterceptor`,
  `EmbeddedAgentInfo`, `RequestFailureException`
- Add unit tests for **proxy module** gaps: `ProxyGrpcService`, `ProxyHttpService`, `AgentContext`,
  `AgentContextManager`, `ProxyOptions`, `ProxyServerInterceptor`, `ProxyServerTransportFilter`,
  `ScrapeRequestWrapper`
- Add unit tests for **common module** gaps: `BaseOptions`, `ConfigWrappers`
- Add spec requirements for interceptor and transport filter testing (not currently in the `testing` spec)

## Impact

- Affected specs: `testing`
- Affected code: New test files in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`
- No changes to production code (test-only change)
