# Testing Guide

This document describes the test suite structure and how to run tests for the prometheus-proxy project.

## Running Tests

```bash
# Run all checks (lint + tests), rerunning the tests even when nothing they read changed
./gradlew check unitTest --rerun harnessTest --rerun

# Run all tests only: the harness specs and the rest, in two JVMs at once (see below)
./gradlew test

# Run specific test class
./gradlew test --tests "ProxyServiceImplTest"

# Run one spec outside the harness, or the harness specs alone, in their partition's JVM
./gradlew unitTest --tests "io.prometheus.misc.TestingGuideTest"
./gradlew harnessTest

# Run a single test method
./gradlew test --tests "io.prometheus.proxy.ProxyServiceImplTest.someTestMethod"

# Run tests matching a pattern
./gradlew test --tests "*PathManager*"

# Run tests with coverage report
./gradlew koverHtmlReport
```

A run of every spec splits them between two Test tasks that Gradle runs at the same time, each in a JVM of its own:
`harnessTest`, the specs in `harness/`, which bind fixed ports (`TestPorts`' `HARNESS_*` ports and their own) and so
run one at a time, and `unitTest`, all the others. `test` runs only those two, so `./gradlew test`, `check`, `build`,
and the Kover tasks still run every spec; each task's reports are under `build/reports/tests/<task>` and
`build/test-results/<task>`. A spec that starts a proxy and an agent through `HarnessSetup` or `TestUtils`, or binds a
`HARNESS_*` port, belongs in `harness/`, which `TestPortsTest` checks, or it could collide with a harness spec running
alongside it. A run that names specs with `--tests`, IntelliJ's among them, or excludes `test` with `-x test`, isn't
split: `test` runs the specs it names in one JVM, as before, whether the command line gives `--tests X` or
`--tests=X`. Either partition can still be named, filtered or not: `./gradlew unitTest --tests …` runs only the specs
it names outside the harness. Only the harness JVM takes a harness profile's heap (`-PharnessConfig`); `unitTest` keeps
1g, since only the harness specs read the profile. To recompute coverage from the last run without rerunning it,
exclude both partitions: `./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`.

### Test Inputs

Gradle reruns a `Test` task when its classpath changes, not when a file its specs open does, so the files the specs
read are inputs of the tasks that run them (`testConfigFiles()` and `checkedFiles()` in `build.gradle.kts`). Without
them, a change that edited only a workflow, the `Makefile`, or this document passed on the result of the run before
it, up to date, or from the build cache after a clean. Every `Test` task takes the configs in `config/test-configs`
and the certificates in `testing`. `unitTest` (and `test`) also takes what the specs that check the docs, CI, and demos
read: the top-level `Makefile`, `README.md`, `AGENTS.md`, changelogs, `llms.txt`, `gradle.properties`, and
`settings.gradle.kts`; `config/config.conf`; `.github`; `docs` (not the code reviews); the docs site's pages and
`zensical.toml`; `demos` (not what a demo's build or `demo.sh` writes); `examples`, `etc`, `grafana`, `bin`, `logback`,
and `src/shadow/resources`; and the text of the sources, main and test, both subprojects' included, and of the docs
site's snippet files beside the test sources. An edit to any of them reruns `unitTest`, but not the harness specs. A
spec that reads a file outside those needs it added there.

### Make Targets

```bash
make tests            # All checks (lint + tests), rerunning the tests; the container tests are SKIPPED
make medium-tests     # `make tests` with the MEDIUM harness profile (a load run; the default is MINI, as in CI)
make xxl-tests        # `make tests` with the XXLARGE harness profile (heaviest; 16g for the harness JVM)
make nh-tests         # Every spec outside the harness (`./gradlew unitTest`)
make ip-tests         # In-process integration tests only
make netty-tests      # Netty integration tests only
make tls-tests        # TLS integration tests only
make lincheck-tests   # Lincheck concurrency tests only (left out of every other target but all-tests)
make tla-checks       # Model-check the TLA+ specs in specs/tla with their quick configs, as CI does
make tla-checks-full  # The quick TLA+ checks plus the slower reconnect config
make container-tests  # Full Testcontainers end-to-end suite (needs Docker)
make scaling-tests    # Parameter-driven scaling container test only (needs Docker)
make all-tests        # Full suite: `make tests` + `make lincheck-tests` + `make container-tests` +
                      #   `make tla-checks-full` (container-tests already includes ContainersScalingTest's
                      #   default table)
make consumer-check   # Publish to the local Maven repository at a throwaway -consumer-check-SNAPSHOT
                      #   version, then build etc/consumer-check, a Maven project that embeds the agent
                      #   (see below); needs mvn
make spring-boot-demo-check     # Build and test the Spring Boot demo's roastery against the same artifact,
                                #   at Spring Boot's versions (CI's consumer-check job); needs mvn
make ktor-demo-check  # Build the Ktor demo's coffee shop against this checkout (CI's consumer-check job)
make plain-kotlin-demo-check    # Build the plain Kotlin demo's oven against this checkout (CI's consumer-check job)
make ci               # The CI workflow's jobs, run in turn: TLA+, alerting rules (`make check-rules`), Helm
                      #   charts (`make helm-lint`), the docs site (`make build-site`), the consumer check and
                      #   the demos built against it, and the build job (`make ci-build`: MINI tests +
                      #   coverage floors); needs Docker
```

The suite builds its proxy and agent images under stable tags, so runs reuse them and a clean run now
leaves nothing behind. They were previously built under a random tag that Testcontainers' shutdown hook
deleted on exit — which is what stranded the bytes, since dropping the tag orphans the image content as a
dangling ~591MB layer set that nothing prunes. A killed or timed-out run still leaks its containers,
networks and any random tags, because that hook never fires. Two targets reclaim that disk:

```bash
make docker-clean-dry  # Preview what would be reclaimed; removes nothing
make docker-clean      # Reclaim it (ARGS="--all" also prunes the build cache, base and built images)
```

`docker-clean` keeps the suite's own images by default so runs stay warm; pass `ARGS="--built"` to remove
them and force a cold rebuild. It refuses to run while a test run is in flight, since removing those
images would break it. It matches only Testcontainers' own images, labels and dangling images, so
unrelated images on the host are unreachable from its selectors — see the comment block in
`bin/docker-clean-tests.sh`.

#### Scaling Presets

For dev/stress work there are curated presets that each delegate to `scaling-tests` with a `SCALE_*` combo that
hammers a different part of the system. They are **not** run by `all-tests` or CI:

```bash
make scaling-paths         # 2000 paths across 4 agents (routing-table stress)
make scaling-agents        # 40 agents x 2 endpoints (gRPC connection stress)
make scaling-payload       # 16 paths x 50k series (chunking/gzip stress)
make scaling-consolidated  # 25-agent consolidated fan-out (response-merge stress)
make scaling-concurrency   # 1800 paths, 150 concurrent (scrape-correlation stress)
make scaling-soak          # every dimension at once (broad mixed-load soak)
make all-scaling           # run all of the presets above in sequence
```

Container count is roughly `2*agents + 2*consolidated + 1`, so many-agents presets stress Docker/the host
while many-endpoints presets stress the proxy cheaply.

The container tests (`io.prometheus.containers.*`) are gated on `RUN_CONTAINER_TESTS=true` and
need Docker, so a plain `make tests` / `./gradlew check` registers each spec as a SKIPPED placeholder. Use
`make container-tests` to run the whole suite on its own, `make scaling-tests` for just the scaling spec, or
`make all-tests` to run everything in one shot.

The Lincheck specs (`*LincheckTest`) explore thread interleavings and together take several minutes, so they carry
the Kotest `Lincheck` tag (`common/Lincheck.kt`), and the `Test` task's default tag expression, `!Lincheck`, leaves
them out of `./gradlew test`, `make tests`, and the CI build. `make lincheck-tests` runs them
(`-PkotestTags=Lincheck`), and so do `make all-tests` and the manual **Lincheck** workflow in GitHub Actions; tag any
new Lincheck spec the same way. The TLA+ specs are not tests but models of the design, checked by `make tla-checks`,
which CI runs on every pull request; `specs/tla/README.md` describes them.

Keep MockK out of the Lincheck specs. To mock a final class, MockK rewrites the bytecode of that class and its
superclasses, `Object`'s methods included, for the rest of the JVM, and routes every `equals()` and exception
constructor through a lookup keyed by identity hash code. Every spec that runs after it in the same JVM then behaves
differently when Lincheck replays an interleaving to record its trace, and Lincheck fails with
`IllegalStateException: Non-determinism found` instead of a result. That is how `AgentContextLincheckTest` failed
now and then under `make lincheck-tests` while passing on its own, until `HttpClientCacheLincheckTest`, which ran
before it, swapped its MockK clients for real ones on a recording engine. If "Non-determinism found" comes back,
look in the trace for something the replay can't reproduce -- MockK's `JvmMockKProxyAdvice` was the tell -- rather
than rerunning. Treat any other Lincheck failure -- `Validation function ... has failed`, `Invalid execution
results`, or `The execution has hung` on its own -- as a finding: read the reported interleaving before rerunning
anything. Two were real bugs, the `agent_backlog_full` mislabel and the HTTP client leak fixed in 4.1.0; others were
the harness itself, such as a log statement or a suspending `Mutex` wait that Lincheck mistook for a hang, and the
comments in the specs record each of those. Don't make the specs retry on failure, which could one day hide a real
one.

The `make scaling-tests` target forwards any `SCALE_*` environment variables to the test, so the scaling
inputs can be tuned without recompiling — for example:

```bash
make scaling-tests SCALE_AGENTS=10 SCALE_ENDPOINTS_PER_AGENT=20 SCALE_CONSOLIDATED_AGENTS=3 \
                   SCALE_SERIES_PER_ENDPOINT=2000 SCALE_CONCURRENT=true
```

Large runs hold many scrape bodies in memory at once. The forked test JVM defaults to a 1g heap; raise it with
`TEST_MAX_HEAP_SIZE` (or `-PtestMaxHeapSize`), and cap the number of in-flight verification coroutines with
`SCALE_CONCURRENCY_LIMIT` so memory stays bounded even at high path counts:

```bash
make scaling-tests SCALE_AGENTS=100 SCALE_ENDPOINTS_PER_AGENT=200 SCALE_SERIES_PER_ENDPOINT=5000 \
                   SCALE_CONCURRENT=true SCALE_CONCURRENCY_LIMIT=64 TEST_MAX_HEAP_SIZE=8g
```

## Frameworks and Libraries

- **Kotest** (StringSpec style) — primary test framework with JUnit 5 runner
- **Kotest matchers** — assertions (`shouldBe`, `shouldNotBeNull`, `shouldContain`, etc.)
- **MockK** — mocking library (`mockk`, `every`, `verify`)
- **Lincheck** — concurrency testing by model checking and stress (`*LincheckTest`, run by `make lincheck-tests`)
- **Kotlin Coroutines** — async and concurrency testing (suspending test bodies)
- **Ktor** (client & server) — HTTP testing utilities
- **gRPC in-process transport** — integration tests without network I/O
- **Kover** — code coverage

## Test Structure

Tests are located in `src/test/kotlin/io/prometheus/` and organized by component:

```
src/test/kotlin/io/prometheus/
├── agent/          # Agent component tests
│   ├── discovery/  # Path-discovery tests
│   └── filter/     # Metric-filter tests
├── common/         # Shared utility and metrics tests, plus the test-only support most specs share
├── proxy/          # Proxy component tests
│   └── dashboard/  # Dashboard renderer tests and fixtures
├── misc/           # Cross-cutting tests (config classes, options, packaging, CI, the docs' names)
├── harness/        # Integration tests: real proxy + agent, in-process or over Netty; scale configs
│   └── support/    # Harness infrastructure (setup, the standard suite)
└── containers/     # Testcontainers end-to-end tests, gated on RUN_CONTAINER_TESTS
    └── support/    # Container factories and the loopback port-binding hook
```

Directories rather than filenames on purpose: a file-by-file listing here duplicates the Test Categories
section below and rots on the next added spec, which is how this document came to be missing eighteen of
them at once. The categories below name every spec and say what it pins, which is upkeep that buys
something, and `misc/TestingGuideTest` fails the build when a spec isn't named in its package's section or a
support file isn't named at all.

Outside `io/prometheus/`, `src/test/kotlin/website/` and `src/test/java/website/` hold the docs site's code
snippets, which `website/prometheus-proxy/zensical.toml` resolves by file name. Most are `.txt` files of marked
regions; **EmbeddedAgentKotlinExample** and **EmbeddedAgentJavaExample** are the embedded-agent examples on the
Embedded Agent page, and **EmbeddedAgentConfigKotlinExample** and **EmbeddedAgentConfigJavaExample** the ones that
start an agent from an in-memory config, on that page and the Cloud Platforms page, compiled with the tests so that a
change to `Agent.startAsyncAgent()` that breaks them fails the build. **EmbeddedAgentSourceKotlinExample** and
**EmbeddedAgentSourceJavaExample** are a worker that has the agent serve a registry and the agent's own metrics
in-process, on the Embedded Agent page; `MetricsSourceTest` runs both. **PlainKotlinDemoJavaExample** is the plain
Kotlin demo's oven's `main` in Java, the Plain Kotlin Demo page's Java tab, which is compiled, not run: its oven and
queue are stand-ins. **EmbeddedAgentShutdownKotlinExample** and **EmbeddedAgentShutdownJavaExample** are a program that
runs until it's stopped, with the shutdown hook that ends its work before `use` stops the agent, on the Embedded Agent
page; they're compiled, not run, since a test can't send its JVM a SIGTERM, and their worker is a stand-in.
**KtorHostExample**, in `src/test/kotlin/hosts/`, is the Cloud Platforms page's Ktor module that starts and stops the
agent; it lives outside `website/` because it needs a Ktor server, which the build below doesn't give a host.

The tests compile those examples against the project's own classpath, which a build that embeds the agent doesn't
get: it gets what the published POM gives it, resolved by its own build tool. `make consumer-check`, which CI's
`consumer-check` job runs, covers that. It publishes this checkout to the local Maven repository and has
`etc/consumer-check/check.sh` build `etc/consumer-check`, a Maven project that depends on that artifact alone, so the
resolution checks and the examples' compile see what an application that depends only on the agent gets. The check
fails unless the examples compile there (a public type's supertype at runtime scope broke the Java one, and the
`PrometheusRegistry` the builder's `source()` takes has to be at compile scope), Maven resolves Jackson, every
netty-tcnative native, Netty, protobuf, and every module of the Prometheus Java client at the versions
`gradle/libs.versions.toml` pins, the registry model and the text formats among them, Logback isn't resolved, for the
agent alone, a Ktor app with the Ktor plugin, or a Spring Boot app with the starter (each would displace the host
application's own SLF4J provider), and no jar on the classpath carries a `logback.xml`, which would replace the host
application's logging config.

## Test Categories

### Unit Tests — Agent (`agent/`)

- **AgentTest** — Agent main class lifecycle, heartbeats, reconnection, scrape request processing; when one
  connection task ends, the others are cancelled, so an idle read stream cannot hold a dead connection open; and a
  gRPC shutdown that throws (a MockK constructor mock) still stops the admin and metrics servers and closes the client
  cache; and a scrape result is counted under its type unless the proxy's dashboard asked for its scrape, which
  `agent_dashboard_scrapes_total` counts instead; and a call the proxy refuses with `UNAUTHENTICATED` after a connect
  logged at ERROR, naming where the token is set, and another status at INFO, as before; a connection task's JVM
  `Error` failing the connection's scope, which stops the agent, rather than ending the connection quietly; and a
  connection's results it can't send counted as dropped
- **AgentStartupTest** — a start that fails or takes too long releases what the agent opened: a dashboard that fails
  to start, on a port the spec holds, stops the admin, metrics, and Zipkin services and removes the JVM shutdown hook,
  through `startSync()` and through `startAsyncAgent(Config)`, and a release step that throws (a MockK constructor
  mock) is attached to the failure while the rest still run; and a start that times out, its dashboard's start held
  by a MockK constructor mock, throws at once, then stops without connecting as soon as its startup ends; and an
  embedded agent's service thread a daemon, which can't keep its host's JVM up, even in a start that never ends, and a
  directly started agent's not
- **AgentGrpcServiceTest** — gRPC service: scrape request/response streaming, heartbeats, registration, error handling;
  a connect counted once the proxy registered the agent, a refused registration as rejected; each endpoint advance and
  each heartbeat sent counted, by outcome; a scrape request's wait for its scrape recorded, not a dashboard's; and a
  result counted as sent once a writer takes it, and as dropped when the stream fails first, the results the producer
  hadn't forwarded included, with no ERROR logged when the other writer is the one that failed; a JVM `Error` in a
  heartbeat or a response stream propagated, not taken for a transient failure; a connect after failed ones dialing an
  in-process proxy started meanwhile at once, rather than waiting out gRPC's backoff, and the channel's backoff reset
  only after a failed connect;
  channel shutdown; with Zipkin on, the channel is built before `zipkinReporterService` is assigned, and every channel a
  reconnect builds traces through one `Tracing`; a repeated connect failure logs one WARN, compared by gRPC status code
  (or class), including against a port that accepts TCP but never answers HTTP/2; a connect the proxy refuses with
  `RESOURCE_EXHAUSTED` is a failed connect, which the agent retries; each result is counted under its type (non-gzipped,
  gzipped, chunked, or dropped) through `Agent.updateScrapeResultCounter`; and for the activity feed, a connect
  recorded, and a failure only when it is new or changes, and failing over from the primary and back recorded, but
  cycling on through the backups not; a failure recorded again after a connect elsewhere, as after a failback to a
  primary still down; and a failure whose status runs on, as nginx's 504 page does, shown as its first line; a connect
  the proxy refuses with `UNAUTHENTICATED` logged at ERROR, without a stack trace, saying the proxy refused the agent's
  token, or that it sends none, and naming where the token is set, its repeats at DEBUG, again at ERROR every 10
  minutes and after a connect since, when the activity feed records it once more
- **AgentGrpcStreamRetentionTest** — a chunk the proxy has read is no longer reachable while the agent's chunked
  stream stays open, checked with weak references after 2 MiB of 1 KiB chunks. Over Netty, gRPC's retry buffer
  commits past its 1 MiB per-RPC limit and lets go; over the in-process transport, which reports no message sizes,
  it never committed, so the agent kept every chunk until it builds an in-process channel without retry (November
  #43, found while fixing #41)
- **AgentHttpServiceTest** — HTTP service: scraping endpoints, compression support, response handling, timeout behavior;
  a retry of Prometheus's scrape counted and a dashboard's not; a scrape a filter lets through unfiltered counted by why;
  error cases; a scrape the proxy's dashboard asked for is counted only in `agent_dashboard_scrapes_total`, however it
  ends, isn't timed, and has its results marked, while the same scrape from Prometheus is counted and timed; a path
  asked for with leading slashes, as a proxy older than #416 asks, served and filtered as its key is, with its
  filter's series under the key
- **AgentHttpServiceDashboardTest** — what the HTTP service keeps for the agent's dashboard: a scrape of a path the agent
  serves recorded in its history with the body's size before and after the filter, a scrape the proxy's dashboard
  asked for marked as one and left out of the filter's counts and metrics, which Prometheus's adds to (MockK), one of a
  path it doesn't serve not recorded, and a failure recorded with its reason; and
  the dashboard's own fetch (`fetchForDashboard`), which returns the filtered body and its size before the filter and
  counts nothing, in the scrape metrics, the filter's counts, or the history (MockK), and says why it failed
- **AgentActivityLogTest** — the agent dashboard's activity feed: newest first and bounded, a burst of like events one
  line, counted and naming its first paths, like events further apart lines of their own, the same event repeated one
  line however long since but never past another, a path repeated counted once, a connect or a disconnect starting a run
  no later event folds back past, a reason from outside cut and escaped, and a change count that every line and fold
  moves, which its changes flow carries
- **ShownFailureTest** — a failure as the agent's dashboard shows it: an exception as its class and the first line of
  its message with text, cut at 200 characters and marked `...`, redacted and escaped, and a message-less one as before;
  and a reason in the proxy's words kept to the longer limit, every line escaped
- **AgentScrapeHistoryTest** — each path's last scrapes as the agent saw them: newest first and bounded per path, keyed
  without leading slashes, and past the paths the agent knows and a few gone ones, the path scraped least recently
  dropped; and a record built from the agent's results, with the body's sizes, whether it was gzipped and chunked as
  the gRPC service decides, a failure's reason cut and escaped, and a scrape the proxy's dashboard asked for marked
- **AgentHttpServiceHeaderTest** — HTTP Accept header handling per-request (prevents stale headers in cached clients)
- **AgentContextTest** — Agent context unique ID generation, validity, invalidation, ScrapeRequestWrapper management
- **AgentContextManagerTest** — Context management: add/remove, concurrent access, chunked contexts
- **AgentConnectionContextTest** — Connection state, close/drain behavior, concurrent scrape request handling; the
  first of its tasks' failures kept as what ended it; and what it can't send handed to `onUndeliveredResult`, which
  the agent counts as dropped: each result still queued when the producer drops them after a close, once, and one the
  producer takes just as it's cancelled, but not one offered after the close, whose scrape counts it
- **AgentConnectionContextLincheckTest** — Lincheck (tagged `Lincheck`, run by `make lincheck-tests`): the agent's
  scrape-request backlog count, kept as `AgentGrpcService` and the scrape loop keep it, never goes negative and returns
  to zero once the connection closes -- including a queued request `close()` drains and a sender blocked on a full
  channel as it closes -- and no accepted scrape result is lost by the close
- **AgentPathManagerTest** — Path registration/unregistration, path lookup, concurrent access; every refusal of a path
  counted by its cause; a discovered path's
  repeating registration failure logs one WARN, compared by gRPC status code, so timeouts whose descriptions differ only
  in the elapsed time log one, while a rejection for another reason is warned about again; so do a static path's
  repeating failed retry, until the proxy answers in between, and a stale discovered path's repeating failed unregister,
  until the path returns to the file; a static path whose retries fail in transport backs off as a rejected one does,
  and still registers once the proxy accepts it; a lookup strips a requested path's leading slashes, and only them; and
  for the activity feed, a path registered, refused once with the proxy's reason, and unregistered, static or
  discovered, paths refused for one cause one line, and filters failing open for different reasons apart; and a JVM
  `Error` in a static path's retry, a discovered path's registration or unregister, or a reclaim propagated, not
  isolated as the path's failure
- **AgentPathManagerLincheckTest** — Lincheck, stress only (tagged `Lincheck`): static registration, unregistration, and
  discovery reconciles race over a real in-process gRPC connection; once they finish, the agent's local path map and
  the proxy's registrations for it agree exactly
- **AgentOptionsTest** — CLI parsing, defaults, validation, SSL settings, gRPC options; embedded, a setting the
  options reject, a wrong-typed or unparseable `-D` override included, throws `ConfigLoadException`;
  `PROXY_HOSTNAME` overrides a malformed or blank config endpoint without parsing it; and options built from an
  in-memory config take its settings, fall back to the built-in defaults, resolve its substitutions, and take a
  trusted certificate as PEM text
- **AgentMetricsProxyPathOptionsTest** — `metricsProxyPath` is none by default, comes from `agent.metrics.proxyPath`
  without a leading slash, with its labels, and `--metrics_proxy_path` takes precedence over it; a path of only a
  slash is rejected, and labels without a path are warned of
- **AgentDashboardOptionsTest** — the agent dashboard's options, `agent.dashboard` and the `--dashboard*` flags, which
  the agent resolves as the proxy resolves its own: off by default, on port 8095 apart from the admin port and the
  proxy's, path and host defaults, settable from the command line and read from the agent's config, not the proxy's; an
  out-of-range port, an empty path, a blank host, or a non-positive `maxSessions` or `refreshIntervalSecs` refused while
  it is on, with a `ConfigLoadException`; the login off with the username `admin` until a password is set, then logged
  masked; without one, a warning on every interface that names the agent's settings; and a non-positive
  `pathHistorySize` refused while it is on, and logged (`pathHistorySize`, `activityLogSize`)
- **AgentClientInterceptorTest** — gRPC client interceptor that adds agent-id metadata to outbound calls
- **AgentTokenClientInterceptorTest** — gRPC client interceptor that attaches the agent token header to outbound calls
- **EndpointFailoverTest** — Proxy endpoint rotation: a failed connect or a rejected registration moves to the next
  endpoint, a dropped registered connection returns to the primary, and whether each case reuses or rebuilds the channel
- **AgentMetricsTest** — Prometheus metrics registration and gauge/counter updates; `agent_dashboard_scrapes_total`
  counts under `launch_id` alone, and an agent's series goes when its metrics close while another agent's stays; the
  connection, path, discovery, and dashboard series registered; `agent_build_info`'s launch ID, name, and version; an
  agent's counters with a known set of labels at zero from the start, discovery's and the dashboard's only when it has
  them; the sampled gauges reporting the connection, the endpoint, the paths by source, and the dashboard's pages; and
  AGENTS.md's `PER_LAUNCH` rule for every agent metric, read from the private `COLLECTORS`: each takes `launch_id` as
  its first label, and closing one agent's metrics takes its series from every family while another agent's stay
- **AgentBacklogDriftTest** — scrapeRequestBacklogSize drift when sendScrapeRequestAction fails
- **HttpClientCacheTest** — HTTP client caching with TTL/idle eviction, keyed by auth credentials
- **HttpClientCacheCancellationTest** — a scrape cancelled while the client cache is busy still releases its client,
  so eviction or shutdown closes it
- **HttpClientCacheLincheckTest** — Lincheck, stress only (tagged `Lincheck`): scrapes under three credentials race LRU
  eviction, expiry, and `close()` in a two-client cache; no scrape is handed a closed client or has its client closed
  under it, and the only open clients are the cached ones, or none after `close()`
- **EmbeddedAgentConfigTest** — the builder for an embedded agent's settings, and its `embeddedAgent { }` DSL: each
  method's setting reaches `AgentOptions` as a config file's would, each Kotlin property sets what its method does
  and reads it back, a property set to `null` takes its setting out, and nothing set gives the defaults;
  `scrapeSelf` and `path` percent-encode a username and password into the URL so a password with any character decodes
  back intact, an empty pair means none, and `path` refuses credentials for a URL that has its own, as the agent's
  parser reads it; labels become JSON; `setting()` takes precedence over a method; `toString()` shows what `setting()`
  sets but not the token, a password, a PEM, or a path's credentials, whatever their keys' case, as Spring Boot
  lowercases a key from an environment variable; and a blank path, an out-of-range port, or a URL
  with no scheme throws `ConfigLoadException`; `source()` adds an in-process path, with or without labels, that
  reaches the agent's options but not the config tree, shows in `toString()` as `in-process`, and fails `start()` when
  another path or the proxy's rules forbid its path, with a message that names it as `source()`'s, not a
  `pathConfigs` entry; `source()` with a `PrometheusRegistry` serves it through `MetricsSource.of`, with or without
  labels; `fromEnvironment()` sets the proxy, the token, and the certificate from the `PROMETHEUS_` variables, read
  through a lookup a spec gives, trimmed, the authority `prometheus-proxy` without `PROMETHEUS_PROXY_AUTHORITY`, a blank
  or unset variable leaving its setting alone, and a builder method called after it taking precedence, and without a
  lookup reads the process's environment, which a JVM of its own, given all four variables, shows; and
  `agentMetricsAt()` gives an agent built from it, never started, an in-process path served by the agent's own metrics
  source, named as `agent.metrics.proxyPath`'s in a startup message, and its metrics collected with their port off; it
  sets `agent.metrics.proxyPath` and its labels, so it shows in `toString()` and `toConfig()`, an agent started from
  that tree serves it, and it moves when called again; an agent started embedded with it registers none of the JVM
  metric groups in the default registry, even with their `agent.metrics` settings on, where one built from the same
  settings directly does
- **MetricsSourceTest** — a path served by a `MetricsSource`: scraped in-process in the content type the source names
  (the text format when it names none), asked in a text format the scrape accepts for the families named with
  `name[]`, filtered, refused past `maxContentLengthMBytes` whether written in arrays or a byte at a time, interrupted
  at the scrape timeout, failed when the source throws, an `Error` or its own cancellation or interrupt included, and
  logged as a failed scrape of `in-process`, not an HTTP request, but a `VirtualMachineError` propagating; every byte
  kept when it writes from several threads at once; read by the dashboard's fetch, which counts nothing; a call that
  ignores the interrupt times out on time, holds neither another path's source nor the service's `close()`, and the
  source isn't called again until it returns; and the docs site's worker, in Kotlin and in Java, doing its work
- **RegistryMetricsSourceTest** — a `PrometheusRegistry` served in-process (`MetricsSource.of`, from Kotlin and from
  Java): written in the text format or OpenMetrics, as the scrape accepts, and only the families it names; a source of
  the default registry leaving out the agent metrics while an agent collects them, named or not, and serving it all
  while none does, and a source of another registry keeping a family with an agent metric's name; and the agent's own
  metrics, as `agent.metrics.proxyPath` (`agentMetricsAt`) serves them: the agent families alone, a counter's, a
  gauge's, and a histogram's series of its launch only, not another agent's in the JVM, only those named, and nothing
  once its metrics are closed; for a standalone agent, the rest of the default registry too, its JVM metrics, named or
  not, with only its own agent series
- **StartIfConfiguredTest** — `EmbeddedAgentConfig.startIfConfigured()`: off, with one INFO line, without a proxy; on
  with the builder's proxy, a `setting()` of the endpoints or the host name, or `PROXY_HOSTNAME`, and off with a blank
  one, the rule the public `namesProxy()` gives too; a start that fails, a checked exception included, returning null,
  logged at ERROR with its cause's message,
  and given to `onFailure` with why and what was thrown; why read along the cause chain, each message once, with a
  URL's credentials masked; and real failed starts, a key, a certificate, a setting, a wrong-typed token, a duplicate
  path, a dashboard password, and an admin port in use, saying why without the token, a password, or a PEM
- **EnvironmentSettingTest** — a value `fromEnvironment()` read that the agent refuses as it starts: an endpoint it
  can't parse names `PROMETHEUS_PROXY` and `agent.proxy.endpoints`, and a certificate that isn't PEM, or PEM with no
  certificate in it, names `PROMETHEUS_PROXY_CERT` and `agent.tls.trustCertCollectionPem`, with the agent's reason and
  never the certificate, as `start()` throws it and `startIfConfigured()` logs it; no token or authority refused as the agent
  starts; a setting the agent's own variable sets not checked; and one set from code after `fromEnvironment()`, by a
  builder method or `setting()`, keeping the agent's own message
- **EmbeddedAgentInfoTest** — EmbeddedAgentInfo data class (launchId, agentName storage), and its connection,
  `isConnected` and `proxyEndpoint`, read live from the agent; and `use` stopping the agent after its block, and
  running the block on a null handle
- **RequestFailureExceptionTest** — RequestFailureException custom exception class
- **RuntimeLibrariesTest** — the libraries an embedded agent checks before it starts: the versions this JVM has are
  read, and pass; Spring Boot 4.2's pass, and 4.1.1's kotlinx-coroutines, 4.1.0's Prometheus client, and both of 4.0's
  are refused, in one message naming each Spring Boot property to set; a version that can't be read passes; and
  versions compare by their leading dotted numbers, a release candidate as its release
- **SslSettingsTest** — SSL keystore/truststore loading for TLS configuration
- **TrustAllX509TrustManagerTest** — TrustAllX509TrustManager (dev/test only, bypasses SSL validation)
- **AgentSnapshotTest** (`dashboard/`) — `AgentSnapshot.collect`, from an agent built but never started: connecting
  to the first of its endpoints, with no id, no failure, and no connect yet; every configured path pending, sorted,
  with its URL's credentials and query redacted, its name and labels; a path's filter with its rules and nothing
  dropped; the settings it's given, and only whether a token is set; no discovery while it is off;
  with the dashboard on, each path's recorded scrapes, newest first, its strip's runs, and the history's size; and
  with it off, no history, and Scrape now as its setting says
- **AgentDashboardServiceTest** (`dashboard/`) — the agent dashboard's site as the push loop reads it, from an agent
  built but never started: a write to the activity feed one of its changes, which wake the loop, and the feed's count
  at the start not one
- **AgentDashboardHtmlTest** (`dashboard/`) — the agent dashboard's markup: a path, a reason, and a URL escaped; a
  refused path's reason and "not until the agent reconnects"; a retry's wait as a countdown, and one already due as the
  next retry; an en dash for missing labels; the empty and gone detail panes; a failing connection's endpoints, each
  with its last error, the one being tried, and "failed over"; the title's count of refused and retrying paths and a
  failing connection; a push's status bar first and stamped, with the regions of the page's view alone; a row's encoded
  selection URL and the script's agent views; a row's last result and history strip, a registered path not scraped yet
  saying so; the detail pane's Recent scrapes, with the body's sizes, gzip, chunking, a failure's reason escaped, and a
  scrape from the proxy's dashboard marked; and the Scrape now button in the pushed detail pane, its result panel
  outside every push on the Paths view alone, and neither with Scrape now off; and the Activity view: newest first, a
  problem a crit tag and news a warn one, a run counted and its paths named, escaped, its search and column guide,
  filtered with a count, a path matched with its slash, its empty state, and its push carrying the table out of band,
  or not when told; its column guide explaining each header in the header's order, after the search and ahead of the
  table, and in no view's push; the Settings view's groups, escaped, none of them on the Connection view, and its push
  carrying them out of band, or not when told; the polite live region on every view's page and in no push; and the
  stored theme applied in the page's head, with the theme picker in the bar and in no push
- **AgentSettingsTest** (`dashboard/`) — the agent's Settings view (`AgentSettings`), from an agent built but never
  started: its groups in order; what it resolved, its endpoints, the client timeout the deprecated `cioTimeoutSecs` set,
  its trust store and TLS files, and its login's username; the defaults with nothing configured; discovery's file and
  interval; an embedded agent's own metrics served in-process (`agentMetricsAt`) shown as on, at their path, with their
  port when it's on too, and a standalone agent's, from `agent.metrics.proxyPath`, the same way; and no token,
  password, PEM, or `-D` value anywhere in it
- **AgentScrapeNowTest** (`dashboard/`) — the agent dashboard's Scrape now, its fetch stood in for: a configured path
  scraped at its own URL through its own filter, though not registered, summarized with its sizes before and after
  the filter; a gzipped body unzipped and a path asked for with a leading slash found; a failure's reason; a path the
  agent doesn't know refused without a fetch, shown escaped; a path at most once a cooldown; at most a few at once;
  and a path served in-process read through its source
- **FileDiscoverySourceTest** (`discovery/`) — Discovery-file parsing: HOCON and JSON, empty or absent `paths`,
  rejection of missing files, malformed files and incomplete entries, and HOCON substitution resolution; and the entries
  a read skips told to the activity feed once per change
- **PathDiscoveryServiceTest** (`discovery/`) — A failed read reconciles the last good read, so a reconnect registers
  its paths again, and reconciles nothing before the first; a successful read, including an empty one, reconciles the
  desired set; a repeating failure logs one WARN, compared by gRPC status code, or by class and message without one, so
  timeouts whose descriptions differ only in the elapsed time log one, while a different read error is news; a
  read's failure told to the activity feed when it is new or changes, not while it repeats; every reconcile reported
  for the agent's metrics by whether it succeeded; and a JVM `Error` from the read or the reconcile propagated, not
  logged as a failed tick
- **MetricFilterTest** (`filter/`) — Allow/deny filtering: fully anchored regexes, deny winning over allow, whole
  families dropped or kept (HELP/TYPE/UNIT lines, histogram, summary and OpenMetrics series), line endings preserved;
  and why a filter lets a scrape through unfiltered, told once per cause

Three support helpers also live here (not test classes themselves):

- **KeyStoreTestSupport** — `createTempKeyStore()`, an empty keystore in an auto-deleted temp file, with its
  password, for the `SslSettings` and `AgentHttpService` trust-store tests and `AgentSettingsTest`, whose agent
  loads one as it's built
- **RegistrySourceFromJava** (`src/test/java/io/prometheus/agent/`) — `MetricsSource.of` called from Java, as the
  static method of the interface it is there, which Kotlin reaches through its `Companion` instead; for
  `RegistryMetricsSourceTest`
- **DiscoveryTestSupport** (`discovery/`) — `discoveryPathsHocon()`, which builds a discovery file's
  `paths = [...]` body for the harness and container discovery specs, so the format they write is defined in one
  place

### Unit Tests — Proxy (`proxy/`)

- **ProxyTest** — Proxy main class lifecycle, agent info retrieval, JSON serialization, stale agent cleanup;
  `removeAgentContext` counts each chunked transfer it reclaims as abandoned, and never one the stream's sweep counted,
  and puts the agent's departure in the activity feed, an eviction as a problem, with the paths it served; and counts an
  announced agent's removal in `proxy_agent_disconnects_total` by its reason, and not an unannounced connection's
- **ProxyServiceImplTest** — gRPC implementation: registerAgent (recording the identity the call authenticated as for
  the dashboard, without binding the context to it, and the connect in the activity feed), registerPath (a path the
  identity may not register noted as a rejection), heartbeat, scrape responses, chunked responses; a result or a chunked transfer for a scrape sent to another agent neither completes nor fails it, even
  when its `agentId` or `headerAgentId` names that agent; a transfer ends with its scrape, even one whose scrape ended
  just before it was stored, one stream has at most the agent's backlog cap of transfers under way, a header's strings
  are cut, and a late result or transfer logs nothing above DEBUG; every result and chunked message, its scrape tracked
  or not, marks the agent that sent it active (with the transport filter disabled, only when sent as the identity the
  agent connected as), and a late result doesn't count as a completed request; with the transport filter disabled, a
  scrape-request stream that names an agentId before it is assigned neither streams nor removes the context another identity then
  connects with under it, and 10,000 connects by one identity leave at most `maxAgentsPerIdentity` agents; a forward
  span is tagged with its request's queue wait before the send, so a scrape that ends while the send waits keeps it,
  and is annotated as sent only while its scrape still awaits the result
- **ProxyServiceMessages** — what an agent sends `ProxyServiceImpl`, for the specs that call its RPCs directly:
  `resultFrom`, `chunkedHeaderFrom`, `chunkFor`, `transferFrom` (a header, one chunk, and a summary), and
  `onConnection`, which runs a call on the connection the transport filter assigned an agentId to
- **ProxyServiceImplMetricsTest** — what each RPC counts, read from real collectors (`CountingProxyMetrics`): a
  connect only once accepted, and a refused one as a refusal by reason (a `transportFilterDisabled` mismatch, the
  `maxAgentsPerIdentity` limit); a heartbeat only once accepted; every path rejection by its cause, not only a path's
  first; a call naming another connection's agentId, or a result for a scrape sent to another agent, as a binding
  mismatch for its RPC; a result, or a chunked transfer's header, for a scrape no longer tracked as late; a transfer
  whose scrape ends before its chunk or its summary as abandoned, once; and the queue wait of Prometheus's scrapes, not
  a dashboard's
- **ProxyGrpcServiceTest** — gRPC server configuration: each handshake, keepalive, and connection-age option reaches
  the server builder (a MockK builder) and an unset one leaves gRPC's default, and the transport filter; reflection
  is not served when disabled, and when enabled it requires a valid agent token if agent auth is configured; with
  Zipkin on, the server is built before `zipkinReporterService` is assigned and traces once it is
- **ProxyHttpServiceTest** — ProxyHttpService string representation, lifecycle, and idle timeout: 45s when
  `http.idleTimeoutSecs` is -1, otherwise the configured value
- **ProxyHttpConfigTest** — HTTP server config: Ktor compression, status pages, CORS; the status line given as the body
  of a 404 without one, an unknown route's or an empty one, and a 404 with a body of its own keeping it; a call
  cancelled by a client disconnect, or a write to one that has gone, is logged at DEBUG, while an unexpected exception
  still logs at WARN
- **ProxyHttpRoutesTest** — HTTP routing: path resolution, query params, error responses, compression,
  ensureLeadingSlash, and the service discovery route on and off; the scrape port's `nosniff` and sandbox headers;
  `recordScrapeOutcome`'s `/debug` line, scrape record, and event, each only while its consumer is on, with a huge
  failure reason and URL cut to one line, and a scrape record carries the agent's id, name, and identity; a scrape of
  `//path` or `///path`, which Ktor routes as `/path`, is sent to the agent as Prometheus's scrape, labeled in the
  per-path metrics, and shown on `/debug` and the dashboard under the path's registered key, with its query unchanged,
  and the blitz, favicon, and unknown-path answers see it with one slash; a blitz path configured with a leading slash is answered at `/path`
  and `//path` and can't be registered; the route logs a client's path and query cut and escaped; a scrape that
  throws counts as `internal_error`; a scrape's span begins a trace of its own at the proxy's sample rate, whatever B3
  or `traceparent` headers the request carries; and the defaults of `ResponseResults`, the route's answer
- **ScrapeDispatchTest** — sending a scrape to a path's agents and merging their answers, which the scrape route and
  the dashboard's scrape-now share: each outcome `submitScrapeRequest` labels (the agent disconnecting before or during
  the scrape, the proxy's timeout or one it is given, the in-flight limit, the agent's backlog cap, the proxy stopping,
  an invalid response, a target's 404 or 500, invalid gzip, a zip bomb, and a success, plain or gzipped), the labels a
  target's status maps to, and a non-exposition content type served as text/plain; a huge agent content type is logged
  cut and escaped; a handler that times out or is cancelled drops its scrape's unfinished chunked transfer, counted as
  abandoned once;
  `submitScrapeRequests` answers in the agents' order and records nothing the proxy reports; a scrape request carries
  its `ScrapeSpec`'s query, headers, and origin (Prometheus or the dashboard) to the agent; an Authorization header
  sent without TLS is warned about once; `ScrapeHeaders` reads Prometheus's scrape timeout; and `mergeResponseResults`
  and `mergeContentTexts` pick the merged status and content type, keep one `# EOF` at the end, and add no blank line
  for a failed agent; each agent of a consolidated path gets a forward span under the scrape's, carried in its own
  request, a forward that timed out or found its agent gone ends as an error tagged with how, a scrape without a trace
  gets none and sends none, and a forward span shows the agent's name cut and escaped
- **ProxyPathManagerTest** — Path registration/unregistration, consolidated mode, agent selection, concurrent access;
  a live agent's path is taken over only by the same auth identity; the activity feed gets a new registration but not
  a repeat, a take-over as one naming the agent it displaced, a rejection the first time the agent is told it, a path
  with more than one segment among them, and an unregistration; removing an agent returns how many paths it served;
  a path whose last agent unregisters it or disconnects is recorded as departed, with the agent, identity, target, and
  source its entry showed, a consolidated path only once its last agent goes, and a registration forgets it; a path
  the scrape port serves itself is refused, the blitz path also when it was configured with leading slashes; a path
  with more than `maxLabelsPerPath` labels is refused, and an accepted one keeps a single, parsed copy of its labels;
  the agents of one identity are refused past `maxPathsPerIdentity` together, agents with no identity sharing one
  limit, the count follows each way a path leaves, and a redeploy at the limit still takes over its own paths; and the
  80% warnings of `maxPathsPerAgent` and `maxPathsPerIdentity` come once until the count falls below 70%, an agent
  that leaves starting over
- **NearLimitWarningsTest** — when the proxy warns that a count nears its limit: as it reaches 80%, and again only
  once it has fallen below 70%, for each key on its own; the first whole count past 80% of a small limit, never for a
  limit of 0, and without overflow near `Int.MAX_VALUE`
- **ProxyPathManagerLincheckTest** — Lincheck, stress only (tagged `Lincheck`): path registration, reclaims,
  unregistration, and agent removal race on the real code the TLA+ `ProxyRegistry` spec models, among agents of
  several launches, one of them reconnected and one predating path notices; no reclaim takes a path from a live agent
  of another launch (`ReclaimNeverDisplaces`), or is refused as held without one, no path is left on a removed agent,
  `pathCountFor` matches the path map and the limit, exclusive paths have one agent, modes never mix, a consolidated
  path never takes an agent of another identity, and an invalidated agent holds no unread notices
  (`InvalidatedHasNoNotices`); each agent waiting on a path reads notices, waits once, is of another launch than the
  path's agent, is still connected, and, while valid, has a notice about the path, and no agent has a notice about a
  path it serves
- **AgentContextTest** — Proxy's AgentContext: unique ID generation, validity, path registration, ScrapeRequestWrapper
  queue; and a scrape request, or a path notice, queued just as its wait times out returned by that wait, not lost
  (`lateArrival`)
- **AgentContextLincheckTest** — Lincheck (tagged `Lincheck`, run by `make lincheck-tests`): the scrape-request
  hand-off -- write, read, invalidate -- from three threads under model checking and stress; each write's outcome
  must fit a sequential order, and no accepted request may be stranded after invalidate
- **AgentContextManagerTest** — Proxy's AgentContextManager: add/remove contexts, chunked context management, concurrent
  access; a conditional removal takes only the context it is given; concurrent adds never pass an identity's
  agent limit, a removal frees a slot, and a refused add logs a WARN, and goes in the activity feed, at most once a
  minute for each identity; and an identity's 80% warning comes once until its count falls below 70%
- **AgentContextCleanupServiceTest** — Stale agent eviction (removes agents inactive beyond maxAgentInactivitySecs);
  an eviction that finds the context already removed isn't counted
- **RemovalReasonTest** — why the proxy removed an agent's context: each reason logs as its string always did, only an
  eviction is a problem in the activity feed, and each gives the feed its cause, an eviction naming
  `proxy.internal.maxAgentInactivitySecs`
- **ChunkedContextTest** — Chunk accumulation, CRC32 checksum validation, header/chunk/trailer sequencing
- **ProxyOptionsTest** — CLI parsing, defaults, validation, TLS settings, gRPC options, the `proxy.internal` limits,
  and the agent-port authentication warnings; an `http.idleTimeoutSecs` of 0 or a negative other than -1, and a
  configured `sdTargetPrefix` with a query or fragment, fail startup with an `Invalid configuration:` message naming
  the setting; the blitz path kept without its leading slashes, and one of slashes alone, blank, or of more than one
  segment failing startup while blitz is on. The dashboard's options are ProxyDashboardOptionsTest's
- **ProxyDashboardOptionsTest** — the dashboard's options, split from ProxyOptionsTest: off by default, on a port
  apart from the admin port, set by its flags; its port refused out of range, and its path, host, session cap,
  refresh interval, path history, and activity log size refused when unusable while the dashboard is on, and logged
  at startup; only a wildcard address counted as listening on all interfaces; `proxy.dashboard.links` parsed from a
  config file in order and logged by name, with a link's `icon` read and logged too, and a link the dashboard can't
  render, or one with an icon it doesn't have, failing startup with an `Invalid configuration:` message, naming the
  link, while the dashboard is on; and the login, its username `admin` until set and off until a password turns it
  on, logged with the password masked, its startup failures (an empty username, a colon in it, a control character in
  either) naming no value, the username left unchecked without a password, and the all-interfaces warning only
  without a login
- **SdTargetTest** — `sdTargetPrefix` parsing: host:port as is; an http(s) URL split into the target, `__scheme__`,
  and a path prefix; anything else, including a host:port with a query or fragment, refused, naming the setting
- **ProxyDynamicConfigTest** — Dynamic config overrides via -D flags (maxZippedContentSizeMBytes,
  scrapeRequestTimeoutSecs, etc.)
- **ProxyServerInterceptorTest** — gRPC server interceptor: agent-id metadata extraction from incoming calls
- **ProxyServerTransportFilterTest** — gRPC transport filter: agent context creation on connection, cleanup on
  disconnect, and a transport that ends after its eviction logging at INFO only for an announced agent
- **ProxyUtilsTest** — Helper functions: invalidAgentContextResponse, respondWith
- **ProxyMetricsTest** — Prometheus metrics registration and gauge/counter updates; the agent, rejection, queue-wait,
  and dashboard series registered with the rest; `proxy_build_info`'s version and launch ID, a closed proxy's series
  removed while another proxy keeps the gauge; `proxy_dashboard_sessions` reporting the open pages; and the labeled
  counters' known labels exposed at zero before anything is counted
- **ActivityLogTest** — the dashboard's activity feed: lines newest first with each event's agent and time, the oldest
  dropped once full, a burst from one agent folded into one line that counts it and names its first paths, bursts from
  agents registering at once each one line, a burst ending at its window or at another agent, kind, or group, a source's
  events within its window counted on one line, past its most sources a window the rest counted on one, every value cut,
  a log with no room refused, and which kinds are problems; every write, a burst's join and a source's repeat included,
  adding one to the change count a snapshot reads with the lines, and a read leaving it as it was
- **PathHistoryTest** — the dashboard's per-path scrape history and departures: each path keeps only its newest
  records, newest first, and a busy path can't push another's out; recording alone holds the history to the live paths
  plus the gone-path cap, evicting the path scraped least recently, with no snapshot to prune it; a registered path
  keeps its history however old, a gone one only until its retention has passed since its last scrape, or since it
  departed if that was later, and gone paths past the cap go oldest first, a path with scrapes and a departure counted
  once; a path that comes back keeps its history, a `//path` scrape keys under the path map's key, and a snapshot is a
  copy; a departure keeps what the path's entry showed, though the path was never scraped, recording alone holds
  departures to the cap, earliest dropped first, a path that departs again counts as the latest, a registration forgets
  its departure, and a path the snapshot reads as registered keeps it
- **ProxyMetricsLincheckTest** — Lincheck, stress only (tagged `Lincheck`): a path registering, scrapes recording its
  latency and response size, and its removal race; no per-path histogram series outlives the path's removal
- **RecentReqsSynchronizationTest** — Synchronized access to recentReqs EvictingQueue (prevents
  ConcurrentModificationException)
- **ScrapeRequestManagerTest** — Add/remove scrape requests (a replaced scrapeId gives back its in-flight slot),
  timeout handling, concurrent access
- **ScrapeRequestManagerLincheckTest** — Lincheck (tagged `Lincheck`): handlers admitting and untracking requests under
  the in-flight limit race an agent's answer, a single failure, and `failAllScrapeRequests`; the limit holds and its
  counter matches the map, each request keeps one completer's result and matching `proxyFailure`, and a disconnect fails
  only its own agent's requests
- **ScrapeResultOwnershipLincheckTest** — Lincheck (tagged `Lincheck`): a handler tracking and untracking a scrape races
  answers for its scrapeId from its owner and from another agent, which can name it before it is tracked and claims to
  be the owner; only the owner's answer ever completes it
- **ChunkedTransferLifetimeLincheckTest** — Lincheck (tagged `Lincheck`): a handler untracking a scrape and removing
  its chunked transfer races a header opening one (store, then re-check the scrape) and a chunk dropping it; once the
  handler has returned, no transfer is left for its scrape. Every operation runs in the parallel part, since a
  `finish()` before it or after it would hide the race
- **ScrapeRequestWrapperTest** — Scrape request lifecycle, timeout behavior, response delivery, and whether the
  request tells the agent the dashboard asked for the scrape
- **AgentAuthManagerTest** — Per-agent auth: path-glob matching, token resolution (including the legacy allow-all
  token), `isEnabled`, and fail-fast rejection of empty, duplicate or colliding identities and of the reserved name
  `legacy-agent-token`, with or without the legacy token set (a name that only resembles it is accepted); read from a
  config file through a MockK `Proxy`, the reserved name fails startup as `Invalid configuration:`
- **AgentAuthServerInterceptorTest** — gRPC auth interceptor: a valid token exposes the resolved identity in the
  context; a missing, unknown or same-length wrong token closes the call with UNAUTHENTICATED, and goes in the
  activity feed, naming its host, a host's repeats counted on its line; and every refused call is reported for
  `proxy_agent_auth_failures_total`, saying whether it sent a token
- **ProxyEventBusTest** — Dashboard event bus: ordered delivery, non-blocking emit with no subscriber, no replay to a
  late subscriber, fan-out to multiple subscribers, and AgentContextManager emitting on connect only
- **ProxyDashboardHtmlTest** (`dashboard/`) — Dashboard renderer: the Log out button only with a login and in no push,
  the Agents view (an agent row's htmx attributes and its one id across the page and the pushes, never a region's; the
  selected agent marked current; an explicit gone state for a selected agent that disconnected, naming it through
  `agentLabel` from a scrape or activity line and never by its id, a prompt when none is selected, and a placeholder
  for one not yet registered; failover markers; an agent's uptime; the eviction countdown only while the stale-agent
  check runs; an empty proxy said to be empty), the detail's Recent scrapes (the selected
  agent's alone from a shared path's history, its latest across its paths, newest first, at most the cap, which the
  header counts), the pushes (top-level out-of-band regions, only those of the session's layout, the status bar alone
  for a session with no known layout, and the view stamp on the frame's first tag, which an agent-supplied value can't
  stand in for), tolerant selection, layout, and view count parsing, the status bar's departed count, an agent's
  identity in its row and detail, the data `demos/basics/bin/screenshots.sh` reads (an agent row's id, name, and
  path count, the name escaped and through `agentLabel`, and the detail's scrape count) and the one width of a scrape
  time it orders as text, links at a root mount, the nav, the Settings view (its groups, escaped, on no other layout,
  and its push carrying them out of band, or the status bar alone when told), accessibility (the connection live
  region, outside every push, and the page's language and main region), and the link icons' stylesheet (in the head
  only while a link shows an icon, with each mark's URL under the route base, at a root mount too, and each colour per
  theme, and in no push)
- **DashboardScriptTest** (`dashboard/`) — the page's script and the htmx it runs on: frames stamped below its message
  count dropped, the arrow keys switching views from anywhere but a text field, the column guide opened and closed from
  its button, its close button, and Escape from anywhere but a text field, the scrape-now request with htmx's header,
  each layout named by its route, an ended session sent to the login page (a session check on the socket's close, and a
  reload on a refused scrape), the WebSocket connection indicator (both labels in the status region, the lifecycle
  events it flips on and the ones it must not, a close while the tab is hidden ignored, a lost connection announced once
  per outage, the capped reconnect backoff, and the stylesheet's disconnected treatment), no htmx 2 WebSocket names,
  history pushes heard on the document, copy clicks from one listener on the body (the clipboard fallback outside a
  secure context, a proxy URL from the page's own host and the scrape port, and the result announced and put back after
  each push), scrape times in the viewer's zone before each swap, one run per document, a second marking the theme
  picker swapped in, error responses kept from being swapped in, and the htmx config ahead of the versioned htmx script;
  and the stored theme applied in the head and the picker's buttons setting it, pressed and titled by the theme in
  force, outside every push
- **PathColumnsHtmlTest** (`dashboard/`) — the Paths view's long-standing columns: a row per path with its target,
  source, and last scrape, and dashes for what a path lacks (no time for one never scraped); its agent named as the
  agent list names it, never by internal id, with the same placeholder before it registers and a consolidated path's
  other agents as a count; a departed path kept, marked gone, and attributed to its last agent; the buttons that copy a
  path's proxy URL (none for a departed path, and the scrape port on the page, not in the pushes) and its target (none
  without one, the URL escaped), each with one id per path across the page and the pushes; a scrape time's instant, to
  the second, in both layouts; and no button sharing a class with a row of the detail's Recent scrapes
- **PathTableHtmlTest** (`dashboard/`) — the Paths view's table and search: the history strip (one element per run, a
  floored percentage, no status pill, visible under forced colours), the Identity column (shown while agent auth is
  on, the legacy token as a shared token), the search (matching rows, the match count, the empty states, the form
  outside the pushed table, an escaped query, the script sending the URL's query, and tolerant parsing of it from the
  socket), the Scrape button on a live path and the result panel outside the pushed table, the Links column (last,
  present while links are configured, each link opening its path in a new tab with an `aria-label` naming the path
  and the tool, a dash for a row with none, a departed row keeping the links that use no label, no extra status pill,
  and the links' quiet style and focus ring; an icon link's mark alone, named in its `title` and `aria-label`, its 24px
  target and forced-colours paint, a separator only between two names, and a departed row dropping an icon link that
  needs a label), the failing count and the tab's title, a departed row's identity
  and target, with en dashes for the scrape cells of one never scraped, and the column guide both tables share (a
  name and an explanation for every header, in its order, Identity and Links exactly when the table has them, the
  button at the search's end, the panel hidden after the form and outside every push, and none on the Agents page)
- **PathLinkTest** (`dashboard/`) — a dashboard link's URL template (`proxy.dashboard.links`): `{path}`, `{agent}`,
  `{identity}`, and `{label.<name>}` filled from the path, each value percent-encoded as a URL component and the
  template's own encoding kept; a path's labels read tolerantly (`PathView.labelValues`), keeping string, number, and
  boolean values; no link for a path lacking a value (a missing or empty label, no identity, the legacy
  shared token, no serving agent), and a departed path keeping the links that use no label; and the refusals, naming
  the link: a blank name, an unknown placeholder, a URL that isn't absolute http or https or doesn't parse, and a
  placeholder in the URL's scheme or host. Also a link's brand icon (`LinkIcon`): read ignoring case, a blank one
  showing the name, an unknown one refused, the startup log naming it, each mark on the classpath (which catches a
  Simple Icons bump that drops a brand), and each colour clearing 3:1 on `--surface-2` in both themes
- **ActivityHtmlTest** (`dashboard/`) — the Activity view: each line's time, event, agent, path, and detail, newest
  first; problems as crit tags and take-overs as warn tags; a burst's count and first paths; dashes for a line with no
  agent, and an agent not yet registered named by `agentLabel`; the Identity column while agent auth is on; escaped
  values; the search, its match count, and its empty states, a path matched with its slash and an agent by its label;
  the form at the view's own route; the column guide (every header explained in its order, Identity only
  while agent auth is on, every problem kind named in Event's explanation, and the panel outside every push); and the
  push carrying the status bar and the table, out of band, or the status bar alone, still the frame's first tag, when
  the page already shows the table
- **ScrapeSummaryTest** (`dashboard/`) — a scrape-now's summary: status, outcome, time, size, content type, families,
  samples, and `# EOF`; families and samples counted as the agent's metric filter reads them: a quoted name whole, a
  tab after the keyword, a HELP or UNIT line with no TYPE, an indented comment, and lines split at line feeds alone; an
  OpenMetrics body counted and a protobuf one not, and said not to be text (`isText`); a consolidated path's agents
  merged as Prometheus sees them; a failure's reason cut and escaped; a long body cut at its last line break, or short
  of a surrogate pair with none; and control characters escaped but for line breaks, tabs, and carriage returns
- **ScrapeNowTest** (`dashboard/`) — scrape-now with its scrape stood in for: the summary and the activity feed's line
  naming the browser; a path not registered, without an agent, or on a proxy not running refused; a path at most once a
  cooldown; at most a few at once; a failed scrape-now kept apart from a successful one in the feed; and a scrape-now
  asking for text and marked as the dashboard's, so the agent leaves it out of its scrape metrics
- **RowFilterTest** (`dashboard/`) — the Paths and Activity views' search: a query matching the path, any agent's
  name, the target URL, or the identity (the legacy token by its label, not its internal name), ignoring case, and a
  departed path's agent and identity, from its departure or its last scrape; a path matched with its slash, as its row
  shows it, and an agent not yet registered by its label, not its placeholder; "problems only" keeping failing and
  departed paths; both having to match; browser input
  trimmed and cut, never inside a surrogate pair; a filter read from a URL's query; an activity line matched by its
  event, agent, identity, paths, or detail, its paths with their slashes and its agent by its label, and its problems
  kept; and identity labels
- **ProxyDashboardServiceTest** (`dashboard/`) — the value a push compares to leave out an Activity table the page
  already shows (`ActivityTable`): equal for an unchanged feed and the same message, different on a change of the
  feed, the search, "Problems only", or the message, and none for another layout; and the settings, sent once a message
  (`SettingsShown`), whatever the feed, and never mistaken for that message's Activity table
- **ProxySettingsTest** (`dashboard/`) — the proxy's Settings view (`ProxySettings`), from a proxy built but never
  started: its groups in order; what it resolved, each identity and its globs, its TLS files, a stale-agent check the
  disabled transport filter forces on, and its links; the defaults, warning of an open agent port; the authentication
  mode's wording and warnings; and no token, password, PEM, or `-D` value anywhere in it
- **ProxySnapshotTest** (`dashboard/`) — Dashboard view models: eviction countdown, health thresholds, failover
  position, a path's history as runs of like results, oldest first, and `buildPathViews` joining registered paths to
  their history and latest scrape and surfacing departed ones with theirs: a path that departed before its first scrape,
  with its departure's agent, identity, target, and source; a departure's attribution over the last scrape's, which
  names the agent and identity when there is no departure; and no gone row for a path the path map still holds; an
  agent's scrapes (`scrapesOf`), its latest across every path's history, live or gone, newest first and at most the
  cap, none for an agent with none or no longer connected, and worked out once per snapshot; a gone agent's name
  (`goneAgentName`), its newest activity line's or scrape's, and none for a connected agent; and `collect`, from a proxy
  built but never started, reading them from the path history and the activity log's lines with their change count,
  and the eviction delay only while the stale-agent check runs

One support helper also lives here (not a test class itself):

- **DashboardFixtures** (`dashboard/`) — builders for the dashboard view models (`pathView`, `departedPathView`,
  `departure`, `agentView`, `scrapeRecord`, `activityEntry`, `snapshot`) with every field defaulted, `iconLinks` (a
  Prometheus and a Grafana link shown as icons), and `renderFullPage` for the
  page a GET serves, shared by `ProxyDashboardHtmlTest`, `DashboardScriptTest`, `PathColumnsHtmlTest`,
  `PathTableHtmlTest`, `PathLinkTest`, `ActivityHtmlTest`, `RowFilterTest`, and `ProxySnapshotTest`, so a test names
  only the field it is about

### Unit Tests — Common (`common/`)

- **AgentValuesTest** — what the proxy does to a value an agent supplies before keeping or logging it: `truncate` never
  splits a surrogate pair; `shownValue` cuts and marks a long value and escapes every control character and line
  separator, so a value can't forge a log line, and `escaped` does the same but for the characters it is told to keep;
  `pathCharacterProblem` names a control character, whitespace, `?`, or `#`, which no scrape's request path can carry,
  and accepts every other character; and `labelsProblem` names a label service discovery can't serve, with the name
  escaped
- **BaseOptionsTest** — Shared CLI argument parsing, config loading, boolean resolution; in a forked JVM, a
  standalone agent or proxy whose config URL carries credentials exits 1 on a setting it rejects, with one log line
  and no credentials or uncaught stack trace
- **ChannelsTest** — `receiveCatchingWithin`, every wait that ends in a keepalive: an element that comes as the wait
  times out returned and the channel left empty (`lateArrival`), where a receive inside `withTimeoutOrNull` loses it;
  null once the timeout passes with nothing, the channel left to the next wait; an element already queued returned at
  once, even with no time to wait; the channel's closing returned with its cause; and an infinite timeout waiting with
  none
- **ConfigLoadExceptionTest** — The public exception embedded hosts catch: message-only and with-cause constructors
- **CommonSettingsTest** (`dashboard/`) — the Settings groups both dashboards share (`CommonSettings`): the About
  group with only the keys of the `-D` overrides; each TLS file named and a PEM only as set; the admin endpoints, the
  metrics endpoint and its JVM metrics, an embedded agent's metrics served in-process with or without the endpoint,
  the dashboard's address and login, never its password, Zipkin, and the log
  level, `info` when unset; and where the config came from (`configShown`): a file, a URL with its credentials redacted, in memory, or the
  built-in defaults
- **ConfigWrappersTest** — Factory methods for AdminConfig, MetricsConfig, ZipkinConfig
- **ConstantsTest** — Constant values (EMPTY_AGENT_ID_MSG, EMPTY_PATH_MSG, EMPTY_INSTANCE)
- **DashboardLoginTest** (`dashboard/`) — a dashboard's login read from a request's `Authorization` header: the right
  pair accepted; no header, a wrong username or password, a prefix of either, another scheme, a value that isn't
  base64, or one with no colon refused, never thrown on; the scheme matched in any case; a split at the first colon,
  so a password may contain one; non-ASCII values compared as UTF-8; the login page's form accepted only with both
  halves right; and neither value in its `toString`
- **DashboardServerTest** (`dashboard/`) — the request rules every dashboard shares (`DashboardServer`): where a login
  may send the browser (`safeNext`: a page under the base, never off-site, a protocol-relative or backslashed path, a
  `.` or `..` segment, or the login page itself, at a root mount too), the session cookie named for the role and the
  port, so a proxy's and an agent's on one host differ, the WebSocket Origin check (own host, `allowedOrigins`, foreign
  or malformed origins refused), and the opt-in `allowedHosts` Host check against DNS rebinding; and changes waking the
  push loop at once, then at most once an interval, with the latest
- **DashboardPushTest** (`dashboard/`) — `DashboardServer`'s push loop over real sockets, serving a site the spec
  stands in for, with no proxy or agent, and ticking once an hour, so a frame comes only from the wake each case tests,
  within 5 seconds: a change pushing a fresh snapshot to every page at once; a collect that throws logged once at ERROR,
  the loop pushing at its next wake; a Log out naming no session, 100 of them, collecting and pushing nothing, and one
  naming a session closing its page (`VIOLATED_POLICY`) and no other; a page whose socket never reads, after a frame
  larger than every buffer, holding up no other page's pushes when Log out or the push loop (its login past its age)
  closes it; and, on a 50 ms tick, such a page closed once its buffer is full, giving up its `maxSessions` slot
- **DashboardResponsesTest** (`dashboard/`) — what `DashboardServer` answers over real sockets, serving a site the spec
  stands in for, with no proxy or agent: every response refusing framing once (`frame-ancestors 'none'` and
  `X-Frame-Options: DENY`), a page, a fragment, an action, an asset, a brand mark beside its own `sandbox` policy, the
  root's redirect, `/session`, a foreign Origin's and a foreign Host's 403, a 404, and a 500 included; without a login,
  no `Cache-Control` on a page, and the assets' year-long one; with a login, `no-store` once on every response but the
  assets, the login page, a 303 to it, a 401, a 404, and a 500 included; and an `Origin`, then a `Host`, of `ESC[2J`
  and 5,000 characters more, sent twice from one address over a raw socket, since the HTTP client refuses a control
  character, refused with 403 and logged at INFO and then DEBUG, the escape written out and the line under 400
  characters
- **DashboardServiceTest** (`dashboard/`) — a dashboard's service lifecycle over a mocked `DashboardServer`: a
  server that fails to start stopped, so Ktor's shutdown hook and application don't outlive it, with a stop that
  fails attached to the start's failure; and a server that starts stopped only when the service stops
- **DashboardSessionsTest** (`dashboard/`) — a dashboard's login-page sessions: a new one valid and a token never
  issued, null, or empty not; 43 base64url characters, distinct each time; ended 24 hours after its last request,
  which a request restarts, and a check that isn't a request doesn't; 7 days after it began however recently used;
  ended by removal, which says whether there was one; the least recently used dropped past the cap; and an expired one
  forgotten
- **DashboardThemeTest** (`dashboard/`) — the dashboards' theme: a dark rule written for the system's dark preference
  unless the page is set to light and for a page set to dark, with a selector or at `:root`; the tokens' dark colours
  under both, and the browser's own widgets following the choice; every text colour (`--ink`, `--ink-2`, `--ink-3`,
  `--ok`, `--warn`, `--crit`) clearing 4.5:1 on `--surface-2` in each token block, and a hovered row's address line
  on `--surface-3`; the head's script applying only a stored light or
  dark; and the theme picker offering light, dark, and auto, each named for its theme, marking the theme in force from
  the page's `data-theme` with the icon buttons' glyphs, and sharing the bar's end with Log out in one box
- **DeferredInterceptorsTest** — the client and server interceptors that build their delegate on the first call, once,
  and again on a later call if building it failed, which lets the Zipkin tracing interceptors be installed before the
  reporter they need exists
- **EnvVarsTest** — Environment variable mappings and fallback defaults
- **ExpositionTest** — how the agent's metric filter and the dashboard's scrape-now both read a text exposition body:
  `isTextContentType` takes a blank, `text/plain`, or OpenMetrics content type, whatever its parameters and case, and
  nothing else; `firstNonBlank` finds a line's first token within its range; `commentFamily` opens a family on a HELP,
  TYPE, or UNIT line, with a tab or several blanks between the tokens and a quoted name unquoted, spaces and all, and
  on nothing else: another comment, a keyword with no name, a quote left open, or a line that doesn't start with `#`;
  and `unquote` unescapes a backslash, a quote, and a line feed, and returns null for a quote its range doesn't close
- **KeyedThrottleTest** — the at-most-once-an-interval throttle behind the refused-connect WARN: a key passes once and
  not again until its interval is over, a refused call neither restarts the interval nor takes a new mark (a MockK
  time source), each key has its own interval, past its cap the key asked about least recently is forgotten and passes
  as a new one, an interval or cap that isn't positive is refused, and of concurrent calls for one key exactly one
  passes
- **LoginHtmlTest** (`dashboard/`) — a dashboard's login page: the heading it is given, escaped, a form posting to the
  login route with labeled, autocompleting, length-capped fields and one Log in button; after a failure the alert, the
  username kept, and the password focused, and without one neither; the alert for too many wrong logins; the next path
  and the username escaped; a root mount posting to `/login`; nothing loaded from anywhere; and the dashboard's color
  tokens, in both themes; and the dashboard's stored theme applied in its head, its one, inline, script
- **LoginLimiterTest** (`dashboard/`) — a dashboard's limit on wrong logins: an address under it let in, one at it
  refused and no other, let in again once its oldest wrong login is a minute old; every address refused at the overall
  limit, until its oldest is a minute old; and at most so many addresses remembered, the least recent going first
- **MetricBuildersTest** — the metric builders keep no exemplars and only classic histogram buckets; a
  `sampledGauge` value that throws is reported as NaN instead of failing the scrape; and `setToCurrentTime` sets
  Unix seconds, which the dashboards' `time() - *_start_time_seconds` panels assume
- **NonFatalTest** — `runCatchingNonFatal`, which the agent's connection tasks and per-path calls catch failures
  with: a result, or an exception as a failure, returned, but a JVM `Error` (`OutOfMemoryError`, `StackOverflowError`,
  `AssertionError`) and a `CancellationException` rethrown
- **ScrapeNowHtmlTest** (`dashboard/`) — the scrape-now result panel, the proxy's and the agent's: the status, path,
  facts, and start of the body; a body's families and samples shown only when it is in a text format, its content type
  either way; a cut body and an EOF named; an agent's size after its filter, and a cut body as the filtered one's
  start; a failure's reason without a body; the body escaped; a refusal and why; and sizes in bytes, KB, or MB
- **ScrapeResultsTest** — ScrapeResults data class, error code mapping, timeout handling, protobuf conversion, and
  `asDashboardScrape`, which marks results without changing any other field or what is sent to the proxy
- **ScrapeTracingTest** — a scrape's spans, the proxy's scrape and forward spans and the agent's fetch span, form one
  trace, the fetch joining it through the context the proxy sends; at a sample rate of 0 none is kept, the agent's
  included; a context that carries no trace begins one at the agent's rate; a rate keeps exactly its share; a heartbeat
  is never sampled and every other gRPC call is at the rate; a call served, over an in-process server, is sampled at the
  serving side's rate whatever its metadata's trace says (sampled, not sampled, or debug, a heartbeat included), and
  still joins the caller's trace; an extracted trace loses its decision and keeps its IDs and extra fields; a span's
  outcome, status, and error tags; `traced`
  finishing its span with what the block returned, a cancellation, or the error it threw, and running the block alone
  without a span; and closing closes the process's tracing
- **SharedCollectorsTest** — the collectors every agent (or proxy) in a JVM shares register on the first acquire
  and unregister, with their series cleared, on the last release; register again after a registry clear; create a
  counter's series that start at zero again at each registration, after a release cleared them; and, when the registry
  rejects one, leave none of them registered
- **SettingsViewTest** (`dashboard/`) — the Settings view's region both dashboards render (`SettingsHtml`): a section
  header for each group and a row for each setting, escaped, a moment shown as a time, out of band in a push, and an
  empty state; and the wording both use (`SettingText`), a secret only as set and TLS material only by its source
- **SnapshotCacheTest** (`dashboard/`) — the snapshot cache a dashboard's pages and sessions render from: one collect
  per refresh interval however many reads, a fresh collect for the push loop's tick that the reads after it share,
  shared by concurrent reads, and none reused across a change, even one landing mid-collect
- **TestPortsTest** — every port in `TestPorts` is distinct, so no two specs can bind the same port, and below 32768,
  outside the ephemeral range the OS hands out to outgoing connections; and none a spec binds is a host port a demo
  publishes (its `docker/compose.yml` ports, its kind configs' `hostPort`s, and its `demo.sh` port defaults), so a
  spec run while a demo is up doesn't reach the demo's container. The constants that mirror the product defaults skip
  the 32768 and demo checks, by name, since no spec binds them on the host. Each port constant in the subprojects'
  tests (`*_PORT = <n>`, in Kotlin or Java), which can't use `TestPorts`, is a `TestPorts` port that no root test
  source uses, as `STARTER_PROXY_*` and `KTOR_PLUGIN_PROXY_*` are, so a subproject's spec never binds a harness port
- **TlsMaterialTest** — TLS material given as PEM text (`*Pem` settings, `*_PEM` env vars): a value is read as PEM,
  as base64 of PEM (wrapped in lines or not), or as PEM on one line with literal `\n` escapes, and anything else is
  refused without the value in the message; a file and a PEM for the same material are refused; server and client
  contexts from PEM, from a mix of PEM and files, and, with no PEM, from common-utils' file builders (MockK); malformed
  PEM armor refused without its content; and the options resolve each `*Pem` setting, turn TLS on with it, log only
  whether it is set, and, embedded, throw `ConfigLoadException`
- **UtilsTest** — Utility functions: parseHostPort, sanitizeUrl, appendQueryParams, decodeParams, toJsonElement,
  setLogLevel, exceptionDetails; failureKey, which keys a failure by its gRPC status code, wrapped or not, and one
  without a status by its class, and its message only when asked; setLogLevel without Logback warning and setting
  nothing, and no main class but `LogbackLevels` naming a Logback class, since the library leaves Logback to the app

These support helpers also live here (not test classes themselves):

- **TestPorts** — canonical port constants shared across the unit, harness, and container suites, so no test
  hard-codes a port literal. The ones that mirror the proxy/agent config defaults and the fixed container ports are
  for asserting those defaults and for container-internal ports; anything a test binds on the host uses a dedicated
  entry instead, one no demo publishes, since a proxy, agent, or Prometheus already running on the machine holds the
  defaults. `TestUtils.startProxy` / `startAgent` bind the `HARNESS_*` gRPC, admin, and metrics ports unless a spec
  passes its own
- **TestCerts** — the repository's test certificates (`testing/certs`): their paths, text, and base64, for the specs
  that give TLS material as PEM
- **TestOptions** — factories that build `ProxyOptions` / `AgentOptions` from a list, `ConfigVals` from a HOCON
  fragment merged with the reference config, and `ProxyOptions` from a temp config file
- **SettingsTestSupport** (`dashboard/`) — lookups into a Settings view's groups (`group`, `value`) and all of its text
  (`text`), to search for a secret, shared by `CommonSettingsTest`, `ProxySettingsTest`, and `AgentSettingsTest`
- **EmbeddedTestServer** — `EmbeddedServer.startAndAwaitReady()`, which starts a Ktor server with `wait = false`
  and polls with real HTTP probes until it serves several consecutive clean replies before returning the port
- **LogCapture** — `captureLogs<T>(level) { … }`, which runs a block and returns what `T`'s logger emitted,
  optionally at a lower level, restoring the logger's appenders and level afterward
- **TracingMocks** — `collectInto(spans)`, a span handler that keeps a copy of each span a Tracing finishes, as it was
  when it finished, for the specs that read what was traced, and `collectingScrapeTracing(spans)`, a `ScrapeTracing`
  whose spans land there
- **MetricsMocks** — `mockProxyForMetrics()` / `mockAgentForMetrics()`, mocks that stub only what `ProxyMetrics`
  and `AgentMetrics` read when they are built and scraped, for the metrics specs and `misc/DashboardMetricNamesTest`;
  and `CountingProxyMetrics`, a relaxed `ProxyMetrics` whose counters are real, unregistered collectors, so a spec reads
  what a call counted (a relaxed mock's labeled counter can't be incremented: its `labelValues` answers a plain
  `DataPoint`); `CountingAgentMetrics`, the agent's counterpart, its collectors labeled `launch_id` first; and
  `registeredMetricNames()` / `registeredSnapshot<T>(name)`, what the default registry holds
- **DeadlineExceeded** — `deadlineExceeded(elapsed)`, a `DEADLINE_EXCEEDED` `StatusException` described as
  grpc-java describes one, elapsed time included, for the specs that check a repeating failure is compared by its
  status code rather than its message
- **DaemonAwait** — `awaitOnDaemonThread { … }`, which runs a block on a daemon thread and suspends until it
  returns, so the spec's timeout can fail a CPU-bound call, such as a backtracking regex, that ignores cancellation
- **LateArrival** — `lateArrival(wait, arrive)`, what a timed wait returns when something arrives just as its timeout
  passes: on runBlocking's one thread, the wait suspends, the arrival runs while the thread is held, and the thread
  stays held past the timeout, so the wait's resumption and its timeout meet without a race, and a wait that takes an
  element and is then cancelled by its timeout loses it every time
- **Lincheck** — the Kotest tag every `*LincheckTest` carries, which the default test run excludes

### Unit Tests — Misc (`misc/`)

- **CiHygieneTest** — every job in `.github/workflows` sets `timeout-minutes`, every tool image the `Makefile` runs
  (`*_IMAGE`) and every third-party image `ContainerTestSupport` gives the container suite is pinned by digest, in a
  form Testcontainers pulls by digest, the suite's Prometheus is the `Makefile`'s, every image each demo's compose file
  (`demos/*/docker/compose.yml`, every demo's but the Kubernetes demo's) pulls is pinned by digest and any Prometheus it
  runs is the `Makefile`'s, as is every image the Kubernetes demo's manifests and kind configs (`demos/kubernetes/k8s`
  and `demos/kubernetes/kind`) name, the kind node image among them, every `astral-sh/setup-uv` step installs a pinned
  uv, the same one in each, the CRD schemas `helm-lint` validates against come from a fixed commit, and `docs.yml`
  uploads and deploys the site only from the `workflow_dispatch` `make release` starts with `make gh-docs`, so a hung
  job gives its check back, what CI runs changes only with a diff here, and a push to `master` leaves the live site
  alone
- **CliReferenceTest** — the CLI Reference's tables against the code: each row's options are all the names of one
  JCommander `@Parameter` of its role's options or `BaseOptions`, and every option has a row; each row's variable is
  one its role's options read (an `EnvVars` entry they import), every one they read has a row, and every `EnvVars`
  entry is read by one role or the other; each row's `<span id>` is its key; each option sets its row's key, the
  options built from it equal to those built from `-D<key>=<value>` (`true` for a flag); each variable is read in the
  options' source by the call that takes its row's option's field, or, without an option, names its key's last part;
  each configuration page row that links to the CLI Reference links to its key's row and gives its option, variable,
  and default, and a configuration page's row for a key that has an option or a variable links to it; and every link
  to the CLI Reference, from any page, lands on an anchor it has
- **ConfigFileKeysTest** — every setting in a HOCON config the repo ships or its tests load (`config/test-configs`,
  `examples`, `testing`, `etc/compose`, each demo's `demos/<name>/config`, the container suite's agent configs, and the
  bundled `reference.conf`) is one `config/config.conf` declares, with the same shape, since HOCON ignores a key it
  doesn't know; a list's entries are checked against the fields its schema entry gives, as for `proxy.auth` and
  `agent.pathConfigs`, and a discovery targets file's `paths` entries against `agent.pathConfigs`'. The same goes for
  every `proxy.` or `agent.` key a test source under `src/test/kotlin` passes as a `-D` setting in a string literal,
  which HOCON ignores just the same; a key built at run time isn't seen. So does every key an
  `EmbeddedAgentConfig.setting()` call spells out, which the builder doesn't check, in the tests, the main sources,
  both subprojects, the demos' apps, the docs site's pages, the READMEs, `docs`, and `llms.txt`, but for the
  wrong-case keys `EmbeddedAgentConfigTest` gives on purpose; and every setting in a fenced HOCON block of those docs,
  with the snippet-file sections the docs site includes in it, checked as a proxy or agent config, a discovery targets
  file, or a lone list entry such as an `agent.filters` entry; a block that doesn't parse fails too
- **ConfigValsTest** — ConfigVals auto-generated from HOCON schema (validates default values, including the empty
  `proxy.dashboard.links`); a config that still sets the retired `proxy.dashboard.recentScrapesQueueSize` loads
- **DashboardMetricNamesTest** — the Grafana dashboards, the alert rules, the docs' PromQL, and the metric names
  on the website's pages all name series the proxy and agent expose (a counter only with its `_total` suffix); proxy
  panels filter by the job and instance pickers and agent panels by the agent picker; every counter is declared
  with the `_total` name it is exposed as; and the latency bucket ranges in `metrics-and-grafana.md`'s tables, and
  the bounds it and the website's Monitoring page list, are `SCRAPE_LATENCY_BUCKETS`. The metrics it builds are
  closed after the spec, which must leave none of their shared collectors registered
- **DataClassTest** — Config data classes (AdminConfig, MetricsConfig, ZipkinConfig parsing); the buffer pool,
  compilation, and native memory export flags each turn on only their own MetricsConfig field
- **DemoReleaseGateTest** — each demo's `make up` (its `demo.sh`'s `cmd_up`, run with a stubbed `docker` and
  `start_demo`) builds from the checkout while the release the docs give is no later than its `NEEDS_RELEASE_AFTER`,
  or while Docker Hub answers that it has no image of the release, and runs the release once Docker Hub has it; the
  Docker demos also run a release whose images Docker has without asking Docker Hub, and leave a Docker Hub they can't
  reach to the pull, while the Kubernetes demo, whose clusters pull the images themselves, stops. Each demo's
  `NEEDS_RELEASE_AFTER` is a release no later than the docs', and its `README_PATH`, which it prints beside its page on
  the docs site, is its README
- **DemoTourTablesTest** — each demo's README and its page on the docs site have a What This Demo Shows table that
  names the demo's tour parts, its `TOUR_PARTS` in `demo.sh`, in order; a README's rows link to their parts' sections
  of its Guided Tour, each headed with its part's name; a page's rows, where they link, link only to sections below the
  table; each `demo.sh`'s `PAGE_URL`, which `make up` and `make tour` print, is its page, and every section of the page
  it links to (`${PAGE_URL}#running-it`) is one the page has
- **DocsCodeBlocksTest** — the fenced blocks of the docs site's pages, the maintainer docs, and the READMEs: every
  HOCON block that has an agent scrape its own metrics port, `localhost:8083`, turns the port on too, as it's off by
  default; and no line of a Mermaid diagram's `accDescr`, the text a screen reader gives for it, ends with a word broken
  at its hyphen
- **DocsExamplesTest** — every `examples/*.conf` loads through `AgentOptions` and `ProxyOptions` (a discovery
  targets file through `FileDiscoverySource`) with labels service discovery can serve; `simple.conf` fetches the
  proxy's metrics from `PROXY_HOSTNAME`, as the Docker quick start needs, or from `localhost`; no `docker run`
  command in the README, `llms.txt`, or the website's pages and snippets combines `--rm` with `--restart`, which
  Docker refuses; and every port a compose file publishes (`etc/compose`, each demo's `docker/compose.yml`, and the
  compose snippets in those docs) is on `127.0.0.1`, since Docker publishes past the host's firewall. The cloud-proxy
  example, which publishes the agents' port with a token and TLS, isn't read
- **HomebrewFormulaTest** — every line of the Homebrew formulae in `etc/homebrew`, with the version and checksum
  `make homebrew-formulae` fills in, fits within 118 characters, the limit of the `brew style` check the tap's CI runs
  and this repository's 120 doesn't hold a formula to
- **LocalTestConfigTest** — no test source under `src/test/kotlin/io` names `raw.githubusercontent.com`, so a spec
  reads its config from the checkout, and a renamed or deleted one fails instead of testing a copy on GitHub `master`
- **LogbackConfigTest** — the fat JARs' `logback.xml` (`src/shadow/resources`, kept out of the library jar), loaded
  from inside a JAR, and `logback/docker-logback.xml`, loaded from disk, configure without a WARN status, so neither
  makes logback print its status list at every start
- **LogoTest** — the website's header (`zensical.toml`'s `logo`), the README's title, and the dashboards' login pages
  show the logo, `website/prometheus-proxy/docs/images/logo.svg`, and the website's tabs (`favicon`) and the
  dashboards' tabs and bars show its small-size cut, `favicon.svg`; the dashboards carry both inline
  (`DashboardLogo.LOGO_SVG` and `SMALL_LOGO_SVG`) as the same markup
- **OptionsTest** — ProxyOptions and AgentOptions config file loading
- **ReleaseCheckTest** — the parts of `make release-check` that read the checkout, run as `make` runs them:
  `_check-version-files`, given the version as the previous one, names every line of the files step 2 of
  `docs/RELEASE.md` bumps that gives the version (the Quick Start's JAR links among them), but `llms.txt`'s release
  annotations and the README's New Features summary, and passes the tree as it is; `_check-changelogs` passes only on
  the day both changelogs date a release, and names the date a changelog gives; and `_check-demos-pull-release`
  refuses a release at or below a demo's `NEEDS_RELEASE_AFTER`, or a demo whose value it can't read (in a temporary
  copy of the demos). `make github-release-notes` gives a release's `RELEASE_NOTES.md` section with each paragraph and
  list item on one line, its headings a level up, and the full changelog's link last. Step 2 of `docs/RELEASE.md` and
  the publishing-release skill name the website pages `RELEASE_VERSION_FILES` lists, no more and no fewer
- **ShadowServiceFilesTest** — every class listed in the pinned fat-JAR service files
  (`src/shadow/resources/META-INF/services/`) loads and implements its service, so a stale provider name fails here
  instead of only in the container suite
- **SiteVersionTest** — `make site-version` prints the version the docs site's image tags and Maven coordinate give:
  the Docker examples' tag, a release version, whatever `gradle.properties` holds. It skips `latest` and a full stop
  after a tag, and fails when the site gives two versions or none, the Spring Boot starter's coordinate counted. Every
  file a page includes with `--8<--` is under the directories it reads, or names no version. `bin/docker-*.sh` read
  their version from it, not from `gradle.properties`, whose `-SNAPSHOT` between releases has no image, and, run with a
  stubbed `docker`, say so when Docker Hub has no image of it rather than leave it to `docker run`
- **TestingGuideTest** — every `*Test.kt` under `src/test/kotlin` is named in its package's section of this
  document, every spec of a subproject that `settings.gradle.kts` includes (`ktor-plugin`, `spring-boot-starter`) in
  its subproject's section, and every support file under `src/test`, the subprojects' included, somewhere in it.
  Every `make` target it names is in the `Makefile`, or, for a demo's own (`make up`), in every demo's; every job
  `make ci` runs (`CI_JOBS`) is named here, and `make all-tests`' entry names every target it runs
- **WebsiteConfigKeysTest** — every `proxy.` and `agent.` config key the website's pages name is in
  `config/config.conf`, since HOCON ignores a key it doesn't know; and the default the pages' tables give for a key is
  the number, boolean, or string `config/config.conf` declares, or a tscfg spec's default (`double | 1.0`), and, for a
  setting it leaves to gRPC (`long | -1`), gRPC's own: `GrpcUtil`'s server and client keepalive defaults, the server
  builder's handshake timeout, and `INT_MAX` for a limit that's off; and the two gRPC defaults `ProxyOptions` names for
  the startup log and the dashboard are gRPC's. A list's or an object's default isn't checked, nor the `logLevel` the
  CLI Reference gives

One support helper also lives here (not a test class itself):

- **WebsitePages** — the docs site's pages with their fenced code blocks removed, and the inline code in them, for
  `DashboardMetricNamesTest` and `WebsiteConfigKeysTest`

### Integration Tests (`harness/`)

Integration tests exercise full proxy+agent workflows with real instances:

- **InProcessTestNoAdminMetricsTest** — In-process gRPC tests without admin/metrics
- **InProcessTestWithAdminMetricsTest** — In-process gRPC tests with admin/metrics enabled
- **NettyTestNoAdminMetricsTest** — Netty transport tests without admin/metrics
- **NettyTestWithAdminMetricsTest** — Netty transport tests with admin/metrics enabled; both metrics endpoints serve
  the JVM buffer pool and compilation metrics `harness.conf` turns on, and no native memory metrics, which need
  `-XX:NativeMemoryTracking`
- **TlsNoMutualAuthTest** — the standard harness suite over a real TLS channel (Netty), without mutual
  authentication
- **TlsWithMutualAuthTest** — the standard harness suite over a real mutual-TLS channel (Netty, client certs)

Each integration test class runs a standard suite defined in `AbstractHarnessTests`, with its agent's chunk size at
1 KB (`HarnessConstants.DEFAULT_CHUNK_SIZE_KBS`), the smallest `agent.chunkContentSizeKbs` takes, so a scrape's
chunked transfer crosses the spec's transport:

1. Scrape metrics through proxy
2. Chunk a scrape larger than the chunk size: the proxy reassembles it from as many chunks as the agent's chunk size
   cuts it into, and the client gets it intact; with metrics on, the agent counts one chunked result
3. Return not found for missing path
4. Return not found for invalid path
5. Add/remove paths correctly
6. Add/remove paths under concurrent access
7. Handle invalid agent URL gracefully
8. Timeout when scrape exceeds deadline

#### Targeted harness specs

The classes above all run that same standard suite. These run their own scenario instead, each pinning one
mechanism that the standard suite cannot reach:

- **AdminDefaultPathTest** — Admin endpoints with default paths (ping, version, health, thread dump)
- **AdminEmptyPathTest** — Admin endpoints with empty paths (disables endpoints)
- **AdminNonDefaultPathTest** — Admin endpoints with custom paths
- **AgentWebDashboardTest** — the agent's dashboard, served by a real agent, and each path row's `data-path` and
  `data-state`, which `demos/basics/bin/screenshots.sh` reads: with its proxy down, failing, with the endpoint's error,
  every path pending, the target's password kept off the page, and its WebSocket pushing the status bar at once and then
  the regions each view asks for; connected, one path registered and one refused, with the proxy's reason, the detail
  pane as a page and as htmx's fragment, the connection's endpoint, connects, and token, and the root's redirect; a
  scrape through the proxy in the path's strip and Recent scrapes, and Scrape now fetching the target from the agent
  through the path's filter, recorded nowhere, not framed, refused without the page's header or for a path the agent
  doesn't know, saying why, and working with the proxy down; with Scrape now off, no button and no route; with a
  password, the login page under the agent's name, not cached or framed, and a cookie named for the agent; and no
  dashboard without `--dashboard`; and its Activity view showing the agent connecting and a refused path, filtered, and
  pushing the disconnect, with the failure that ended it, and failing connects to an open page when the proxy stops;
  and, with the agent's metrics on, its connect, its refused path, its disconnect, and its dashboard sign-in counted
- **AgentDiscoveryTest** — dynamic target discovery: the agent reconciles a watched file's paths, adding,
  replacing and removing them with no restart
- **AgentMetricFilterTest** — per-path metric filtering: a denied metric family never reaches the proxy
- **AgentPathAuthTest** — per-agent identity with path-glob authorization, over Netty so the token header
  actually crosses the wire; a security boundary. Its `proxy.auth` list is in `config/test-configs/path-auth.conf`,
  read through `HarnessConstants.localConfigFile`
- **AgentPathTakeoverTest** — two agents of different launches register the same exclusive path, and the later
  takes it over: the proxy tells the first, which shows the path as taken over rather than registered, waits while
  the second holds it, and takes it back on its own connection, within seconds of the second leaving, which a 60s
  retry couldn't do; the first is told while scrapes of its other path keep it busy; and, with the path in its static
  config, it still waits after losing its connection and reconnecting, then takes the path back on the new
  connection once the second leaves. Its configs are `config/test-configs/path-takeover.conf` and
  `path-takeover-static.conf`
- **AgentProxyFailoverTest** — the agent lands on its second endpoint once the first proxy stops. Netty
  only: an in-process channel ignores host and port, so failover cannot be expressed there
- **AgentRejectedPathRetryTest** — a static path rejected because a live agent of another identity serves it
  registers once that agent disconnects, with no reconnect, while a path the agent's identity may never register
  reaches the proxy once rather than on every retry, whether static or discovered; Netty, so each token resolves
  to its own identity
- **EmbeddedAgentApiTest** — the public `startAsyncAgent()` handle connects from a config file, reports its
  identity, and disconnects on `shutdown()`; the `EmbeddedAgentConfig` builder's `start()` starts an agent from
  settings built in memory, whose path the proxy serves, and the overload of `startAsyncAgent()` that takes an
  in-memory `Config` throws `ConfigLoadException` for a setting it can't use; the handle's `isConnected` and
  `proxyEndpoint` report the connection once the proxy serves the agent's path, go false after `shutdown()` or once the
  proxy goes away, and are false before the first connection; a path given a username and password with the builder
  reaches a target that demands them as Basic Auth, whatever their characters; a path the builder's `source()` adds
  serves what its source writes, through the proxy; a program's default registry, served with `source()`, and the
  agent's own metrics, served with `agentMetricsAt()`, each series at one path, the agent's connection among them and
  no JVM metrics; and a config served from a URL with credentials that holds a setting the agent rejects, whether its
  options or its constructor reject it, throws `ConfigLoadException`, with the credentials in none of its messages, its
  cause chain, or the log
- **AgentMetricsProxyPathTest** — a standalone agent with `agent.metrics.proxyPath` set and its metrics port off
  serves, through the proxy, its own `agent_*` series and the JVM metric groups its `agent.metrics` settings turn on,
  which it registers itself, since only the port's service did; its `toString()` names the path
- **EmbeddedAgentMetricsTest** — with metrics on, an agent started again after `shutdown()` and two agents at
  once each connect and count their own connects without a registry clear in between; a stopped agent's series go
  with it; and a host metric with an agent metric's name fails `startAsyncAgent()`, with the registry's rejection
  as the cause
- **AgentTokenAuthTest** — the legacy pre-shared agent token, over Netty so the header crosses the wire: a
  matching token registers, and a wrong one is rejected with UNAUTHENTICATED and never registers; a security
  boundary
- **InProcessClientCancelledScrapeTest** — a scrape Prometheus abandons before a slow target answers is
  still recorded, as `client_cancelled`, rather than vanishing from the metrics, `/debug`, and the dashboard
- **InProcessScrapeTimeoutHeaderTest** — a client's `X-Prometheus-Scrape-Timeout-Seconds` reaches the agent, which
  stops a slow target's scrape at that deadline (408) instead of running to its own longer `scrapeTimeoutSecs`
- **InProcessHealthCheckTest** — the agent and proxy scrape-backlog health checks through the admin
  endpoint, including both unhealthy branches
- **InProcessHeartbeatDisabledTest** — with the heartbeat disabled the connection stays usable and shutdown
  still completes promptly (finding 6 in `docs/archive/CODE_REVIEW_JULY_2026.md`)
- **InProcessHeartbeatEvictionTest** — a heartbeat reporting eviction ends the connection so the run loop
  reconnects with a fresh agent id, rather than leaving a zombie agent
- **InProcessIdleShutdownTest** — stopping an idle connected agent must not deadlock (finding 1 in
  `docs/archive/CODE_REVIEW_JULY_2026.md`)
- **InProcessReconnectTest** — the full disconnect → reconnect → re-register cycle, in-process; then a scrape
  chunked at 1 KB arrives intact over the new connection's stream
- **InProcessStaleAgentCleanupTest** — the eviction thread staying off, and being forced on by the
  transport-filter mode
- **InProcessTransportFilterDisabledTest** — both sides with the transport filter off: a scrape succeeds,
  and the request stream's own termination is what reclaims the agent context; an agent that
  `maxAgentsPerIdentity` refuses keeps retrying, and connects once another agent leaves
- **InProcessZipkinTest** — with Zipkin on, the proxy and the agent each start, serve a scrape, and stop, against a
  collector that can't be reached; with it on for both and a stand-in collector, the agent's client spans and the
  proxy's server spans arrive under their configured service names, in one trace, a scrape's scrape, forward, and fetch
  spans form a trace of their own, and an agent that heartbeats reports no heartbeat span; and at a sample rate of 0,
  neither side reports a span
- **ProxyWebDashboardTest** — the dashboard service rather than the renderer: page, WebSocket push, selection
  round-trip, both layouts, the versioned asset and brand icon routes (a year's `max-age`, an icon served as
  `image/svg+xml` with `nosniff` and a `sandbox` CSP, and a 404 off the allowlist or without the version), a registered
  path's target, source, and the config's links built from its job label, one of them an icon the page's head gives its
  mark, a root-mounted base path, with `allowedHosts` set a 403 on every route for a request naming an unknown host, an
  agent's page and detail fragment not framed, page GETs and WebSocket messages rendering the cached snapshot while a
  topology change still shows on the next page, a gone path staying in the path layout after more scrapes than the
  retired recent-scrapes queue held, an agent's identity in both layouts with the path search filtering from the URL and
  over the socket, a path the agent's identity may not register in the Activity view, from a GET and a push, and a
  session kept open through a push that fails to render, and a scrape-now against a stub target returning what it had,
  not framed, while the proxy's and the agent's scrape metrics, the History, and the agent's Recent scrapes stay as
  Prometheus's scrape left them and the agent counts it in `agent_dashboard_scrapes_total`, refused within its cooldown,
  for an unknown path, saying why, without htmx's header, or from another origin, and absent when off; and an Activity
  page sent its table over a real socket in the first frame rendered from its message, left out of the ticks after it
  while nothing is recorded, and sent again for a new message and once an agent's connection is recorded
- **ProxyWebDashboardLoginTest** — the dashboard with a password set, split from ProxyWebDashboardTest, a proxy per
  spec: a page navigation without a session sent to the login page with its path as `next`, and anything else (the
  WebSocket handshake, the scrape action, an asset, `/session`) a 401 with no `WWW-Authenticate`; the login page, not
  cached or framed; a wrong login answered with the alert, and one WARN for two, never naming the username; a right one
  setting the session cookie (`HttpOnly`, `SameSite=Lax`, the base's `Path`, 7 days, `Secure` from an https origin) and
  returning to `next`, never off-site; the dashboard, not cached or framed, its WebSocket, and `/session` working with
  the cookie; Log out ending the session; a foreign origin refused on the login and Log out forms; an oversized or
  chunked login body refused; Basic Auth for scripts; past 10 wrong logins from one address, every login from it refused
  with a 429 and `Retry-After`, the right one included, from the form or as Basic Auth; an open page's WebSocket closed
  once its session ends (`VIOLATED_POLICY`), without waiting for a reconnect; a browser's saved Basic login (a request
  with `Sec-Fetch-Mode`) ignored, the page, `/session`, and the WebSocket included, and not logged as wrong; a root
  mount's login at `/login`; with no password, no login page and no Log out; and each login counted in
  `proxy_dashboard_logins_total` by outcome, a script's right Basic Auth not counted as a sign-in
- **TlsMutualAuthRejectionTest** — the negative mutual-TLS path over a real Netty handshake, which the
  in-process TLS specs cannot perform (item 28 in `docs/archive/CODE_REVIEW_JUNE_2026.md`)
- **TlsPemTest** — TLS over Netty with every certificate and key given as PEM, none as a file: the proxy's as base64
  `-D` settings, and an embedded agent's as PEM text, set with the builder; a scrape crosses the channel one-way and
  with mutual TLS

#### Harness Infrastructure (`harness/support/`)

- **HarnessSetup** — Base class providing `setupProxyAndAgent()` / `takeDownProxyAndAgent()` lifecycle; before each
  spec it clears the default registry and has `SystemMetrics` forget it, so each spec's proxy and agent register the
  JVM metric groups again
- **HarnessHelpersTest** — `awaitPortFree()` returns on a port of the spec's own and fails when a port stays taken,
  `awaitPortReady()` fails on a port nothing listens on, held for the test by a socket bound but not listening, so no
  other process can take it meanwhile, and `HarnessConstants.localConfigFile()` fails for a missing test config rather
  than fetching one from GitHub
- **TestUtils** — Utility functions: `startProxy()`, `startAgent()`, `exceptionHandler()`
- **EmbeddedAgentScrape** — `withEmbeddedAgentScraped()`, which starts a proxy, a scrape target, which can demand
  Basic Auth, and an agent built with `embeddedAgent { }`, waits until the proxy serves the target's metrics, and tears
  all three down; `EmbeddedAgentApiTest` and `TlsPemTest` share it
- **AbstractHarnessTests** — Abstract base defining the standard 8-test suite
- **BasicHarnessTests** — Reusable test implementations (missing path, invalid path, add/remove paths, etc.)
- **HarnessTests** — Core integration logic: `proxyCallTest()` (sequential, parallel, concurrent queries),
  `chunkedScrapeTest()` (a target several chunks long once gzipped, checked against the proxy's DEBUG summary of the
  transfer it reassembled, and the agent's `chunked` result count when metrics are on), and `timeoutTest()`

Two more live one level up, in `harness/` itself:

- **HarnessConfig** — Enum defining test scale configs (MINI, SMALL, MEDIUM, LARGE, XLARGE, XXLARGE)
- **HarnessConstants** — Test constants (ports, delays, config paths) and the active `HarnessConfig`, selected by
  the `HARNESS_CONFIG` env var or `-PharnessConfig` (default MINI, as CI runs; `make medium-tests` and
  `make xxl-tests` run the heavier profiles)

### Container Tests (`containers/`)

End-to-end Testcontainers specs that build the proxy and agent images from `etc/docker/*.Dockerfile`, stand them up
alongside `nginx:1.31-alpine` metrics stubs (and `prom/prometheus:v3.14.0` where needed), each pinned by digest as
well as tag, and exercise the full Prometheus → proxy → agent → endpoint scrape path over real network transport.
Every spec is gated on `RUN_CONTAINER_TESTS=true`; shared factories live in
`containers/support/ContainerTestSupport.kt`. With `RUN_CONTAINER_TESTS=true` the test task builds the fat JARs first,
since the images are built from them, so `make container-tests` and container-tests.yml are one Gradle invocation.

`containers/support/ContainerTestsConfig.kt` is the Kotest config of a run of only the container specs (`--tests`
naming nothing else, as `make container-tests`, `make scaling-tests`, and container-tests.yml do), which
`build.gradle.kts` selects with `kotest.framework.config.fqn`: it runs three specs at a time. Each spec starts its own
containers on a network of its own, with random host ports, and shares only the two images, which build once, so the
specs spend most of their time waiting on containers to start, side by side. A run with any other spec stays one spec
at a time.

`containers/support/LoopbackPortBindings.kt` publishes every container port on `127.0.0.1` instead of
Docker's `0.0.0.0` default. It has no call sites: it is registered through
`src/test/resources/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier`, which is what
lets it cover containers built directly by a spec as well as Testcontainers' own Ryuk reaper. Deleting that
service file silently disables it. Without it a wildcard-published port can collide with a loopback-specific
listener already on the host — both binds succeed, and the OS then routes the test's requests to the other
process, which surfaces as a wait strategy timing out against a status the container never produced.

- **ContainersSmokeTest** — baseline: Prometheus scrapes a single metric through the proxy and agent
- **ContainersServiceDiscoveryTest** — Prometheus finds the agent's path only through `http_sd_configs`, with
  `sdTargetPrefix` given as a URL, and scrapes it (`up == 1`)
- **ContainersProxyHttpTest** — HTTP surfaces: registered-path scrapes, 404/503 passthrough, admin servlets,
  self-metrics, service discovery
- **ContainersConsolidatedTest** — two consolidated agents register the same path; the proxy merges responses
- **ContainersLargePayloadTest** — forces the chunked + gzipped scrape path with a large synthetic payload
- **ContainersReconnectTest** — agent reconnects to a replacement proxy and scrapes resume
- **ContainersAgentTokenAuthTest** — pre-shared agent-token authentication on the gRPC channel
- **ContainersTlsTest** / **ContainersHttpsTargetTest** — server-only and mutual TLS on gRPC, and TLS with the
  certificates and key given as `*_PEM` environment variables, no file mounted, in the distroless images; HTTPS
  upstreams
- **ContainersDiscoveryTest** — dynamic target discovery in the packaged JARs: a path reconciled from a
  watched file, with no static `pathConfigs`, scrapes end to end
- **ContainersProxyFailoverTest** — two proxies on distinct network aliases; the agent moves to the standby
  when the active one stops
- **ContainersNginxTest** — the proxy behind nginx, which ends the agent's TLS and passes its gRPC calls on
  (`grpc_pass`), with the transport filter off on both sides and nginx's stream timeouts cut to 10 seconds: a scrape
  reaches the target, and the stream keepalives then keep the idle agent connected (one connect, no stream ended),
  while with them off nginx cuts it off, the control that shows the timeouts bite
- **ContainersWebDashboardTest** — the dashboard against the real fat JAR, which is the point: htmx ships as
  a WebJar served from the classpath, so a packaging regression would leave the page loading a 404
- **ContainersPortBindingTest** — every published port binds loopback rather than the wildcard address
- **ContainersPackagingTest** — the images run as UID and GID 1001, given by number, as the charts expect, and own
  `/app`; their JVM knows its user and uses UTF-8 (the distroless base has neither a user at 1001 nor locales); and
  each fat JAR's `META-INF/NOTICE` and `META-INF/LICENSE` carry every dependency's notice and license text
- **ContainersScalingTest** — parameter-driven scaling (see below)

#### Scaling Test (`ContainersScalingTest`)

A single parameter-driven spec that stands up a fresh topology per scenario and verifies every path is
scrapable end-to-end, then asserts the proxy's own `proxy_agent_map_size` / `proxy_path_map_size` gauges match
the expected counts. Each scenario (a `ScalingScenario`) scales four dimensions:

| Dimension | `SCALE_*` env var | Default |
|-----------|-------------------|---------|
| Number of agents | `SCALE_AGENTS` | table |
| Endpoints (paths) per agent | `SCALE_ENDPOINTS_PER_AGENT` | table |
| Series per endpoint (payload size) | `SCALE_SERIES_PER_ENDPOINT` | table |
| Consolidated fan-out agents (share one path) | `SCALE_CONSOLIDATED_AGENTS` | table |
| Concurrent vs sequential scrape verification | `SCALE_CONCURRENT` | table |
| Max in-flight checks when concurrent (0 = unbounded) | `SCALE_CONCURRENCY_LIMIT` | `0` |

All agent configs and per-endpoint exposition payloads are generated at runtime (no fixture files). With no
overrides, a small CI-safe default table runs (a baseline row, an agents × endpoints row, a large-payload row,
and a consolidated-fan-out row). Setting **any** `SCALE_*` variable collapses the run to a single tuned
scenario built from those values (with modest fallbacks), letting the inputs be dialed up via
`make scaling-tests SCALE_...=...` without editing code.

### Spring Boot Starter Tests (`spring-boot-starter/`)

The starter is a Java subproject, so its tests are JUnit 5 with AssertJ, in the Spring Boot idiom, under
`spring-boot-starter/src/test/java/`. `./gradlew :spring-boot-starter:test` runs them, and so does `build`. They run
at Spring Boot's versions of the agent's libraries, enforced as an app's dependency management enforces them, so the
agent runs with what it gets in an app. They run twice: `test` at the Spring Boot the starter compiles against, and
`oldestSpringBootTest`, which `test` runs too, at the oldest it supports (`spring-boot-oldest` in
`libs.versions.toml`, Spring Boot 4.0's last release), with kotlinx-coroutines and the Prometheus client set as the
Spring Boot Starter page tells such an app to set them. A run that names tests with `--tests` filters `test` alone,
and leaves `oldestSpringBootTest` out. `TestingGuideTest` fails on a spec of the starter's that isn't named here, or a
support file that isn't named in this document.

- **PrometheusProxyAgentAutoConfigurationTest** — the auto-configuration, through `ApplicationContextRunner`: the agent
  stays off without a proxy, starts and stops with the context with one, and isn't made with `enabled=false`; each
  property becomes its `EmbeddedAgentConfig` setting, `metrics-proxy-path` and its labels included, with the secrets
  out of `toString`, and a target URL's user info and query values masked in the properties' own, and labels without a
  path are warned of; the default name (the app's,
  with its build's version) and path (`<app>_metrics`, or `application_metrics`); the registry served, or not with
  `source=none` or no `PrometheusRegistry` bean; `settings` keys keeping their case from a properties file, and losing
  it from an environment variable; customizers running after the properties in their order, a builder method of one
  replacing a property but not a `settings` entry, which only its `setting()` replaces; a customizer's proxy, or a
  `settings` key's, starting the agent with no `proxy` property, a required one too; `start()` on a running agent
  keeping it; the started line naming no proxy, which a customizer can change; `required` failing the
  app without a proxy, on a bad setting, on a setting only the agent rejects as it starts (a certificate it can't
  read), which only `start()` reaches, or on a customizer's failure, a checked exception included, and the app
  running without the agent otherwise, whatever stopped it: a setting the builder or the agent rejects, a certificate
  it can't read, a `settings` key its config can't hold, or a customizer that throws; and the `prometheusAgent` health
  indicator, from its own auto-configuration: UNKNOWN while off or connecting, turned off by
  `management.health.prometheusagent.enabled`, absent for an app with two agents of its own, and absent without
  spring-boot-health, which the app then starts without.
- **PrometheusProxyAgentIntegrationTest** — a `@SpringBootTest` app with Actuator and no web server, its agent pointed
  at a proxy in the same JVM, on fixed ports that the root project's `TestPorts` lists (`STARTER_PROXY_*`): the proxy
  serves the app's Micrometer meter and Spring Boot's JVM metrics at
  `/roastery_metrics`, its service discovery has the path's labels, and the health component is UP with the agent's
  name and proxy.
- **ConfigurationMetadataTest** — the configuration metadata in the jar, which IDEs complete the properties from: every
  property has a description, and the defaults are the fields' initial values. An incremental compile drops the
  descriptions, so the build compiles the starter in full.
- **StarterExamplesTest** — the Spring Boot Starter page's examples: `website/application.yml`, in the test resources,
  reads the agent's settings from the environment, and with `PROMETHEUS_PROXY_CERT` checks the proxy's certificate
  against the name `fromEnvironment()` does, `PROMETHEUS_PROXY_AUTHORITY` or `prometheus-proxy`; and
  **AgentCustomizations** (`website.AgentCustomizations`), with its stand-ins **SecretStore** and **BatchJobs**, sets
  the certificate and serves a second registry.

### Ktor Plugin Tests (`ktor-plugin/`)

The Ktor plugin is a Kotlin subproject, with Kotest specs under `ktor-plugin/src/test/kotlin/`. `./gradlew
:ktor-plugin:test` runs them, and so does `build`; the root project aggregates their coverage, so the coverage floors
count them. `TestingGuideTest` fails on a spec of the plugin's that isn't named here, or a support file that isn't named
in this document.

- **AgentLifecycleTest** — the lifecycle's events raised by hand on a mocked app's monitor, with the agent's start
  replaced: the app's `ApplicationStarted` starts the agent and puts its handle in the app's attributes, and another
  app's, as a development-mode reload gives, doesn't; the agent stays off when disabled or without a proxy, and a
  required one fails the start; a proxy that only the file's `settings` or an agent block's `setting()` names starts
  it; a start that throws leaves the agent off, or, when required, fails the app's start with
  the exception, a checked one wrapped, and a start that returns none, as `startIfConfigured()` does for a failure,
  leaves it off; `ApplicationStopPreparing` begins the stop without waiting for it, and `ApplicationStopping` waits for
  that stop, its agent's `shutdown()` held until the disposal is in its join, stops an agent the engine didn't, and
  gives up waiting at its timeout, once whichever come; an agent that fails to stop doesn't fail the app's; and the
  handlers are gone once the app stops.
- **PrometheusProxyAgentConfigTest** — `prometheus-proxy.agent` in HOCON, YAML (`full-section.yaml`), and a
  `MapApplicationConfig` builds the tree the same builder calls do, `metrics-proxy-path` and its labels included; a blank
  value, such as YAML's empty default for an unset environment variable (`unset-proxy.yaml`), counts as unset; the
  dashboard login's default username; a value that isn't a boolean or a number; metrics proxy path labels without a
  path warned of; the registry served at its path, and refused without one; the install block's settings taking
  precedence, and every `agent { }` block running in turn.
- **PrometheusProxyAgentPluginTest** — the plugin in a `testApplication`, with a real agent pointed at a port nothing
  listens on (`closedPort()`, in **TestSupport.kt**): started by the file's proxy or the install block's, and stopped
  with the app; off when the file disables it; off on a setting it rejects, which fails the app's start when required.
- **PrometheusProxyAgentIntegrationTest** — a `testApplication` with Micrometer and the plugin, its agent pointed at a
  proxy in the same JVM on fixed ports that the root project's `TestPorts` lists (`KTOR_PLUGIN_PROXY_*`): the proxy
  serves the app's meter and Micrometer's JVM metrics at `/ktor_app_metrics`, its service discovery has the path's
  labels, the handle reports the connection, and once the app stops, the proxy no longer has the path.
- **ServerBindFailureTest** — an app on the CIO engine with the plugin (**TakenPortApp**), run in a JVM of its own,
  whose port the spec holds (`KTOR_PLUGIN_TAKEN_PORT` in the root project's `TestPorts`): its start fails with the
  `BindException` after the agent started, and the JVM exits with status 1, its app disposed by Ktor's shutdown hook,
  rather than run on with its agent.
- **KtorPluginExamplesTest** — the Ktor Plugin page's examples, in `website/KtorPluginExample.kt` and the test
  resources' `website/application.conf`: the module runs without the agent where `PROMETHEUS_PROXY` isn't set, and its
  health route says so; the file starts the agent with the environment's proxy and the file's name, and leaves it off
  without one, and builds settings that serve the registry at its path.

## Testing Patterns

### Mocking with MockK

Tests use relaxed mocks for dependencies that aren't central to the test:

```kotlin
val mockProxy = mockk<Proxy>(relaxed = true)
every { mockProxy.options } returns mockOptions
```

### Test Mode

Some components support a `isTestMode` flag that disables certain behaviors (like health check registration):

```kotlin
val manager = ProxyPathManager(proxy, isTestMode = true)
```

### Coroutine Testing

Specs are `StringSpec` classes that declare their tests in an `init {}` block. Kotest test bodies are already
suspending, so a test calls suspend functions directly with no `runBlocking` wrapper, and MockK's `coEvery` /
`coVerify` stub and verify suspend functions:

```kotlin
class PathDiscoveryServiceTest : StringSpec() {
  init {
    "a successful read reconciles the desired set" {
      val desired = [DiscoveredPath("a", "a_metrics", "http://a/m", "{}")]
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { desired }, 30)

      service.reconcileOnce()

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(desired) }
    }
  }
}
```

`runBlocking` is only needed from non-suspending code, such as a lifecycle callback.

### Harness Test Scaling

Integration tests scale via `HarnessConfig` (MINI to XXLARGE), which controls the number of agents, paths, and
iterations used in integration tests.

### Bug Regression Tests

Several tests serve as regression guards for specific bugs, documented inline:

- **RecentReqsSynchronizationTest** — Prevents ConcurrentModificationException on recentReqs
- **ChunkedContextTest** — Validates chunk sequencing and input validation
- **AgentHttpServiceHeaderTest** — Prevents stale Accept headers in cached HTTP clients

## Coverage

Generate coverage report:

```bash
./gradlew koverHtmlReport
# Report available at build/reports/kover/html/index.html
```

Coverage excludes generated code, as configured in `configureCoverage()` in `build.gradle.kts`: the gRPC stubs
(`io.prometheus.grpc.*`), `io.prometheus.BuildConfig` (buildconfig plugin), and `io.prometheus.common.ConfigVals`
with its nested classes (tscfg).

Two gates enforce it, and CI runs both in one invocation, as `make coverage-verify` does:

```bash
./gradlew koverVerify koverVerifyPerClass
```

`koverVerify` checks the total floors (line and branch coverage across the project); `koverVerifyPerClass` checks
every hand-written class at 80% line coverage on its own. The per-class floor skips compiler-generated lambda
classes and the `Proxy`/`Agent` entry points, so a new class needs its own tests.

## Writing New Tests

1. Place tests in the appropriate package under `src/test/kotlin/io/prometheus/`
2. Use Kotest matchers (`shouldBe`, `shouldNotBeNull`, etc.)
3. Use MockK for mocking (`mockk`, `every`, `verify`)
4. Write each spec as a `StringSpec` with its tests in an `init {}` block; call suspend functions directly
5. Add `@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")` to avoid lint warnings
6. Add comments for complex test scenarios explaining what is being validated
7. For integration tests, extend `AbstractHarnessTests` and use the harness support infrastructure
