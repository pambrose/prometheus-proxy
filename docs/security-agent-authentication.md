# Security Finding: Unauthenticated Agent Registration / Path Hijacking

**Status:** Open
**Severity:** High (conditional on network exposure of the agent gRPC port)
**Component:** Proxy gRPC service (agent-facing port, default `50051`)
**Identified:** 2026-06 code review

> This document records a known security limitation in the proxy's agent-facing
> gRPC interface so it can be tracked and remediated deliberately. It is a design
> gap, not a regression — the proxy has never performed application-level agent
> authentication.

## Summary

The proxy accepts agent gRPC connections on port `50051` with **no application-level
authentication**. Any process that can open a TCP connection to that port can register
itself as an agent and register paths. Because path registration for a non-consolidated
path **displaces** the existing owner of that path, a rogue agent can take over a path
and serve arbitrary metrics to Prometheus in place of the legitimate agent.

The only authentication mechanism available today is **mutual TLS**, which is opt-in and
off by default.

## Affected code

The agent connection/registration chain performs no identity check beyond confirming that
an `agentId` exists in the in-memory context map — and that map is populated by the
transport filter the moment a TCP connection is accepted:

- `proxy/ProxyServerTransportFilter.kt:32-41` — `transportReady()` constructs an
  `AgentContext` and assigns the next `agentId` on every accepted transport, before any
  application message is exchanged.
- `proxy/ProxyServiceImpl.kt:70-79` — `connectAgent()` only checks that the agent's
  `transportFilterDisabled` config matches the proxy's; it returns success unconditionally.
  No token, shared secret, or client identity is required.
- `proxy/ProxyServiceImpl.kt:97-115` — `registerAgent()` succeeds whenever the supplied
  `agentId` is present in the context map (which the transport filter just populated).
- `proxy/ProxyServiceImpl.kt:117-139` → `proxy/ProxyPathManager.kt:98-124` —
  `registerPath()` → `addPath()`. For a non-consolidated registration against a path that
  already has a non-consolidated owner, the proxy **overwrites** the path
  (`pathMap[path] = AgentContextInfo(false, labels, mutableListOf(agentContext))`),
  increments `agentDisplacementCount`, and invalidates the displaced agent's context if it
  holds no other paths.

## Preconditions

The finding is exploitable only when **both** of the following hold:

1. **Network reachability** — the attacker can reach the proxy's agent gRPC port
   (default `50051`). In a correct deployment this port is exposed only to trusted agents
   inside the firewall; the exposure is what elevates this to High.
2. **No mutual TLS** — the proxy is not configured with a client trust store
   (`trustCertCollectionFilePath`), so it does not require agents to present a client
   certificate. TLS is opt-in: `isTlsEnabled` requires both `certChainFilePath` and
   `privateKeyFilePath` to be set (`common/BaseOptions.kt:204-205`), and both default to
   empty, i.e. plaintext.

## Attack scenario

1. Attacker reaches `proxy-host:50051` (e.g. the port is exposed to a wider network than
   intended, or the attacker has a foothold on an adjacent host).
2. Attacker runs a minimal gRPC client speaking the `ProxyService` protocol
   (`src/main/proto/proxy_service.proto`). gRPC reflection is enabled by default
   (`config/config.conf:7`, `proxy/ProxyGrpcService.kt:111-112`), so the service shape can
   be enumerated with `grpcurl` rather than reading the source.
3. Attacker calls `connectAgent` → `registerAgent` → `registerPath` for an existing path
   such as `/node-exporter`.
4. The proxy overwrites the path's owner with the attacker's context and invalidates the
   legitimate agent (if it has no other paths).
5. Prometheus scrapes `proxy-host:8080/node-exporter` and receives **attacker-controlled
   metrics**. The attacker can fabricate values to mask an outage, hide an intrusion, or
   trigger/suppress alerting and autoscaling decisions driven by those metrics.

In **consolidated** mode the attacker cannot displace the existing owner
(`ProxyPathManager.kt:91-95, 99-103` reject a consolidated/non-consolidated mismatch), but
can still *join* a consolidated path and answer a fraction of scrape requests with
fabricated data.

## Impact

- **Integrity of metrics** — Prometheus consumes attacker-controlled data for hijacked
  paths. This is the primary risk: monitoring/alerting/autoscaling built on these metrics
  can be misled.
- **Availability** — the legitimate agent's context is invalidated on displacement, so it
  must reconnect and re-register; an attacker re-registering in a loop can keep a path
  effectively denied to the real agent.
- **Information disclosure** — gRPC reflection lets an unauthenticated peer enumerate the
  full RPC surface.

There is **no confidentiality breach of secrets** from this finding alone — the proxy does
not hand agent credentials or scrape-target secrets to connecting agents.

## Current mitigations

- **Mutual TLS** fully closes the finding when configured: set the proxy's
  `trustCertCollectionFilePath` (and the TLS cert/key) so the proxy requires a valid client
  certificate, and provision agents with certificates. See the `tls { … }` blocks in
  `config/config.conf` and the TLS integration tests
  (`src/test/kotlin/io/prometheus/harness/TlsWithMutualAuthTest.kt`,
  `TlsMutualAuthRejectionTest`).
- **Network segmentation** — restricting port `50051` to trusted agents (firewall rules,
  private network, service mesh) removes the reachability precondition. This is the de facto
  control today and should be documented as a hard requirement for any plaintext deployment.

## Recommended remediation

In rough priority order:

1. **Optional pre-shared agent token (lowest-friction app-level auth).** Add a configurable
   secret (e.g. `--agent-token` / `AGENT_TOKEN` / `proxy.agentToken`) that agents send in a
   gRPC metadata header. A server interceptor (alongside `ProxyServerInterceptor`) rejects
   any RPC lacking the correct token with `Status.UNAUTHENTICATED`. When the token is empty,
   preserve today's open behavior for backward compatibility — but log a prominent startup
   **warning** that the agent port is unauthenticated, mirroring the existing
   `trustAllX509Certificates` warning pattern.
2. **Document mutual TLS as the recommended production posture** and network segmentation as
   the minimum bar for plaintext deployments (README + CLI/config docs).
3. **Disable gRPC reflection by default** (`reflectionDisabled = true` in
   `config/config.conf`), so an unauthenticated peer cannot trivially enumerate the API.
   Operators who need reflection for tooling can opt back in.
4. **Consider rejecting path displacement by default**, making overwrite an explicit,
   configurable behavior. The current silent-overwrite is convenient for redeploys but is
   the mechanism that turns missing auth into hijacking. (`agentDisplacementCount` already
   exists as an observability hook for this event.)

Items 1–3 are individually small and non-breaking; item 4 is a behavior change that needs
its own design discussion.

## References

- gRPC service definition: `src/main/proto/proxy_service.proto`
- Path management / displacement: `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt`
- Agent registration RPCs: `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt`
- Transport filter (agentId assignment): `src/main/kotlin/io/prometheus/proxy/ProxyServerTransportFilter.kt`
- TLS configuration: `config/config.conf` (`tls { … }` blocks), `common/BaseOptions.kt`
