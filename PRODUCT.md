# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Primary: a platform / infrastructure team running the proxy as shared, multi-team infrastructure.**
Their work is routine operations rather than firefighting — day-to-day health checks, onboarding a new
agent, confirming a team's auth scope covers the paths they registered, and verifying that a target
that stopped reporting is a target problem and not a proxy problem. They already run Prometheus and
already know its vocabulary; they do not need the concept of a scrape explained.

Because the proxy is shared, "whose agent is this and what is it allowed to do" is a routine question,
not an exceptional one. Design decisions favor inventory clarity and completeness over incident-speed
triage. The on-call-mid-incident case is real but secondary.

Secondary audiences that exist but do not drive decisions: solo/homelab operators running proxy plus a
handful of agents, and developers embedding the agent inside another JVM application.

## Product Purpose

Prometheus's pull model breaks when a firewall separates the Prometheus server from its metrics
endpoints. Prometheus Proxy restores it: an **agent** inside the firewall opens a persistent gRPC
connection outward to a **proxy** outside the firewall, and Prometheus scrapes the proxy over ordinary
HTTP. No inbound firewall rule, no push gateway, no change to Prometheus's scrape semantics.

Success is that a target behind a firewall is indistinguishable, from Prometheus's point of view, from
one in front of it — and that when it is *not*, an operator can tell why from one page.

## Positioning

