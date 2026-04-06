# Changelog

All notable changes to this project are documented in this file.

---

## [3.1.0] - 2026-04-04

### Breaking Changes

- **Maven coordinates changed**: Published to Maven Central as `com.pambrose:prometheus-proxy`
- JitPack is no longer used; all dependencies resolve from Maven Central

### New Features

- Add Zensical documentation site with comprehensive guides, code examples, and architecture diagrams
- Publish documentation to GitHub Pages via CI

### Build & Tooling

- Migrate publishing from JitPack to Maven Central using vanniktech maven-publish plugin
- Replace manual `maven-publish` + sources/javadoc JAR tasks with `mavenPublishing` DSL
- Remove JitPack plugin resolution strategy from `settings.gradle.kts`
- Remove `jitpack.yml`
- Add GPG signing for Maven Central (skipped when no key is provided)
- Add `google()` repository to build script
- Add `overrideVersion` property support for snapshot publishing
- Import `VisibilityModifier` directly instead of using fully qualified name in Dokka config

### Documentation

- Add full Zensical documentation site in `website/prometheus-proxy/` with 13 pages covering architecture, getting started, configuration, security/TLS, Docker, embedded agent, service discovery, monitoring, CLI reference, and advanced topics
- Add code example snippets in `src/test/kotlin/website/*.txt` imported via pymdownx.snippets
- Extract Java/Kotlin code examples into compilable source files (`EmbeddedAgentJavaExample.java`, `EmbeddedAgentKotlinExample.kt`) so API changes are caught by the compiler
- Add mkdocs-material dependency to CI workflow and Makefile for material theme support
- Add KDocs nav entry with `material/book-open-page-variant` icon linking to Dokka API docs
- Add API Reference section and grid card layouts for Next Steps on index, getting-started, and configuration pages
- Add markdown extensions: `admonition`, `pymdownx.details`, `attr_list`, `md_in_html`, `pymdownx.emoji` with material icon support
- Update `zensical.toml`: dark mode default, navigation tabs, snippet base paths, Mermaid support, GitHub social link
- Fix KDocs nav redirect 404 by renaming `kdocs.md` to `api.md` to avoid path conflict with Dokka output
- Fix GitHub Actions docs workflow to use correct `working-directory` and artifact path
- Update `README.md` with Maven Central badge, documentation site link, and dependency coordinates
- Update `CLAUDE.md` with documentation site and publishing sections
- Update `llms.txt` with Maven Central link and documentation site reference

### Dependencies

- Bump utils to 2.7.1
- Bump Kotest to 6.1.10, Ktor to 3.4.2, Logback to 1.5.32
- Bump gradle-plugins to 1.0.12, Protoc to 4.34.1, Dropwizard to 4.2.38
- Bump Dokka to 2.2.0, maven-publish plugin to 0.36.0, Kover to 0.9.8

### Metrics & Observability

- Add new proxy metrics: `proxy_chunk_validation_failures_total`, `proxy_chunked_transfers_abandoned_total`, `proxy_agent_displacement_total`, `proxy_scrape_response_bytes`
- Convert proxy and agent latency metrics from summaries to histograms
- Add new agent metrics: `agent_client_cache_size`, `agent_scrape_backlog_size`
- Add `path` and `encoding` labels to proxy response metrics
- Rebuild Grafana dashboards for new metric schema
- Add `docs/metrics-and-grafana.md` with complete metrics reference and PromQL examples

### Bug Fixes

- Fix flaky `HttpClientCacheTest` by ensuring deterministic LRU eviction order
- Fix scrape response bytes metric to observe correct unzipped size

### Misc

- Use portable bash shebang (`#!/usr/bin/env bash`) in `bin/` scripts
- Extract Docker image version from `build.gradle.kts` in `bin/` scripts
- Remove `.superset` config files
- Remove legacy files and clean up `.gitignore`

---

## [3.0.3] - 2026-03-18

### Dependencies

- Upgrade Kotlin to 2.3.20, Gradle wrapper to 9.4.0, gRPC to 1.80.0, Kotest to 6.1.7, Protoc to 4.34.0

### Build & Tooling

- Extract JitPack URLs into reusable Makefile variables
- Enable Gradle configuration caching and daemon
- Use `forEach` instead of `map` in coroutine launches for clarity in tests

### Documentation

- Remove outdated GEMINI.md, AGENTS.md, and OpenSpec instructions
- Clean up CLAUDE.md

---

## [3.0.1] - 2026-02-28

### Build & Tooling

- Add homepage link to plugins configuration in build.gradle.kts
- Update dependency management and plugin versions
- Update .gitignore to include test configuration files

