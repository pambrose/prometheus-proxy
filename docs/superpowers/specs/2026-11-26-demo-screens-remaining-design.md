# The Screens Page in the nginx, Plain Kotlin, Spring Boot, and Kubernetes Demos

**Date:** 2026-11-26. **Status:** approved; all four demos chosen.

## Intent

The basics, network, and Ktor demos run their tours in a proxy's screens page
([2026-11-26-demo-screens-network-ktor-design.md](2026-11-26-demo-screens-network-ktor-design.md)), which left the
other four out: "fewer URLs, or a chart's config to go through". On review, each has a part where two screens side by
side help, so every demo's `make up` prints one "All screens" address, `make open` opens it, and its tour's links open
in it.

## Goals

- As in the network and Ktor demos: each demo's proxy config lists its screens, each `url` following its port through
  a `${?…}` variable its proxy is given; the tour, scenarios, README, and docs page link into `$SCREENS_URL#…`; `make
  up` prints and waits for the page; `make open` opens it; `DemoScreensLinksTest` checks the demo.
- At least one link to a screen, one to a screen's view, and one Split in each demo.

## Non-Goals

- Changing what the tours teach, their parts, or their scenarios: only where a link opens changes.
- Screenshot pipelines for the plain Kotlin and Spring Boot demos, which have none; nginx and Kubernetes, which have
  `bin/screenshots.sh`, add a `screens` shot.

## Anti-Goals

- A tour link that doesn't load: no link into a stopped proxy's page.
- A screen that's blank because it refuses framing.

## Constraints

- The page and frameable agent dashboards are 4.7.1's, so each demo's `NEEDS_RELEASE_AFTER` goes from 4.6.0 to 4.7.0.
- No alert rules exist in these demos' Prometheus: its screen opens on the tour's graph or `/targets`.

## Design

**nginx** (page `http://localhost:8594/screens`). Screens: `agent-current`, `through nginx` (the proxy's dashboard
through nginx, 8590), and Prometheus on the cuts graph. agent-legacy (4.6.0) has no dashboard. Splits: part 2's watch
puts the proxy's Activity view beside the cuts graph, part 3's after `keepalive 10` puts agent-current's Activity view
beside the proxy's, and part 4 puts the dashboard through nginx beside the one reached directly. `keepalive` and
`reset` recreate the proxy for seconds and wait for its dashboard, so no stretch is read while it's down.

**Plain Kotlin** (page `http://localhost:8694/screens`). Screens: Grafana's Bakery dashboard (embedding on) and
Prometheus's targets. Split: part 3's rush, Grafana beside the proxy's Paths view. No part stops the proxy.

**Spring Boot** (page `http://localhost:8494/screens`). Screens: the roastery's status page, its agent's dashboard,
and Prometheus on the batches graph. Split: part 5, the proxy beside the roastery as it stops. Part 4 stops the proxy:
from its `proxy-stop` to its `proxy-start`, and in the `proxy-stop` scenario, links give each screen's own address, as
the Ktor demo's part 8 does.

**Kubernetes** (page `http://localhost:8294/screens`). Screens: the agent's dashboard at the port `./demo.sh
agent-dashboard` forwards (red until then, and after part 4 replaces the pod) and Prometheus's targets, listed in
`config/proxy.conf`, which the chart takes with `--set-file`. The chart's `env` gives the proxy each screen's url from
the demo's port variables, in place of compose; `DemoScreensLinksTest`'s port check learns that source.

## Delivery

Four pull requests, one per demo: nginx, plain Kotlin, Spring Boot, then Kubernetes, which also generalizes the test's
port check.
