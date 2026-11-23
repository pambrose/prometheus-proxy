# Worker demo: an agent embedded in a plain Kotlin program

## Intent

The repository has demos of the agent embedded in a Ktor app (`demos/embedded`, the coffee shop) and in a Spring Boot
app (`demos/spring-boot`, the roastery), but none of a plain JVM program with no framework. That is the case where the
embedded agent matters most: a worker (a queue consumer, a scheduler, a daemon) usually has no HTTP server at all, so
reading its metrics in-process is the only way to get them to Prometheus without opening a port.

The demo exists to show that embedding the agent in a plain program takes a few lines of high-level code. It is built
on the core API additions of the `simpler-embedding` work (approach A): `fromEnvironment()`, `source(path, registry)`,
`agentMetricsAt(path)`, `startIfConfigured()`, and an `AutoCloseable` `EmbeddedAgentInfo`, in PR #517
(branch `simpler-embedding`); this document uses the names it ships. That work is being built and reviewed first, as its own
change; this demo is built after it lands, on top of it, and needs no core change of its own. If the demo turns up
something the core should do differently, report it rather than changing the core here.

## Goals

- A self-contained demo in `demos/worker`, laid out and run like the other demos (`make up`, `make tour`, `make down`,
  `make help`, `./demo.sh <scenario>`), with a README and a website page.
- The worker's agent wiring is short enough to show whole on the website page and in the README.
- The worker opens no listening socket at all, and the demo proves it.
- Scaling the worker out adds Prometheus targets with no change to Prometheus's config, and scaling in removes them
  cleanly.
- A worker with no proxy configured, or with a setting the agent rejects, keeps working.

## Non-goals

- The agent's dashboard, its admin and debug endpoints, TLS, secrets from PEM variables, and the proxy going away at
  runtime: the embedded demo shows them.
- Consolidated mode, eviction of a silent agent, and path takeover: the basics demo shows them.
- A Java version of the worker. The website page's Java tab, compiled in `consumer-check`, covers plain Java.
- Short-lived batch jobs. The README says, in a sentence, that a job finishing before Prometheus's next scrape needs a
  Pushgateway, not an agent.

## Anti-goals

- Agent wiring that needs explaining line by line, or boilerplate a reader would copy: if the shutdown plumbing (below)
  reads as boilerplate once written, report it as a candidate for the core API rather than hiding it.
- Anything in the demo that only works because of the demo, such as a port opened for a check.

## Constraints

- Everything `AGENTS.md` says about demos: self-contained directory with its own Makefile, every path in it from the
  Makefile's own location, the root Makefile's `DEMOS` list, `make help` annotations.
- `TestPortsTest` checks every host port a demo publishes against the test ports. `CiHygieneTest` requires a digest on
  every third-party image (compose files pin `image: name:tag@sha256:...`, as the other demos do).
- `demos/*` copy style: tours teach the product. Each part explains a point or gives the reader an action, never a
  presenter prompt ("Explain...", "Point out..."); plain titles; the tour's parts with minutes in a timed list, as
  `TOUR_PARTS` in `demos/embedded/demo.sh`; URLs end unpunctuated.
- `NEEDS_RELEASE_AFTER` in `demo.sh` matches the embedded demo's current value (`4.7.0`), so `make up` builds the
  library from the checkout until a release has the API the worker uses.
- Docs-visible Kotlin: a call naming one argument names them all.

## Approaches considered

1. **Shared Redis queue (chosen).** Ovens take orders from one Redis list. Scaling out really drains a backlog, which
   is what makes the scale part worth watching. Costs one more container and a Redis client library in the app.
2. **Each oven generates its own orders.** Fewer moving parts, but scaling only adds targets; nothing drains, so the
   scale part loses its point.

For the targets:

1. **One path per oven, found through the proxy's service discovery (chosen).** Prometheus's convention is one target
   per instance; the basics demo already shows Prometheus reading the proxy's `/discovery` (`http_sd_configs`).
2. **Consolidated mode, one path for all ovens.** The proxy merges every oven's metrics into one target, so each
   series needs a per-oven label to avoid duplicates, and `up` can't tell a missing oven. Its redundancy story is the
   basics demo's.

