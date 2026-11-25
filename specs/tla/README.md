# TLA+ specs

Two independent specs: `ProxyRegistry.tla` for the proxy side and `AgentFailover.tla` for the agent's failover.

`make tla-checks` (from the repo root) model-checks both with their quick configs in about a minute, and fails if either
finds a violation. `make tla-checks-full` adds the slower `ProxyRegistryReconnect.cfg`, about a minute more here and
several in CI. CI's `tla` job runs the full set when a file in this directory or the pinned TLA+ tools change, as TLC
reads nothing else, and the quick configs otherwise, on every pull request and push to `master`; a manual run of the
workflow runs the full set. Either downloads the TLA+ tools jar (pinned in the Makefile's `TLA_VERSION`) into this
directory, and refuses a download whose SHA-256 doesn't match `TLA_SHA256`; the jar is git-ignored. It checks the jar
on every run, so after a version bump the old one is replaced rather than run. To run a config by hand, or the long
`ProxyRegistrySafety.cfg`, use the commands below from this directory.

## ProxyRegistry

`ProxyRegistry.tla` models the proxy's agent registry, path map, and scrape-request lifecycle: `AgentContextManager`,
`AgentContext` (validity, queue, notifier, `invalidate()` and its drain), `ProxyPathManager` (`addPath`, after
`registerPath`'s out-of-lock context lookup, and its `synchronized(pathMap)` block in `addValidatedPath`, `removePath`,
`removeFromPathManager`), `ScrapeRequestManager`, `Proxy.removeAgentContext`, one HTTP handler per scrape, and the
`readRequestsFromProxy` loop, including, with the transport filter disabled, a stream that names another connection's
agentId, which only the identity check binds.
It also models path notices and reclaims: the notice `takeOverPath` queues for a displaced connection of another
launch, its delivery on `readPathNoticesFromProxy`, the agent counting the path as taken over, which it keeps across
its reconnects, and its reclaim, which the proxy refuses while a live connection of another launch holds the path,
telling the agent again that it's taken. Invalidating a connection drops its unread notices, as `invalidate()` does,
whether the proxy removed it or a registration of its own launch orphaned it.
Heartbeats, stream keepalives, chunking, metrics, and the event bus are abstracted away, and so are the notice that a
path is free and the waiting list that sends it, which only bring a reclaim forward. Agent-side failover is in
`AgentFailover.tla`.

It is a model of the code, not the code: when you change one of those classes, update the matching action.

Each connection belongs to one of `Launches`, as an agent keeps its launch id across reconnects. A registration that
displaces a connection and leaves it with no paths invalidates it only when it is of the registering connection's
launch, a stale connection of the same agent; one of another launch stays connected without paths. `Init` fixes one
connection's launch, so the others cover both cases without relabelings that check nothing new.

### Properties

| Property | Checks |
|---|---|
| `ExclusivePathHasOneAgent` | A non-consolidated path has exactly one agent |
| `NoStrandedPath` | Every agent on a path is valid or about to be swept (finding 7) |
| `PathCountsAgree` | `pathCounts` agrees with the path map |
| `IdentityIsolation` | No identity takes over or joins a path another identity's live agent serves |
| `AnswerFromOwner` | Only the agent a scrape was sent to can answer it, even when another agent names it before it is tracked |
| `ModesNeverMix` | Consolidated and non-consolidated agents never share a path |
| `StreamIdentity` | With the transport filter disabled, a scrape-request stream reads, and as it ends removes, only a context of its caller's identity, even when it named the agentId before that context was registered |
| `InvalidatedHasNoNotices` | An invalidated connection, removed or orphaned, holds no unread notices, which `invalidate()` clears and `queuePathNotice` refuses after it |
| `EveryScrapeFinishes` | With the handler timeout off, every started scrape still finishes |
| `ReclaimNeverDisplaces` | A reclaim never takes a path from a live connection of another launch, so two agents with the same exclusive path never swap it back and forth |
| `DisplacedReclaims` | An agent told its path was taken over takes it back once no connection holds it, on whichever connection it has then, unless another takes it first or that connection is invalidated |
| `ReconnectReclaims` | An agent registers a path it was told was taken over only as a reclaim, after a reconnect too, so its reconnect never takes the path from the live agent holding it |

### Running

With `tla2tools.jar` in this directory (`make tla-checks` fetches it, or download it from the
[TLA+ releases](https://github.com/tlaplus/tlaplus/releases)):

```bash
# Safety and liveness, 2 connections (under a minute)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistry.cfg ProxyRegistry.tla

# Path notices and reclaims, 2 connections of one identity on one path (seconds)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistryReclaim.cfg ProxyRegistry.tla

# The same with a reconnect, 3 exclusive connections (about a minute; make tla-checks-full, which CI runs when this
# directory or the pinned tools change, and on a manual run)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistryReconnect.cfg ProxyRegistry.tla

# Safety only, 3 connections, 2 paths, 2 scrapes (hours; not yet run to completion)
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config ProxyRegistrySafety.cfg ProxyRegistry.tla
```

`-deadlock` turns off deadlock checking: a run that ends with every scrape done and nothing left to do is fine.

To see a property fail, set `CheckOwnership = FALSE` in `ProxyRegistry.cfg`: TLC then finds an agent answering another
agent's scrape and reports `AnswerFromOwner` violated with the trace. This flag and the two below matter only in
`ProxyRegistry.cfg` and `ProxyRegistrySafety.cfg`: the reclaim and reconnect configs have no scrapes, don't check
`AnswerFromOwner`, and have only one identity, so `StreamIdentity` can't fail there.

`SameLookup = FALSE` keeps the owner check but restores how it ran before October #21's fix: on a lookup of its own,
before `assignScrapeResults` looked the scrape up again, with an untracked scrape id let through. TLC then reports
`AnswerFromOwner` violated by an agent answering a scrape id before it is tracked, the HTTP handler tracking that id for
another agent, and the answer completing it. The configs set `SameLookup = TRUE`, where the check runs on the wrapper
`assignScrapeResults` completes.

`StreamSameLookup = FALSE` restores `readRequestsFromProxy` as it was before November #14's fix, with the transport
filter disabled: it checked the caller's identity on a lookup of its own, let an agentId with no context through,
streamed whatever a second lookup found, and removed the agentId in its `finally` even after that lookup found nothing.
TLC then reports `StreamIdentity` violated by a connection naming an agentId before it is assigned, its second lookup
finding nothing, and an agent of another identity connecting under that agentId before the stream's `finally` removes
it. The configs set `StreamSameLookup = TRUE`, where the stream checks, reads, and removes the one context it looked up.
One connection, the `Intruder`, plays the caller: connections are interchangeable and `Init` tries every mix of
identities, so letting each connection intrude only doubles the states.

`UseReclaim` turns path notices and reclaims on; `ProxyRegistry.cfg` and `ProxyRegistrySafety.cfg` leave it off, as
for agents and proxies predating them, so their state spaces are unchanged, and `ProxyRegistryReclaim.cfg` and
`ProxyRegistryReconnect.cfg` turn it on.
`CheckHolder = FALSE`, in `ProxyRegistryReclaim.cfg`, drops the proxy's refusal of a reclaim while a live connection of
another launch holds the path, as a proxy predating the flag ignores it: TLC then reports `ReclaimNeverDisplaces`
violated by a displaced connection taking the path back from the one that took it.

The paths an agent was told were taken over are its launch's, not one connection's, so a reconnect, a connection of the
same launch, keeps them. `KeepTold = FALSE` has the agent forget them as it connects, as it did before: TLC then
reports `ReconnectReclaims` violated by a displaced agent reconnecting and registering the path the usual way.
`ProxyRegistryReconnect.cfg` has three connections, so one agent can reconnect while an agent of another launch
holds the path; with two, as in `ProxyRegistryReclaim.cfg`, a reconnect never meets an agent of another launch, so
`ReconnectReclaims` holds there trivially. It sets `Modes = {FALSE}`, leaving out consolidated connections, which are
never told and never reclaim: with them it took several times as long. The other configs set `Modes = {TRUE, FALSE}`.

`InvalidatedHasNoNotices` holds trivially in `ProxyRegistry.cfg` and `ProxyRegistrySafety.cfg`, which send no notices.
In the reclaim and reconnect configs it checks that a removed connection loses its notices, but never meets an orphan
holding one: with one path, an orphan is a connection that served the path until its own launch took it, and serving a
path forgets the notice about it. An orphan with a notice about another path takes two paths and three connections.
With `Paths = {p1, p2}` in `ProxyRegistryReconnect.cfg` and the orphan's notices kept, as the model kept them before
November 23 #1's fix, TLC reports the invariant violated in seconds, at depth 12, but that model is too large to check
in full: in five minutes, without its temporal properties, TLC passed 60 million distinct states, at depth 25, with 15
million still queued.

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
the 4.0.0 rule, which failed back once an attempt had connected: TLC then reports `FailsForwardAfterFailure` violated,
as it restates the current rule, by an agent going back to a primary that accepted its connection but rejected its
registration, and stops there. To see the loop that rule caused, drop `FailsForwardAfterFailure` from `PROPERTIES` too:
TLC then shows `SettlesOnAWorkingProxy` violated by an agent looping forever on a primary that accepts connections but
rejects registration while another proxy is up.
