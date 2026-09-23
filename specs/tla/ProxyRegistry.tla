--------------------------- MODULE ProxyRegistry ---------------------------
(***************************************************************************)
(* The proxy's agent registry, path map, and scrape-request lifecycle.     *)
(*                                                                         *)
(* Models, at the granularity of their locks and atomic operations:        *)
(*   AgentContextManager  -- the agentId -> AgentContext map               *)
(*   AgentContext         -- validity, request queue, notifier channel,    *)
(*                           invalidate() and its drain                    *)
(*   ProxyPathManager     -- addPath (out-of-lock lookup, then the         *)
(*                           synchronized(pathMap) block), removePath,     *)
(*                           removeFromPathManager                         *)
(*   ScrapeRequestManager -- tracked scrapes, ownership check, CAS         *)
(*                           completion                                   *)
(*   Proxy.removeAgentContext -- the three-step teardown                   *)
(*   ProxyHttpRoutes      -- one HTTP handler per scrape                   *)
(*   ProxyServiceImpl.readRequestsFromProxy -- the per-agent reader loop   *)
(*                                                                         *)
(* Heartbeats, chunking, metrics, and the event bus are abstracted away.  *)
(* Eviction and transport termination both reach removeAgentContext, so   *)
(* both are the nondeterministic Disconnect action.                        *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    Conns,          \* agent connections; each gets its own AgentContext / agentId
    Paths,          \* scrape paths agents may register
    Identities,     \* per-agent auth identities
    Scrapes,        \* scrape requests; one HTTP handler each
    NoConn,         \* model value: "no connection"
    UseTimeout,     \* FALSE disables the HTTP handler's timeout, so liveness proves no scrape is stranded
    CheckOwnership  \* FALSE removes isScrapeOwnedByConnection, to see TLC catch the cross-agent answer

ASSUME UseTimeout \in BOOLEAN /\ CheckOwnership \in BOOLEAN

VARIABLES
    \* Fixed per connection at Init, so TLC explores every mix of identities and modes.
    ident,          \* [Conns -> Identities]
    consol,         \* [Conns -> BOOLEAN]  RegisterAgentRequest.consolidated

    \* AgentContext and its lifecycle
    phase,          \* [Conns -> {"unborn","live","ctxRemoved","swept","gone"}]
    inMgr,          \* SUBSET Conns: keys of AgentContextManager.agentContextMap
    valid,          \* [Conns -> BOOLEAN]: valid && notifier open (invalidate() flips both)
    draining,       \* [Conns -> BOOLEAN]: invalidate()'s drain loop is running
    queue,          \* [Conns -> Seq(Scrapes)]: scrapeRequestQueue
    tokens,         \* [Conns -> Nat]: buffered Units in scrapeRequestNotifier
    reading,        \* [Conns -> SUBSET Scrapes]: polled by the reader, not yet emitted
    agentHas,       \* [Conns -> SUBSET Scrapes]: delivered to the agent, not yet answered

    \* ProxyPathManager
    regPending,     \* SUBSET (Conns \X Paths): registerPath past its out-of-lock lookup
    pathMap,        \* [Paths -> [consolidated, identity, ctxs]]; ctxs = {} means absent
    pathCount,      \* [Conns -> Nat]: pathCounts

    \* HTTP handlers and ScrapeRequestManager
    hstate,         \* [Scrapes -> {"idle","picked","tracked","enqueued","awaiting","done"}]
    target,         \* [Scrapes -> Conns \cup {NoConn}]
    tracked,        \* SUBSET Scrapes: keys of scrapeRequestMap
    result,         \* [Scrapes -> {"none","agent","disconnected","cancelled"}]
    completedBy     \* [Scrapes -> Conns \cup {NoConn}]: the agent whose answer won the CAS

connVars  == <<phase, inMgr, valid, draining, queue, tokens, reading, agentHas>>
pathVars  == <<regPending, pathMap, pathCount>>
scrapeVars == <<hstate, target, tracked, result, completedBy>>
vars == <<ident, consol, connVars, pathVars, scrapeVars>>

Absent == [consolidated |-> FALSE, identity |-> CHOOSE i \in Identities : TRUE, ctxs |-> {}]

-----------------------------------------------------------------------------
(* Helpers *)

\* ScrapeRequestWrapper.complete: only the first completion wins the CAS.
Complete(s, r, by) ==
    IF result[s] = "none"
    THEN /\ result' = [result EXCEPT ![s] = r]
         /\ completedBy' = [completedBy EXCEPT ![s] = by]
    ELSE UNCHANGED <<result, completedBy>>

\* The proxy failing a set of requests itself (failAllScrapeRequests).
FailAll(S, r) ==
    /\ result' = [s \in Scrapes |-> IF s \in S /\ result[s] = "none" THEN r ELSE result[s]]
    /\ UNCHANGED completedBy

Remove(seq, s) == SelectSeq(seq, LAMBDA x : x # s)

\* The owner check on a response RPC (isScrapeOwnedByConnection).
OwnedBy(s, c) == ~CheckOwnership \/ target[s] = c

-----------------------------------------------------------------------------
(* Agent connection lifecycle *)

\* ProxyServerTransportFilter.transportReady / connectAgent: a new AgentContext.
Connect(c) ==
    /\ phase[c] = "unborn"
    /\ phase' = [phase EXCEPT ![c] = "live"]
    /\ inMgr' = inMgr \cup {c}
    /\ valid' = [valid EXCEPT ![c] = TRUE]
    /\ UNCHANGED <<ident, consol, draining, queue, tokens, reading, agentHas, pathVars, scrapeVars>>

\* removeAgentContext step 1: removeFromContextManager, which invalidates the context. Losing the transport also
\* loses whatever the agent was scraping.
Disconnect(c) ==
    /\ phase[c] = "live"
    /\ phase' = [phase EXCEPT ![c] = "ctxRemoved"]
    /\ inMgr' = inMgr \ {c}
    /\ valid' = [valid EXCEPT ![c] = FALSE]
    /\ draining' = [draining EXCEPT ![c] = TRUE]
    /\ agentHas' = [agentHas EXCEPT ![c] = {}]
    /\ UNCHANGED <<ident, consol, queue, tokens, reading, pathVars, scrapeVars>>

\* removeAgentContext step 2: removeFromPathManager sweeps every path by agentId.
SweepPaths(c) ==
    /\ phase[c] = "ctxRemoved"
    /\ phase' = [phase EXCEPT ![c] = "swept"]
    /\ pathMap' = [p \in Paths |->
                    IF c \in pathMap[p].ctxs
                    THEN IF pathMap[p].ctxs = {c} THEN Absent
                         ELSE [pathMap[p] EXCEPT !.ctxs = @ \ {c}]
                    ELSE pathMap[p]]
    /\ pathCount' = [pathCount EXCEPT ![c] = 0]
    /\ UNCHANGED <<ident, consol, inMgr, valid, draining, queue, tokens, reading, agentHas, regPending, scrapeVars>>

\* removeAgentContext step 3: failAllScrapeRequests for the agent's tracked scrapes.
FailScrapes(c) ==
    /\ phase[c] = "swept"
    /\ phase' = [phase EXCEPT ![c] = "gone"]
    /\ FailAll({s \in tracked : target[s] = c}, "disconnected")
    /\ UNCHANGED <<ident, consol, inMgr, valid, draining, queue, tokens, reading, agentHas, pathVars,
                   hstate, target, tracked>>

\* invalidate()'s drain loop: poll a queued request and fail it with agent-disconnected.
Drain(c) ==
    /\ draining[c]
    /\ queue[c] # << >>
    /\ queue' = [queue EXCEPT ![c] = Tail(@)]
    /\ Complete(Head(queue[c]), "disconnected", NoConn)
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, tokens, reading, agentHas, pathVars,
                   hstate, target, tracked>>

DrainEnd(c) ==
    /\ draining[c]
    /\ queue[c] = << >>
    /\ draining' = [draining EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, queue, tokens, reading, agentHas, pathVars, scrapeVars>>

-----------------------------------------------------------------------------
(* Path registration *)

\* registerPath's out-of-lock getAgentContext() lookup succeeded.
RegisterStart(c, p) ==
    /\ c \in inMgr
    /\ <<c, p>> \notin regPending
    /\ regPending' = regPending \cup {<<c, p>>}
    /\ UNCHANGED <<ident, consol, connVars, pathMap, pathCount, scrapeVars>>

\* addValidatedPath: the synchronized(pathMap) block, which may run after the context was torn down.
RegisterLocked(c, p) ==
    /\ <<c, p>> \in regPending
    /\ regPending' = regPending \ {<<c, p>>}
    /\ LET e       == pathMap[p]
           others  == e.ctxs \ {c}
           already == c \in e.ctxs
           rejected ==
               \/ ~valid[c]
               \/ e.ctxs # {} /\ e.consolidated # consol[c]
               \/ e.ctxs # {} /\ e.identity # ident[c] /\ \E o \in others : valid[o]
           \* Displaced agents left with no paths are invalidated inside the lock.
           newCount == [x \in Conns |->
                          IF x = c THEN IF already THEN pathCount[x] ELSE pathCount[x] + 1
                          ELSE IF ~consol[c] /\ x \in others THEN pathCount[x] - 1
                          ELSE pathCount[x]]
           orphans == IF consol[c] THEN {} ELSE {o \in others : newCount[o] = 0 /\ valid[o]}
       IN IF rejected
          THEN UNCHANGED <<connVars, pathMap, pathCount>>
          ELSE /\ pathMap' = [pathMap EXCEPT ![p] =
                                [consolidated |-> consol[c],
                                 identity     |-> ident[c],
                                 ctxs         |-> IF consol[c] THEN e.ctxs \cup {c} ELSE {c}]]
               /\ pathCount' = newCount
               /\ valid' = [x \in Conns |-> IF x \in orphans THEN FALSE ELSE valid[x]]
               /\ draining' = [x \in Conns |-> IF x \in orphans THEN TRUE ELSE draining[x]]
               /\ UNCHANGED <<phase, inMgr, queue, tokens, reading, agentHas>>
    /\ UNCHANGED <<ident, consol, scrapeVars>>

\* unregisterPath from a connected agent (ProxyPathManager.removePath).
Unregister(c, p) ==
    /\ phase[c] = "live"
    /\ c \in pathMap[p].ctxs
    /\ pathMap' = [pathMap EXCEPT ![p] =
                     IF @.consolidated /\ Cardinality(@.ctxs) > 1 THEN [@ EXCEPT !.ctxs = @ \ {c}] ELSE Absent]
    /\ pathCount' = [pathCount EXCEPT ![c] = @ - 1]
    /\ UNCHANGED <<ident, consol, connVars, regPending, scrapeVars>>