### Documentation

- Add GitHub workflow commands and API documentation section to README

---

## [3.0.0] - 2026-02-15

### Bug Fixes

- Fix integer overflow in `ChunkedContext.totalByteCount` (Int -> Long)
- Fix chunk checksum calculation to use actual byte count instead of full buffer size
- Fix `toScrapeResponseHeader` to propagate actual `srZipped` value (was hardcoded to `true`)
- Fix `IOException` error code from `NotFound` (404) to `ServiceUnavailable` (503)
- Fix catch-all HTTP exception handler from `NotFound` (404) to `InternalServerError` (500)
- Fix OpenMetrics `# EOF` marker handling in consolidated responses
- Fix `parseHostPort` to strip brackets from IPv6 addresses
- Fix TOCTOU race in `AgentContextCleanupService`
- Fix negative `scrapeRequestBacklogSize` with atomic CAS-loop decrement
- Fix `ConcurrentModificationException` in `ProxyPathManager.removePathsForAgentId`
- Fix `HttpClientCache.close()` deadlock
- Fix idle HTTP clients now closed on eviction (previously only marked for close)
- Fix `AgentHttpService` now properly closed during agent shutdown (resource leak)
- Fix path registration concurrency by moving gRPC calls outside the mutex
- Fix `AgentClientInterceptor` to use the `next` channel parameter
- Fix synchronized `agentId` assignment in `AgentClientInterceptor`
- Fix `ScrapeRequestWrapper.markComplete()` is now idempotent via `AtomicBoolean.compareAndSet`
- Fix `runCatching` replaced with `runCatchingCancellable` to avoid swallowing `CancellationException`
- Fix orphaned `ChunkedContext` cleanup on stream failure
- Fix credential leak in `HttpClientCache` logs
- Fix password `CharArray` zeroed after use in `SslSettings.getKeyStore`
- Fix `FileInputStream` resource leak in `SslSettings`
- Fix URL sanitization in agent logs to strip credentials before logging

### New Features

- Content size limits to prevent zip bombs and unbounded memory
- Unary RPC deadline (`agent.grpc.unaryDeadlineSecs`, default 30s)
- Graceful scrape request failure on agent disconnect
- Consolidated/non-consolidated mismatch rejection
- Authorization header TLS warning
- Bounded scrape request channel with configurable backpressure
- Outer scrape timeout as safety net beyond Ktor client timeout
- Strict env var parsing for booleans and integers
- Input validation for `parseHostPort` and `parsePort`
- TLS config validation

### Refactoring

- `ScrapeResults` fields changed from `var` to `val` (fully immutable)
- `ResponseResults` and `ScrapeRequestResponse` converted to immutable data classes
- `ProxyUtils` response functions now return values instead of mutating
- `AgentContextManager` and `ScrapeRequestManager` maps made private
- gRPC metadata constants consolidated into `GrpcConstants`
- Config file moved: `etc/config/config.conf` -> `config/config.conf`
- Scrape request queue changed from `Channel` to `ConcurrentLinkedQueue` with notifier
- Scrape request polling loop replaced with event-driven `awaitCompleted()` suspension

### Dependencies

- Kotlin 2.2.20 -> 2.3.10, Gradle 8.x -> 9.2.0
- Ktor 3.2.3 -> 3.4.0, gRPC 1.75.0 -> 1.79.0, JCommander 2.0 -> 3.0
- Kotest 6.0.3 -> 6.1.3, Logback 1.5.18 -> 1.5.31
- Add MockK 1.14.9 for mocking support

### CI/CD

- Add GitHub Actions CI workflow
- Add GitHub Actions workflow for Dokka API documentation
- Remove Travis CI configuration

### Testing

- ~26,000+ lines of new unit tests added
- Tests reorganized into agent, proxy, common, and misc packages
- Add MockK for mocking support

### Breaking Changes

- Default scrape failure status changed from 404 to 503
- IOException scrape error changed from 404 to 503
- New HTTP status codes: 413 (zip bomb), 502 (invalid gzip/chunk), 503 (agent disconnected)
- Content size limits enforced by default (10 MB)
- Unary RPC deadline enforced (30s)
- Retry policy: only 5xx (previously retried on any non-success except 404)
- Strict boolean env vars (`"true"`/`"false"` only)
- JCommander 2.0 -> 3.0
- Kotlin 2.3.10 / Gradle 9.2.0 required

---

## [2.4.0] - 2025-09-10

### Dependencies

- Refactor dependency management in build.gradle.kts and libs.versions.toml
- Update gRPC, Jetty, Kotest, Dropwizard, and tcnative versions
- Upgrade Kotlin to 2.2.20