## The design

### What runs

```
 oven ×1..3  (no ports, no server)          proxy host
 ┌──────────────────────────────┐          ┌───────────────────────────┐
 │ main → Oven loop             │          │ proxy (scrape, dashboard) │
 │ Prometheus client registry   │  gRPC →  │ Prometheus (http_sd)      │
 │ embedded agent (in-process)  │ outbound │ Grafana                   │
 └──────────────┬───────────────┘          └───────────────────────────┘
                └── redis (the order queue, not published)
```

- **oven** (the worker, scaled 1 to 3): a Kotlin `main`, no web framework, no HTTP server. Takes orders from Redis,
  "bakes" each (a sleep drawn from a per-item distribution), records metrics. Publishes no port.
- **redis**: the order queue, a Redis list. Not published on the host; `demo.sh` reaches it with
  `docker compose exec redis redis-cli`.
- **a steady trickle of orders**: so the graphs move between scenarios. The builder picks how (a tiny producer
  container, or the ovens' own scheduler pushing a trickle onto the shared queue), as long as the rush and the scale
  parts still read clearly.
- **proxy**: the stock proxy image, as the other demos run it, with an agent token.
- **Prometheus**: one job reading the proxy's `/discovery`, so each oven's path becomes a target on its own.
- **Grafana**: one Bakery dashboard (orders baked per oven, queue depth, bake time, ovens connected).

### The worker's code

The agent wiring, which the README and website page show between `--8<--` snippet markers:

```kotlin
fun main() {
  val replica = System.getenv("HOSTNAME")
  val oven = Oven(queue = OrderQueue(url = env("REDIS_URL")), registry = PrometheusRegistry.defaultRegistry)

  embeddedAgent {
    name = "oven-$replica"
    fromEnvironment()
    source(path = "oven_${replica}_metrics", registry = PrometheusRegistry.defaultRegistry)
    agentMetricsAt(path = "oven_${replica}_agent_metrics")
  }.startIfConfigured(logBanner = false).use {
    oven.runUntilStopped()
  }
}
```

- Metrics come from the Prometheus Java client directly (`Counter`, `Gauge`, `Histogram` in the default registry), not
  Micrometer: the coffee shop and roastery both use Micrometer, so this covers the other common registry.
- The agent's own metrics are served at their own path and are not repeated in the oven's path (the approach A work
  guarantees this; the demo shows it).
- **Shutdown:** on SIGTERM, a shutdown hook tells the oven to stop and waits for `main` to return. The oven finishes
  the order it's baking, takes no more, and returns. About four lines; see the anti-goal above.
  - *As built:* the agent doesn't wait for `use {}`. Its base class, common-utils' `AbstractGenericService`, registers a
    JVM shutdown hook of its own when it starts, which stops the agent (`*** Agent shutting down ***` on stderr) while
    the oven's hook runs, so the agent disconnects a few hundred milliseconds before the oven returns, and `use {}`'s
    close is then a no-op. The oven's hook is still needed: without it the JVM halts mid-bake and the order it took is
    lost. `use {}` stops the agent when `main` returns by itself. Reported as a core question, not changed here.
  - *Settled:* an embedded agent registers no JVM shutdown hook since #519 (common-utils 5.3.0's
    `registersShutDownHook`), so on SIGTERM the oven finishes its order and returns, and `use {}` then stops the agent.
- `HOSTNAME` is the container's, so each replica's path is unique. A path can't hold whitespace, `?`, or `#`; a
  container ID can't either.
  - *As built:* `val replica = replicaName()`, a small function outside the snippet markers: `HOSTNAME`, or, where it
    isn't set or is blank, as for the JVM on a developer's machine, whose shell doesn't usually export it,
    `InetAddress.getLocalHost().hostName`, with any character but `[A-Za-z0-9._-]` made `-`, so a local run isn't
    `oven-null`.

### The tour

`make tour` walks it; the README lists the same parts. About 12 minutes:

