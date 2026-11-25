# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Build System and Common Commands

All commands from project root.

```bash
./gradlew check unitTest --rerun harnessTest --rerun  # All checks (lint + tests), rerunning the tests (make tests)
./gradlew agentJar proxyJar              # Generate standalone JARs
./gradlew generateProto                  # Regenerate protobuf stubs
make ci                                  # Run the CI workflow's jobs locally (needs Docker)
```

### Code Quality

```bash
./gradlew lintKotlinMain lintKotlinTest  # Run kotlinter linter
./gradlew detekt                         # Run detekt static analysis
./gradlew formatKotlin                   # Auto-format code
make zizmor                              # Audit .github/ workflows and dependabot.yml (needs Docker)
make codescene                           # CodeScene's pull request check, run locally with its cs CLI
```

The `zizmor` check fails a pull request on any finding, so a workflow change must keep the conventions it enforces:
actions pinned to a commit SHA with the version in a trailing comment, `persist-credentials: false` on every
`actions/checkout`, least-privilege `permissions`, and a `cooldown` on every Dependabot entry. The Makefile and the
workflow pin the same zizmor version; update them together.

The `CodeScene Code Health Review (master)` check comes from CodeScene's GitHub app, not a workflow, and isn't
required. `make codescene` runs the same delta analysis with the local `cs` CLI (signed in with `cs auth login`) on the
branch's changes since `origin/master`, uncommitted and untracked files included, and fails as the check does: on a
code health finding that's new or worse outside `src/test/`. Both read `.codescene/code-health-rules.json`, which turns
off, for every Kotlin source, the rules that flagged the code's intended shape rather than a problem (Primitive
Obsession, String Heavy Function Arguments, Excess Number of Function Arguments, Global Conditionals); the
function-level rules stay on, so split a function CodeScene rates complex or bumpy rather than working around a rule.
It turns every rule off for the docs site's vendored third-party builds (`website/prometheus-proxy/docs/vendor`),
which `.codacy.yml` excludes too.

Always run lint and build before completing tasks:
`./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -x test`

### Useful Make Targets

Run `make help` for the current target list with descriptions (auto-extracted from `## …` annotations in the
`Makefile`). The container and scaling targets need Docker.

Each demo under `demos/` is self-contained, with its own Makefile run from its directory (`make -C demos/<name> help`
lists its targets). The root `Makefile`'s only demo targets, `demos-up`, `demos-up-local`, and `demos-down`, run every
demo's `up`, `up-local`, or `down` in turn (its `DEMOS` list), and keep going past a demo that fails.
The nginx demo's `agent-legacy` runs release 4.6.0's agent image on purpose, as an agent from before the stream
keepalives, so keep it pinned there (Dependabot ignores it).

