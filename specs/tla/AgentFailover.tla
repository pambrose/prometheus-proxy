--------------------------- MODULE AgentFailover ---------------------------
(***************************************************************************)
(* The agent's proxy failover: Agent.run's connectToProxy loop and         *)
(* EndpointFailover.beforeAttempt, over an ordered agent.proxy.endpoints   *)
(* list (AgentGrpcService.advanceEndpoint / resetEndpoint).                *)
(*                                                                         *)
(* Each proxy is up, down (connects fail), or rejects (accepts the         *)
(* connection but rejects registration, or every static path for a cause  *)
(* that can't clear, which registerPaths turns into a failed attempt).     *)
(* Proxies change state freely until the environment freezes, after which  *)
(* they stay put, so liveness asks where the agent ends up.                *)
(***************************************************************************)
EXTENDS Naturals

CONSTANTS
    NumEndpoints,       \* length of agent.proxy.endpoints; 1 is a non-failover agent
    FailbackOnConnect   \* TRUE restores 4.0.0: fail back once the previous attempt connected, not registered

ASSUME NumEndpoints \in Nat \ {0} /\ FailbackOnConnect \in BOOLEAN

Endpoints == 1..NumEndpoints
ProxyStates == {"up", "down", "rejects"}

VARIABLES
    proxy,          \* [Endpoints -> ProxyStates]
    frozen,         \* proxies no longer change, and only a dead proxy drops a connection
    idx,            \* endpointIndex, 1-based
    firstAttempt,   \* EndpointFailover.firstAttempt
    prevRegistered, \* EndpointFailover.previousAttemptRegistered
    connected,      \* Agent.agentId is non-empty: this attempt's connectAgent succeeded
    phase           \* "idle" (between attempts), "connecting", "registering", "registered"

agentVars == <<idx, firstAttempt, prevRegistered, connected, phase>>
vars == <<proxy, frozen, agentVars>>

-----------------------------------------------------------------------------
(* Environment *)

ProxyChanges(e, s) ==
    /\ ~frozen
    /\ proxy' = [proxy EXCEPT ![e] = s]
    /\ UNCHANGED <<frozen, agentVars>>

Freeze ==
    /\ ~frozen
    /\ frozen' = TRUE
    /\ UNCHANGED <<proxy, agentVars>>

\* A registered connection ends: a network blip or restart before the freeze, or a proxy that is no longer up (the
\* retryRejectedStaticPaths task ending the connection is the "rejects" case).
Drop ==
    /\ phase = "registered"
    /\ ~frozen \/ proxy[idx] # "up"
    /\ phase' = "idle"
    /\ UNCHANGED <<proxy, frozen, idx, firstAttempt, prevRegistered, connected>>

-----------------------------------------------------------------------------
(* Agent *)

\* connectToProxy's head: EndpointFailover.beforeAttempt(previousAttemptConnected), then agentId = "".
BeforeAttempt ==
    /\ phase = "idle"
    /\ LET failBack == IF FailbackOnConnect THEN connected ELSE prevRegistered
       IN idx' = IF failBack THEN 1                                               \* resetEndpoint
                 ELSE IF ~firstAttempt /\ NumEndpoints > 1 THEN (idx % NumEndpoints) + 1  \* advanceEndpoint
                 ELSE idx
    /\ prevRegistered' = FALSE
    /\ firstAttempt' = FALSE
    /\ connected' = FALSE
    /\ phase' = "connecting"
    /\ UNCHANGED <<proxy, frozen>>

\* grpcService.connectAgent().
Connect ==
    /\ phase = "connecting"
    /\ IF proxy[idx] = "down"
       THEN /\ phase' = "idle"
            /\ UNCHANGED connected
       ELSE /\ phase' = "registering"
            /\ connected' = TRUE
    /\ UNCHANGED <<proxy, frozen, idx, firstAttempt, prevRegistered>>

\* registerAgent and registerPaths, then endpointFailover.registrationSucceeded().
Register ==
    /\ phase = "registering"
    /\ IF proxy[idx] = "up"
       THEN /\ phase' = "registered"
            /\ prevRegistered' = TRUE
       ELSE /\ phase' = "idle"
            /\ UNCHANGED prevRegistered
    /\ UNCHANGED <<proxy, frozen, idx, firstAttempt, connected>>

-----------------------------------------------------------------------------

Init ==
    /\ proxy \in [Endpoints -> ProxyStates]
    /\ frozen = FALSE
    /\ idx = 1
    /\ firstAttempt = TRUE
    /\ prevRegistered = FALSE
    /\ connected = FALSE
    /\ phase = "idle"

Next ==
    \/ \E e \in Endpoints, s \in ProxyStates : ProxyChanges(e, s)
    \/ Freeze
    \/ Drop
    \/ BeforeAttempt
    \/ Connect
    \/ Register

\* The agent keeps retrying, the environment eventually settles, and a connection to a dead proxy eventually ends.
Spec == Init /\ [][Next]_vars /\ WF_vars(BeforeAttempt) /\ WF_vars(Connect) /\ WF_vars(Register)
                              /\ WF_vars(Freeze) /\ WF_vars(Drop)

-----------------------------------------------------------------------------
(* Properties *)

TypeOK ==
    /\ proxy \in [Endpoints -> ProxyStates]
    /\ frozen \in BOOLEAN
    /\ idx \in Endpoints
    /\ firstAttempt \in BOOLEAN
    /\ prevRegistered \in BOOLEAN
    /\ connected \in BOOLEAN
    /\ phase \in {"idle", "connecting", "registering", "registered"}

\* Once proxies settle with at least one up, the agent ends up registered and stays there -- in particular a proxy that
\* accepts connections but rejects registration can't hold it forever.
SettlesOnAWorkingProxy ==
    (<>[](frozen /\ \E e \in Endpoints : proxy[e] = "up")) => <>[](phase = "registered")

\* After a registered connection drops, the next attempt goes to the primary, so a recovered primary is picked up.
FailsBackAfterRegisteredDrop ==
    [][phase = "idle" /\ phase' = "connecting" /\ prevRegistered => idx' = 1]_vars

\* A failed or rejected attempt never sends the agent back to the primary it just left: with more than one endpoint,
\* each unregistered retry moves on.
FailsForwardAfterFailure ==
    [][phase = "idle" /\ phase' = "connecting" /\ ~prevRegistered /\ ~firstAttempt /\ NumEndpoints > 1
          => idx' = (idx % NumEndpoints) + 1]_vars

=============================================================================
