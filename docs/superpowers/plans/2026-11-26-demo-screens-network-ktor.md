# The Screens Page in the Network and Ktor Demos: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the network and Ktor demos' tours in the proxy's screens page, as the basics demo's runs (#577, #580).

**Architecture:** Each demo lists its screens in a proxy config (the network demo's standby, the Ktor demo's proxy),
with each screen's `url` overridden from compose (`url = ${?…_URL}`), and its `demo.sh`, README, and docs page link into
`/screens#…`. `DemoScreensLinksTest` becomes a check over every demo with a screens page.

**Tech Stack:** HOCON, Docker Compose, bash (`demo.sh`, `bin/screenshots.sh`), Markdown (Zensical), Kotest.

**Spec:** `docs/superpowers/specs/2026-11-26-demo-screens-network-ktor-design.md`

## Global Constraints

- Network: the page is the standby's, `http://localhost:8195/` (`NETWORK_DEMO_STANDBY_DASHBOARD_PORT`); the screens
  are listed in `demos/network/config/proxy-standby.conf` only.
- Ktor: the page is the proxy's, `http://localhost:8394/` (`KTOR_DEMO_DASHBOARD_PORT`).
- Each demo's `NEEDS_RELEASE_AFTER` goes from `4.6.0` to `4.7.0`.
- Each demo's Grafana gets `GF_SECURITY_ALLOW_EMBEDDING: "true"`.
- Mirror basics: `demos/basics/config/proxy.conf` (screens block), `demos/basics/docker/compose.yml` (proxy's
  `DEMO_*_URL`), `demos/basics/demo.sh` (`SCREENS_URL`, `SCREENS_HOME_URL`, `print_urls`, `cmd_open`),
  `demos/basics/bin/screenshots.sh` (`shoot_screens`), `website/prometheus-proxy/docs/basics-demo.md` (paragraph,
  table, figure).
- Copy: each tour step explains or gives an action; URLs end unpunctuated; headings in Chicago title case. Only where a
  link opens changes; the tours' parts and scenarios stay as they are.
- Changelogs: one concise 4.7.1 Documentation entry per demo in `CHANGELOG.md` and `RELEASE_NOTES.md`.
- Commits go on the task's branch only with the user's go-ahead; no push or PR until asked.
- Docs specs read files Gradle doesn't track: run them with `--rerun`.

## Review Focus

- A port moved with a `*_PORT` variable: every tab and printed link follows it. Pinned by Task 1's "every screen
  follows its port" test.
- A screen that refuses framing shows a blank pane: Grafana before `GF_SECURITY_ALLOW_EMBEDDING`, Zipkin's UI, or
  the coffee shop through Caddy. Pinned for Grafana by Task 1's test; checked live with `curl -sI` for the others in
  Tasks 2 and 6.
- A link printed while the page's proxy is stopped opens a connection error. Ktor's part 8 is pinned by its `proxyDown`
  entry (Task 6); the network demo never stops the standby (checked in Task 5's live walk).
- A screen that runs only in some parts (1.1's agent, before part 6's deploy) shows red: the tour says so where it
  first appears, as basics says of agent-rogue. Checked in Task 8's live walk.
- A link mistyped or a screen renamed sends the reader to the proxy's tab silently. Pinned by the existing "every link
  should name …" test, now per demo.

---

## Part 1: Network Demo

Worktree `.claude/worktrees/network-screens`, branch `network-demo-screens`, from `master`.

### Task 1: Check Every Demo With a Screens Page

**Files:**
- Modify: `src/test/kotlin/io/prometheus/misc/DemoScreensLinksTest.kt`
- Modify: `docs/TESTING.md` (its DemoScreensLinksTest entry)

**Interfaces:**
- Produces: `private data class ScreensDemo(val dir: String, val config: String, val page: String, val pageUrl: String,
  val proxyService: String, val proxyDown: ProxyDown?)` and `private data class ProxyDown(val scenario: String,
  val pageSection: String, val directUrl: String, val directUrlOnPage: String)`, and a `DEMOS: List<ScreensDemo>`
  holding basics alone: `ScreensDemo("demos/basics", "config/proxy.conf", "website/prometheus-proxy/docs/basics-demo.md",
  "http://localhost:8094/screens", "proxy", ProxyDown("proxy-down", "With the proxy stopped",
  "\$EAST_DASHBOARD_URL/connection", "http://localhost:8095/dashboard/connection"))`. Tasks 2 and 7 add entries.

- [ ] **Step 1:** Make every existing test iterate `DEMOS`, each failure clued with the demo's dir; the proxy-down test
  runs for each demo whose `proxyDown` isn't null (basics' `proxy-down)` case and "With the proxy stopped" paragraph,
  as now). `DOCS_LINK` becomes per demo: `Regex.escape(pageUrl) + "#(…)"`.
