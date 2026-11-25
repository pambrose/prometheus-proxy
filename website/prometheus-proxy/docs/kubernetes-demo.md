---
icon: lucide/boxes
---

# Kubernetes Demo

The [Basics Demo](basics-demo.md), the [Network Demo](network-demo.md), and the [nginx Demo](nginx-demo.md) show the
proxy, its dashboard, and the link through the firewall. This fourth demo deploys the same proxy and agent the way a
platform team would run them: on two [kind](https://kind.sigs.k8s.io/) clusters, with the project's own Helm charts, and
Kubernetes doing the operating. It shows that the target cluster exposes nothing, how `kubectl port-forward` reaches the
agent's own dashboard inside it all the same, that annotating a Service is all it takes for Prometheus to scrape it,
that Kubernetes replaces a lost agent by itself, and what a change to the agent's config costs next to a change to its
targets. Every screenshot on this page comes from it. Unlike the other demos, it needs kind, `kubectl`, and Helm as well
as Docker: see [Running It](#running-it).

``` mermaid
%%{init: {'flowchart': {'curve': 'linear'}}}%%
graph TB
  accTitle: The Kubernetes Demo's two clusters
  accDescr {
    In pp-monitoring, outside the firewall, Prometheus on port 9290 discovers and scrapes paths through the
    prometheus-proxy chart, dashboard 8294. In pp-target, behind the firewall with nothing exposed, the prometheus-
    agent chart, dashboard 8295 through kubectl port-forward, connects out over gRPC to a NodePort of the proxy and
    fetches metrics over HTTP from the orders, payments, shipping, and search services. targets-sync watches those
    services and writes the agent-targets ConfigMap, which is mounted in the agent.
  }
  P[Prometheus :9290] -->|discovery and scrapes| Proxy[prometheus-proxy chart, dashboard :8294]
  %% The agent connects out to the proxy, so its edge's one arrowhead is at the proxy. The edge is written from the
  %% proxy, which keeps the agent below it, as a two-way edge whose other arrowhead linkStyle takes off. Written from
  %% the agent, it needed an invisible link to keep that order, and the layout bent the edge around the link.
  Proxy <-->|outbound gRPC, to a NodePort| Agent[prometheus-agent chart, dashboard :8295 by kubectl port-forward]
  Agent -->|HTTP fetch| Svc[orders, payments, shipping, search]
  Sync[targets-sync] -->|watches| Svc
  Sync -->|writes| CM[agent-targets ConfigMap]
  CM -->|mounted| Agent
  linkStyle 1 marker-end:none

  %% Each zone's title starts with a line break, which grows the zone to fit it, so the title stands clear of the
  %% zone's top edge. Mermaid's subGraphTitleMargin moved the titles down without growing the zones, and moved the
  %% edges' labels onto their arrowheads.
  subgraph pp-monitoring ["<br>pp-monitoring: outside the firewall"]
    P
    Proxy
  end

  subgraph pp-target ["<br>pp-target: behind the firewall, nothing exposed"]
    Agent
    Sync
    CM
    Svc
  end
```

## Running It

On macOS or Linux, the demo needs [Docker](https://docs.docker.com/get-started/get-docker/), `git`, GNU `make`, and
`curl`, as the other demos do, and also [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) 0.33.0 or
later, [`kubectl`](https://kubernetes.io/docs/tasks/tools/) 1.36 to 1.38, and
[Helm](https://helm.sh/docs/intro/install/) 3 or 4. The clusters run Kubernetes 1.37, the version of the node image the
demo pins, and kubectl supports one minor version either side of it; `demo.sh` checks for each, and for kind's and
Helm's versions. kind runs each cluster's node as a container, which uses about 1 to 1.5 GiB of memory, so give Docker
Desktop about 4 GB (its Resources settings). If kind can't create a cluster, see its [known
issues](https://kind.sigs.k8s.io/docs/user/known-issues/).

Clone the repository at the latest release, then run the demo's commands from its own directory, where `make help` lists
its targets:

```bash
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.0 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/kubernetes
make up      # creates both clusters, installs everything, prints the URLs
make tour    # walks through every scenario below, pausing before each step
make down    # deletes both clusters
```

The first `make up` pulls the kind node image, and each new cluster pulls its images, the proxy's and Prometheus's or
the agent's and Python's, which takes a few minutes. After that, `make up` on running clusters takes under a minute.

| What | URL | Port variable |
|---|---|---|
| Proxy dashboard | <http://localhost:8294/dashboard> | `KUBERNETES_DEMO_DASHBOARD_PORT` |
| Agent dashboard | <http://localhost:8295/dashboard>, while `./demo.sh agent-dashboard` runs | `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` |
| Prometheus | <http://localhost:9290> (targets at `/targets`) | `KUBERNETES_DEMO_PROMETHEUS_PORT` |
| The proxy's service discovery | <http://localhost:8280/discovery> | `KUBERNETES_DEMO_PROXY_PORT` |
| `kubectl` | `kubectl --context kind-pp-monitoring …` and `kubectl --context kind-pp-target …` | — |

Each port is the [Basics Demo](basics-demo.md)'s plus 200, so the demos can run side by side. kind publishes the ports,
through NodePort Services in the monitoring cluster, when it creates `pp-monitoring`, so if one is already taken, set
its variable from the table for the `make up` that creates the clusters, for example
`KUBERNETES_DEMO_PROMETHEUS_PORT=9291 make up`. You set it once: every command after reads the ports back from the
running cluster, which keeps them until `make down` deletes it, and `make up` says so if a variable asks for another.
The proxy dashboard's link into Prometheus keeps the default port (`config/proxy.conf`).

Every port binds to `127.0.0.1` only. The dashboard runs without its login, so this setup is for a laptop, not for
anything shared.

The agent's dashboard is the exception, since the target cluster publishes no port: `./demo.sh agent-dashboard` reaches
it with `kubectl port-forward`, on `localhost` only, and holds its port only while it runs.
`KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` moves it without recreating a cluster.

## What's Running

| Cluster | Workload | Role |
|---|---|---|
| `pp-monitoring` | `prometheus-proxy` | The proxy, from the `prometheus-proxy` chart, with its dashboard and [service discovery](service-discovery.md) on, and its gRPC port a NodePort (the chart's `agentService`) for the agent in the other cluster. |
| `pp-monitoring` | `prometheus` | Prometheus. Its one job finds every target through the proxy's `/discovery`, and scrapes each through the proxy, every 5 seconds. |
| `pp-target` | `prometheus-agent` | The agent, from the `prometheus-agent` chart, named for its pod. It dials the proxy's NodePort, with a token from a Secret, and takes its targets from a ConfigMap it re-reads every 5 seconds ([dynamic target discovery](configuration/agent.md#dynamic-target-discovery)). Its [dashboard](agent-dashboard.md) is on, in its pod only, not on its Service. |
| `pp-target` | `targets-sync` | Lists and watches the Services in the `apps` namespace, and writes each one annotated `prometheus-proxy/path` into the agent's targets ConfigMap. Part of the demo, not the product. |
| `pp-target` | `orders`, `payments`, `shipping` | Simulated services, each behind a ClusterIP Service annotated `prometheus-proxy/path`. |

The charts get nothing but a values file each, a config file, and the proxy's address.

## What This Demo Shows

Each part of the guided tour, `make tour`, makes one point. A part's name links to where this page covers it.

| Part | What it shows | Why it matters |
|---|---|---|
| 1. [The Helm charts on two clusters, with no inbound access](#the-helm-charts-on-two-clusters-with-no-inbound-access) | The proxy chart in one cluster, and the agent chart in another that exposes nothing | Nothing in the monitored cluster opens to the outside |
| 2. [The agent's own dashboard, reached with kubectl port-forward](#the-agents-own-dashboard-reached-with-kubectl-port-forward) | The agent's dashboard through `kubectl port-forward`, and Scrape now against cluster-local URLs | The agent's view, reached without exposing anything new |
| 3. [Annotate a Service, and Prometheus scrapes it](#annotate-a-service-and-prometheus-scrapes-it) | An annotated Service is scraped within seconds, and leaves when the annotation does, with no restart | A team adds monitoring with one annotation |
| 4. [Kubernetes replaces a deleted agent pod in about a second](#kubernetes-replaces-a-deleted-agent-pod-in-about-a-second) | A deleted agent pod is replaced, and the new one registers every path again | The Deployment heals a lost agent by itself, at the cost of a scrape |
| 5. [A config change restarts the agent; a targets change doesn't](#a-config-change-restarts-the-agent-a-targets-change-doesnt) | A change to the agent's main config replaces its pod, where part 3's change of targets didn't | Why the chart keeps targets in a ConfigMap of their own |

## Scenarios

The commands that add, remove, or replace something print how long it took; the times on this page were measured on an
Apple-silicon laptop.

### The Helm Charts on Two Clusters, with No Inbound Access

```bash
./demo.sh clusters    # pp-target's Services, each cluster's Helm release, and Prometheus's one job
```

``` { .text .pp-output title="./demo.sh clusters" }
$ kubectl --context kind-pp-target get services --all-namespaces
NAMESPACE     NAME               TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)                  AGE
apps          orders             ClusterIP   10.96.135.38    <none>        8000/TCP                 2m27s
apps          payments           ClusterIP   10.96.151.15    <none>        8000/TCP                 2m27s
apps          shipping           ClusterIP   10.96.20.183    <none>        8000/TCP                 2m27s
default       kubernetes         ClusterIP   10.96.0.1       <none>        443/TCP                  3m8s
kube-system   kube-dns           ClusterIP   10.96.0.10      <none>        53/UDP,53/TCP,9153/TCP   3m7s
monitoring    prometheus-agent   ClusterIP   10.96.100.246   <none>        8083/TCP                 2m27s
```

Every Service in the target cluster is ClusterIP: nothing in it can be reached from outside, and kind maps none of its
ports to the host but the API server's, for `kubectl`. The agent opened its connection to the proxy; nothing connected
in. `helm list` shows the proxy chart in one cluster and the agent chart in the other, and Prometheus's config names no
target in the target cluster: its one job asks the proxy's `/discovery` what to scrape.

<figure markdown="span">
  ![The agent, named for its pod, with its four paths](images/kubernetes-demo/agent.webp#only-dark){ loading=lazy }
  ![The agent, named for its pod, with its four paths](images/kubernetes-demo/agent-light.webp#only-light){ loading=lazy }
  <figcaption>The agent on the dashboard, under its pod's name, connected from the target cluster</figcaption>
</figure>

The dashboard lists one agent, under its pod's name, the one `kubectl` shows: the agent chart sets `AGENT_NAME` from the
pod with the downward API. It has four paths: its own metrics, from its config, and the three services, from
discovery.

<figure markdown="span">
  ![Prometheus's targets, every one found through the proxy](images/kubernetes-demo/targets.webp#only-dark){ loading=lazy }
  ![Prometheus's targets, every one found through the proxy](images/kubernetes-demo/targets-light.webp#only-light){ loading=lazy }
  <figcaption>Prometheus's targets: every one found through the proxy's /discovery, and scraped through the proxy</figcaption>
</figure>

### The Agent's Own Dashboard, Reached with kubectl port-forward

```bash
./demo.sh agent-dashboard   # in a second terminal: kubectl port-forward to the agent's dashboard, until Ctrl-C
```

The proxy's dashboard shows what the proxy sees. The agent's own [dashboard](agent-dashboard.md) shows its side of the
firewall: its connection, each path's registration and recent scrapes, and its discovery. The demo turns it on with
the agent chart's `dashboard.enabled`, and the chart leaves its port off the agent's Service, so the target cluster
still exposes nothing. `agent-dashboard` runs `kubectl port-forward`, as the chart's notes tell an operator to, which
carries `localhost:8295` through the target cluster's API server to the agent's pod: only someone `kubectl` lets into
the cluster can reach it.

The Paths view lists the agent's four paths, each registered with the proxy: its own metrics, static, from its config,
and the three services, discovered, with the cluster-local URLs the agent scrapes, each with its recent scrapes. A
path's Scrape now has the agent fetch its target at once, from inside the target cluster, the only place its address
resolves. The Connection view shows the agent connected to the proxy's NodePort, and under Discovery the file the
chart mounts from the targets ConfigMap, read every 5 seconds, with how its last read went. The Activity view lists
what the agent did since its pod started: it connected, then registered its static path and its discovered ones.

The agent keeps that list in memory, so each pod starts its own, and a port-forward reaches one pod, so once the
agent's pod is replaced, as in the scenarios below that delete the pod or change its config, it fails at the next
request: run `./demo.sh agent-dashboard` again to reach the new one.

### Annotate a Service, and Prometheus Scrapes It

```bash
./demo.sh add-service     # deploys search, then its Service, annotated prometheus-proxy/path: search_metrics
```

targets-sync sees the new Service and writes it into the agent's targets ConfigMap. The kubelet copies the ConfigMap
into the agent's pod, the agent re-reads it and registers the path, and Prometheus picks it up from the proxy's
`/discovery`. The path was in `/discovery` up to about 10 seconds after the Service was created, and in Prometheus up
to about 10 seconds after that, at a 5-second discovery refresh. Nothing restarted: the agent's pod is the same one,
and no other path missed a scrape.

<figure markdown="span">
  ![The Paths view, with search_metrics from discovery](images/kubernetes-demo/discovered.webp#only-dark){ loading=lazy }
  ![The Paths view, with search_metrics from discovery](images/kubernetes-demo/discovered-light.webp#only-light){ loading=lazy }
  <figcaption>search_metrics on the Paths view, its source disc: from discovery, not the agent's config</figcaption>
</figure>

On a production kubelet, the delay is mostly the kubelet's. It copies a changed ConfigMap into the pods that mount it
on its periodic sync, every `syncFrequency`, which is a minute by default; the demo's target cluster lowers it to 5
seconds. With the default, which the demo's monitoring cluster keeps, the same change took 63 to 74 seconds to reach a
pod; with 5 seconds, 1 to 7. The agent's own re-read, `agent.discovery.reconcileIntervalSecs`, comes on top, so in the
demo the two take about as long as each other.
`./demo.sh remove-service` removes the annotation, and the path leaves `/discovery` within about 10 seconds, again
without a restart. On the agent's own dashboard, `search_metrics` joined the Paths view as discovered and registered,
and left it again, each with a line in its Activity view. Prometheus's last scrape of search fails: the agent lets the
path go before Prometheus's next discovery refresh drops the target, so the scrape that lands in between gets a 404
from the proxy, which logs `WARN Invalid path request /search_metrics`, and `up{job="search"}` ends at 0.

The [Basics Demo](basics-demo.md) shows dynamic discovery itself, from a file; here Kubernetes drives it. targets-sync
is the demo's: a discovery source in the agent that read Services itself would replace it.

### Kubernetes Replaces a Deleted Agent Pod in About a Second

```bash
./demo.sh delete-agent-pod   # deletes the agent's pod; its Deployment starts another
```

``` { .text .pp-output title="./demo.sh delete-agent-pod" }
$ kubectl --context kind-pp-target -n monitoring delete pod prometheus-agent-8588c987c9-dqns8 --wait=false
pod "prometheus-agent-8588c987c9-dqns8" deleted from monitoring namespace
prometheus-agent-8588c987c9-llglr is on the dashboard with its paths, 2s after
  Scrapes of orders_metrics through the proxy failed for about 1s.
```

The Deployment starts a new pod at once. Its agent connects out to the proxy and registers every path again, under its
own pod's name, about 1.3 seconds after the delete, and scrapes through the proxy fail for about a second meanwhile: a
Prometheus scrape that lands in it fails, and the next succeeds. Nobody touched the agent. In Prometheus, each target's
`agent` label is the pod's name, so the new pod's scrapes start new series, as a `pod` label's would.

<figure markdown="span">
  ![The Activity view, as the agent's pod is replaced](images/kubernetes-demo/replaced.webp#only-dark){ loading=lazy }
  ![The Activity view, as the agent's pod is replaced](images/kubernetes-demo/replaced-light.webp#only-light){ loading=lazy }
  <figcaption>The Activity view, newest first: the new pod's agent arriving with every path, the old one leaving, and below, the search service's path coming and going</figcaption>
</figure>

The agent's own dashboard reached the old pod, since a port-forward reaches one pod, so its port-forward fails at the
next request. Run `./demo.sh agent-dashboard` again, and the new pod's Activity view lists only its own start, where
the proxy's, above, saw both pods.

### A Config Change Restarts the Agent; a Targets Change Doesn't

```bash
./demo.sh change-config      # helm upgrade with a change to the agent's main config
```

``` { .text .pp-output title="./demo.sh change-config" }
Upgrading the prometheus-agent chart with config/agent-change.conf added to its main config
$ helm --kube-context kind-pp-target --namespace monitoring upgrade prometheus-agent ../../charts/prometheus-agent \
    --reuse-values --set-file config=<(cat config/agent.conf config/agent-change.conf)
prometheus-agent-85db4c79cb-s6d98 is on the dashboard with its paths, 2s after
  Scrapes of orders_metrics through the proxy failed for about 2s.
```

The change turns on the agent's JVM metrics. The chart renders the agent's config into a ConfigMap and stamps the pod
with its checksum, so the upgrade replaces the pod, and the chart's `strategy: Recreate` stops the old one before
starting the new, since both would register the same paths. That's a gap: scrapes through the proxy fail for about
1.3 seconds, which the script rounds up to whole seconds. Compare the annotated Service above, where the targets
changed and nothing restarted. That's why the agent chart keeps the targets in a ConfigMap of their own, which the
agent re-reads in place, apart from its main config, which only a restart applies. `./demo.sh restore-config` puts the
original config back, with another replacement.

## Scenario Reference

| Command | Effect | How it works |
|---|---|---|
| `demo.sh clusters` | Shows the target cluster's Services, each cluster's Helm release, and Prometheus's scrape config | `kubectl get services`, `helm list`, and Prometheus's ConfigMap |
| `demo.sh add-service` / `remove-service` | Deploys the search service and then its annotated Service; or removes the annotation and then deletes both | `kubectl apply` / `kubectl annotate ... prometheus-proxy/path-`; targets-sync rewrites the `agent-targets` ConfigMap, which the kubelet copies into the agent's pod |
| `demo.sh delete-agent-pod` | Deletes the agent's pod, and reports when its replacement registered and how long scrapes failed | `kubectl delete pod`; the agent's Deployment starts a replacement |
| `demo.sh change-config` / `restore-config` | Upgrades the agent chart with or without the config change, and reports the replacement the same way | `helm upgrade --set-file config=...`; the config's checksum on the pod template makes the Deployment replace the pod |
| `demo.sh agent-dashboard` | Forwards `localhost:8295` to the agent's dashboard, in the foreground, until Ctrl-C or the first request after the agent's pod is replaced | `kubectl port-forward deploy/prometheus-agent 8295:8095`, through the target cluster's API server |
| `make tour [PART=n]` | Walks through every scenario, from part 1 or the one given, pausing before each step |  |
| `make status` | Both clusters' pods and Services, the Helm releases, and the URLs |  |
| `make logs [SERVICE=name]` | Follows a workload's logs: `agent`, the default, `proxy`, `prometheus`, `targets-sync`, or a service |  |
| `make open` | Opens the dashboard and Prometheus's targets in the browser |  |

`remove-service` and `restore-config` undo every scenario. For a clean slate, `make down && make up`.

## Going Further

- The demo's [README](https://github.com/pambrose/prometheus-proxy/tree/master/demos/kubernetes) has these scenarios
  as a guided tour, explaining how each one works, and lists the files that set the demo up,
  including how `make up` connects the two clusters. It also says why the demo runs one agent, not two consolidated
  replicas.
- [Kubernetes](kubernetes.md) is the guide to the deployment itself: the charts, exposing the proxy's gRPC port to
  other clusters, and updating targets without restarting the agent.
- [Agent Configuration](configuration/agent.md#dynamic-target-discovery) covers dynamic target discovery, which the
  agent's targets ConfigMap feeds.
- [Agent Dashboard](agent-dashboard.md) covers the agent's dashboard: each view, Scrape now, and its login, which the
  agent chart's `dashboard.login.existingSecret` turns on.
- The other demos, in the order the [Demos](demos/index.md) page gives them:
    - [Basics Demo](basics-demo.md): the whole system on one machine, the proxy and its dashboard, three agents behind
      a simulated firewall, Prometheus, and Grafana
    - [Network Demo](network-demo.md): a simulated WAN between the agents and the proxies, and what a far, thin, dead,
      flaky, or missing link does
    - [nginx Demo](nginx-demo.md): nginx between the agents and the proxy, its stream timeouts, its reloads, and TLS
      ended on one port
    - [Ktor Demo](ktor-demo.md): a coffee shop, a Ktor app that runs the agent in its own JVM through the Ktor plugin
    - [Spring Boot Demo](spring-boot-demo.md): a roastery, a Spring Boot app in Java whose agent the starter starts
      from properties
    - [Plain Kotlin Demo](plain-kotlin-demo.md): a bakery's ovens, workers with no framework or HTTP server, each
      reading its own metrics in-process