1. **A worker with no port at all** (2 min). The compose file publishes nothing for the oven, and
   `./demo.sh sockets` shows its JVM listens on nothing (for example from `/proc/net/tcp*` in the container, whatever
   works on its image). Prometheus's targets page lists `oven_<id>_metrics` through the proxy anyway. Then the code
   above.
2. **The metrics in Grafana** (2 min). Orders baked, queue depth, and bake times from the Prometheus client; the
   agent's own metrics at their own path, not mixed in.
3. **A rush, and two more ovens clear it** (4 min). `./demo.sh rush` queues about 500 orders and the backlog climbs;
   `./demo.sh scale 3` adds two ovens, each its own target through the proxy's service discovery, with no change to
   Prometheus's config; the backlog drains.
4. **Scaling in stops ovens cleanly** (2 min). `./demo.sh scale 1`: each stopped oven finishes its order, then
   `use {}` stops its agent (see Shutdown above); the proxy's Paths view drops their
   paths at once, and their targets leave Prometheus within one
   discovery refresh, with no restart and no config change. Don't claim no failed scrape: a scrape that comes between
   the agent's disconnect and Prometheus's next `/discovery` refresh gets a 404 and records `up` 0, as a terminating
   pod's does on Kubernetes. (A drain, where a path leaves `/discovery` before the proxy stops serving it, would avoid
   that; it's a proxy feature, out of scope here.)
5. **No proxy, no outage** (2 min). An oven started without `PROMETHEUS_PROXY` logs one line and bakes; one started
   with a setting the agent rejects at start, such as a `PROMETHEUS_PROXY_CERT` that isn't a certificate, logs an
   error saying why, and bakes. A third, with a wrong token, starts its agent, which the proxy then refuses: it logs
   that at ERROR once, keeps retrying quietly, and bakes. Metrics aren't worth an outage. (A wrong token isn't a
   start failure, so `startIfConfigured()` doesn't see it; see "A wrong token" below.)

### Repository wiring

- `demos/worker/`: `Makefile`, `demo.sh`, `README.md`, `app/` (Gradle project of its own, built like
  `demos/embedded/app`: `-PproxyVersion` from Maven Central, or `-PlocalProxy` for a composite build against the
  checkout, which lints it), `docker/compose.yml`, `config/proxy.conf`, `prometheus/prometheus.yml`,
  `grafana/{dashboards,provisioning}`.
- Root `Makefile`: `worker-demo-check` (`$(GRADLE) -p demos/worker/app -PlocalProxy build`), added to `CI_JOBS` and to
  the `make -k ... ` line of CI's `consumer-check` job in `.github/workflows/ci.yml`; `worker` in `DEMOS`.
- IntelliJ: link `demos/worker/app` as a Gradle project as `.idea/gradle.xml` does the coffee shop, with its wrapper
  properties a symlink to the root's (see AGENTS.md on the embedded demo).
- Website: `website/prometheus-proxy/docs/worker-demo.md`, in `zensical.toml`'s nav after the Spring Boot Demo,
  showing the worker's code between snippet markers, and a Java tab from a compiled example in `etc/consumer-check`.
  Link it from `embedded-agent.md` where the page points at the demos. `llms.txt` files if they list the demos.
- `AGENTS.md`: a paragraph on the worker demo beside the embedded and Spring Boot ones (what it is, how CI builds it).
- `docs/TESTING.md`, if anything new needs naming there.

## A wrong token (decided)

An agent the proxy refuses (`UNAUTHENTICATED`) used to log it at INFO on every reconnect. The approach A work changes
that: the first rejection logs at ERROR, naming the token setting to check, repeats log at DEBUG, and the agent keeps
retrying. So part 5 shows a wrong token too: `./demo.sh` starts an oven with one, it logs that error once, and bakes.
Use the message text that work ships.

## Left to the builder

- Host ports: follow the other demos' pattern (`86xx` for the proxy, `9x90` and `3x00` for Prometheus and Grafana),
  away from `TestPorts`' constants and the harness's range from `HARNESS_DEFAULT_START_PORT`; `TestPortsTest` decides.
- The Redis client library (Lettuce or Jedis), the Redis image tag, and how orders are encoded.
- The bake-time distribution, menu, and rates, tuned so the rush builds a visible backlog that three ovens clear in a
  couple of minutes and one can't.