The mechanism a neighboring product cannot truthfully copy: **the connection is initiated from inside
the firewall, but the data flow stays pull-based.** Push gateways change scrape semantics (staleness,
timestamps, target liveness all become the sender's problem); VPNs and tunnels solve it at the network
layer, requiring infrastructure changes outside the monitoring team's control. Prometheus Proxy sits
in the monitoring layer and preserves pull.

Two consequences of that position are also differentiators:

- **Metric filtering happens at the agent** — before the payload crosses the WAN. This is the one place
  where dropping metric families pays the most, and the one place Prometheus's own
  `metric_relabel_configs` cannot reach.
- **The proxy is not a single point of failure.** Agents hold an ordered list of proxy endpoints, fail
  over to a standby when the primary is unreachable, and return to the primary when it recovers — with
  no external health prober and no manual step.

## Operating Context

**Deployment.** Two long-running services. The proxy runs outside the firewall alongside Prometheus
(HTTP scrape port default `8080`, admin `8092`); the agent runs inside, next to the monitored
services (admin `8093`). Both ship as standalone JARs (`agentJar` / `proxyJar`), Docker images
(`pambrose/prometheus-proxy`, `pambrose/prometheus-agent`), and a Maven Central artifact
(`com.pambrose:prometheus-proxy`) for embedded use. Java 17+.

**Configuration.** Typesafe Config (HOCON). Precedence: CLI args → env vars → config file → built-in
defaults. Every setting is reachable from all four, because deployment environments differ in which
one they can control (a Kubernetes ConfigMap, a systemd unit, a `docker run` line).

**Surfaces in scope for design work:**

1. **The operational dashboard** — served from its own port (default `8094`, base path `/dashboard`),
   **opt-in and disabled by default**. Two bookmarkable layouts: an agent view (master–detail: which
   agents are connected, what each is doing) and a path view (a table of every registered path and how
   its last scrape went, *including paths whose agent has departed* — precisely the case that used to
   vanish from view). A status bar carries connection liveness and proxy-internal health counters.
   Updates arrive live over a WebSocket.
2. **A project landing page** — a surface that persuades a prospective adopter, distinct from the
   reference documentation.

Explicitly **out of scope** for now: the Zensical documentation site at
`pambrose.github.io/prometheus-proxy`, and the Grafana dashboard JSON under `grafana/`.

**Vocabulary** (used consistently in code, docs, and UI — do not invent synonyms):
*agent*, *proxy*, *path* (the registered scrape path), *target URL* (what the agent actually scrapes),
*scrape*, *agent id / agent name / launch id*, *consolidated* (multiple agents serving one path),
*departed* (an agent that disconnected, leaving its paths orphaned), *chunking*, *heartbeat*,
*failover*, *identity* (a named per-agent auth principal), `STATIC` vs `DISCOVERED` path source.

## Capabilities and Constraints

**Confirmed capabilities:**

- Firewall-traversing scrape via persistent agent-initiated gRPC stream.
- Chunked responses for large payloads (above ~32KB), heartbeat every 5s during inactivity.
- Stale-agent eviction after `maxAgentInactivitySecs` (default 60s).
- Consolidated mode: several agents register the same path for redundancy.
- Embedded agent — runs inside another JVM app via `Agent.startAsyncAgent()`.
- Proxy failover across an ordered `agent.proxy.endpoints` list.
- Dynamic target discovery: the agent watches a file and reconciles paths without restarting;
  `STATIC` entries are never touched by discovery.
- Per-agent identities with path-glob authorization on `registerPath`; the legacy shared
  `proxy.agentToken` maps to an allow-all identity so existing deployments keep working.
- Per-path metric filtering (allow/deny regexes, Prometheus-compatible anchoring); whole metric
  families are kept or dropped atomically, and filtering fails open on non-text or non-UTF-8 payloads.
- TLS with optional mutual auth.

**Binding constraint — no CDN, no external assets.** Everything the dashboard serves must be
self-contained in the JAR. htmx ships as a vendored WebJar for exactly this reason. No Google Fonts,
no CDN scripts, no remote images, no runtime network fetch. This is not incidental: the proxy is
frequently deployed in restricted networks where an outbound asset fetch would simply fail, and it
must render identically there.

**Current implementation, not a constraint.** Today the dashboard is rendered server-side from Kotlin
(`kotlinx.html`) and updated via htmx `hx-swap-oob` fragments over a WebSocket — no client-side
templating, no build step, no JS framework. The user did **not** mark this as binding, so a future
change of approach is permitted; it is recorded here as the incumbent reality, not a rule.

**Planned direction — control actions.** The dashboard is read-only today, but this is explicitly
temporary. Mutating operations (evicting a stale agent, triggering a discovery reload, toggling a path)
are a real future direction. Future design should anticipate destructive-action patterns —
confirmation, undo where possible, clear attribution of who acted — rather than assuming an
observe-only surface.

**Undecided:** the exact set of control actions and their authorization model; where the landing page
lives relative to the docs site.

## Brand Commitments

- **Name:** `prometheus-proxy` (lowercase, hyphenated) is the project name and the string used as the
  dashboard's brand mark. The two services are `proxy` and `agent`.
- **License:** Apache 2.0. Author: Paul Ambrose. Repository: `pambrose/prometheus-proxy`.
- **Existing assets:** the architecture diagram at `docs/prometheus-proxy.png`. There is no logo,
  wordmark, or defined brand palette — none has been established, and none should be assumed to exist.
- *Observed, not user-confirmed:* the existing README and docs voice is direct and problem-first —
  it states the operational pain, then the mechanism, in concrete technical terms, without marketing
  superlatives. Worth preserving unless the user says otherwise.

## Evidence on Hand

**Real and usable:**

- **Repo signals only** — GitHub releases (v4.0.1, released 2026-07-31), Docker Hub pull counts for
  both images, Maven Central presence, Apache 2.0 license, CI / Codecov / Codacy badges. All
  independently verifiable.
- **Working configuration and demonstration** — the config examples under `examples/`, the reference
  schema at `config/config.conf`, the architecture diagram at `docs/prometheus-proxy.png`, and real
  screenshots of the running dashboard. Demonstration over claims.

**Explicitly absent — must not be fabricated:**

- No named or describable production users, and no customer logos.
- No benchmarks, throughput figures, latency measurements, or quantified WAN-savings numbers.
- No testimonials, case studies, press coverage, or analyst mentions.
- No pricing, commercial tiers, or support offering — this is an Apache 2.0 open-source project.

Any future landing page must persuade using the mechanism, the working configuration, and the
verifiable repo signals. Nothing else is available.

## Product Principles

1. **Preserve the pull model.** The firewall boundary is the problem; Prometheus's semantics are not.
   Any solution that asks operators to give up pull-based scraping is off-strategy.
2. **Answer "why isn't this target scraping?" in one place.** The failure this product exists to make
   visible spans two machines and two log files. Collapsing that into a single legible view is the
   dashboard's whole reason to exist — including the orphaned and departed cases that are easiest to
   omit and most important to show.
3. **Shared infrastructure is the default assumption.** One proxy serves multiple teams. Per-agent
   identity, path scoping, and unambiguous attribution are first-class concerns, not add-ons.
4. **Configuration is the interface; automation writes files, not restarts.** Anything an operator can
   change should be changeable by writing a file or setting an env var, without a process restart and
   without granting automation the right to restart processes.
5. **Self-contained at the edge.** The proxy and agent are deployed into networks that may permit no
   outbound traffic beyond what the product itself defines. Everything needed to run and to render
   must ship inside the artifact.

## Accessibility & Inclusion

No formal conformance standard has been committed to, and none should be claimed. The existing
dashboard already handles `:focus-visible` outlines and `prefers-reduced-motion`, and light/dark
schemes via `prefers-color-scheme`; keep doing sensible things at that level. Recorded here so a
future pass does not re-ask: this was a deliberate decision, not an oversight.
