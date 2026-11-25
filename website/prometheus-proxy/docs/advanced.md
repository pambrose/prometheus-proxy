---
icon: lucide/wrench
---

# Advanced Topics

## nginx Reverse Proxy

To use Nginx as a reverse proxy in front of the gRPC service, disable the transport filter
on both proxy and agent:

```text
--8<-- "AdvancedExamples.txt:nginx-cli"
```

!!! warning "Disconnect detection"

    With `transportFilterDisabled`, the proxy can't see a connection close, only the agent's request
    stream end. An agent that disconnects ends that stream, and is removed at once. One that dies before
    opening its stream, or whose connection stays half-open, is removed after the inactivity timeout
    (default: 60 seconds, controlled by
    [`proxy.internal.maxAgentInactivitySecs`](configuration/proxy.md#internal-settings)).

In this mode the proxy also limits the agents connected as one auth identity, which is every agent when
agent auth is off; see [Transport Filter](configuration/proxy.md#transport-filter).

!!! warning "Nginx's request body limit"

    nginx counts everything an agent sends on a stream as one request body, and stops a body at
    `client_max_body_size`, 1 MB by default. An agent's streams of scrape results stay open for as long as it's
    connected, so behind nginx's default, every agent, of any version, is cut off each time its results add up to a
    megabyte: every few hours for results of a few hundred bytes every few seconds, every few minutes for results of
    tens of KB. It reconnects at once, and is briefly without its paths, which can fail a scrape. Turn the limit off in
    the `server` or `location` block that passes to the proxy, as
    [`nginx/docker/nginx.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf) does:

    ```nginx
    client_max_body_size 0;
    ```

    Without it, nginx's access log ends `writeResponsesToProxy` or `writeChunkedResponsesToProxy` with 413, its error
    log says `client intended to send too large chunked body`, and the agent logs
    `writeResponsesToProxy(): UNKNOWN HTTP status code 413`, then `Disconnected from proxy`.

!!! warning "Nginx's stream timeouts"

    An agent's connection ends when any of its streams closes, and nginx closes a stream that's quiet for 60 seconds by
    default. A stream can be quiet while the agent is scraped: the one it sends chunked results on carries nothing while
    its results stay under `chunkContentSizeKbs` (32 KB) gzipped. A current agent and proxy keep each of their streams
    open with a keepalive after 30 seconds of quiet, so nginx's default timeouts are enough for them. The proxy sets the
    interval for both directions, `proxy.internal.streamKeepAliveSecs`: keep it under the reverse proxy's idle timeout,
    or set it to 0 to turn keepalives off. Each side sends keepalives only once the other says it takes them, so with an
    agent or a proxy from 4.6.0 or earlier on either end, the agent is cut off every minute, scraped or not, reconnects,
    and is briefly without its paths, which can fail a scrape. For such a pair, raise both timeouts, in the `server` or
    `location` block that passes to the proxy:

    | Directive | Covers |
    |-----------|--------|
    | `grpc_read_timeout` | the streams the proxy sends on: scrape requests, and a current proxy's path notices |
    | `client_body_timeout` | the streams the agent sends scrape results on |

    Set them to a day or more, as
    [`nginx/docker/nginx.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf) does:
    a stream for chunked results can stay quiet for as long as the results stay small, so a timeout above the scrape
    interval isn't enough, and even a day's cuts such an agent off once a day. Without them, nginx's access log ends
    the stream it closed with 408 (`client_body_timeout`) or 504 (`grpc_read_timeout`), and the agent's log shows
    `RST_STREAM closed stream` on `writeChunkedResponsesToProxy()` or `writeResponsesToProxy()`, or
    `HTTP status code 504` on `readRequestsFromProxy()` or `readPathNoticesFromProxy()`, then
    `Disconnected from proxy`, a minute after it connected.

!!! warning "Reloads and connected agents"

    `nginx -s reload` starts new workers under the new config and leaves each open connection with the old worker it's
    on, under the old config, until the connection ends. An agent's connection, which the keepalives keep open, doesn't
    end, so a change a reload brings doesn't reach an agent connected before it, and each reload leaves old workers
    running. Set `worker_shutdown_timeout` in nginx's main context:

    ```nginx
    worker_shutdown_timeout 10s;
    ```

    An old worker then closes its connections that long after a reload, and each agent reconnects at once, to a new
    worker, under the new config, at the cost of one reconnect per agent, which a scrape can land in. A worker takes
    the value from the config it started with, so it covers the reloads after it's in place: restart nginx once to
    bring it in.

Example Nginx and proxy configuration files are available in the repository:

- [`nginx/docker/nginx.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/docker/nginx.conf)
- [`nginx/nginx-proxy.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/nginx/nginx-proxy.conf)
- The nginx Demo's configs, [`demos/nginx/nginx/`](https://github.com/pambrose/prometheus-proxy/tree/master/demos/nginx/nginx):
  `make up` in `demos/nginx` runs nginx with TLS and the dashboard on its stock timeouts, and
  `./demo.sh nginx-shutdown-timeout` adds `worker_shutdown_timeout`

### TLS at nginx

To end the agents' TLS at nginx, serve TLS on its listener and pass the calls to the proxy's agent port in plaintext:

```nginx
server {
  listen 443 ssl;
  # gRPC needs HTTP/2.
  http2 on;
  ssl_certificate     /etc/nginx/certs/server.pem;
  ssl_certificate_key /etc/nginx/certs/server.key;

  location / {
    grpc_pass grpc://proxy-host:50051;
  }
}
```

The proxy needs no TLS settings. The agent needs to know what to trust: set `agent.tls.trustCertCollectionFilePath`
(or `trustCertCollectionPem`) to the CA certificate that signed nginx's. An agent uses TLS only when it has a trust
collection or a client certificate, so set it even for a certificate a public CA signed, to a CA bundle. The agent
checks nginx's certificate against the host it dials; `agent.tls.overrideAuthority` names another. The proxy warns at
startup that agent tokens cross without TLS, as they do on the hop from nginx to the proxy, so keep that hop on a
private network.

To put the proxy's dashboard behind nginx too, see
[Behind a reverse proxy](web-dashboard.md#behind-a-reverse-proxy): its live updates need nginx to pass the WebSocket
on. The [nginx Demo](nginx-demo.md) runs all of this, and shows each of the warnings above.

## Prometheus Federation

Scrape an existing Prometheus instance via the `/federate` endpoint:

```hocon
--8<-- "AdvancedExamples.txt:federation-config"
```

This leverages Prometheus's built-in federation support, allowing you to pull metrics from
another Prometheus server through the proxy.

A complete federation config is available at:
[`examples/federate.conf`](https://github.com/pambrose/prometheus-proxy/blob/master/examples/federate.conf)

## Consolidated Mode

By default, each scrape path is owned by a single agent. If a second agent tries to register
the same path, it displaces the first agent, provided both connected with the same auth identity.
With [per-agent identities](security/index.md#per-agent-identities-and-path-authorization), another
identity's registration is rejected while the first agent is connected.

The displaced agent stays connected without the path, so two agents configured with the same path settle with
the later one serving it. The proxy tells the displaced agent, whose [dashboard](agent-dashboard.md) and log show the
path as taken over, and the agent takes the path back once no agent serves it, as when a rolling deploy's new version
stops, without reconnecting. While the other agent serves the path it only waits, through its own reconnects too, so
the two never take it from each other. Only a connection the agent itself has since replaced, one of the same launch,
is disconnected when it loses its last path. To serve a path from both agents, make them consolidated.

In **consolidated mode**, multiple agents can register the same path:

```hocon
agent.consolidated = true
```

When a scrape request arrives for a consolidated path, the proxy sends it to every agent serving the
path, waits for all of them, and merges their responses into one. If one agent disconnects, the
remaining agents continue serving the path.

Use cases:

- **Several instances behind one path** — each agent scrapes its own instance of a service, whose
  series carry a label that tells the instances apart, and Prometheus scrapes them as one target
- **Redundancy** — the path keeps answering, with the remaining agents' series, while one agent is down
- **Rolling upgrades** — new agent registers before old one deregisters

!!! warning "Agents that scrape the same endpoint send each series twice"

    The proxy joins the agents' answers as they are, so when two consolidated agents scrape the same
    endpoint, Prometheus gets each of its series once per agent, with the same labels. It keeps the first
    sample of each and drops the others, logging `Error on ingesting samples with different value but same
    timestamp` for the target on every scrape and counting them in
    `prometheus_target_scrapes_sample_duplicate_timestamp_total`, while `up` stays 1. Give each
    consolidated agent its own endpoint, and keep the agent Helm chart's `replicaCount` at 1, since its
    replicas all run the same config and scrape the same targets; Kubernetes replaces a failed agent pod.
    A rolling upgrade sends each series twice for as long as the old and new agents overlap.

!!! note "A consolidated path belongs to one identity"

    With [per-agent identities](security/index.md#per-agent-identities-and-path-authorization), a
    consolidated path records the identity that registered it, and only agents presenting that identity
    may join. Another identity's registration is rejected and retried, so it joins once the agents
    serving the path disconnect. Agents that share an identity — all of them, when agent auth is off --
    can still join each other's consolidated paths, and a joining agent's output is merged into every
    scrape of the path, so one that never answers holds each scrape until it times out.

## gRPC Reflection

[gRPC Reflection](https://grpc.io/docs/guides/reflection/) is disabled by default, because it lets anyone who
reaches the agent port list and describe the proxy's API. Enable it with `proxy.reflectionDisabled = false` (or
`-Dproxy.reflectionDisabled=false` on the command line) to let tools like
[grpcurl](https://github.com/fullstorydev/grpcurl) inspect the service. When agent authentication is configured,
reflection calls need a valid agent token, like every other call on the agent port:

```text
--8<-- "AdvancedExamples.txt:grpc-reflection-list"
```

```text
--8<-- "AdvancedExamples.txt:grpc-reflection-describe"
```

!!! note

    When using grpcurl with the `-plaintext` option, ensure the proxy is running
    without TLS. When TLS is enabled, provide the appropriate certificate flags.

## Performance Tuning

### Concurrent Scraping

Increase the number of parallel scrapes for high-throughput scenarios:

=== "CLI"

    ```bash
    --8<-- "AdvancedExamples.txt:performance-tuning-cli"
    ```

=== "Config File"

    ```hocon
    --8<-- "AdvancedExamples.txt:performance-tuning-config"
    ```

### Key Tuning Parameters

| Parameter              | Default | Guidance                                          |
|:-----------------------|:--------|:--------------------------------------------------|
| `maxConcurrentClients` | 1       | Increase for many endpoints or slow targets       |
| `clientTimeoutSecs`    | 90      | Lower for fast-failing scrapes                    |
| `chunkContentSizeKbs`  | 32      | Increase for large payloads to reduce chunk count |
| `minGzipSizeBytes`     | 512     | Lower to compress more aggressively               |
| `scrapeTimeoutSecs`    | 15      | Increase for slow targets                         |
| `clientCache.maxSize`  | 100     | Increase if many unique auth credentials are used |

## gRPC Keepalive Tuning

Fine-tune gRPC keepalive behavior for specific network environments:

```hocon
--8<-- "AdvancedExamples.txt:grpc-keepalive-config"
```

See the [gRPC keepalive guide](https://grpc.io/docs/guides/keepalive/) for detailed tuning advice.

These settings send HTTP/2 PINGs, which check the connection but carry no data on a stream, so they don't keep a
reverse proxy from closing a quiet stream: nginx answers a PING without resetting a stream's timeout. The agent and
the proxy send keepalive messages on their streams for that, every `proxy.internal.streamKeepAliveSecs`; see
[Nginx Reverse Proxy](#nginx-reverse-proxy).

## Stale Agent Cleanup

The proxy periodically checks for inactive agents and evicts them:

```hocon
--8<-- "AdvancedExamples.txt:stale-agent-config"
```

!!! info

    When `transportFilterDisabled` is `true`, stale agent cleanup is automatically
    force-enabled, regardless of the `staleAgentCheckEnabled` setting. This ensures
    leaked agent contexts are eventually cleaned up.

## Zipkin Tracing

Both proxy and agent support distributed tracing via Zipkin:

```hocon
proxy.internal.zipkin {
  enabled = true
  hostname = "zipkin.example.com"
  port = 9411
  path = "api/v2/spans"
  serviceName = "prometheus-proxy"
  sampleRate = 1.0
}

agent.internal.zipkin {
  enabled = true
  hostname = "zipkin.example.com"
  port = 9411
  path = "api/v2/spans"
  serviceName = "prometheus-agent"
  sampleRate = 1.0
}
```

Tracing covers each scrape and the gRPC calls between agent and proxy, each side reporting its spans under its own
`serviceName`. Spans are sent in the background, so a Zipkin server that can't be reached costs the spans, not the
agent or the proxy.

**A scrape's trace.** Each scrape Prometheus makes is a trace of its own:

| Span      | Reported by | Covers                                                    | Tags                                                                                                                                      |
|:----------|:------------|:----------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------|
| `scrape`  | Proxy       | Prometheus's request, until the proxy answers it          | `path`, `agents`, `outcome`                                                                                                               |
| `forward` | Proxy       | The request to one of the path's agents, until its result | `agentId`, `agentName`, `outcome`, `http.status_code`, `queue.wait_ms`                                                                    |
| `fetch`   | Agent       | The agent's request to the target                         | `path`, `http.url`, `outcome`, `http.status_code`, `gzipped`, `chunked`, `bytes.raw`, `bytes.filtered`, `filter.lines_dropped`, `retries` |

A `scrape` span's remote endpoint is the address the request came from, which Zipkin shows as the span's client, so a
trace says which Prometheus scraped. A path several agents serve has a `forward` span for each. A `forward` span's
`queue.wait_ms` is how long, in milliseconds, the request waited in the agent's queue before the proxy sent it, and the
span is annotated `sent to agent` once the agent's stream takes it. A scrape that ends first, as one that times out on
an agent that has stopped reading can, has the wait but not the annotation. `outcome` is the scrape counters' label (see
[Monitoring](monitoring.md)), and a span whose scrape failed is tagged as an error, which Zipkin shows in red.
`bytes.raw` and `bytes.filtered` are the payload's size before and after the path's
[metric filter](configuration/agent.md#metric-filtering), and `filter.lines_dropped`, on a path with a filter, the
lines it dropped. `retries`, on a fetch the agent retried (`agent.scrapeMaxRetries`), is how many retries it made,
whether or not the last one succeeded. The proxy sends the trace with each request, so the agent's `fetch` span joins
it; an agent tracing scrapes for a proxy that doesn't begins a trace for each fetch. A scrape from a dashboard's
Scrape now isn't traced. The proxy doesn't join trace headers a scrape request carries: its scrape port takes requests
from anyone, and a client that marked its trace sampled could make the proxy keep every one.

**The gRPC calls.** Zipkin joins the agent's client side and the proxy's server side of each call into one span, so a
trace names both services. Connecting and registering are unary calls (`connectAgent`, `registerAgent`, and a
`registerPath` for each path), each traced as it completes. `sendHeartBeat`, which an idle agent sends every few
seconds, is never traced, and neither is `readPathNoticesFromProxy`, a stream that lasts the connection and tells the
agent only when another agent takes one of its paths. Scrapes travel over long-lived streams (`readRequestsFromProxy`, `writeResponsesToProxy`, and
`writeChunkedResponsesToProxy`), and a stream's span completes only when the stream ends, so it appears when its
connection closes, lasting as long as it did; each scrape's own trace shows the scrape.

**Sampling.** `sampleRate` is the share of traces each side keeps: 1.0, the default, keeps every one, 0.1 one in ten,
and 0 none. It takes 0, or from 0.01 to 1, and anything else fails startup. The proxy decides for each scrape, and the
agent follows the decision the proxy sends, so a scrape's trace is kept whole or not at all. Each side's rate applies
to its gRPC calls too: the agent's to the calls it makes, and the proxy's to the calls it serves, whatever trace
headers a call carries, so no caller can have the proxy keep more of them than its rate, or trace a heartbeat. With
different rates, Zipkin can show a call with one side's half only.

Start Zipkin before the proxy and agents. Spans sent while it can't be reached are dropped: the reporter warns once,
then logs further failures only at the `FINE` level, so after that first warning, lost spans leave nothing in the
logs.