- [ ] **Step 2:** Add `"every screen should follow its port: compose gives the page's proxy each url's variable"`: for
  each screen entry in the demo's config, its `url` has a `${?NAME}` override, and `docker/compose.yml`'s
  `proxyService` service sets `NAME`. Add `"a demo whose page lists Grafana should let Grafana be framed"`: its
  compose's `grafana` service sets `GF_SECURITY_ALLOW_EMBEDDING: "true"`.
- [ ] **Step 3:** Run `./gradlew unitTest --tests 'io.prometheus.misc.DemoScreensLinksTest' --rerun`. Expected: PASS
  (basics already meets both new checks).
- [ ] **Step 4:** Mutation check: temporarily remove `url = ${?DEMO_GRAFANA_URL}` from basics' `proxy.conf` and
  `GF_SECURITY_ALLOW_EMBEDDING` from its compose; each new test fails; restore both; `git diff demos/basics` is empty.
- [ ] **Step 5:** Update the TESTING.md entry (every demo with a screens page, the two new checks). Commit.

### Task 2: The Standby Lists the Network Demo's Screens

**Files:**
- Modify: `demos/network/config/proxy-standby.conf`, `demos/network/docker/compose.yml`, `demos/network/demo.sh`
  (`NEEDS_RELEASE_AFTER`), `src/test/kotlin/io/prometheus/misc/DemoScreensLinksTest.kt` (`DEMOS`)

**Interfaces:**
- Consumes: Task 1's `ScreensDemo`.
- Produces: screen ids `primary`, `agent-hq`, `agent-branch`, `grafana`, `prometheus`, `zipkin` (the last only if its
  UI allows framing), and compose variables `NETWORK_DEMO_PRIMARY_URL`, `NETWORK_DEMO_HQ_URL`,
  `NETWORK_DEMO_BRANCH_URL`, `NETWORK_DEMO_GRAFANA_URL`, `NETWORK_DEMO_PROMETHEUS_URL`, `NETWORK_DEMO_ZIPKIN_URL` on
  `proxy-standby`.

- [ ] **Step 1:** Add `ScreensDemo("demos/network", "config/proxy-standby.conf",
  "website/prometheus-proxy/docs/network-demo.md", "http://localhost:8195/screens", "proxy-standby", null)` to
  `DEMOS`. Run the spec: FAIL ("proxy.conf should list the demo's screens", and the link tests find no links).
- [ ] **Step 2:** In `proxy-standby.conf`, `proxy.dashboard.screens`: `primary` → `http://localhost:8194/dashboard`;
  `agent-hq` → `:8196/dashboard`; `agent-branch` → `:8197/dashboard`; Grafana → `:3100`, start
  `/d/prometheus-proxy-network-demo?kiosk`; Prometheus → `:9190`, start `/alerts`; Zipkin → `:9511`, start `/zipkin/`.
  Each `url` overridden by its `${?…}` variable; a comment as basics' says why.
- [ ] **Step 3:** In compose, add the six `*_URL` variables to `proxy-standby`'s `environment:` (it replaces the
  anchor's, so it holds them itself), each from its `*_PORT` default, and `GF_SECURITY_ALLOW_EMBEDDING: "true"` to
  `grafana`. Set `NEEDS_RELEASE_AFTER=4.7.0`.
