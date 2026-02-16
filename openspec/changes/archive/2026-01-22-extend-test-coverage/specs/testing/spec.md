# Testing Capability - Spec Delta

## ADDED Requirements

### Requirement: Unit Test Coverage for gRPC Services

The test suite SHALL provide unit tests for all gRPC service implementations to validate communication protocol
behavior, error handling, and state management.

#### Scenario: Proxy service agent registration

- **WHEN** `ProxyServiceImpl.registerAgent()` is called with valid agent info
- **THEN** the agent context is created and stored in the context manager
- **AND** the gRPC channel is ready for bidirectional communication

#### Scenario: Proxy service handles duplicate registration

- **WHEN** `ProxyServiceImpl.registerAgent()` is called for an already-registered agent ID
- **THEN** the old context is removed and a new context is created
- **AND** appropriate metrics are updated

#### Scenario: Agent service connects to proxy

- **WHEN** `AgentGrpcService.connectToProxy()` is called with valid proxy address
- **THEN** a gRPC channel is established successfully
- **AND** the connection status is tracked

#### Scenario: Agent service handles connection failure

- **WHEN** `AgentGrpcService.connectToProxy()` is called but the proxy is unreachable
- **THEN** the connection attempt fails with appropriate exception
- **AND** retry logic is triggered if configured

#### Scenario: Agent service registers paths

- **WHEN** `AgentGrpcService.registerPath()` is called with valid path configuration
- **THEN** the path is sent to the proxy via gRPC
- **AND** the local path manager is updated

### Requirement: Unit Test Coverage for HTTP Services

The test suite SHALL provide unit tests for HTTP scraping logic and route handling to validate request processing, error
handling, and response building.

#### Scenario: Agent scrapes metrics successfully

- **WHEN** `AgentHttpService.scrapeMetrics()` is called with valid endpoint URL
- **THEN** HTTP request is made to the target endpoint
- **AND** response body and status code are returned

#### Scenario: Agent handles scrape timeout

- **WHEN** `AgentHttpService.scrapeMetrics()` is called and the request times out
- **THEN** a timeout exception is thrown
- **AND** appropriate metrics are recorded

#### Scenario: Agent retries failed requests

- **WHEN** `AgentHttpService.scrapeMetrics()` encounters a transient error (5xx)
- **THEN** the request is retried according to retry configuration
- **AND** retry attempts are tracked in metrics

#### Scenario: Proxy routes scrape request

- **WHEN** `ProxyHttpRoutes` receives a scrape request for a registered path
- **THEN** the request is forwarded to the appropriate agent via gRPC
- **AND** the agent's response is returned to Prometheus

#### Scenario: Proxy handles missing agent

- **WHEN** `ProxyHttpRoutes` receives a request for a path with no active agent
- **THEN** a 503 Service Unavailable status is returned
- **AND** error metrics are incremented

### Requirement: Unit Test Coverage for Path Management

The test suite SHALL provide unit tests for path management logic to validate registration, removal, lookup, and
concurrent access patterns.

#### Scenario: Proxy adds new path

- **WHEN** `ProxyPathManager.addPath()` is called with valid path and agent context
- **THEN** the path is registered and associated with the agent
- **AND** the path is available for routing

#### Scenario: Proxy removes path

- **WHEN** `ProxyPathManager.removePath()` is called for an existing path
- **THEN** the path is unregistered
- **AND** subsequent requests to that path fail with 404

#### Scenario: Agent registers path with proxy

- **WHEN** `AgentPathManager.registerPath()` is called
- **THEN** the path is sent to the proxy via gRPC
- **AND** the registration is tracked locally

#### Scenario: Concurrent path operations

- **WHEN** multiple threads add/remove paths concurrently
- **THEN** all operations complete without race conditions
- **AND** path state remains consistent

### Requirement: Unit Test Coverage for State Management

The test suite SHALL provide unit tests for agent context and connection state management to validate lifecycle
operations, cleanup, and concurrent access.

#### Scenario: Agent context creation