The Ktor demo's coffee shop, `demos/ktor/app`, is a Gradle project of its own, outside the root build, that
embeds the agent through the Ktor plugin, which reads its Micrometer registry in-process, and reads
`gradle/libs.versions.toml` (its Ktor and Micrometer entries are there for it). CI's `consumer-check` job builds it
against the checkout with `make ktor-demo-check` (`./gradlew -p demos/ktor/app -PlocalProxy build`, a composite
build, which lints it too; its `settings.gradle.kts` substitutes `:ktor-plugin` for `com.pambrose:prometheus-proxy-ktor`,
since the project's name isn't the artifact's), so a change to the plugin or to `EmbeddedAgentConfig` must keep it
compiling.
IntelliJ imports it as a second linked Gradle project (`.idea/gradle.xml`); its `gradle/wrapper/gradle-wrapper.properties`
is a symlink to the root's, so the IDE syncs it with the root's Gradle instead of generating a wrapper of its own.

The Spring Boot demo's roastery, `demos/spring-boot/app`, is a Maven project, a Spring Boot app in Java that embeds the
agent through the Spring Boot starter, with no agent code of its own: `application.yml` sets the starter's
`prometheus-proxy.agent.*` properties, and exposes only Actuator's health. Spring Boot's dependency management sets the
versions of some of the agent's libraries, older than the agent's own (`./demo.sh versions` compares them); `make
spring-boot-demo-check` (in CI's `consumer-check` job) builds it against the snapshot `consumer-check` publishes, the
starter's included, and runs its `RoasteryAgentTest`, which starts the agent against a proxy in the same JVM and scrapes
through it, so a change to the starter, or a Spring Boot release that breaks the agent at runtime, fails there. Its
`prometheus-proxy.version` is a release version literal; while the latest release predates what the demo shows,
`demo.sh`'s `NEEDS_RELEASE_AFTER` has `make up` build the library from the checkout.

The plain Kotlin demo's oven, `demos/plain-kotlin/app`, is a Gradle project of its own, like the coffee shop: a plain
Kotlin program with no framework or HTTP server that takes orders from a Redis queue (through Jedis, whose version is in
`gradle/libs.versions.toml` for it) and embeds the agent in its `main` with `embeddedAgent { }`, `fromEnvironment()`,
`source(path, registry)` of the Prometheus Java client's default registry, `agentMetricsAt(path)`, and
`startIfConfigured().use { }`. The website's Plain Kotlin Demo page shows that `main` between snippet markers, and its
Java tab is `src/test/java/website/PlainKotlinDemoJavaExample.java`, which `make consumer-check` compiles. Each
replica's paths are named after its container's host name, and Prometheus finds them through the proxy's service
discovery, so `demo.sh scale` adds and removes targets with no Prometheus change. CI's `consumer-check` job builds it
against the checkout with `make plain-kotlin-demo-check` (`./gradlew -p demos/plain-kotlin/app -PlocalProxy build`, a
composite build, which lints it too), so a change to `EmbeddedAgentConfig`, `MetricsSource`, or `EmbeddedAgentInfo` must
keep it compiling. IntelliJ links it as the coffee shop is, its wrapper properties a symlink to the root's.

The Spring Boot starter, `spring-boot-starter/`, is one of the build's two subprojects (`:spring-boot-starter`), in Java,
published alongside the agent at its version as `com.pambrose:prometheus-proxy-spring-boot-starter`: auto-configuration
(`io.prometheus.agent.spring`) that runs an embedded agent as a `SmartLifecycle` bean from the `prometheus-proxy.agent.*`
properties and serves the app's `PrometheusRegistry` bean in-process (the builder's `source(path, registry)`), starting
a required agent with `start()` and any other with `startIfConfigured()`. Its POM names no Spring
Boot artifact and imports no BOM, so an app's own Spring Boot is the one it runs with: the Spring Boot artifacts are
`compileOnly`, through the BOM at `spring-boot` in `libs.versions.toml`, and its JUnit 5 tests run the agent at Spring
Boot's versions, enforced as an app's dependency management enforces them (`./gradlew :spring-boot-starter:test`, part
of `build`), and again, in `oldestSpringBootTest`, which `test` runs, under the oldest Spring Boot it supports
(`spring-boot-oldest`, 4.0's last release), with kotlinx-coroutines and the Prometheus client set as the starter's page
tells such an app to set them. An embedded agent refuses to start on versions of those two it can't run on
(`RuntimeLibraries`), naming the Spring Boot property that sets each. Kover, detekt, and kotlinter don't cover it. Its `compileJava` runs in full and isn't cached,
since an incremental compile drops the configuration metadata's descriptions (`ConfigurationMetadataTest`), and the
docs site's Spring Boot Starter page shows a YAML file and a customizer from its tests, which `StarterExamplesTest` runs.

The Ktor plugin, `ktor-plugin/` (`:ktor-plugin`), in Kotlin, is the other, published alongside the agent at its version
as `com.pambrose:prometheus-proxy-ktor`: `install(PrometheusProxyAgent)` (`io.prometheus.agent.ktor`) runs an embedded
agent with a Ktor app and serves the `PrometheusRegistry` it's given in-process. Its settings are the
`prometheus-proxy.agent` section of the app's config file, read through Ktor's `ApplicationConfig` by the starter's
property names (`FileSettings`), then the install block's. `AgentLifecycle` starts the agent on `ApplicationStarted`,
begins its stop on `ApplicationStopPreparing` on a thread of its own, so the engine's grace period stays the app's, and
waits for it on `ApplicationStopping`; keep that split. A required agent starts with `start()`, and any other with
`startIfConfigured()`, which logs a failure itself. Its POM puts the agent and `ktor-server-core` at compile scope, since
its API names their types (the `PrometheusRegistry` it takes comes with the agent's), and `make consumer-check` resolves
it as a Ktor app does (the consumer check's `ktor` profile) and compiles the docs site's Ktor Plugin examples, which
`KtorPluginExamplesTest` runs.
It applies kotlinter and detekt (the root's `config/detekt/detekt.yml`), and the root aggregates its coverage
(`kover(project(":ktor-plugin"))`), so the coverage floors cover it; `./gradlew :ktor-plugin:test` runs its Kotest
specs, and so does `build`.

## Architecture

A **Prometheus Proxy** system enabling Prometheus to scrape metrics from endpoints behind firewalls.

### Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

### Core Components

Source lives under `src/main/kotlin/io/prometheus/{proxy,agent,common}/`. The proxy runs outside the firewall
alongside Prometheus, the agent runs inside it next to the monitored services, and `common/` holds what both
share (notably `BaseOptions`, the parent of `AgentOptions` / `ProxyOptions`, and `ConfigVals`, auto-generated
from HOCON via tscfg — see `make tsconfig`).

### Public API Surface (Dokka)

Only these types are part of the supported, documented public API. Everything else is `internal`:

- `io.prometheus.Agent` (entry point + companion `main` / `startSyncAgent` / `startAsyncAgent`, which takes a config
  file or URL, or a Typesafe `Config` built in memory; `typesafe-config` is therefore an `api` dependency)
- `io.prometheus.Proxy` (entry point + companion `main`)
- `io.prometheus.agent.AgentOptions` / `io.prometheus.proxy.ProxyOptions` / `io.prometheus.common.BaseOptions`
- `io.prometheus.agent.EmbeddedAgentConfig` and `embeddedAgent { }`: an embedded agent's settings, built in code with
  typed methods (from Java, with `EmbeddedAgentConfig.builder()`). It builds the same `Config` that
  `startAsyncAgent(Config)` takes.
  - `start()` starts the agent, and throws on a setting the agent rejects or a start that fails.
  - `startIfConfigured()` returns null in those cases instead, logging why, and also when no proxy is set.
  - `fromEnvironment()` reads `PROMETHEUS_PROXY`, `PROMETHEUS_AGENT_TOKEN`, `PROMETHEUS_PROXY_CERT`, and
    `PROMETHEUS_PROXY_AUTHORITY`, which nothing else reads. Its `internal` overload takes the lookup function, so
    tests can call it, as with `EnvVars`' parsing. The builder remembers which values it read, until code sets the same
    key, and `start()` checks them first with the agent's own parsers (`EnvironmentSetting`), so a value the agent
    refuses names its variable as well as its setting, and never the value.
  - `agentMetricsAt(path, labels)` sets `agent.metrics.proxyPath` and `agent.metrics.proxyPathLabels`, which a
    standalone agent sets in its config, or with `--metrics_proxy_path` / `METRICS_PROXY_PATH`
    (`AgentOptions.metricsProxyPath`). The agent serves its own metric families there in-process
    (`AgentMetricsSource`), and collects them (`Agent.collectsMetrics`) even when `isMetricsEnabled`, which controls
    the metrics port, is off. A standalone agent's path serves the rest of the default registry too, its JVM metrics,
    whose groups it registers itself with the port off (`registerJvmMetricGroups` in `Agent.kt`), since only the port's
    service does; an embedded agent's (`isEmbedded`) serves only its `agent_*` series, the JVM's being its host's.
- `io.prometheus.agent.MetricsSource` (a host's metrics, which the agent reads in-process for a path added with the
  builder's `source()`, in place of a URL; a source is code, so it reaches the agent in `AgentOptions.metricsSources`,
  not in the `Config` tree, and `AgentHttpService.fetchFromSource` reads it with a target's limits and filter, calling
  it in a scope of its own, so a call that ignores the timeout's interrupt holds only its own thread, and anything it
  throws but a `VirtualMachineError` fails only its scrape). `MetricsSource.of(registry)` and `source(path, registry)`
  serve a `PrometheusRegistry` (`RegistryMetricsSource`), which leaves out the agent's own families when it's the
  default registry and an agent collects them, so each series is served once; `prometheus-metrics-model` is therefore
  an `api` dependency, and the text formats an `implementation` one
- `io.prometheus.agent.EmbeddedAgentInfo` (handle returned by `Agent.startAsyncAgent`, with the agent's connection,
  `isConnected` and `proxyEndpoint`, read live for a host's health check; `AutoCloseable`, `close()` being `shutdown()`)
- `io.prometheus.common.EnvVars`
- `io.prometheus.common.ConfigLoadException` (thrown by `startAsyncAgent` and `startSyncAgent` when `exitOnMissingConfig` is false, and by `startAsyncAgent(Config)` always, on a config that can't be loaded or a setting the options or the Agent reject, with a config URL's credentials redacted from its whole cause chain, so embedded hosts can catch it instead of the JVM exiting)

When promoting a type from `internal` to `public`, also add a cross-reference to it in `docs/packages.md` (the Dokka `includes.from` file). When demoting, remove the link to avoid dangling references in the generated site.

### gRPC Service Definition

Defined in `src/main/proto/proxy_service.proto` — read it for the current RPC set. Note the defaults that
aren't in the proto: a scrape result is sent in chunks once its gzipped size reaches `chunkContentSizeKbs` (32 KB), the heartbeat fires every 5s during inactivity, and each stream sends a keepalive after `proxy.internal.streamKeepAliveSecs` (30s) of quiet, so a reverse proxy's idle timeout doesn't close it; the proxy tells the agent the interval for its response streams at registration (`RegisterAgentResponse.stream_keepalive_millis`). A side sends keepalives only once the other said at registration that it takes them (the agent's `stream_keepalives`, a nonzero interval from the proxy), since one predating them would take a keepalive for a scrape request or a result. Every wait that ends in a keepalive goes through `receiveCatchingWithin` (`common/Channels.kt`), a `select` with `onTimeout`, never a receive inside `withTimeoutOrNull`, which can take a channel's element as it times out and lose it.

### Key Mechanisms

- **Chunking**: Large metric payloads are split into `ChunkedScrapeResponse` messages to stay within gRPC limits. Configurable via `chunkContentSizeKbs`.
- **Stale agent cleanup**: `AgentContextCleanupService` evicts inactive agents after `maxAgentInactivitySecs` (default 60s).
- **Consolidated mode**: Multiple agents can register the same path for redundancy.
- **Embedded agent**: Agents can run inside other JVM apps via `startAsyncAgent()`. One started that way
  (`Agent.startEmbedded`, which `EmbeddedAgentConfig` uses too) sets `isEmbedded`, so it registers no JVM shutdown
  hook (common-utils' `registersShutDownHook`): its host stops it after the work that needs it, where the hook stopped
  it on SIGTERM alongside the host's own hooks, in no set order. The standalone agent keeps the hook.
- **Proxy failover**: The agent rotates through an ordered `agent.proxy.endpoints` list (`AgentGrpcService.advanceEndpoint` / `resetEndpoint`, chosen by `EndpointFailover.beforeAttempt`); a failed connect or a rejected registration advances, and only a connection that registered and then dropped retries from the head.
- **Path takeover**: a non-consolidated registration takes the path (`ProxyPathManager.takeOverPath`), and a displaced
  agent of another launch stays connected. The proxy tells it on `readPathNoticesFromProxy`
  (`AgentContext.queuePathNotice`); it keeps the path, its `PathEntry` in `AgentPathManager.paths` carrying the
  takeover and shown `retrying`, and registers it again only with `reclaim`, which `ProxyPathManager.reclaimRejection`
  refuses while a live agent of another launch serves it. The path entry's `waiting` list gets a "free" notice when the
  path is dropped, so the reclaim comes at once. A reconnect keeps the takeover (`AgentPathManager.clear`), and
  `registerPaths` leaves such a path to the reclaim task. A discovery edit registers its path again as a reclaim too,
  in place for an exclusive path, and a notice that comes while the proxy answers a registration waits for the answer
  (`AgentPathManager.registering`), as the two come on different streams. Never register a taken-over path without
  `reclaim`: that brings back two agents swapping the path every few seconds (`ProxyRegistry.tla`'s
  `ReclaimNeverDisplaces` and `ReconnectReclaims`).
- **Dynamic target discovery**: `PathDiscoveryService` polls a watched file and calls `AgentPathManager.reconcileDiscoveredPaths`; paths are tagged `STATIC`/`DISCOVERED`, and static entries are never touched.
- **Per-agent auth**: `AgentAuthManager` + `AgentAuthServerInterceptor` resolve `proxy.auth` identity tokens and enforce path globs on `registerPath`; legacy `proxy.agentToken` maps to an allow-all identity. `registerPath` runs `ProxyPathManager.formRejection` (length, form, labels size) before the globs see a path, and `AgentAuthManager.globMatches` matches in time proportional to the path's length; keep both, since a regex or the other order lets one authenticated agent stall the proxy with a long path. With `transportFilterDisabled`, where the agent names its own agentId on every call, `readRequestsFromProxy` looks the context up once and removes only that object (`Proxy.removeAgentContextIfCurrent`), and `AgentContextManager.addAgentContextWithinLimit` caps the contexts one identity holds at `proxy.internal.maxAgentsPerIdentity`.
- **Agent-supplied values**: the proxy cuts what an agent reports about itself (`ProxyServiceImpl.boundedRegistration`, `proxy.internal.maxAgentFieldLength` / `maxAgentProxyEndpoints`) and about each path (`ProxyPathManager.addPath`: the target URL, redacted before it's cut, to `maxTargetUrlLength`), and logs every agent-supplied value through `AgentValues.shownValue`, which cuts it and escapes its control characters so it can't forge a log line (`ProxyPathManager.shownPath` for a path or `agentId`, its first 64 characters); bound or cut any new agent-supplied value before storing it, and pass it through `shownValue` before logging it. `formRejection` refuses a path with whitespace, a control character, `?`, or `#` (`AgentValues.pathCharacterProblem`), which the agent checks on its `pathConfigs` at startup too.
- **Shared metrics**: `AgentMetrics` / `ProxyMetrics` build their collectors once per JVM in their companion objects and share them through `SharedCollectors`, since the default registry rejects a second collector with the same name and label names, which kept a second agent in a JVM from connecting. Add a new metric to its companion's `SHARED` list (the agent's `COLLECTORS`, whose names `agent.metrics.proxyPath` serves and a registry source of the default registry leaves out); an agent metric takes `launch_id` as its first label and goes in `PER_LAUNCH`, which `COLLECTORS` includes, so a stopped agent's series are removed (and, given the label values, start at zero).
- **TLS material**: each certificate, key, or trust collection is a file (`tls.*FilePath`) or PEM text or its base64
  (`tls.*Pem`, `*_PEM` env vars, no CLI flags), never both (`TlsSource.requireOneSource`); `TlsMaterial` builds the
  gRPC TLS context from either with `GrpcSslContexts`. Never log a PEM value, only whether it's set.
- **Tracing**: `ScrapeTracing` (common) is each process's Brave tracing, owned by `Proxy.tracing` / `Agent.tracing`
  and built on first use (the reporter is assigned after the gRPC server and channel are built). It samples gRPC calls
  with an `RpcRuleSampler` that never samples `sendHeartBeat`, and traces each scrape: the proxy's `scrape` span
  (`ProxyHttpRoutes`) and a `forward` span per agent (`ScrapeDispatch`), whose context rides `ScrapeRequest`'s
  `trace_context` to the agent's `fetch` span (`AgentHttpService`). Each span's lifecycle goes through
  `ScrapeTracing.traced`, which tags how its block ended and finishes it. Pass spans explicitly, never put them in
  scope: Brave's current span is thread-local, which coroutines break.
- **Metric filtering**: `MetricFilter` (per-path `agent.filters`) drops whole metric families in `AgentHttpService` before gzip/chunking; fails open on non-text or non-UTF-8 payloads.
- **Dashboard**: Ktor + htmx (WebJar, no CDN) on its own port, fed by the `ProxyEvent` bus; see `proxy/dashboard/`.
  What a dashboard shares, its server, login, guards, versioned assets, sessions, and push loop, is
  `common/dashboard/DashboardServer`, which serves a `DashboardSite`: the proxy's is in `ProxyDashboardService`, and the
  agent's (`agent.dashboard`, off by default) in `agent/dashboard/AgentDashboardService`, which reads the agent's
  connection, path registration failures, filters, discovery, and each path's recent scrapes (`AgentScrapeHistory`,
  filled by `AgentHttpService.fetchScrapeUrl`) through `AgentSnapshot`, serves the agent's own Scrape now
  (`AgentScrapeNow`), and shows an Activity feed (`AgentActivityLog`, recorded where the agent logs each event at WARN
  or INFO, never a DEBUG repeat, and whose changes wake the push loop). `DashboardPage`, `DashboardScript`, and
  `DashboardCss` are the page, script, and stylesheet both render with, and `RowFilter` and `TableText` the filtered
  tables of both Activity views and the proxy's Paths view.
  The Links column's brand icons (`LinkIcon`) come from the Simple Icons WebJar, of which the build's
  `extractDashboardIcons` copies only the SVGs `LinkIcon` names into the main resources, so the 7.5 MB WebJar stays off
  the runtime classpath and out of the POM and the fat JARs; a new icon needs its slug in `configureDashboardIcons` too.

Two load-bearing constraint sets live in `.claude/rules/` (Claude Code loads them when you touch the matching files; other agents should read them first): `dashboard-constraints.md` (dashboard live-region, contrast, and agent-label invariants) and `shadow-jar.md` (fat-JAR service-file merging).

## Configuration

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file → built-in defaults. Reference schema: `config/config.conf`. Example configs in `examples/`.

The `ConfigVals` class is auto-generated from the HOCON schema using tscfg (`make tsconfig`).

`AgentOptions` and `ProxyOptions` resolve each option through `BaseOptions`' `resolveIntOption` /
`resolveLongOption` / `resolveStringOption` (where -1 or an empty string marks a CLI value as unset) and
`resolveBooleanOption` (which checks whether the flag was given); resolve a new option the same way, inside the
per-section `assign*` function it belongs to.

## Build Version

`group` and `version` live in `gradle.properties` (single source of truth). The version can be overridden on the command line for CI snapshot publishing:

```bash
./gradlew build -PoverrideVersion=4.0.1-SNAPSHOT
```

`-PoverrideVersion` keeps its `override` prefix because it intentionally only applies when supplied (so the `gradle.properties` default is never accidentally cleared).

`BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` are the time of the commit the build is from (`git log -1 --format=%ct`, read by a `ValueSource`; the current time without git), and are not overridable. Every build of a commit therefore makes the same `BuildConfig`, so main isn't recompiled on each Gradle invocation, the configuration cache is reused, and compiled classes and test results can come from the build cache; a release shows the release commit's date. Stamped with the current time, as they once were, they defeated all three.

Releases follow `docs/RELEASE.md`: after the version bump and changelogs are merged, `make release` publishes to Maven
Central and Docker Hub, gated by `make release-check` (clean, up-to-date `master` on which every check, and each CI
job `RELEASE_REQUIRED_JOBS` lists, passed; a dated section for the version in both changelogs; the version, and not
the previous one, in every bumped file; every demo's `NEEDS_RELEASE_AFTER` below the version; not already
published). `RELEASE_VERSION_FILES` in the `Makefile` mirrors step 2's list of hard-coded version literals; keep
the two in step. Step 2 raises each demo's `NEEDS_RELEASE_AFTER` to the new version, so `make up` builds from the
checkout while the release isn't published, and the last change before `make release` lowers it again
(`_check-demos-pull-release`), so `make up` from the release's tag pulls its images.

`make release` ends by pushing the release tag (`make release-tag`), which the docs' `git clone --branch` commands
check out, and deploying the docs site (`make gh-docs`, a `workflow_dispatch` of `docs.yml`), the only run
that deploys it: a push to `master` builds the site and the KDocs as a check, without deploying, so the live site
describes the last release until the next one, docs changes merged in between included (`CiHygieneTest` pins this).
`make gh-docs` by hand deploys `master` as it is, unreleased pages included. `make site-version` prints the version the
site shows as its image tag and Maven coordinate, the one step 2 bumps, which `bin/docker-*.sh` and the demos run; not
`gradle.properties`, which can hold a `-SNAPSHOT` (`SiteVersionTest`). CI's `docs-site` job
(`make build-site`) builds the site on every pull request; it's named so as not to share a name with the `build`
check the branch protection requires.

## Testing

- **Coverage**: kotlinx-kover. HTML report: `./gradlew koverHtmlReport`. XML report (CI): `./gradlew koverXmlReport`. Console summary: `./gradlew koverLog` (also runs after `koverXmlReport` / `koverVerify` via `onCheck = true`). Generated gRPC stubs, `BuildConfig`, and `ConfigVals` are excluded from report statistics (configured in `build.gradle.kts` `configureCoverage()`). `koverVerify` enforces the total line and branch floors; `koverVerifyPerClass` (the `perClass` report variant) enforces a per-class line floor that skips compiler-generated lambda classes and the `Proxy`/`Agent` entry points, so a new class needs its own tests. Both floors run in one invocation (`./gradlew koverVerify koverVerifyPerClass`, as in CI and `make coverage-verify`); keep Kover at 0.9.10 or later, since 0.9.9 leaked one report variant's filters into another built in the same run.
- **JVMs**: main code compiles for Java 17 (`jvm` in `libs.versions.toml`), the floor the published artifact supports; the `Test` tasks run on a Java 25 launcher (`testJvm`), the runtime the Docker images ship. The images copy the JRE out of `eclipse-temurin:25-jre-noble` onto `gcr.io/distroless/base-nossl-debian13` (glibc, no shell or package manager), so the required `image-scan` check sees only a few Debian packages; a step that needs a shell goes in the Temurin stage. Keep the `-noble` tag: the images also copy its C.UTF-8 locale, which has to load under Debian 13's glibc (`ContainersPackagingTest` checks the JVM's encodings). The foojay resolver provisions either toolchain when it isn't installed.

### Test Structure

Integration tests in `src/test/kotlin/io/prometheus/harness/`:
- `InProcessTest*` — uses gRPC in-process server (no network I/O, faster)
- `NettyTest*` — tests over actual network transport
- `TlsNoMutualAuthTest` / `TlsWithMutualAuthTest` — TLS communication tests
- `support/HarnessSetup.kt` — base class that sets up proxy+agent in test mode; it clears the default registry before each spec and has common-utils' `SystemMetrics` forget it (`SystemMetrics.forget`), so every spec's proxy and agent serve the JVM metric groups

A run of every spec splits them between two Test tasks Gradle runs at once, each in its own JVM
(`configureTestPartitions` in `build.gradle.kts`): `harnessTest`, the `harness/` specs, which bind fixed ports and so run
one at a time, and `unitTest`, the rest; `test` only depends on both. A spec that starts a proxy and agent through
`HarnessSetup` or `TestUtils`, or binds a `HARNESS_*` port, goes in `harness/` (`TestPortsTest` checks). A run with
`--tests` or `-x test` isn't split. To recompute coverage from the last run without rerunning it, exclude both
partitions: `./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`.

Container tests in `src/test/kotlin/io/prometheus/containers/` — a full Testcontainers suite that builds the proxy and agent images from `etc/docker/*.Dockerfile`, stands them up alongside an `nginx:1.31-alpine` metrics stub and a `prom/prometheus:v3.14.0` container, and verifies the full Prometheus → proxy → agent → endpoint scrape path. Shared container/network/HTTP/PromQL factories live in `support/ContainerTestSupport.kt`; `ls` that directory
for the current spec list.

All container specs require Docker and are gated on `RUN_CONTAINER_TESTS=true` (set automatically by `make container-tests` / `make scaling-tests`). Default `./gradlew test` registers placeholders marked SKIPPED. With it set, the test task builds the fat JARs first, and a run that names only container specs with `--tests` runs three specs at a time (`support/ContainerTestsConfig.kt`, selected in `build.gradle.kts`), so a container spec must keep to its own containers and network and share nothing else in the JVM.

Lincheck concurrency specs (`*LincheckTest`) carry the Kotest `Lincheck` tag (`common/Lincheck.kt`) and take minutes, so the `Test` task's default `kotest.tags` expression (`!Lincheck`) leaves them out of `./gradlew test` and the CI build; run them with `make lincheck-tests` (`-PkotestTags=Lincheck`) or the manual **Lincheck** workflow (`.github/workflows/lincheck.yml`). Tag any new Lincheck spec the same way. Lincheck records an exception thrown inside an operation as that operation's result, not a failure, so a spec's checks must be reported through its `@Validate` function; and keep MockK out of Lincheck specs: its inline mocking rewrites `Object`'s methods JVM-wide, so later specs in the same JVM fail with `Non-determinism found` (see `docs/TESTING.md`).

TLA+ specs in `specs/tla/` model the design rather than test the code: `ProxyRegistry.tla` covers `AgentContextManager`, `AgentContext`'s invalidate and drain, `ProxyPathManager`'s register and remove, `ScrapeRequestManager`, and `Proxy.removeAgentContext`; `AgentFailover.tla` covers `EndpointFailover`. When you change the locking, ordering, or lifecycle of one of those, update the matching action and run `make tla-checks` (about a minute; it fetches the pinned TLA+ tools into `specs/tla/`, git-ignored, and checks their SHA-256), which CI's `tla` job runs on every pull request; it runs `make tla-checks-full`, which adds the slower `ProxyRegistryReconnect.cfg`, instead when a file under `specs/tla/` or the pinned TLA+ tools (`TLA_VERSION`, `TLA_SHA256`) change, as TLC reads nothing else. Run `make tla-checks-full` locally when you change how a reconnect treats a taken-over path. `specs/tla/README.md` lists the properties.

What embedders get from Maven Central is checked by a Maven build, not a Gradle one: `make consumer-check` (CI's `consumer-check` job, which `RELEASE_REQUIRED_JOBS` lists) publishes to the local Maven repository at a throwaway `-consumer-check-SNAPSHOT` version and builds `etc/consumer-check` against it. It fails unless the website's embedded-agent examples compile, Maven resolves Jackson, every netty-tcnative native, Netty, protobuf, and every Prometheus Java client module at the catalog's versions, and no jar carries a `logback.xml`; it resolves and compiles the examples as a project that depends only on the agent. So a type a public class exposes goes in `api(...)`, a pin an embedder needs is declared directly (Maven ignores a dependency's BOM imports, and a classifier jar is its own artifact), and a resource only the fat JARs need goes in `src/shadow/resources`, as `logback.xml` does.

Specs in `src/test/kotlin/io/prometheus/misc/` check the docs and CI against the code, so a change can fail them without touching a test: `TestingGuideTest` fails when a spec isn't named in its package's section of `docs/TESTING.md` or a support file isn't named there at all, so add each new one; `WebsiteConfigKeysTest` and `DashboardMetricNamesTest` check the `proxy.`/`agent.` config keys, table defaults, and `proxy_*`/`agent_*` series the website names against `config/config.conf` and the exposed metrics; `ConfigFileKeysTest` fails on a setting in a shipped or test config (`config/test-configs`, `examples`, …), or a `-D` key a test passes in a string literal, that `config/config.conf` doesn't declare; `CiHygieneTest` requires a `timeout-minutes` on every workflow job, a digest on every `*_IMAGE` in the `Makefile` and on every third-party `*_IMAGE` in `ContainerTestSupport`, a pinned uv `version` on every `setup-uv` step, and a fixed commit for the CRD schemas; and `SiteVersionTest` pins `make site-version`, which `docs.yml`'s deploy check and `bin/docker-*.sh` read.

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`. Shared test constants live in `src/test/kotlin/io/prometheus/common/TestPorts.kt` (`TestPorts` object) — canonical proxy/agent/Prometheus/nginx port numbers used across the unit, harness, and container suites; reference these instead of hard-coding port literals in new tests. The constants that equal a product default (8080, 8082, 50051, …) are for asserting those defaults and for ports inside containers; never bind one on the host, since a proxy, agent, or Prometheus already running on the machine holds it. Each harness spec's dedicated ports live there too — `TestUtils.startProxy` / `startAgent` bind the `HARNESS_*` ones unless a spec passes its own — and `TestPortsTest` fails on a duplicate, on a port of 32768 or above (the OS's ephemeral range, where another test's outgoing connection can hold it), or on a port a demo publishes on the host (a `demos/*/docker/compose.yml` port, a kind `hostPort`, or a `demo.sh` port default; the product-default constants are exempt, by name), so give a new spec its own entries below that, and move a spec's port rather than a demo's.

`EnvVars.getEnv()` reads `java.lang.System.getenv()`, which can't be set in-process, so its parse-and-throw branches aren't reachable by setting an env var in a test. The numeric/boolean parsing is therefore extracted into `internal` companion helpers (`parseBooleanStrict` / `parseIntStrict` / `parseLongStrict`) that the tests call directly. When adding a new typed `getEnv` overload, follow this pattern so the invalid-value path stays testable.

## Code Style

Formatting is pinned in `.editorconfig` and enforced by kotlinter + detekt (`config/detekt/detekt.yml`) — don't
restate those rules here. Beyond them: mimic existing code patterns in nearby files.