- How the trickle of orders is produced.
- The Grafana dashboard's panels beyond the four named, and whether an alert is worth adding (none is required).
- Whether the oven's base image is the agent's distroless JRE or a plain Temurin JRE; `./demo.sh sockets` has to work
  on it.
- Screenshots for the website page, if the other demo pages' scripts (`bin/screenshots.sh`) make that cheap.

### As settled

- **Host ports:** the basics demo's plus 600: proxy scrape port 8680, its dashboard 8694, Prometheus 9690, Grafana 3600
  (`WORKER_DEMO_*_PORT`). None is a `TestPorts` constant, and 9690 is above the harness's block from 9600 (at most 50
  ports, XXLARGE's). The ovens and Redis publish nothing.
- **Redis:** Jedis 8 (`RedisClient`, `BLPOP` with a one-second timeout, so a stopped oven returns within a second),
  synchronous, which suits a plain blocking loop and brings no Netty beside the agent's; its version is in
  `gradle/libs.versions.toml`, for Dependabot. Image `redis:8.10.3-alpine`, by digest, with persistence off and a
  tmpfs `/data`, so the queue starts empty and leaves no volume. An order is the menu item's name.
- **Menu and rates:** cookie 0.25s, muffin 0.35s, croissant 0.45s, baguette 0.6s, sourdough 0.85s, picked uniformly,
  each log-normal (sigma 0.3) about its mean: about 0.5s an order, two a second an oven (measured 2.07/s at 100%). The
  trickle is one order every 0.65s, about 1.5/s (measured 1.47 to 1.57/s), so one oven keeps up at about 75%. The rush
  is 500 orders at ten a second for 50 seconds: the backlog climbs to about 480 (measured), one oven would drain it
  at about 0.5/s (a quarter of an hour), and three drained 467 in about 1 minute 45 seconds.
- **Trickle:** a `counter` service on the Redis image, running `counter/counter.sh` (busybox sh and `redis-cli`), which
  `demo.sh rush` also runs, as `counter.sh rush 500`, in the same container. The ovens don't produce orders, so the
  trickle doesn't grow with scale.
- **Dashboard:** the four named (orders baked per oven, the backlog against the number of ovens, bake time, agents
  connected), plus ovens scraped, orders a minute, p95 bake time, each oven's time spent baking (`rate` of the bake
  histogram's `_sum`), orders by item, and, in a "Through the proxy" row, `up` per oven (where a scale-in's one 0
  shows), each agent's `agent_connected`, and scrape time. No alert: the tour has no part that would fire one.
- **Base image:** the Temurin 25 JRE on Ubuntu (`eclipse-temurin:25-jre-noble`, the coffee shop's), whose `sh` and
  `cat` `demo.sh sockets` needs. It reads the JVM's sockets by inode, from `/proc/1/fd` (the JVM is process 1) and
  `/proc/net/{tcp,tcp6,udp,udp6,unix}`, since the container's network namespace also holds Docker's DNS resolver,
  listening on 127.0.0.11, which isn't the oven's.
- **Prometheus targets:** one `http_sd_configs` job reading `/discovery`, relabeled so `instance` is the agent's name,
  `oven-<id>`, and the agent's path, `/oven_<id>_agent_metrics`, is the job `oven-agent`, which the Agents dashboard
  reads; the code keeps `source()` and `agentMetricsAt()` without labels.
- **The misconfigured ovens:** three compose services in a `misconfigured` profile, each started, watched until its
  agent's line and its first order are logged (the wrong token's for 20 seconds more, to count one ERROR against the
  proxy's `proxy_agent_auth_failures_total`), and removed, so each scenario leaves the demo as it found it.
- **Screenshots:** none. The other pages' `bin/screenshots.sh` needs Chrome, ImageMagick, and cwebp and a scripted run;
  not cheap enough here.
- **Java tab:** `src/test/java/website/WorkerDemoJavaExample.java`, the same `main` in Java with a stand-in oven and
  queue below the snippet, compiled by the root tests and `make consumer-check`.