---

## [2.3.0] - 2025-08-13

### Misc

- Fix formatting and capitalization in README.md
- Dependency updates

---

## [2.2.0] - 2025-06-25

### Misc

- Dependency updates
- Project cleanup

---

## [2.1.0] - 2025-03-22

### Improvements

- Refactor coroutine dispatchers to use IO for better performance
- Refactor coroutine exception handling for improved safety
- Refactor atomic operations for improved clarity

### Dependencies

- Upgrade Kotlin to 2.1.20

---

## [2.0.0] - 2025-02-14

### New Features

- Add gRPC keepalive support

---

## [1.23.2] - 2025-02-10

### Dependencies

- Update jars

---

## [1.23.1] - 2024-12-09

### Bug Fixes

- Catch and report exceptions on labels JSON deserialization
- Fix platform type issue
- Default Plain content types to charset UTF-8
- Add charset to JSON values

### Improvements

- Add logging to `--debug` option
- Replace explicit size check with `.isNotEmpty()` call

### Dependencies

- Update Ktor and logging jars

---

## [1.23.0] - 2024-11-29

### New Features

- Add support for gRPC reflection
- Add support for service discovery labels in pathConfigs
- Remove krotodc library

### Build

- Convert build.gradle to build.gradle.kts
- Change minimum JDK from 17 to 11

### Dependencies

- Upgrade to Kotlin 2.1.0, Ktor 3.0.1, gRPC 1.68.2

---

## [1.22.0] - 2024-06-11

### Misc

- Misc refactoring
- Fix detekt complaints
- Update Copyright date

### Dependencies

- Update logging and Kotlin jars

---

## [1.21.0] - 2024-01-06

### Dependencies

- Upgrade to Kotlin 1.9.22

---

## [1.20.0] - 2023-12-11

### Improvements

- Refactor configuration handling in Agent and Proxy classes

### Dependencies

- Update Kotlin and gRPC versions
- Update Kotlinter, ktor-client, ktor-server, and other dependencies
- Update Gradle wrapper

---

## [1.19.0] - 2023-11-02

### Dependencies

- Upgrade to Kotlin 1.9.20
- Upgrade to Kotlinter 4.0.0
- Update jars

---

## [1.18.0] - 2023-07-19

### Improvements

- Convert try/catch statements to `runCatching`
- Suppress warnings on functions intended for embedded client usage

### Dependencies

- Kotlin 1.9.0, Ktor 2.3.1, gRPC 1.56.1, Dropwizard 4.2.19
- Gradle 8.2.1

---

## [1.17.0] - 2023-05-16

### New Features

- Add krotodc data classes

### Dependencies

- Update jars

---

## [1.16.0] - 2023-04-11

### Dependencies

- Update jars
- Upgrade to Kotlin 1.8.20

---

## [1.15.0] - 2022-12-14

### New Features

- Add support for using nginx as a reverse proxy for prometheus_proxy

### Dependencies

- Update gRPC, Jetty, and Ktor jars

---

## [1.14.2] - 2022-11-26

### Bug Fixes

