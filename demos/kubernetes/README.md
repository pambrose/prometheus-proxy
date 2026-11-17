# Kubernetes demo

A self-contained demo of Prometheus Proxy deployed the way a platform team would run it, for customers. It runs two
[kind](https://kind.sigs.k8s.io/) clusters on one laptop: a monitoring cluster with the proxy, its web dashboard, and
Prometheus, and a target cluster, behind the "firewall", with a set of simulated services and the agent. Both are
installed with the project's own Helm charts, from [`charts/`](../../charts). A presenter can show that the target
cluster exposes nothing, that annotating a Service is all it takes for Prometheus to scrape it, that Kubernetes
replaces a lost agent by itself, and what a change to the agent's config costs next to a change to its targets.

It follows the [network demo](../network), which follows the [dashboard demo](../dashboard): those two show the
dashboard, Grafana, the proxy's features, and the link through the firewall, and this one assumes them. It keeps the
same proxy and agent and shows them on Kubernetes. The website's
[Kubernetes Demo](https://pambrose.github.io/prometheus-proxy/kubernetes-demo/) page walks through the same scenarios
with screenshots, and its [Kubernetes](https://pambrose.github.io/prometheus-proxy/kubernetes/) page is the guide to
the deployment itself.

```
 pp-target (kind cluster, behind the "firewall"; no port mapped to the host, every Service ClusterIP)
   apps:        orders, payments, shipping (and search, in act 2): Services annotated prometheus-proxy/path
   monitoring:  targets-sync ── watches the Services ──► agent-targets ConfigMap ──► mounted in the agent's pod
                prometheus-agent (the agent chart): scrapes each Service in the cluster
                    │  gRPC, outbound only, across Docker's kind network
                    ▼
 pp-monitoring (kind cluster, outside the firewall)
   monitoring:  prometheus-proxy (the proxy chart) ── gRPC NodePort for the agent (agentService)
                    ▲  HTTP service discovery (/discovery) and scrapes, through the chart's Service
                Prometheus :9290 (5s scrapes, 4s timeout)        dashboard :8294        scrape port :8280
```

The proxy and agent images are built from this checkout, so the demo always runs the proxy and agent as they are in
the code. They're the other demos' images, `prometheus-proxy-demo/{proxy,agent}:local`, and `make up` loads each into
the cluster that runs it, so neither cluster pulls them.

## Quick start

You need Docker, a JDK 17 or later to run Gradle, [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation)
0.33.0 or later, `kubectl`, and [Helm](https://helm.sh/docs/intro/install/) 3 or 4. `demo.sh` checks for each. The
demo's commands run from its own directory, where `make help` lists its targets. From the repository root:

```bash
cd demos/kubernetes
make up      # builds the jars and images, creates both clusters, installs everything, and prints the URLs
make tour    # walks through the presenter's script below, pausing before each step
make down    # deletes both clusters
```

The first `make up` pulls the kind node image and builds the jars, and each new cluster pulls the Prometheus and Python
images, which takes a few minutes. After that, `make up` on running clusters takes under a minute. `make down` deletes
the clusters, and the images inside them, so the next `make up` needs a network connection again. **Run it once before
the meeting, and leave it running.**

| What | URL |
|---|---|
| Proxy dashboard | <http://localhost:8294/dashboard> |
| Prometheus | <http://localhost:9290> (targets at `/targets`) |
| The proxy's service discovery | <http://localhost:8280/discovery> |
| `kubectl` | `kubectl --context kind-pp-monitoring …` and `kubectl --context kind-pp-target …` |

`make open` opens the dashboard and Prometheus's targets in your browser.

Every port is the dashboard demo's plus 200, so all three demos can run at once. kind publishes them when it creates
`pp-monitoring`, so to move one that's already taken, set its variable when the cluster is created, and for every
command after, for example `KUBERNETES_DEMO_PROMETHEUS_PORT=9291 make up`. The variables are
`KUBERNETES_DEMO_DASHBOARD_PORT`, `KUBERNETES_DEMO_PROMETHEUS_PORT`, and `KUBERNETES_DEMO_PROXY_PORT`; the dashboard's
link into Prometheus keeps the default port (`config/proxy.conf`). A running cluster keeps the ports it was created
with, and `make up` says so if they differ: `make down` first. Every port binds to `127.0.0.1` only. The dashboard has
no authentication, so this setup is for a laptop, not for anything shared.

kind adds a `kind-pp-monitoring` and a `kind-pp-target` context to your kubeconfig. `demo.sh` names the context on
every command, and puts back the context that was current when it creates a cluster.

## What's running

| Cluster / namespace | Workload | Role |
|---|---|---|
| `pp-monitoring` / `monitoring` | `prometheus-proxy` | The proxy, from the `prometheus-proxy` chart (`values/proxy.yaml`). Its dashboard is on, service discovery tells Prometheus about every path the agent registers, and its gRPC port is a NodePort (the chart's `agentService`), for the agent in the other cluster. |
| `pp-monitoring` / `monitoring` | `prometheus` | Prometheus, from a plain Deployment (`k8s/monitoring.yaml`). Its one job finds every target through the proxy's `/discovery`, and scrapes each through the proxy, every 5s with a 4s timeout. |
| `pp-monitoring` / `monitoring` | `proxy-dashboard`, `proxy-scrape`, `prometheus` Services | The demo's NodePort Services, which kind publishes on the host's `127.0.0.1`. The chart leaves the dashboard out of its own Service. |
| `pp-target` / `monitoring` | `prometheus-agent` | The agent, from the `prometheus-agent` chart (`values/agent.yaml`), named for its pod. It dials the proxy's NodePort on `pp-monitoring`'s node, and its token comes from a Secret. Its one static path is its own metrics; the services come from discovery. |
| `pp-target` / `monitoring` | `targets-sync` | Lists and watches the Services in `apps`, and writes each annotated `prometheus-proxy/path` into the agent's targets ConfigMap, `agent-targets` (`sync/targets_sync.py`). Part of the demo, not the product. |
| `pp-target` / `apps` | `orders`, `payments`, `shipping` | The simulated services (`app/services_app.py`), one per pod, each behind a ClusterIP Service annotated `prometheus-proxy/path`. Traffic follows a 5-minute "day", so the graphs move. |

## Presenter's script

Each act lists what to show and what it means for the customer. Run the scenario commands from `demos/kubernetes`;
each one prints what to watch, and how long it took. The times below were measured on an Apple-silicon laptop.

### 1. Two clusters, one outbound link

Assume the earlier demos have been seen: the same proxy and agent, now deployed with their Helm charts.

```bash
./demo.sh clusters        # pp-target's Services, each cluster's Helm release, and Prometheus's one job
```

- Every Service in `pp-target` is ClusterIP: nothing in it can be reached from outside, and kind maps no port of it to
  the host but its API server's, for `kubectl`. The agent dialed out to the proxy; nothing dialed in.
- `helm list` shows the proxy chart in `pp-monitoring` and the agent chart in `pp-target`: the charts the project
  ships, with nothing but a values file each.
- Prometheus's config names no target in `pp-target`. Its one job asks the proxy's `/discovery` what to scrape.
- Open the dashboard. One agent is connected, named for its pod, `prometheus-agent-<hash>-<id>`, the name `kubectl`
  shows, with four paths: its own metrics (source **cfg**, from its config) and the three services (source **disc**,
  from discovery). Prometheus's targets page shows all four up.

### 2. Annotate a Service, and Prometheus scrapes it

```bash
./demo.sh add-service     # deploys search, then its Service, annotated prometheus-proxy/path: search_metrics
```

- `add-service` deploys search and creates its Service once the pod is ready, as a team would deploy an app and then
  expose it. targets-sync sees the new Service and writes it into the agent's targets ConfigMap. The kubelet copies
  the ConfigMap into the agent's pod, the agent re-reads it, and registers the path: in the proxy's `/discovery` 1 to
  7 seconds after the Service was created (nine runs), and in Prometheus within its 5-second discovery refresh after
  that.
- The dashboard's Paths view shows `search_metrics` with source **disc**. The agent's pod is the same one, with no
  restarts: nothing restarted, and no other path missed a scrape.
- The delay is mostly the kubelet's. It copies a changed ConfigMap into the pods that mount it on its periodic sync,
  every `syncFrequency`, which is a minute on a production kubelet; `kind/pp-target.yaml` lowers it to 5 seconds. With
  the default, the same change took 63 to 74 seconds to reach a pod (six runs, in `pp-monitoring`, which keeps it), and
  with 5 seconds, 1 to 7 (six runs). The agent's own re-read, every 5 seconds here, comes on top.

```bash
./demo.sh remove-service  # removes the annotation, then deletes search once its path has gone
```

The path leaves `/discovery` 3 to 10 seconds after the annotation goes (three runs), again without a restart, and
`remove-service` then deletes search. Deleting a Service whose path is still registered would fail its scrapes until
the agent heard.

The dashboard demo shows dynamic discovery itself, from a file; this act shows Kubernetes driving it.

### 3. Kubernetes replaces the agent

```bash
./demo.sh delete-agent-pod   # deletes the agent's pod; its Deployment starts another
```

- The Deployment starts a new pod at once. Its agent connects out to the proxy and registers every path again, under
  its own pod name: on the dashboard about 1.3 seconds after the delete (three runs). The old one leaves the dashboard
  as it shuts down.
- Scrapes through the proxy fail for about a second meanwhile (0.9 to 1.0 seconds in three runs), so a Prometheus
  scrape that lands in it fails, and the next succeeds. Nobody touched the agent.
- The dashboard's Activity view shows the old pod's paths leaving and the new pod's arriving. In Prometheus, each
  target's `agent` label is the pod's name, so a new pod starts new series, as a `pod` label would.

### 4. A config change restarts the agent; a targets change doesn't

```bash
./demo.sh change-config      # helm upgrade with config/agent-change.conf added to the agent's main config
```

- The change turns on the agent's JVM metrics. The chart renders the config into a ConfigMap and stamps the pod with
  its checksum (`checksum/config`), so the upgrade replaces the pod, and the chart's `strategy: Recreate` stops the old
  one before starting the new, since both would register the same paths.
- That's a gap: scrapes through the proxy fail for about 1.3 seconds (1.3 to 1.4 in six runs), and the new pod's agent
  is on the dashboard about 2 seconds after the upgrade began. Its `agent_metrics` now carries the JVM's metrics, so
  `count({job="prometheus-agent"})` in Prometheus jumps from about 30 series to over 200.
- Compare act 2, where the targets changed and nothing restarted. That's why the agent chart keeps its targets in a
  ConfigMap of their own, which the agent re-reads in place, and its main config, which only a restart applies, apart.

```bash
./demo.sh restore-config     # back to config/agent.conf alone: another replacement
```

## Scenario reference

| Command | Effect |
|---|---|
| `demo.sh clusters` | Shows `pp-target`'s Services, each cluster's Helm release, and Prometheus's scrape config |
| `demo.sh add-service` / `remove-service` | Deploys the search service and then its Service, annotated `prometheus-proxy/path`; or removes the annotation and then deletes both |
| `demo.sh delete-agent-pod` | Deletes the agent's pod, and reports when its replacement registered and how long scrapes failed |
| `demo.sh change-config` / `restore-config` | Upgrades the agent chart with or without `config/agent-change.conf`, and reports the replacement the same way |
| `make tour [ACT=n]` | Walks through the presenter's script, from act 1 or the one given, pausing before each step |
| `make status` | Both clusters' pods and Services, the Helm releases, and the URLs |
| `make logs [SERVICE=name]` | Follows a workload's logs: `agent` (the default), `proxy`, `prometheus`, `targets-sync`, or a service |

`remove-service` and `restore-config` undo every scenario. For a clean slate, `make down && make up`.

## How it's put together

| File | What it sets up |
|---|---|
| `kind/pp-monitoring.yaml` | The monitoring cluster: its node image, and the host ports kind publishes its NodePorts on |
| `kind/pp-target.yaml` | The target cluster: its node image, no host ports, and the kubelet's 5-second `syncFrequency` for act 2 |
| `values/proxy.yaml` | The proxy chart's values: the image built from this checkout, the token's Secret, the dashboard, and a NodePort `agentService` |
| `values/agent.yaml` | The agent chart's values: the image, the token's Secret, discovery from `agent-targets` every 5 seconds, and `AGENT_NAME` from the pod's name |
| `config/proxy.conf` | The proxy's config, which `demo.sh` passes to the chart with `--set-file`: the dashboard and its link into Prometheus, and service discovery |
| `config/agent.conf` | The agent's main config: its scrape timeout, and its one static path, its own metrics |
| `config/agent-change.conf` | Act 4's change: the agent's JVM metrics, which `change-config` appends to `agent.conf` |
| `k8s/monitoring.yaml` | `pp-monitoring`'s namespace, Prometheus, and the demo's NodePort Services |
| `k8s/target.yaml` | `pp-target`'s namespaces, and targets-sync with its RBAC |
| `k8s/agent-targets.yaml` | The agent's targets ConfigMap, created empty once; targets-sync owns its contents |
| `k8s/services.yaml`, `k8s/search.yaml` | The simulated services, each a Deployment and an annotated ClusterIP Service; search is act 2's |
| `prometheus/prometheus.yml` | One HTTP service discovery job against the proxy |
| `app/services_app.py` | The simulated services |
| `sync/targets_sync.py` | targets-sync |
| `Makefile` | The demo's targets: `make up` (which builds the jars first), `down`, `status`, `open`, `tour`, and `logs`, which `make help` lists, and `screenshots`, which it leaves out, since it's for maintaining the site |
| `demo.sh` | Creating and deleting the clusters, installing the charts, and the scenarios |
| `bin/screenshots.sh` | The Kubernetes Demo page's screenshots, in the dark and light themes. `make screenshots` builds and starts the demo, waits a minute so the agent's uptime reads in minutes, plays acts 1 to 3, and writes them to `website/prometheus-proxy/docs/images/kubernetes-demo/` (needs Chrome, ImageMagick 7, and `cwebp`). While checking a change, `SKIP_UPTIME_WAIT=1` skips the minute, and `WAIT_SECS` sets how long each wait for a scene may take before the script fails (default 150) |

How `make up` wires the two clusters together:

- **The images.** It builds `prometheus-proxy-demo/{proxy,agent}:local` from `etc/docker/*.Dockerfile`, as the other
  demos do, and `kind load`s the proxy's into `pp-monitoring` and the agent's into `pp-target`. The charts run them
  with `pullPolicy: Never`, and with the image's ID as a pod annotation, so a rebuilt image replaces the pod. The
  images are built without a provenance attestation, which would give the same jars a new image ID on every build.
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

## Two replicas of the agent

The agent chart's `replicaCount` can run more than one agent pod, each registering the same paths, which the proxy
accepts with `agent.consolidated = true`. The demo keeps one, because two give Prometheus every series twice. With two
consolidated replicas, a scrape through the proxy goes to both agents and the proxy joins their answers. Both scraped
the same Service, so each of its series appears twice in what Prometheus gets. Prometheus keeps the first sample and
drops the second, logging `Error on ingesting samples with different value but same timestamp` for each target on
every scrape, and counting them in `prometheus_target_scrapes_sample_duplicate_timestamp_total` (115 in the first 40
seconds here). `up` stays 1, and each service is scraped twice as often. The agents' own metrics don't collide, since
each carries its `launch_id`. One agent per cluster, which Kubernetes replaces in about a second (act 3), avoids it.

## Troubleshooting

- **`make up` fails creating `pp-monitoring` with a port in use**: another service holds 8294, 9290, or 8280; the other
  demos' ports are 100 and 200 lower, so it isn't them. Move it with the variables above, then `make down && make up`.
- **`make up` says a port differs**: the running `pp-monitoring` was created with other ports. `make down` first.
- **The dashboard shows no agent**: `make logs` shows the agent's log, and `make status` both clusters' pods. The
  agent reaches the proxy through `pp-monitoring`'s node address, which changes if Docker restarts the node:
  `make up` installs the agent with the current one.
- **A Service doesn't appear**: `make logs SERVICE=targets-sync` shows what it wrote, and why it skipped a Service.
- **kind or Helm is too old**: `demo.sh` needs kind 0.33.0 or later, for its node image, and Helm 3 or 4.
- The proxy logs a warning at startup that the dashboard has no authentication, and the agent one that its token
  crosses without TLS. Both are expected here, and are why every port binds to `127.0.0.1`.
