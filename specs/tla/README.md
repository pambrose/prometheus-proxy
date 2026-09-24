# TLA+ specs

Two independent specs: `ProxyRegistry.tla` for the proxy side and `AgentFailover.tla` for the agent's failover.

`make tla-checks` (from the repo root) model-checks both with their quick configs in about 15 seconds, and fails if
either finds a violation; CI's `tla` job runs it on every pull request and push to `master`. It downloads the TLA+
tools jar (pinned in the Makefile's `TLA_VERSION`) into this directory on first use, and refuses a download whose
SHA-256 doesn't match `TLA_SHA256`; the jar is git-ignored. To run a config by hand, or the long
`ProxyRegistrySafety.cfg`, use the commands below from this directory.

## ProxyRegistry

`ProxyRegistry.tla` models the proxy's agent registry, path map, and scrape-request lifecycle: `AgentContextManager`,
`AgentContext` (validity, queue, notifier, `invalidate()` and its drain), `ProxyPathManager` (the out-of-lock lookup and
the `synchronized(pathMap)` block of `addPath`, `removePath`, `removeFromPathManager`), `ScrapeRequestManager`,
`Proxy.removeAgentContext`, one HTTP handler per scrape, and the `readRequestsFromProxy` loop. Heartbeats, chunking,
metrics, and the event bus are abstracted away. Agent-side failover is in `AgentFailover.tla`.

It is a model of the code, not the code: when you change one of those classes, update the matching action.

### Properties

| Property | Checks |
|---|---|
| `ExclusivePathHasOneAgent` | A non-consolidated path has exactly one agent |
| `NoStrandedPath` | Every agent on a path is valid or about to be swept (finding 7) |
| `PathCountsAgree` | `pathCounts` agrees with the path map |
| `IdentityIsolation` | No identity takes over or joins a path another identity's live agent serves |
| `AnswerFromOwner` | Only the agent a scrape was sent to can answer it |
| `ModesNeverMix` | Consolidated and non-consolidated agents never share a path |
| `EveryScrapeFinishes` | With the handler timeout off, every started scrape still finishes |

### Running

With `tla2tools.jar` in this directory (`make tla-checks` fetches it, or download it from the
[TLA+ releases](https://github.com/tlaplus/tlaplus/releases)):

```bash
# Safety and liveness, 2 connections (about 15s)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistry.cfg ProxyRegistry.tla

# Safety only, 3 connections, 2 paths, 2 scrapes (hours; not yet run to completion)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistrySafety.cfg ProxyRegistry.tla
```

`-deadlock` turns off deadlock checking: a run that ends with every scrape done and nothing left to do is fine.

To see a property fail, set `CheckOwnership = FALSE` in a config: TLC then finds an agent answering another agent's
scrape and reports `AnswerFromOwner` violated with the trace.

## AgentFailover

`AgentFailover.tla` models the agent's reconnect loop in `Agent.run` and `EndpointFailover.beforeAttempt` over an
ordered `agent.proxy.endpoints` list: fail back to the primary after a registered connection drops, fail forward after
a failed connect or a rejected registration. Each proxy is up, down, or accepting connections but rejecting
registration, and changes freely until the environment settles.

| Property | Checks |
|---|---|
| `SettlesOnAWorkingProxy` | Once proxies settle with one up, the agent ends up registered and stays |
| `FailsBackAfterRegisteredDrop` | After a registered connection drops, the next attempt goes to the primary |
| `FailsForwardAfterFailure` | A failed or rejected attempt moves on to the next endpoint |

```bash
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config AgentFailover.cfg AgentFailover.tla
```

It takes about a second at `NumEndpoints = 3` and passes for 1 through 4. Setting `FailbackOnConnect = TRUE` restores
the 4.0.0 rule, which failed back once an attempt had connected: TLC then shows `SettlesOnAWorkingProxy` violated by an
agent looping forever on a primary that accepts connections but rejects registration while the secondary is up.
(`FailsForwardAfterFailure` fails too, as it restates the current rule.)
