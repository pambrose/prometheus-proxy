# Security Policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue, pull request, or discussion.

Use GitHub's private vulnerability reporting:
[Report a vulnerability](https://github.com/pambrose/prometheus-proxy/security/advisories/new) (the **Security**
tab, then **Report a vulnerability**). The report, and the discussion that follows, stay visible only to you and
the maintainer until an advisory is published.

A useful report includes:

- the version (`--version`, or the image tag) and which part is affected: the proxy, the agent, or an agent
  embedded with `Agent.startAsyncAgent()`
- the relevant configuration: TLS or mutual TLS, `proxy.auth` identities or `agentToken`,
  `transportFilterDisabled`, and which of the admin, metrics, and dashboard endpoints are enabled
- the steps to reproduce, or a proof of concept, and what an attacker gains

Once a fix is ready it ships in a release, and the advisory is published with it, crediting you if you want.

## Supported versions

Security fixes go into the latest release. Upgrade to it to receive them; older releases are not patched.

## Before you report

Some behavior is by design and documented, so check the
[security guide](https://pambrose.github.io/prometheus-proxy/security/) first:

- The agent gRPC port (default `50051`) accepts any agent unless a pre-shared agent token, per-agent identities,
  or mutual TLS is configured. The proxy logs a warning at startup when it is left open.
- The admin, metrics, and dashboard endpoints have no authentication and no TLS. Keep their ports on an internal
  network.

A way around a documented control (an identity registering a path its globs don't allow, one agent reading or
answering another's scrapes, credentials leaking into logs or the dashboard) is a vulnerability, and so is a
weakness in the defaults themselves. Please report it.
