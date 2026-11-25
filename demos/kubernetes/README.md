# Kubernetes Demo

A self-contained demo of Prometheus Proxy deployed the way a platform team would run it. It runs two
[kind](https://kind.sigs.k8s.io/) clusters on one laptop: a monitoring cluster with the proxy, its web dashboard, and
Prometheus, and a target cluster, behind the "firewall", with a set of simulated services and the agent. Both are
installed with the project's own Helm charts, from [`charts/`](../../charts). It shows that the target
cluster exposes nothing, how `kubectl port-forward` reaches the agent's own dashboard inside it all the same, that
annotating a Service is all it takes for Prometheus to scrape it, that Kubernetes replaces a lost agent by itself, and
what a change to the agent's config costs next to a change to its targets.
Unlike the other demos, it needs kind, `kubectl`, and Helm as well as Docker: see [Quick Start](#quick-start).

It follows the [nginx demo](../nginx), the [network demo](../network), and the [basics demo](../basics): those show
the dashboard, Grafana, the proxy's features, and the link through the firewall, and this one assumes them. It keeps the
same proxy and agent and shows them on Kubernetes. The website's
[Kubernetes Demo](https://prometheus-proxy.com/kubernetes-demo/) page walks through the same scenarios
with screenshots, and its [Kubernetes](https://prometheus-proxy.com/kubernetes/) page is the guide to
the deployment itself.

```
 pp-target (kind cluster, behind the "firewall"; no port mapped to the host, every Service ClusterIP)
   apps:        orders, payments, shipping (and search, in part 3): Services annotated prometheus-proxy/path
   monitoring:  targets-sync ── watches the Services ──► agent-targets ConfigMap ──► mounted in the agent's pod
                prometheus-agent (the agent chart): scrapes each Service in the cluster, and serves its dashboard
                on :8095 in its pod, which kubectl port-forward brings to localhost:8295 (./demo.sh agent-dashboard)
                    │  gRPC, outbound only, across Docker's kind network
                    ▼
 pp-monitoring (kind cluster, outside the firewall)
   monitoring:  prometheus-proxy (the proxy chart) ── gRPC NodePort for the agent (agentService)
                    ▲  HTTP service discovery (/discovery) and scrapes, through the chart's Service
                Prometheus :9290 (5s scrapes, 4s timeout)        dashboard :8294        scrape port :8280
```

## Quick Start

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
git clone -c advice.detachedHead=false --depth 1 --branch 4.7.1 https://github.com/pambrose/prometheus-proxy.git
cd prometheus-proxy/demos/kubernetes
make up      # creates both clusters, installs everything, and prints the URLs
make tour    # walks through the guided tour below, pausing before each step
make down    # deletes both clusters
```

The first `make up` pulls the kind node image, and each new cluster pulls its images, the proxy's and Prometheus's or
the agent's and Python's, which takes a few minutes. After that, `make up` on running clusters takes under a minute.
`make down` deletes the clusters, and the images inside them, so the next `make up` needs a network connection again.
To show it without a network connection, run `make up` once beforehand and leave it running.

| What | URL | Port variable |
|---|---|---|
| Proxy dashboard | <http://localhost:8294/dashboard> | `KUBERNETES_DEMO_DASHBOARD_PORT` |
| Agent dashboard | <http://localhost:8295/dashboard>, while `./demo.sh agent-dashboard` runs | `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` |
| Prometheus | <http://localhost:9290> (targets at `/targets`) | `KUBERNETES_DEMO_PROMETHEUS_PORT` |
| The proxy's service discovery | <http://localhost:8280/discovery> | `KUBERNETES_DEMO_PROXY_PORT` |
| `kubectl` | `kubectl --context kind-pp-monitoring …` and `kubectl --context kind-pp-target …` | — |

`make open` opens the proxy dashboard and Prometheus's targets in your browser.

Each port is the basics demo's plus 200, so the demos can run side by side. kind publishes the ports, through NodePort
Services in the monitoring cluster, when it creates `pp-monitoring`, so if one is already taken, set its variable from
the table, all but the agent dashboard's, for the `make up` that creates the clusters, for example
`KUBERNETES_DEMO_PROMETHEUS_PORT=9291 make up`. You set it once: every command after reads the ports back from the
running cluster, which keeps them until `make down` deletes it, and `make up` says so if a variable asks for another.
The proxy dashboard's link into Prometheus keeps the default port (`config/proxy.conf`).

Every port binds to `127.0.0.1` only. The dashboard runs without its login, so this setup is for a laptop, not for
anything shared.

The agent dashboard's port, 8295, isn't one kind publishes: `pp-target` publishes none, and the agent chart leaves the
dashboard off the agent's Service. `./demo.sh agent-dashboard` reaches it as the chart's notes tell an operator to,
with `kubectl --context kind-pp-target -n monitoring port-forward deploy/prometheus-agent 8295:8095`, in the
foreground. It holds the port only while it runs, on `localhost` only, and `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` moves
it without recreating a cluster. Nothing keeps it, so give it to each `./demo.sh agent-dashboard` and `make tour`, or
export it in your shell. A port-forward reaches one pod, so once the agent's pod is replaced, as
`delete-agent-pod` and `change-config` do, it fails at the next request, with `error: lost connection to pod`: run it
again. The agent's dashboard runs without its login too.

kind adds a `kind-pp-monitoring` and a `kind-pp-target` context to your kubeconfig. `demo.sh` names the context on
every command, and puts back the context that was current when it creates a cluster.

## What's Running

| Cluster / namespace | Workload | Role |
|---|---|---|
| `pp-monitoring` / `monitoring` | `prometheus-proxy` | The proxy, from the `prometheus-proxy` chart (`values/proxy.yaml`). Its dashboard is on, service discovery tells Prometheus about every path the agent registers, and its gRPC port is a NodePort (the chart's `agentService`), for the agent in the other cluster. |
| `pp-monitoring` / `monitoring` | `prometheus` | Prometheus, from a plain Deployment (`k8s/monitoring.yaml`). Its one job finds every target through the proxy's `/discovery`, and scrapes each through the proxy, every 5s with a 4s timeout. |
| `pp-monitoring` / `monitoring` | `proxy-dashboard`, `proxy-scrape`, `prometheus` Services | The demo's NodePort Services, which kind publishes on the host's `127.0.0.1`. The chart leaves the dashboard out of its own Service. |
| `pp-target` / `monitoring` | `prometheus-agent` | The agent, from the `prometheus-agent` chart (`values/agent.yaml`), named for its pod. It dials the proxy's NodePort on `pp-monitoring`'s node, and its token comes from a Secret. Its one static path is its own metrics; the services come from discovery. Its dashboard is on (`dashboard.enabled`), on port 8095 in its pod only, not on its Service. |
| `pp-target` / `monitoring` | `targets-sync` | Lists and watches the Services in `apps`, and writes each annotated `prometheus-proxy/path` into the agent's targets ConfigMap, `agent-targets` (`sync/targets_sync.py`). Part of the demo, not the product. |
| `pp-target` / `apps` | `orders`, `payments`, `shipping` | The simulated services (`app/services_app.py`), one per pod, each behind a ClusterIP Service annotated `prometheus-proxy/path`. Traffic follows a 5-minute "day". |

## What This Demo Shows

Each part of the guided tour, `make tour`, makes one point. A part's name links to its section under [Guided
Tour](#guided-tour).

| Part | What it shows | Why it matters |
|---|---|---|
| 1. [The Helm charts on two clusters, with no inbound access](#1-the-helm-charts-on-two-clusters-with-no-inbound-access) | The proxy chart in one cluster, and the agent chart in another that exposes nothing | Nothing in the monitored cluster opens to the outside |
| 2. [The agent's own dashboard, reached with kubectl port-forward](#2-the-agents-own-dashboard-reached-with-kubectl-port-forward) | The agent's dashboard through `kubectl port-forward`, and Scrape now against cluster-local URLs | The agent's view, reached without exposing anything new |
| 3. [Annotate a Service, and Prometheus scrapes it](#3-annotate-a-service-and-prometheus-scrapes-it) | An annotated Service is scraped within seconds, and leaves when the annotation does, with no restart | A team adds monitoring with one annotation |
| 4. [Kubernetes replaces a deleted agent pod in about a second](#4-kubernetes-replaces-a-deleted-agent-pod-in-about-a-second) | A deleted agent pod is replaced, and the new one registers every path again | The Deployment heals a lost agent by itself, at the cost of a scrape |
| 5. [A config change restarts the agent; a targets change doesn't](#5-a-config-change-restarts-the-agent-a-targets-change-doesnt) | A change to the agent's main config replaces its pod, where part 3's change of targets didn't | Why the chart keeps targets in a ConfigMap of their own |

## Guided Tour

`make tour` walks through these parts one step at a time. To follow them here instead, run the scenario commands
from `demos/kubernetes`; most commands print what to watch, and the ones that add, remove, or replace something, how
long it took. The times below were measured on an Apple-silicon laptop.

### 1. The Helm Charts on Two Clusters, with No Inbound Access

These are the earlier demos' proxy and agent, now deployed with their Helm charts.

```bash
./demo.sh clusters        # pp-target's Services, each cluster's Helm release, and Prometheus's one job
```

- Every Service in `pp-target` is ClusterIP: nothing in it can be reached from outside, and kind maps no port of it to
  the host but its API server's, for `kubectl`. The agent opened its connection to the proxy; nothing connected in.
- `helm list` shows the proxy chart in `pp-monitoring` and the agent chart in `pp-target`: the charts the project
  ships, given nothing but a values file and a config file each, and, for the agent, the proxy's address.
- Prometheus's config names no target in `pp-target`. Its one job asks the proxy's `/discovery` what to scrape.
- Open the proxy dashboard. One agent is connected, named for its pod, `prometheus-agent-<hash>-<id>`, the name
  `kubectl` shows, with four paths: its own metrics (source **cfg**, from its config) and the three services (source
  **disc**, from discovery). Prometheus's targets page shows all four up.

### 2. The Agent's Own Dashboard, Reached with kubectl port-forward

The proxy dashboard shows what the proxy sees. The agent's own dashboard shows its side of the firewall: its
connection, each path's registration and recent scrapes, and its discovery. See
[Agent Dashboard](https://prometheus-proxy.com/agent-dashboard/) for each view.

```bash
./demo.sh agent-dashboard   # in a second terminal: kubectl port-forward to the agent's dashboard, until Ctrl-C
```

- `values/agent.yaml` turns the dashboard on with the chart's `dashboard.enabled`, and the chart leaves its port, 8095,
  off the agent's Service, so nothing new is exposed. `agent-dashboard` runs `kubectl port-forward`, which carries
  `localhost:8295` through `pp-target`'s API server to the agent's pod: only someone `kubectl` lets into the cluster
  can reach it.
- The Paths view lists the agent's four paths, each **registered** with the proxy: `agent_metrics`, **static**, from
  its config, and the three services, **discovered**, with the cluster-local URLs the agent scrapes. The dots beside
  each are its recent scrapes, one for each scrape Prometheus sent through the proxy. Open it at
  <http://localhost:8295/dashboard>
- Select `/orders_metrics`. Recent scrapes lists its last scrapes as the agent saw them: the target's status, how long
  the fetch took, and the body's size. Scrape now has the agent fetch the target at once, from inside `pp-target`, the
  only place its address resolves: `http://orders.apps.svc.cluster.local:8000/metrics`
- The Connection view shows the agent connected to the proxy's NodePort, under its pod's name. Under Discovery is the
  file the chart mounts from `agent-targets`, `/app/discovery/targets.conf`, read every 5 seconds, with its last read.
  The Settings view shows what the agent resolved from `config/agent.conf`, such as its 3-second scrape timeout.
- The Activity view lists what the agent did since its pod started, newest first. Read from the bottom, it connected to
  the proxy, then registered its static path, and its three discovered ones on a line of their own. Each line is in the
  agent's log too, but the log stays behind the firewall and scrolls away.
- Keep it open through the parts that follow. Part 3's new path appears on it. Parts 4 and 5 replace the agent's pod,
  and the port-forward fails at the next request after that: run `./demo.sh agent-dashboard` again to reach the new
  one, whose Activity view starts with only its own connect and registrations, since the agent keeps it in memory.

### 3. Annotate a Service, and Prometheus Scrapes It

```bash
./demo.sh add-service     # deploys search, then its Service, annotated prometheus-proxy/path: search_metrics
```

- `add-service` deploys search and creates its Service once the pod is ready, as a team would deploy an app and then
  expose it. targets-sync sees the new Service and writes it into the agent's targets ConfigMap. The kubelet copies
  the ConfigMap into the agent's pod, the agent re-reads it, and registers the path: in the proxy's `/discovery` up to
  about 10 seconds after the Service was created, and in Prometheus up to about 10 seconds after that, at a 5-second
  discovery refresh.
- The proxy dashboard's Paths view shows `search_metrics` with source **disc**. The agent's pod is the same one, with
  no restarts: nothing restarted, and no other path missed a scrape.
- On the agent's dashboard, `search_metrics` joins the Paths view, **discovered** and **registered**, Discovery on the
  Connection view counts four entries, and the Activity view adds a **registered** line for it.
- On a production kubelet, the delay is mostly the kubelet's. It copies a changed ConfigMap into the pods that mount
  it on its periodic sync, every `syncFrequency`, which is a minute by default; `kind/pp-target.yaml` lowers it to 5
  seconds. With the default, the same change took 63 to 74 seconds to reach a pod (six runs, in `pp-monitoring`, which
  keeps it), and with 5 seconds, 1 to 7 (six runs). The agent's own re-read, every 5 seconds here, comes on top, so in
  the demo the two take about as long as each other.

```bash
./demo.sh remove-service  # removes the annotation, then deletes search once its path has gone
```

The path leaves `/discovery` 3 to 10 seconds after the annotation goes (three runs), again without a restart, and
`remove-service` then deletes search. Deleting a Service whose path is still registered would fail its scrapes until
the agent heard. The agent's Activity view adds an **unregistered** line for the path.

Prometheus's last scrape of search fails all the same. The agent lets the path go before Prometheus's next discovery
refresh drops the target, so the scrape that lands in between gets a 404 from the proxy, and `up{job="search"}` ends
at 0. The proxy logs it as `WARN Invalid path request /search_metrics`, which `make logs SERVICE=proxy` shows.

The basics demo shows dynamic discovery itself, from a file; this part shows Kubernetes driving it.

### 4. Kubernetes Replaces a Deleted Agent Pod in About a Second

```bash
./demo.sh delete-agent-pod   # deletes the agent's pod; its Deployment starts another
```

- The Deployment starts a new pod at once. Its agent connects out to the proxy and registers every path again, under
  its own pod name: on the dashboard about 1.3 seconds after the delete (three runs). The old one leaves the dashboard
  as it shuts down.
- Scrapes through the proxy fail for about a second meanwhile (0.9 to 1.0 seconds in three runs), so a Prometheus
  scrape that lands in it fails, and the next succeeds. Nobody touched the agent.
- The proxy dashboard's Activity view shows the old pod's paths leaving and the new pod's arriving. In Prometheus, each
  target's `agent` label is the pod's name, so a new pod starts new series, as a `pod` label would.
- `./demo.sh agent-dashboard` reached the old pod, since a port-forward reaches one pod, so it fails at the next
  request after the replacement. Run it again: the new pod's Activity view lists only its own start, its connect and
  registrations. The proxy saw both pods, but each agent sees only its own.

### 5. A Config Change Restarts the Agent; a Targets Change Doesn't

```bash
./demo.sh change-config      # helm upgrade with config/agent-change.conf added to the agent's main config
```

- The change turns on the agent's JVM metrics. The chart renders the config into a ConfigMap and stamps the pod with
  its checksum (`checksum/config`), so the upgrade replaces the pod, and the chart's `strategy: Recreate` stops the old
  one before starting the new, since both would register the same paths.
- That's a gap: scrapes through the proxy fail for about 1.3 seconds (1.3 to 1.4 in six runs), and the new pod's agent
  is on the dashboard about 2 seconds after the upgrade began. Its `agent_metrics` now carries the JVM's metrics, so
  `count({job="prometheus-agent", __name__=~"jvm_.*|process_.*"})` in Prometheus, which found no series before, finds
  about 80 within about 10 seconds.
- Compare part 3, where the targets changed and nothing restarted. That's why the agent chart keeps its targets in a
  ConfigMap of their own, which the agent re-reads in place, and its main config, which only a restart applies, apart.
- The port-forward fails at the next request here too; run `./demo.sh agent-dashboard` again. The new pod's dashboard
  is on, since `--reuse-values` keeps the chart's `dashboard.enabled` from `values/agent.yaml`.

```bash
./demo.sh restore-config     # back to config/agent.conf alone: another replacement
```

## Scenario Reference

| Command | Effect | How it works |
|---|---|---|
| `demo.sh clusters` | Shows `pp-target`'s Services, each cluster's Helm release, and Prometheus's scrape config | `kubectl get services`, `helm list`, and Prometheus's ConfigMap |
| `demo.sh add-service` / `remove-service` | Deploys the search service and then its Service, annotated `prometheus-proxy/path`; or removes the annotation and then deletes both | `kubectl apply` / `kubectl annotate ... prometheus-proxy/path-`; targets-sync rewrites the `agent-targets` ConfigMap, which the kubelet copies into the agent's pod |
| `demo.sh delete-agent-pod` | Deletes the agent's pod, and reports when its replacement registered and how long scrapes failed | `kubectl delete pod`; the agent's Deployment starts a replacement |
| `demo.sh change-config` / `restore-config` | Upgrades the agent chart with or without `config/agent-change.conf`, and reports the replacement the same way | `helm upgrade --set-file config=...`; the config's checksum on the pod template makes the Deployment replace the pod |
| `demo.sh agent-dashboard` | Forwards `localhost:8295` to the agent's dashboard, in the foreground, until Ctrl-C or the first request after the agent's pod is replaced | `kubectl port-forward deploy/prometheus-agent 8295:8095`, through `pp-target`'s API server |
| `make tour [PART=n]` | Walks through the guided tour above, from part 1 or the one given, pausing before each step |  |
| `make status` | Both clusters' pods and Services, the Helm releases, and the URLs |  |
| `make logs [SERVICE=name]` | Follows a workload's logs: `agent` (the default), `proxy`, `prometheus`, `targets-sync`, or a service |  |
| `make open` | Opens the proxy dashboard and Prometheus's targets in the browser |  |

`remove-service` and `restore-config` undo every scenario. For a clean slate, `make down && make up`.

## How It's Put Together

| File | What it sets up |
|---|---|
| `kind/pp-monitoring.yaml` | The monitoring cluster: its node image, and the host ports kind publishes its NodePorts on |
| `kind/pp-target.yaml` | The target cluster: its node image, no host ports, and the kubelet's 5-second `syncFrequency` for part 3 |
| `values/proxy.yaml` | The proxy chart's values: the token's Secret, the dashboard, and a NodePort `agentService` |
| `values/agent.yaml` | The agent chart's values: the token's Secret, discovery from `agent-targets` every 5 seconds, the agent's dashboard, and `AGENT_NAME` from the pod's name |
| `config/proxy.conf` | The proxy's config, which `demo.sh` passes to the chart with `--set-file`: the dashboard and its link into Prometheus, and service discovery |
| `config/agent.conf` | The agent's main config: its scrape timeout, and its one static path, its own metrics |
| `config/agent-change.conf` | Part 5's change: the agent's JVM metrics, which `change-config` appends to `agent.conf` |
| `k8s/monitoring.yaml` | `pp-monitoring`'s namespace, Prometheus, and the demo's NodePort Services |
| `k8s/target.yaml` | `pp-target`'s namespaces, and targets-sync with its RBAC |
| `k8s/agent-targets.yaml` | The agent's targets ConfigMap, created empty once; targets-sync owns its contents |
| `k8s/services.yaml`, `k8s/search.yaml` | The simulated services, each a Deployment and an annotated ClusterIP Service; search is part 3's |
| `prometheus/prometheus.yml` | One HTTP service discovery job against the proxy |
| `app/services_app.py` | The simulated services |
| `sync/targets_sync.py` | targets-sync |
| `Makefile` | The demo's targets: `make up`, `make up-local` (which runs the proxy and agent built from this checkout, for trying a change to them), `down`, `status`, `open`, `tour`, and `logs`, which `make help` lists, and `screenshots`, which it leaves out, since it's for maintaining the site |
| `demo.sh` | Creating and deleting the clusters, installing the charts, the scenarios, and `agent-dashboard`. Its `NEEDS_RELEASE_AFTER` is the newest release that lacks something the demo shows: until the release the docs give is later and its images are on Docker Hub, `make up` builds the proxy and agent from this checkout, as `make up-local` does, so set it to the latest release on Docker Hub when the demo comes to use something no release has yet |
| `bin/screenshots.sh` | The Kubernetes Demo page's screenshots, in the dark and light themes. `make screenshots` builds and starts the demo, waits a minute so the agent's uptime reads in minutes, plays parts 1, 3, and 4, and writes them to `website/prometheus-proxy/docs/images/kubernetes-demo/` (needs Chrome, ImageMagick 7, and `cwebp`). While checking a change, `SKIP_UPTIME_WAIT=1` skips the minute, and `WAIT_SECS` sets how long each wait for a scene may take before the script fails (default 150) |

How `make up` wires the two clusters together:

- **The images.** It installs each chart with its own image, `pambrose/prometheus-proxy` or `pambrose/prometheus-agent`,
  at the latest release, the version the docs give (`make site-version`), and each cluster pulls it from Docker Hub.
  `make up-local` instead builds `prometheus-proxy-demo/{proxy,agent}:local` from `etc/docker/*.Dockerfile`, as the
  other demos do, and `kind load`s the proxy's into `pp-monitoring` and the agent's into `pp-target`; the charts run
  them with `pullPolicy: Never`, and with the image's ID as a pod annotation, so a rebuilt image replaces the pod. Those
  images are built without a provenance attestation, which would give the same jars a new image ID on every build. A
  pulled release can't be loaded that way: Docker holds it for one platform, and `kind load` imports every platform.
- **The link.** The proxy chart's `agentService` is a NodePort, since kind has no load balancer. `demo.sh` reads its
  port, and `pp-monitoring`'s node address on Docker's `kind` network, which both clusters' nodes are on, and installs
  the agent chart with `--set proxy.hostname=<address>:<port>`. A real target cluster would dial a load balancer or an
  ingress instead.
- **The token.** A random token, in an `agent-token` Secret in each cluster's `monitoring` namespace, which both charts
  read (`agentToken.existingSecret`). `make up` keeps the one either cluster already has.
- **The targets.** targets-sync may list and watch the Services in `apps`, and get and update the one ConfigMap,
  `agent-targets`, and nothing else. A create can't be limited to one name, so `make up` creates the ConfigMap, empty,
  and never applies it again. The demo owns it, not the chart (`discovery.existingConfigMap`), so a `helm upgrade`
  doesn't put back an old list. A Service's `prometheus-proxy/port` and `prometheus-proxy/metrics-path` annotations
  choose another port or path; the defaults are its first port and `/metrics`.
- **Changes.** Each `make up` upgrades both releases, restarts Prometheus if its config changed, and restarts
  targets-sync or the services if their script did. Helm 3 and 4 both run it: `demo.sh` uses only flags both have, and
  changes the charts' objects only through `helm`.

targets-sync stands in for a discovery source that reads Kubernetes itself. The agent's discovery reads a file; a
source in the agent that watched Services directly would replace the script, the ConfigMap, and the kubelet's delay.

From the repository root, `make check-rules` validates `prometheus/prometheus.yml` with promtool, `ConfigFileKeysTest`
checks every key in `config/*.conf` against `config/config.conf`, and that each file loads as the proxy or agent loads
it, and `CiHygieneTest` checks that every image in `k8s/` and `kind/` is pinned by digest, and that Prometheus is the
`Makefile`'s `PROMETHEUS_IMAGE`. Dependabot updates the Python image in `k8s/`. The kind node image moves with the kind
release it was built for, by hand: take the image and digest that release's notes give, in both `kind/` files, and
raise `MIN_KIND_VERSION` in `demo.sh` to match.

## Two Replicas of the Agent

The agent chart's `replicaCount` can run more than one agent pod, each registering the same paths, which the proxy
accepts with `agent.consolidated = true`. The demo keeps one, because two give Prometheus every series twice. With two
consolidated replicas, a scrape through the proxy goes to both agents and the proxy joins their answers. Both scraped
the same Service, so each of its series appears twice in what Prometheus gets. Prometheus keeps the first sample and
drops the second, logging `Error on ingesting samples with different value but same timestamp` for each target on
every scrape, and counting them in `prometheus_target_scrapes_sample_duplicate_timestamp_total` (115 in the first 40
seconds here). `up` stays 1, and each service is scraped twice as often. The agents' own metrics don't collide, since
each carries its `launch_id`. One agent per cluster, which Kubernetes replaces in about a second (part 4), avoids it.

## Troubleshooting

- **`make up` fails creating `pp-monitoring` with a port in use**: another service holds 8294, 9290, or 8280, and not
  another demo, since none of them uses those ports. Move it with the variables above, then `make down && make up`.
- **`make up` says a port differs**: a variable asks for a port other than the one the running `pp-monitoring` was
  created with. Unset it to keep the cluster's port, or `make down` first to move it.
- **The dashboard shows no agent**: `make logs` shows the agent's log, and `make status` both clusters' pods. The
  agent reaches the proxy through `pp-monitoring`'s node address, which changes if Docker restarts the node:
  `make up` installs the agent with the current one.
- **A Service doesn't appear**: `make logs SERVICE=targets-sync` shows what it wrote, and why it skipped a Service.
- **kind or Helm is too old**: `demo.sh` needs kind 0.33.0 or later, for its node image, and Helm 3 or 4.
- **`./demo.sh agent-dashboard` stops with `lost connection to pod`**: the agent's pod was replaced; run it again. If
  it can't listen on 8295, another program holds it: set `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT`. If it says the
  dashboard is off, the agent was installed before the demo turned it on: `make up` upgrades it.
- The proxy logs warnings at startup that the dashboard has no authentication and listens on all interfaces, which
  its NodePort Service needs, and that agent tokens cross without TLS, and the agent one that its token crosses without
  TLS. All are expected here: the token crosses only Docker's `kind` network on this machine, and every published port
  binds to `127.0.0.1`. The agent's dashboard has no login either, but the chart has it listen on its pod's loopback,
  where only `kubectl port-forward` reaches it, so the agent doesn't warn about it.

## The Other Demos

In the order the website's [Demos](https://prometheus-proxy.com/demos/) page gives them:

- [Basics demo](../basics): the whole system on one machine, the proxy and its dashboard, three agents behind a
  simulated firewall, Prometheus, and Grafana
- [Network demo](../network): a simulated WAN between the agents and the proxies, and what a far, thin, dead, flaky, or
  missing link does
- [nginx demo](../nginx): nginx between the agents and the proxy, its stream timeouts, its reloads, and TLS ended on one
  port
- [Ktor demo](../ktor): a coffee shop, a Ktor app that runs the agent in its own JVM through the Ktor plugin
- [Spring Boot demo](../spring-boot): a roastery, a Spring Boot app in Java whose agent the starter starts from
  properties
- [Plain Kotlin demo](../plain-kotlin): a bakery's ovens, workers with no framework or HTTP server, each reading its own
  metrics in-process