-----------------------------------------------------------------------------
(* One HTTP scrape handler per scrape id *)

\* processRequestsBasedOnPath: look the path up, refuse it when every context is invalid, and pick an agent.
HttpStart(s) ==
    /\ hstate[s] = "idle"
    /\ \E p \in Paths :
        /\ \E v \in pathMap[p].ctxs : valid[v]
        /\ \E c \in pathMap[p].ctxs :
            /\ target' = [target EXCEPT ![s] = c]
            /\ hstate' = [hstate EXCEPT ![s] = "picked"]
    /\ UNCHANGED <<ident, consol, connVars, pathVars, tracked, result, completedBy>>

\* tryAddToScrapeRequestMap (the in-flight limit is not modeled).
HttpTrack(s) ==
    /\ hstate[s] = "picked"
    /\ hstate' = [hstate EXCEPT ![s] = "tracked"]
    /\ tracked' = tracked \cup {s}
    /\ UNCHANGED <<ident, consol, connVars, pathVars, target, result, completedBy>>

\* writeScrapeRequest part 1: scrapeRequestQueue.add (the backlog cap is not modeled).
HttpEnqueue(s) ==
    /\ hstate[s] = "tracked"
    /\ queue' = [queue EXCEPT ![target[s]] = Append(@, s)]
    /\ hstate' = [hstate EXCEPT ![s] = "enqueued"]
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, tokens, reading, agentHas, pathVars,
                   target, tracked, result, completedBy>>