- Fix README.md typo
- Fix service discovery format problem (#83)

---

## [1.14.1] - 2022-10-14

### Build

- Update Dockerfiles from openjdk11-jre to openjdk17-jre

---

## [1.14.0] - 2022-08-30

### New Features

- Add support for agent `scrapeMaxRetries`
- Add example of how to scrape Prometheus from the agent

### Dependencies

- Upgrade to Ktor 2.1.0, Kotlin 1.7.10, gRPC 1.49.0, Dropwizard 4.2.11

---

## [1.13.0] - 2022-03-03

### Dependencies

- Update jars

---

## [1.12.0] - 2022-01-13

### New Features

- Add Authorization header to proxied requests (#70)

### Improvements

- Convert to Ktor 2.0

### Dependencies

- Upgrade jars

---

## [1.11.0] - 2021-12-14

### Dependencies

- Update jars

---

## [1.10.0] - 2021-06-25

### New Features

- Multiplatform Docker images (#64)

### Dependencies

- Upgrade jars
- Revert Gradle to 6.8.3

---

## [1.10.1] - 2021-06-26

### Bug Fixes

- Fix missing sources (#66)

### Misc

- Remove wercker.yml config

---

## [1.9.1] - 2021-05-23

### Dependencies

- Update jars

---

## [1.9.0] - 2021-05-01

### Improvements

- Remove no-cache HTTP directive
- Code cleanup

### Dependencies

- Upgrade to Kotlin 1.5.0
- Update jars

---

## [1.8.8] - 2021-02-03

### Dependencies

- Update jars

---

## [1.8.7] - 2020-12-04

### Dependencies

- Update jars

---

## [1.8.6] - 2020-10-08

### Dependencies

- Upgrade to Kotlin 1.4.10

---

## [1.8.5] - 2020-09-03

### Dependencies

- Update jars

---

## [1.8.4] - 2020-09-03

### Dependencies

- Update jars

---

## [1.8.3] - 2020-09-01

### Dependencies

- Update jars

---

## [1.8.1] - 2020-08-31

### Documentation

- Update README.md

---

## [1.8.0] - 2020-08-28

### Dependencies

- Update jars

---

## [1.7.1] - 2020-08-12

### Dependencies

- Update jars

---

## [1.7.0] - 2020-07-08

### Dependencies

- Update jars

---

## [1.6.4] - 2020-04-30

### Dependencies

- Update jars

---

## [1.6.3] - 2019-12-21

### Dependencies

- Update jars

---

## [1.6.2] - 2019-12-18

### Dependencies

- Update jars

---

## [1.6.1] - 2019-12-15

### Bug Fixes

- Minor fixes

---

## [1.6.0] - 2019-12-15

### Improvements

- Update Docker scripts

---

## [1.5.0] - 2019-12-04

### Dependencies

- Update jars

---

## [1.4.5] - 2019-11-22

### Dependencies

- Update jars

---

## [1.4.4] - 2019-11-22

### Dependencies

- Update jars

---

## [1.4.3] - 2019-11-18

### Dependencies

- Update jars

---

## [1.4.2] - 2019-11-15

### Bug Fixes

- Fix utils jar issue

---

## [1.4.1] - 2019-11-14

### Bug Fixes

- Minor fixes

---

## [1.4.0] - 2019-11-14

### Improvements

- Convert from Maven to Gradle build system
- Convert from Java to Kotlin coroutines for gRPC streaming
- Replace Spark HTTP framework with Ktor
- Replace OkHttp client with Ktor HTTP client
- Modernize project structure

### Documentation

- Minor version and proxy/agent port corrections (#21)
- Added network diagram and other clarifications (#19)

---

## [1.3.10] - 2019-01-18

### Documentation

- Added network diagram and other clarifications (#19)
- Make app names consistent with diagram in README.md

---

## [1.3.9] - 2019-01-03

### Dependencies

- Update jars

---

## [1.3.8] - 2018-12-08

### Dependencies

- Update jars

---

## [1.3.7] - 2018-09-18

### Dependencies

- Update jars

---

## [1.3.6] - 2018-04-08

### Misc

- Cleanup compiler warnings

---

## [1.3.5] - 2018-04-08

### Dependencies

- Update jars

---

## [1.3.4] - 2018-03-02

### Dependencies

- Update jars

---

## [1.3.3] - 2018-01-25

### Dependencies

- Update jars

---

## [1.3.2] - 2018-01-03

### Misc

- Remove ConfigVals.java from Code Climate review

---

## [1.3.1] - 2017-12-29

### Improvements

- Add Kotlin idioms

---

## [1.3.0] - 2017-12-24

### New Features

- Convert codebase from Java to Kotlin

---

## [1.2.5] - 2017-12-07

### Dependencies

- Update gRPC, Spark, OkHttp, Metrics, and Brave jars

---

## [1.2.4] - 2017-11-06

### Dependencies

- Update jars

---

## [1.2.3] - 2017-05-24

### Dependencies

- Update jars

---

## [1.2.2] - 2017-04-30

### Dependencies

- Update jars

---

## [1.2.1] - 2017-04-30

### Bug Fixes

- Minor fixes

---

## [1.2.0] - 2017-04-29

### Improvements

- Minor improvements

---

## [1.1.0] - 2017-04-28

### New Features

- Add agent and proxy Grafana dashboards

---

## [1.0.0] - 2017-04-26

### Initial Release

- Prometheus Proxy enabling Prometheus to scrape metrics from endpoints behind firewalls
- **Proxy** component runs alongside Prometheus outside the firewall
- **Agent** component runs inside the firewall with monitored services
- gRPC-based communication between proxy and agent
- Streaming scrape request/response via gRPC server/client streams
- Docker support with Alpine/OpenJDK base containers
- Docker Compose configuration
- Typesafe Config (HOCON) configuration support
- CLI argument parsing with JCommander
- Prometheus metrics for agent and proxy
- Zipkin distributed tracing support
- Agent heartbeat and inactive agent cleanup
- InProcess gRPC transport for testing
- Logback logging
- Maven build with wrapper
- Travis CI and Wercker CI support
- Code Climate integration
