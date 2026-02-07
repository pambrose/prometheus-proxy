## 1. Agent Module Tests (Critical)

- [x] 1.1 Create `AgentHttpServiceTest.kt` — test scrape logic, timeout handling, error responses, header forwarding
- [x] 1.2 Create `AgentOptionsTest.kt` — test option parsing, defaults, environment variable overrides, path configs
- [x] 1.3 Create `AgentClientInterceptorTest.kt` — test header injection and call interception
- [x] 1.4 Create `EmbeddedAgentInfoTest.kt` — test agent metadata construction and defaults
- [x] 1.5 Create `RequestFailureExceptionTest.kt` — test exception message formatting and properties

## 2. Proxy Module Tests (Critical)

- [x] 2.1 Create `AgentContextTest.kt` — test context state, scrape channel lifecycle, and marking stale
- [x] 2.2 Create `AgentContextManagerTest.kt` — test context add/remove/lookup, concurrent access, cleanup
- [x] 2.3 Create `ProxyGrpcServiceTest.kt` — test agent registration, heartbeat handling, path registration via gRPC
- [x] 2.4 Create `ProxyHttpServiceTest.kt` — test server configuration, route setup, admin endpoints
- [x] 2.5 Create `ProxyOptionsTest.kt` — test option parsing, defaults, and configuration overrides
- [x] 2.6 Create `ScrapeRequestWrapperTest.kt` — test request wrapping, timeout tracking, and completion
- [x] 2.7 Create `ProxyServerInterceptorTest.kt` — test server-side metadata injection and call interception
- [x] 2.8 Create `ProxyServerTransportFilterTest.kt` — test transport-level connection filtering

## 3. Common Module Tests

- [x] 3.1 Create `BaseOptionsTest.kt` — test shared option parsing, config file loading, version info
- [x] 3.2 Create `ConfigWrappersTest.kt` — test config wrapper accessors and default handling

## 4. Validation and Coverage

- [x] 4.1 Run full test suite to ensure all new tests pass
- [x] 4.2 Generate Kover coverage report and verify improvement
- [x] 4.3 Identify any remaining low-coverage areas for follow-up
