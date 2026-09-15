# Security Finding: Unauthenticated Agent Registration / Path Hijacking

**Status:** Mitigated when agent authentication is configured — per-agent identities (`proxy.auth`), a pre-shared
token, and/or mutual TLS. The agent port is still **unauthenticated by default**, and remediation item 4 is only
partly implemented.
**Severity:** High when no authentication is configured and the agent port is reachable
**Component:** Proxy gRPC service (agent-facing port, default `50051`)
**Identified:** 2026-06 code review · **Updated:** 2026-09 (per-agent identities, call binding, and reflection off by default)

> This document tracks a security limitation in the proxy's agent-facing gRPC interface. It was first recorded as
> a design gap: the proxy performed no application-level agent authentication. Since then the proxy has gained a
> pre-shared token, per-agent identities with path authorization (#204), and checks that bind each agent call to
> the agent it names (#208, and findings #1 and #2 of `docs/archive/CODE_REVIEW_SEPTEMBER_2026.md`). The sections below
> describe the current state and the gaps that remain.

## Summary

By default the proxy accepts agent gRPC connections on port `50051` with **no application-level
authentication**. Any process that can reach that port can register itself as an agent and register paths.
Because path registration for a non-consolidated path **displaces** the path's existing owner, a rogue agent can
take over a path and serve arbitrary metrics to Prometheus in place of the legitimate agent.

Three controls close this, and they can be combined:

| Control                            | What it stops                                                          |
|:-----------------------------------|:-----------------------------------------------------------------------|
| Per-agent identities (`proxy.auth`) | Unknown agents, and registration of paths outside an identity's globs  |
| Pre-shared token (`agentToken`)    | Unknown agents only — token holders can still take over any path       |
| Mutual TLS                         | Peers without a client certificate from the proxy's trust store        |

Network segmentation — restricting port `50051` to trusted agents — remains the minimum bar for any plaintext
deployment.

## How agent calls are authenticated and bound

1. **Authentication.** When at least one identity is configured (a `proxy.auth` entry or the legacy
   `proxy.agentToken`), `AgentAuthServerInterceptor` requires an `agent-token` metadata header on every
   `ProxyService` call and, when reflection is enabled, every reflection call. `AgentAuthManager` resolves the token
   to an identity by comparing SHA-256 digests with `MessageDigest.isEqual`. A missing or unknown token is rejected
   with `UNAUTHENTICATED` before the call runs, and token values are never logged.
2. **Path authorization.** `registerPath` rejects a path that matches none of the caller identity's glob patterns.
   An identity with an empty `paths` list — and the legacy shared token — may register any path.
3. **Binding calls to the agent they name.** Agent calls carry a caller-supplied `agentId`, and agentIds are
   sequential, so the proxy checks that the caller is the agent it names:
   - **Transport filter enabled (the default).** `ProxyServerTransportFilter` assigns an agentId to each connection
     and `ProxyServerInterceptor` publishes it on the gRPC context. `registerAgent`, `registerPath`,
     `unregisterPath`, `readRequestsFromProxy`, and `sendHeartBeat` reject a request whose agentId is not the
     connection's. Scrape results — unary and chunked — are accepted only from the agent the scrape was sent to.
   - **Transport filter disabled (`transportFilterDisabled`, for example behind nginx).** There is no
     connection-assigned agentId. Instead, `connectAgentWithTransportFilterDisabled` records the caller's identity
     on the new agent context, and the same calls, including scrape results, must present that identity.

The binding checks are `connectionMismatchReason`, `identityMismatchReason`, and `isScrapeOwnedByConnection` in
`proxy/ProxyServiceImpl.kt`. A rejected call never refreshes the named agent's activity time and never removes its
context.

## Remaining gaps

- **Unauthenticated by default.** With no token, no identities, and no mutual TLS, the original finding applies in
  full. The proxy logs a startup warning in this configuration. If reflection is also enabled, any peer can list
  and describe the API.
- **A shared token authenticates agents but cannot tell them apart.** Every holder of `proxy.agentToken` has
  allow-all path authorization and the same identity, so any of them can take over any other holder's path. When
  `proxy.auth` is added alongside a legacy token, the legacy token keeps allow-all path authorization until it is
  removed, though it can no longer take over a path a live `proxy.auth` identity serves.
- **Transport filter disabled, without per-agent identities.** In this mode calls can be bound only to an
  identity. Agents that share one — the legacy token, or a single `proxy.auth` entry used by several agents — or
  that connect with no authentication at all are indistinguishable to the proxy. One of them can act on another's
  agent context: read its scrape requests (including a forwarded Prometheus `Authorization` header), unregister its
  paths, or answer its scrapes. Give each agent its own identity, or keep the transport filter enabled.
- **Path takeover within one identity.** A non-consolidated registration still replaces a live owner that
  connected with the same identity, which keeps redeploys working. Unauthenticated agents, and every holder of the
  legacy token, share one identity, so any of them can still take over another's path. See remediation item 4.
- **Consolidated paths are open to any authorized identity.** The same-identity rule covers only non-consolidated
  paths. An agent that registers with `consolidated = true` joins a consolidated path whenever its identity's path
  patterns allow it, even while agents of another identity serve that path. Every scrape of the path then goes to it
  too: its output is merged into the response, and an agent that never answers holds each scrape until it times out.
  It stays on the path until it disconnects. Keep identities' path patterns from overlapping on consolidated paths.

## Affected code

These code paths define the default, unauthenticated behavior:

- `proxy/ProxyServerTransportFilter.kt` — `transportReady()` creates an `AgentContext` and assigns the next
  `agentId` for every accepted transport, before any application message is exchanged.
- `proxy/ProxyServiceImpl.kt` — `connectAgent()` only checks that the agent's `transportFilterDisabled` setting
  matches the proxy's. With no identities configured, no token or client identity is required.
- `proxy/ProxyServiceImpl.kt` → `proxy/ProxyPathManager.kt` — `registerPath()` → `addPath()`. For a
  non-consolidated registration of a path that already has a non-consolidated owner, the proxy overwrites the
  path when the owner connected with the same identity or is no longer valid, increments
  `agentDisplacementCount`, and invalidates the displaced agent's context if it holds no other paths. A live owner
  of another identity keeps the path.
- `proxy/ProxyGrpcService.kt` — installs `AgentAuthServerInterceptor` for every service on the agent port only when
  `AgentAuthManager` holds at least one identity, and registers the reflection service only when
  `reflectionDisabled` is false (the default is true).

## Preconditions

The original finding is exploitable when **all** of the following hold:

1. **Network reachability** — the attacker can reach the proxy's agent gRPC port (default `50051`). In a correct
   deployment this port is exposed only to trusted agents inside the firewall; the exposure is what elevates this
   to High.
2. **No mutual TLS** — the proxy does not require agents to present a client certificate. TLS is opt-in:
   `isTlsEnabled` requires both `certChainFilePath` and `privateKeyFilePath`, and both default to empty, i.e.
   plaintext.
3. **No agent authentication, or a token the attacker holds** — no token or identities are configured, or the
   attacker holds a token whose identity is authorized for the target path (for example the shared
   `proxy.agentToken`).

## Attack scenario

1. The attacker reaches `proxy-host:50051` — for example, the port is exposed to a wider network than intended, or
   the attacker has a foothold on an adjacent host. *Blocked by network segmentation.*
2. The attacker runs a minimal gRPC client speaking the `ProxyService` protocol
   (`src/main/proto/proxy_service.proto`), which is public in the project's repository. If gRPC reflection has been
   enabled without agent authentication, `grpcurl` can also enumerate the service shape.
3. The attacker calls `connectAgent` → `registerAgent` → `registerPath` for an existing path such as
   `/node-exporter`. *Blocked by mutual TLS or by agent authentication; per-agent identities also block a path
   outside the attacker's globs, and a path a live agent of another identity already serves.*
4. The proxy overwrites the path's owner with the attacker's context and invalidates the legitimate agent if it has
   no other paths.
5. Prometheus scrapes `proxy-host:8080/node-exporter` and receives **attacker-controlled metrics**. The attacker
   can fabricate values to mask an outage, hide an intrusion, or trigger or suppress alerting and autoscaling
   decisions driven by those metrics.

In **consolidated** mode the attacker cannot displace the existing owner — the proxy rejects a consolidated /
non-consolidated mismatch — but can still *join* a consolidated path and answer a fraction of scrape requests with
fabricated data.

## Impact

- **Integrity of metrics** — Prometheus consumes attacker-controlled data for hijacked paths. This is the primary
  risk: monitoring, alerting, and autoscaling built on these metrics can be misled.
- **Availability** — the legitimate agent's context is invalidated on displacement, so it must reconnect and
  re-register; an attacker re-registering in a loop can keep a path effectively denied to the real agent.
- **Information disclosure** — when reflection is enabled without agent authentication, a peer can enumerate the
  full RPC surface.
- **Confidentiality, in one configuration** — with the transport filter disabled, a peer that shares another
  agent's identity (or any peer, when no authentication is configured) can read that agent's scrape requests,
  which include any `Authorization` header Prometheus forwards. With the transport filter enabled, the connection
  binding prevents this.

## Current mitigations

- **Per-agent identities with path authorization** — define identities under `proxy.auth`, each with its own token
  and allowed path globs. Agents present their identity's token with `--agent_token` / `AGENT_TOKEN` /
  `agent.agentToken`. This closes path takeover between identities and makes a shared proxy safe for multiple
  teams. Identities are read at startup, so revoking one requires a proxy restart. See the Security page of the
  documentation site for configuration and migration from a shared token.
- **Pre-shared agent token** — set the same secret on both sides with `--agent_token` / `AGENT_TOKEN` /
  `proxy.agentToken` (proxy) and `agent.agentToken` (agent). The proxy rejects any call with a missing or
  unrecognized token. It authenticates agents but does not scope what they may register.
- **Mutual TLS** — set the proxy's `trustCertCollectionFilePath` together with the TLS certificate and key so the
  proxy requires a valid client certificate, and provision agents with certificates. See the `tls { … }` blocks in
  `config/config.conf`.
- **Network segmentation** — restricting port `50051` to trusted agents (firewall rules, a private network, a
  service mesh) removes the reachability precondition, and should be treated as a hard requirement for any
  plaintext deployment.

## Recommended remediation

In rough priority order:

1. **Optional pre-shared agent token.** ✅ **Implemented.** A shared secret (`--agent_token` / `AGENT_TOKEN` /
   `proxy.agentToken` on the proxy, `agent.agentToken` on the agent) sent in the `agent-token` metadata header. The
   proxy rejects a missing or unrecognized token with `UNAUTHENTICATED`, and when no token is configured it keeps
   the open behavior and logs a startup warning. It has since been extended into per-agent identities (below).
2. **Document mutual TLS as the recommended production posture** and network segmentation as the minimum bar for
   plaintext deployments. **Partially addressed:** the documentation site's Security page recommends mutual TLS
   and/or restricting the agent port for production.
3. **Disable gRPC reflection by default.** ✅ **Implemented.** `reflectionDisabled` defaults to `true` in
   `config/config.conf`, so a peer cannot trivially enumerate the API; operators who need reflection for tooling can
   set it to `false`. When enabled, reflection sits behind the same agent authentication as `ProxyService`.
4. **Consider rejecting path displacement by default.** **Partly implemented.** A live agent's non-consolidated
   path can now be taken over only by an agent that connected with the same identity, so a redeploy still reclaims
   its paths while one `proxy.auth` identity can no longer replace another's. Takeover between agents that share an
   identity — every unauthenticated agent, or every holder of the legacy token — is unchanged, because rejecting it
   would break redeploys; separating those agents takes per-agent identities. `agentDisplacementCount` counts each
   takeover. Consolidated paths, which agents join rather than take over, are not covered; see Remaining gaps.

### Implemented since the original finding

- **Per-agent identities and path authorization** (#204) — `proxy.auth` identities, each scoped by path globs.
- **agentId bound to the connection** (#208) — `registerAgent`, `registerPath`, `unregisterPath`, and
  `readRequestsFromProxy` reject a request naming another connection's agent.
- **Scrape results and heartbeats bound, and identity binding with the transport filter disabled** (#241, findings
  #1 and #2 of `docs/archive/CODE_REVIEW_SEPTEMBER_2026.md`) — scrape results are accepted only from the agent the scrape
  was sent to, `sendHeartBeat` is bound like the other calls, and filter-disabled deployments bind calls to the
  identity recorded at connect.
- **Accurate startup warnings** (finding #6 of `docs/archive/CODE_REVIEW_SEPTEMBER_2026.md`) — the "agent port is
  unauthenticated" warning no longer treats a trust store as mutual TLS unless TLS is enabled, and the proxy and
  the agent each warn when agent tokens are configured without TLS and would be sent in cleartext.

## References

- gRPC service definition: `src/main/proto/proxy_service.proto`
- Authentication: `src/main/kotlin/io/prometheus/proxy/AgentAuthManager.kt`,
  `src/main/kotlin/io/prometheus/proxy/AgentAuthServerInterceptor.kt`
- Call binding and agent RPCs: `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt`,
  `src/main/kotlin/io/prometheus/proxy/ProxyServerInterceptor.kt`
- Transport filter (agentId assignment): `src/main/kotlin/io/prometheus/proxy/ProxyServerTransportFilter.kt`
- Path management and displacement: `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt`
- Interceptor and reflection registration: `src/main/kotlin/io/prometheus/proxy/ProxyGrpcService.kt`
- TLS configuration: `config/config.conf` (`tls { … }` blocks), `common/BaseOptions.kt`
- Documentation site: `website/prometheus-proxy/docs/security/index.md`, `website/prometheus-proxy/docs/security/tls.md`
- Tests: `harness/AgentTokenAuthTest.kt`, `harness/AgentPathAuthTest.kt`, `harness/TlsWithMutualAuthTest.kt`,
  `harness/TlsMutualAuthRejectionTest.kt`, `containers/ContainersAgentTokenAuthTest.kt`, the binding tests in
  `proxy/ProxyServiceImplTest.kt`, and the reflection tests in `proxy/ProxyGrpcServiceTest.kt`