\* writeScrapeRequest part 2: notifier.send. On a closed channel it takes the request back out of the queue (if the
\* drain hasn't already) and the handler answers agent-disconnected; its finally untracks the scrape.
HttpSend(s) ==
    /\ hstate[s] = "enqueued"
    /\ LET c == target[s] IN
         IF valid[c]
         THEN /\ tokens' = [tokens EXCEPT ![c] = @ + 1]
              /\ hstate' = [hstate EXCEPT ![s] = "awaiting"]
              /\ UNCHANGED <<queue, tracked>>
         ELSE /\ queue' = [queue EXCEPT ![c] = Remove(@, s)]
              /\ hstate' = [hstate EXCEPT ![s] = "done"]
              /\ tracked' = tracked \ {s}
              /\ UNCHANGED tokens
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, reading, agentHas, pathVars,
                   target, result, completedBy>>

\* awaitCompleted returns because the request completed; the finally untracks it.
HttpFinish(s) ==
    /\ hstate[s] = "awaiting"
    /\ result[s] # "none"
    /\ hstate' = [hstate EXCEPT ![s] = "done"]
    /\ tracked' = tracked \ {s}
    /\ UNCHANGED <<ident, consol, connVars, pathVars, target, result, completedBy>>

\* awaitCompleted times out.
HttpTimeout(s) ==
    /\ UseTimeout
    /\ hstate[s] = "awaiting"
    /\ result[s] = "none"
    /\ hstate' = [hstate EXCEPT ![s] = "done"]
    /\ tracked' = tracked \ {s}
    /\ UNCHANGED <<ident, consol, connVars, pathVars, target, result, completedBy>>

-----------------------------------------------------------------------------
(* readRequestsFromProxy and the agent *)

\* readScrapeRequest: receive a Unit (buffered ones survive close), then poll the queue, which the drain may have
\* emptied. isStillAwaited drops a request its handler no longer tracks.
Read(c) ==
    /\ phase[c] \in {"live", "ctxRemoved", "swept"}
    /\ tokens[c] > 0
    /\ tokens' = [tokens EXCEPT ![c] = @ - 1]
    /\ IF queue[c] = << >>
       THEN UNCHANGED <<queue, reading>>
       ELSE /\ queue' = [queue EXCEPT ![c] = Tail(@)]
            /\ reading' = IF Head(queue[c]) \in tracked
                          THEN [reading EXCEPT ![c] = @ \cup {Head(queue[c])}]
                          ELSE reading
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, agentHas, pathVars, scrapeVars>>

\* emit() reaches the agent.
Emit(c, s) ==
    /\ s \in reading[c]
    /\ phase[c] = "live"
    /\ reading' = [reading EXCEPT ![c] = @ \ {s}]
    /\ agentHas' = [agentHas EXCEPT ![c] = @ \cup {s}]
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, queue, tokens, pathVars, scrapeVars>>

\* emit() is cancelled: the reader fails the request (finding 14).
EmitCancelled(c, s) ==
    /\ s \in reading[c]
    /\ reading' = [reading EXCEPT ![c] = @ \ {s}]
    /\ IF s \in tracked THEN Complete(s, "cancelled", NoConn) ELSE UNCHANGED <<result, completedBy>>
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, queue, tokens, agentHas, pathVars,
                   hstate, target, tracked>>

\* The agent answers a scrape it was sent (writeResponsesToProxy -> assignScrapeResults).
Respond(c, s) ==
    /\ phase[c] = "live"
    /\ s \in agentHas[c]
    /\ agentHas' = [agentHas EXCEPT ![c] = @ \ {s}]
    /\ IF s \in tracked /\ OwnedBy(s, c) THEN Complete(s, "agent", c) ELSE UNCHANGED <<result, completedBy>>
    /\ UNCHANGED <<ident, consol, phase, inMgr, valid, draining, queue, tokens, reading, pathVars,
                   hstate, target, tracked>>

\* A connected agent answers a scrape id it was never sent. Scrape ids come from one process-wide counter, so any
\* agent can name any in-flight scrape.
Forge(c, s) ==
    /\ phase[c] = "live"
    /\ s \in tracked
    /\ s \notin agentHas[c]
    /\ IF OwnedBy(s, c) THEN Complete(s, "agent", c) ELSE UNCHANGED <<result, completedBy>>
    /\ UNCHANGED <<ident, consol, connVars, pathVars, hstate, target, tracked>>

-----------------------------------------------------------------------------

Init ==
    /\ ident \in [Conns -> Identities]
    /\ consol \in [Conns -> BOOLEAN]
    /\ phase = [c \in Conns |-> "unborn"]
    /\ inMgr = {}
    /\ valid = [c \in Conns |-> FALSE]
    /\ draining = [c \in Conns |-> FALSE]
    /\ queue = [c \in Conns |-> << >>]
    /\ tokens = [c \in Conns |-> 0]
    /\ reading = [c \in Conns |-> {}]
    /\ agentHas = [c \in Conns |-> {}]
    /\ regPending = {}
    /\ pathMap = [p \in Paths |-> Absent]
    /\ pathCount = [c \in Conns |-> 0]
    /\ hstate = [s \in Scrapes |-> "idle"]
    /\ target = [s \in Scrapes |-> NoConn]
    /\ tracked = {}
    /\ result = [s \in Scrapes |-> "none"]
    /\ completedBy = [s \in Scrapes |-> NoConn]

\* Steps the environment may or may not take: connections, registrations, requests, misbehaving agents.
Environment ==
    \/ \E c \in Conns : Connect(c) \/ Disconnect(c)
    \/ \E c \in Conns, p \in Paths : RegisterStart(c, p) \/ Unregister(c, p)
    \/ \E s \in Scrapes : HttpStart(s) \/ HttpTimeout(s)
    \/ \E c \in Conns, s \in Scrapes : EmitCancelled(c, s) \/ Forge(c, s)

\* Steps the system promises to finish once started.
System ==
    \/ \E c \in Conns : SweepPaths(c) \/ FailScrapes(c) \/ Drain(c) \/ DrainEnd(c) \/ Read(c)
    \/ \E c \in Conns, p \in Paths : RegisterLocked(c, p)
    \/ \E s \in Scrapes : HttpTrack(s) \/ HttpEnqueue(s) \/ HttpSend(s) \/ HttpFinish(s)
    \/ \E c \in Conns, s \in Scrapes : Emit(c, s) \/ Respond(c, s)

Next == Environment \/ System

Fairness ==
    /\ \A c \in Conns : WF_vars(SweepPaths(c)) /\ WF_vars(FailScrapes(c)) /\ WF_vars(Drain(c))
                        /\ WF_vars(DrainEnd(c)) /\ WF_vars(Read(c))
    /\ \A c \in Conns, p \in Paths : WF_vars(RegisterLocked(c, p))
    /\ \A s \in Scrapes : WF_vars(HttpTrack(s)) /\ WF_vars(HttpEnqueue(s)) /\ WF_vars(HttpSend(s))
                          /\ WF_vars(HttpFinish(s))
    /\ \A c \in Conns, s \in Scrapes : WF_vars(Emit(c, s)) /\ WF_vars(Respond(c, s))

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
(* Properties *)

TypeOK ==
    /\ phase \in [Conns -> {"unborn", "live", "ctxRemoved", "swept", "gone"}]
    /\ inMgr \subseteq Conns
    /\ valid \in [Conns -> BOOLEAN]
    /\ draining \in [Conns -> BOOLEAN]
    /\ tokens \in [Conns -> Nat]
    /\ reading \in [Conns -> SUBSET Scrapes]
    /\ agentHas \in [Conns -> SUBSET Scrapes]
    /\ regPending \subseteq Conns \X Paths
    /\ pathCount \in [Conns -> Nat]
    /\ hstate \in [Scrapes -> {"idle", "picked", "tracked", "enqueued", "awaiting", "done"}]
    /\ target \in [Scrapes -> Conns \cup {NoConn}]
    /\ tracked \subseteq Scrapes
    /\ result \in [Scrapes -> {"none", "agent", "disconnected", "cancelled"}]
    /\ completedBy \in [Scrapes -> Conns \cup {NoConn}]

\* A non-consolidated path has exactly one agent.
ExclusivePathHasOneAgent ==
    \A p \in Paths : pathMap[p].ctxs # {} /\ ~pathMap[p].consolidated => Cardinality(pathMap[p].ctxs) = 1

\* Every agent on a path is either valid or mid-teardown, about to be swept (finding 7: no path stranded on a dead
\* context that no cleanup removes).
NoStrandedPath ==
    \A p \in Paths : \A c \in pathMap[p].ctxs : valid[c] \/ phase[c] = "ctxRemoved"

\* pathCounts, which decides whether a displaced agent is orphaned, agrees with the path map.
PathCountsAgree ==
    \A c \in Conns : pathCount[c] = Cardinality({p \in Paths : c \in pathMap[p].ctxs})

\* One identity can neither take over nor join a path another identity's live agent serves.
IdentityIsolation ==
    \A p \in Paths : \A c \in pathMap[p].ctxs : valid[c] => ident[c] = pathMap[p].identity

\* Only the agent a scrape was sent to can answer it.
AnswerFromOwner ==
    \A s \in Scrapes : completedBy[s] # NoConn => completedBy[s] = target[s]

\* A consolidated agent never shares a path with a non-consolidated one.
ModesNeverMix ==
    \A p \in Paths : \A c \in pathMap[p].ctxs : consol[c] = pathMap[p].consolidated

\* With the timeout disabled, every started scrape still finishes: nothing waits on a request that nobody will
\* answer or fail.
EveryScrapeFinishes ==
    \A s \in Scrapes : (hstate[s] # "idle") ~> (hstate[s] = "done")

=============================================================================
