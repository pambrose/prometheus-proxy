# README Feedback

This document provides focused, actionable feedback on README.md and tracks project improvements over time.

---

## Test Coverage Improvements (2026-02) ✅

### Current State

The test suite has grown substantially since the initial Phase 1 effort. The project now has **63 test-related files**
across 5 directories with comprehensive coverage of all core components.

**Test Files by Component:**

| Component | Files | Description                                                           |
|:----------|------:|:----------------------------------------------------------------------|
| Agent     |    17 | Agent lifecycle, gRPC streaming, HTTP scraping, options, metrics, SSL |
| Proxy     |    21 | Proxy lifecycle, gRPC service, HTTP routing, path management, cleanup |
| Common    |     6 | CLI parsing, config wrappers, env vars, scrape results, utilities     |
| Misc      |     6 | Admin endpoints, ConfigVals, data classes, options integration        |
| Harness   |    13 | Integration tests (in-process, Netty, TLS) + support infrastructure   |

**Testing Frameworks:** Kotest (StringSpec), MockK, JUnit 5, Kotlin Coroutines, Ktor, gRPC in-process transport, Kover

**Notable additions since Phase 1:**

- Agent: AgentTest, AgentHttpServiceTest, AgentHttpServiceHeaderTest, AgentConnectionContextTest, AgentBacklogDriftTest,
  AgentClientInterceptorTest, AgentMetricsTest, AgentOptionsTest, EmbeddedAgentInfoTest, RequestFailureExceptionTest,
  SslSettingsTest, TrustAllX509TrustManagerTest
- Proxy: ProxyTest, ProxyGrpcServiceTest, ProxyHttpConfigTest, ProxyHttpRoutesTest, ProxyHttpServiceTest,
  ProxyOptionsTest, ProxyDynamicConfigTest, ProxyServerInterceptorTest, ProxyServerTransportFilterTest,
  ProxyMetricsTest, AgentContextCleanupServiceTest, AgentContextTest, AgentContextManagerTest, ChunkedContextTest,
  RecentReqsSynchronizationTest, ScrapeRequestWrapperTest
- Common: BaseOptionsTest, ConfigWrappersTest, ConstantsTest, EnvVarsTest, ScrapeResultsTest, UtilsTest
- Misc: AdminDefaultPathTest, AdminEmptyPathTest, AdminNonDefaultPathTest, ConfigValsTest
- Bug regression tests for concurrency issues, chunked context validation, and stale HTTP headers

**Commands for Verification:**

```bash
./gradlew test                     # Run all tests
./gradlew koverMergedHtmlReport    # Generate coverage report
open build/reports/kover/html/index.html

make nh-tests     # Unit tests only (no harness)
make ip-tests     # In-process integration tests
make netty-tests  # Netty integration tests
make tls-tests    # TLS integration tests
```

**Documentation:** See [docs/TESTING.md](docs/TESTING.md) for the full testing guide.

---

## README Feedback (2026-02)

This section provides focused, actionable feedback on README.md. Suggestions are grouped by priority and include
concrete example edits.

### Summary of Strengths

- Clear problem statement and architecture overview with diagram
- Practical Quick Start (CLI and Docker) with many real-world examples
- Comprehensive configuration tables with CLI, env vars, and property mappings
- Good coverage of TLS, nginx reverse proxy, admin endpoints, and troubleshooting
- Table of contents with clear section organization
- Auth header forwarding security guidance with TLS recommendation

### Resolved Items ✅

These items from earlier feedback have been addressed:

- ~~Travis CI badge~~ — Removed. README now shows JitPack, Codacy, Kotlin, and ktlint badges
- ~~Table of contents~~ — Present with Monitoring & Observability and Configuration Examples
- ~~Embedded Agent snippet formatting~~ — `agentInfo.close()` formatted correctly
- ~~Quick Start includes --proxy variant~~ — Present
- ~~Verify it works curl step~~ — Present
- ~~Service Discovery --sd_target_prefix~~ — Present
- ~~Nginx wording consistency~~ — Section uses "Nginx Reverse Proxy" heading consistently

### Open Items

#### 1. Docker Quick Start: networking/hostname caveat

**Priority:** Medium

The Docker Quick Start agent uses `examples/simple.conf`, which sets `agent.proxy.hostname = localhost`. In separate
containers, `localhost` resolves to the agent container itself, not the proxy. Consider one of:

- Prefer docker-compose (as already provided in `etc/compose/proxy.yml`) so the agent can reach the proxy by service
  name
- Create a user-defined bridge network and set `PROXY_HOSTNAME=proxy` (if the proxy container is named `proxy`)
- Override via env var: `--env PROXY_HOSTNAME=host.docker.internal` (macOS/Windows; on Linux provide the host IP or use
  compose)

#### 2. Service Discovery: sample payload

**Priority:** Low

Consider adding a small sample of the JSON payload returned by `/discovery` to set expectations:

```json
[
  {
    "targets": [
      "proxy-host:8080"
    ],
    "labels": {
      "job": "app1 metrics",
      "path": "app1_metrics",
      "key1": "value1"
    }
  }
]
```

#### 3. Contributing section

**Priority:** Low

Consider adding a short section near the end:

```markdown
## Contributing

- Build & test: `./gradlew build`
- Run tests only: `./gradlew test`
- Lint: `./gradlew lintKotlinMain lintKotlinTest`
- Static analysis: `./gradlew detekt`
- Auto-format: `./gradlew formatKotlin`
```

#### 4. Ports quick-reference

**Priority:** Low

The Components section covers ports informally. A dedicated cheat-sheet table could help:

| Service       | Default Port | Description             |
|:--------------|:-------------|:------------------------|
| Proxy HTTP    | 8080         | Prometheus scrapes here |
| Proxy gRPC    | 50051        | Agents connect here     |
| Proxy Admin   | 8092         | Health/ping endpoints   |
| Proxy Metrics | 8082         | Internal proxy metrics  |
| Agent Admin   | 8093         | Health/ping endpoints   |
| Agent Metrics | 8083         | Internal agent metrics  |

#### 5. Stale .travis.yml

**Priority:** Low

The `.travis.yml` file still exists in the repo root (references openjdk11, not Java 17). There is no active CI
configuration (no GitHub Actions). Consider removing `.travis.yml` if Travis is no longer used.

### Notes

- Links: The nginx links already use blob/raw paths; no change needed
- Docker Compose: README points to `etc/compose/proxy.yml`; good as-is
- CLI args doc: `docs/cli-args.md` uses `--config` consistently; no mismatch found
- Java baseline: README states Java 17 or newer, consistent with `build.gradle.kts` (`jvmToolchain(17)`)