- [ ] **Step 4:** `make -C demos/network up-local`; then `curl -sI` each screen's start URL: no `X-Frame-Options`, and no
  `frame-ancestors` in a `Content-Security-Policy`. If Zipkin refuses framing, drop it from the list (its own link stays as it
  is). Open `http://localhost:8195/` in Chrome: every tab's dot green.
- [ ] **Step 5:** The spec's config tests pass (the link tests still fail until Task 3). Commit.

### Task 3: The Network Tour Opens in the Page

**Files:**
- Modify: `demos/network/demo.sh`, `demos/network/Makefile` (`open`'s description)

**Interfaces:**
- Consumes: Task 2's screen ids.
- Produces: `SCREENS_URL="http://localhost:${NETWORK_DEMO_STANDBY_DASHBOARD_PORT:-8195}/screens"` and
  `SCREENS_HOME_URL="http://localhost:${NETWORK_DEMO_STANDBY_DASHBOARD_PORT:-8195}/"`.

- [ ] **Step 1:** `print_urls`: "All screens: $SCREENS_HOME_URL" first, keeping Proxy discovery; the per-screen lines go,
  as basics' did. `cmd_open` opens `$SCREENS_HOME_URL` alone. The Makefile's `open` says so.
- [ ] **Step 2:** Every tour line that prints a screen's URL prints a link into the page instead: the standby's
  dashboard is `#proxy`; part 1's setup opens the page and says the standby serves it, since it stays up through the
  tour; part 4's "agent-branch's Connection view beside the standby's dashboard" is
  `${SCREENS_URL}#agent-branch/connection+proxy`; part 5's beside the primary is `#agent-branch/connection+primary`.
  Keep `$PROXY_URL/discovery` and the API, curl, and log lines as they are.
- [ ] **Step 3:** Run the spec: the demo.sh links pass; README and page still fail until Task 4. `bash -n
  demos/network/demo.sh`. Commit.

### Task 4: The Network Demo's README and Docs Page

**Files:**
- Modify: `demos/network/README.md`, `website/prometheus-proxy/docs/network-demo.md`, `CHANGELOG.md`,
  `RELEASE_NOTES.md`

- [ ] **Step 1:** Each gives the page's address as `http://localhost:8195/` in its Running It paragraph and the first
  row of its URL table (the screens page, `NETWORK_DEMO_STANDBY_DASHBOARD_PORT`), says the standby serves it, and
  links each scenario's screens as the tour does (`http://localhost:8195/screens#…`). The page links the Web Dashboard
  page's Screens section once.
- [ ] **Step 2:** One 4.7.1 Documentation entry in each changelog.
- [ ] **Step 3:** Run every `misc` spec with `--rerun`: PASS. Commit.

### Task 5: The Network Screens Shot, the Live Walk, and the PR

**Files:**
- Modify: `demos/network/bin/screenshots.sh`, `website/prometheus-proxy/docs/network-demo.md`, `demos/network/README.md`
  (its `bin/screenshots.sh` row), `website/prometheus-proxy/docs/images/network-demo/*.webp`

- [ ] **Step 1:** Add basics' `shoot_screens` to the script and a `screens` shot at part 4's moment, after the
  failover, `#agent-branch/connection+proxy`; add it to the WebP loop. Add a figure under the scenario's paragraph.
- [ ] **Step 2:** `make -C demos/network screenshots`; look at both themes of `screens` (no overlap, every pane drawn).
- [ ] **Step 3:** Walk every tour link in Chrome on the running demo, parts 1 to 7: each loads its screen and view; in
  part 4 the page stays up while the primary is stopped, and its `primary` tab turns red.
- [ ] **Step 4:** `./gradlew check unitTest --rerun harnessTest --rerun koverVerify koverVerifyPerClass` and
  `make build-site`: PASS. Commit, then push and open the PR when the user asks.

## Part 2: Ktor Demo

Worktree `.claude/worktrees/ktor-screens`, branch `ktor-demo-screens`, from `network-demo-screens` once Task 1 is
committed; rebased onto `master` after the network PR merges.

### Task 6: The Ktor Proxy Lists Its Screens

**Files:**
- Modify: `demos/ktor/config/proxy.conf`, `demos/ktor/docker/compose.yml`, `demos/ktor/demo.sh`
  (`NEEDS_RELEASE_AFTER`), `src/test/kotlin/io/prometheus/misc/DemoScreensLinksTest.kt` (`DEMOS`)

**Interfaces:**
- Produces: screen ids `coffee-shop`, `agent`, `agent-1-1` (named `agent 1.1`), `grafana`, `prometheus`, `debug`, and
  compose variables `KTOR_DEMO_APP_URL`, `KTOR_DEMO_AGENT_URL`, `KTOR_DEMO_NEXT_AGENT_URL`, `KTOR_DEMO_GRAFANA_URL`,
  `KTOR_DEMO_PROMETHEUS_URL`, `KTOR_DEMO_DEBUG_URL` on `proxy`.

- [ ] **Step 1:** Add `ScreensDemo("demos/ktor", "config/proxy.conf", "website/prometheus-proxy/docs/ktor-demo.md",
  "http://localhost:8394/screens", "proxy", ProxyDown("proxy-stop", "<the page's proxy-stopped paragraph's opening>",
  "\$AGENT_DASHBOARD_URL/connection", "http://localhost:8395/dashboard/connection"))`, the page section read from "The
  Proxy Goes Away". Run the spec: FAIL.
- [ ] **Step 2:** `proxy.conf`'s screens: coffee shop → `:8390`; agent → `:8395/dashboard`; agent 1.1 →
  `:8396/dashboard`; Grafana → `:3300`, start `/d/coffee-shop?kiosk`; Prometheus → `:9390`, start `/alerts`; debug →
  `:8393`, start `/debug`. Compose: the six variables on `proxy`, Grafana embedding; `NEEDS_RELEASE_AFTER=4.7.0`.
- [ ] **Step 3:** `make -C demos/ktor up-local`; `curl -sI` the coffee shop through Caddy and `/debug`: frameable.
  Config tests pass. Commit.

### Task 7: The Ktor Tour, README, and Docs Page

**Files:**
- Modify: `demos/ktor/demo.sh`, `demos/ktor/Makefile`, `demos/ktor/README.md`,
  `website/prometheus-proxy/docs/ktor-demo.md`, `CHANGELOG.md`, `RELEASE_NOTES.md`

- [ ] **Step 1:** `SCREENS_URL`/`SCREENS_HOME_URL` on `KTOR_DEMO_DASHBOARD_PORT` (8394); `print_urls`, `cmd_open`, and
  the Makefile as in Task 3. Tour links into the page, but part 8 (`proxy-stop`), which keeps each screen's own URL;
  part 6 says 1.1's tab is red until the deploy starts it.
- [ ] **Step 2:** README and docs page as in Task 4, at `http://localhost:8394/`; changelog entries.
- [ ] **Step 3:** Every `misc` spec with `--rerun`: PASS. Commit.

### Task 8: The Ktor Screens Shot, the Live Walk, and the PR

**Files:**
- Modify: `demos/ktor/bin/screenshots.sh`, `website/prometheus-proxy/docs/ktor-demo.md`, `demos/ktor/README.md`,
  `website/prometheus-proxy/docs/images/ktor-demo/*.webp`

- [ ] **Step 1:** `shoot_screens`, and a `screens` shot of the coffee shop beside its Grafana dashboard during part 4's
  rush, `#coffee-shop+grafana`; a figure in "The Coffee Shop in Grafana".
- [ ] **Step 2:** `make -C demos/ktor screenshots`; look at both themes.
- [ ] **Step 3:** Walk every tour link in Chrome, parts 1 to 9; part 8's links open each screen directly while the
  proxy is stopped.
- [ ] **Step 4:** Rebase onto `master` once the network PR has merged; the full suite and `make build-site`: PASS.
  Commit, then push and open the PR when the user asks.