- **WHEN** `AgentContextManager.createContext()` is called with agent info
- **THEN** a new agent context is created with initial state
- **AND** the context is registered in the manager

#### Scenario: Agent context removal

- **WHEN** `AgentContextManager.removeContext()` is called for an existing agent
- **THEN** the agent context is removed
- **AND** associated resources are cleaned up

#### Scenario: Agent context eviction

- **WHEN** `AgentContextCleanupService` runs and detects stale agents
- **THEN** inactive agent contexts are removed
- **AND** eviction metrics are updated

#### Scenario: Scrape request tracking

- **WHEN** `ScrapeRequestManager.trackRequest()` is called for a new scrape
- **THEN** the request is added to the active request set
- **AND** timeout handling is initiated

### Requirement: Unit Test Coverage for Configuration

The test suite SHALL provide unit tests for configuration parsing and validation to ensure all options are correctly
loaded from files, environment variables, and command-line arguments.

#### Scenario: Complex agent path configuration

- **WHEN** agent options are loaded with multiple path configurations
- **THEN** all paths are parsed correctly with their respective settings
- **AND** invalid configurations are rejected with clear error messages

#### Scenario: Environment variable override

- **WHEN** configuration is loaded with environment variables set
- **THEN** environment variables override default values
- **AND** precedence rules are respected

#### Scenario: SSL configuration validation

- **WHEN** SSL settings are loaded with certificate paths
- **THEN** certificate file existence is validated
- **AND** invalid paths produce clear error messages

### Requirement: Unit Test Coverage for Security Components

The test suite SHALL provide unit tests for SSL/TLS configuration and certificate handling to validate secure
communication setup.

#### Scenario: SSL settings initialization

- **WHEN** `SslSettings` is created with valid certificate paths
- **THEN** SSL context is initialized correctly
- **AND** certificates are loaded successfully

#### Scenario: Trust all manager behavior

- **WHEN** `TrustAllX509TrustManager.checkServerTrusted()` is called
- **THEN** all certificates are accepted (for development/testing)
- **AND** no validation errors occur

### Requirement: Unit Test Coverage for Utility Functions

The test suite SHALL provide unit tests for utility functions to validate URL parsing, encoding, JSON handling, and
other helper operations.

#### Scenario: URL decoding

- **WHEN** `Utils.decodeUrl()` is called with percent-encoded URL
- **THEN** the URL is decoded correctly
- **AND** special characters are handled properly

#### Scenario: Scrape result serialization

- **WHEN** `ScrapeResults.toJson()` is called with scrape data
- **THEN** JSON representation is created
- **AND** all fields are serialized correctly

#### Scenario: Zstd compression

- **WHEN** `ScrapeResults` uses zstd compression for large responses
- **THEN** data is compressed correctly
- **AND** compression ratio is tracked in metrics

### Requirement: Test Framework Standards

The test suite SHALL use consistent testing patterns and frameworks across all test files.

#### Scenario: MockK usage

- **WHEN** tests require mocked dependencies
- **THEN** MockK is used with relaxed mocks where appropriate
- **AND** verification of mock interactions is performed

#### Scenario: Factory pattern for DI

- **WHEN** tests need to inject dependencies
- **THEN** factory patterns are used to create testable instances
- **AND** production code supports constructor-based injection

#### Scenario: Kotest structure

- **WHEN** new test files are created
- **THEN** Kotest framework is used for test structure
- **AND** test naming follows existing conventions

### Requirement: Coverage Reporting

The test suite SHALL achieve measurable improvements in code coverage and provide detailed coverage reports.

#### Scenario: Coverage measurement

- **WHEN** `./gradlew koverMergedHtmlReport` is executed
- **THEN** coverage report is generated for all modules
- **AND** report shows line and branch coverage percentages

#### Scenario: Coverage target achievement

- **WHEN** all phases of testing are complete
- **THEN** overall line coverage reaches 70-80%
- **AND** critical components have >80% coverage

#### Scenario: Coverage gap identification

- **WHEN** coverage reports are reviewed
- **THEN** untested code paths are identified
- **AND** priorities for additional testing are established
