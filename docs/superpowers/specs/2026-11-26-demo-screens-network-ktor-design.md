# The Screens Page in the Network and Ktor Demos

**Date:** 2026-11-26. **Status:** approved approach (A for the network demo); design awaiting approval.

## Intent

The basics demo's tour runs in the proxy's screens page (#577, #580): one browser tab holds every screen, and the
tour's links open views in it, so the reader stops juggling URLs. The network and Ktor demos print seven URLs each
(two proxies, two agents, Grafana, Prometheus, Zipkin; the coffee shop, the proxy, two agents' dashboards, Grafana,
Prometheus, `/debug`), the most of any demo but basics, so they gain the most from the same page.

## Goals

- Each demo's tour opens its screens in the page: `$SCREENS_URL#<screen>[/<path>][+<screen>...]` links, as basics'.
- `make up`'s "All screens" line prints the page's port root, as basics' does since #580, and `make open` opens it.
- Each demo's README and docs page give the page's address and show it in a screenshot taken by its
  `bin/screenshots.sh`, in both themes.
- `DemoScreensLinksTest` checks every demo with a screens page, not basics alone.

## Non-Goals

- The other demos (Spring Boot, nginx, plain Kotlin, Kubernetes): fewer URLs, or a chart's config to go through.
- Changing what the tours teach, their parts, or their scenarios: only where a link opens changes.
- Teaching the page again: basics teaches it; these demos use it and link to the Web Dashboard page's Screens
  section.

## Anti-Goals

- A tour link that doesn't load: a link into a stopped proxy's page opens only the browser's connection error.
- A screen that's blank because it refuses framing.

## Constraints

- The page and frameable agent dashboards are 4.7.1's, so each demo's `NEEDS_RELEASE_AFTER` goes from 4.6.0 to 4.7.0:
  `make up` builds from the checkout (a JDK) until 4.7.1's images and artifacts are published, then pulls them.
- Each screen must allow framing: Grafana needs `GF_SECURITY_ALLOW_EMBEDDING=true` (neither demo sets it); Zipkin's UI
  and the coffee shop are to be checked; agents' dashboards allow it by default from 4.7.1.
- A screen's `url` is the address a browser on this machine reaches it by, set from compose's `*_PORT` variables
  (`url = ${?...}`, as basics' `proxy.conf` does), so a moved port moves its tab.
- The demos' copy style: each tour step explains or gives the reader an action; URLs end unpunctuated. All seven
  demos share `demo.sh` helpers; these changes touch only per-demo text and settings.

## Approaches (network demo: which proxy serves the page)

Part 4 stops the primary proxy, and its page goes with it.

- **A. The standby's page, throughout (chosen).** The screens are listed in `proxy-standby.conf` alone; no part stops
  the standby, so every link works through the tour, and it teaches serving the page from the proxy that stays up.
  The page is on the standby's dashboard port (8195), not the primary's (8194).
- **B. The primary's page, with direct URLs while it's down,** as basics does in its proxy-down part. The page is on
  the main proxy, but part 4 falls back to separate tabs.

The user chose A.

## Design

**Network demo** (page: `http://localhost:8195/`, the standby's dashboard port).

- `proxy-standby.conf` lists the screens; `proxy.conf` doesn't, so the primary serves no page. The page's own tab,
  `#proxy`, is the standby's dashboard; the primary is a screen named `primary`. Proposed screens: `primary`,
  `agent-hq`, `agent-branch`, Grafana (on the network dashboard, `?kiosk`), Prometheus (on `/alerts`), and Zipkin (if
  its UI allows framing; otherwise left out, its link printed as now).
- Compose gives the standby the `*_URL` variables, as basics' gives its proxy.
- Part 4's Split, agent-branch's Connection view beside the standby, is a natural `#agent-branch/connection+proxy`.

**Ktor demo** (page: `http://localhost:8394/`, the proxy's dashboard port).

- `proxy.conf` lists the screens: the coffee shop (through Caddy, 8390), the agent's dashboard, 1.1's dashboard (red
  until part 6's deploy starts it, as basics' agent-rogue is until its part), Grafana, Prometheus on `/alerts`, and
  the agent's admin `/debug`.
- Part 8 stops the proxy: its links point at each screen's own URL, as basics' proxy-down part does, and the
  generalized `DemoScreensLinksTest` checks it.

**Both.** `NEEDS_RELEASE_AFTER=4.7.0`; Grafana embedding on; `SCREENS_URL` (the page) and `SCREENS_HOME_URL` (the
port root) in `demo.sh`; the README's and docs page's opening paragraph and URL table give the root; a `screens`
shot in `bin/screenshots.sh` and on the docs page; a 4.7.1 Documentation entry in both changelogs.

## Delivery

Two worktrees and two PRs, network first: its PR generalizes `DemoScreensLinksTest` to every demo whose proxy config
lists screens, and the Ktor PR adds its own once that merges.

## Left to the Builder

- Exact screen names (an id is the name in lowercase, each run of other characters a `-`), and which tour links open
  a Split.
- Each screenshot's link and moment, chosen as basics' was: a moment in the tour that shows the page's point.
- Whether Zipkin's UI or the coffee shop needs anything to be frameable, found by checking their headers.
