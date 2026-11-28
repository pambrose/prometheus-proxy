#!/usr/bin/env bash
# Drives the Prometheus Proxy Kubernetes demo (see README.md): creates and deletes its two kind clusters, installs the
# proxy and agent charts in them, and runs the scenarios a presenter shows. The Makefile beside it wraps the lifecycle
# commands (`make up` runs `demo.sh up`). Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
# The make that runs the repository's targets: the one that ran this script, which the Makefile beside it exports as
# MAKE, or, run directly, make, or else gmake, the name GNU make has on some systems. MAKE_NAME is the one the
# messages tell the reader to type.
MAKE="${MAKE:-$(command -v make || command -v gmake || echo make)}"
MAKE_NAME="${MAKE##*/}"

# The two clusters: pp-monitoring, outside the firewall, runs the proxy, its dashboard, and Prometheus; pp-target,
# behind it, runs the services, the agent, and targets-sync. kind names each one's kubectl context kind-<name>.
MONITORING=pp-monitoring
TARGET=pp-target
MK=(kubectl --context "kind-$MONITORING")
TK=(kubectl --context "kind-$TARGET")
# The proxy and the agent are each in their cluster's monitoring namespace; the services are in pp-target's apps.
MH=(helm --kube-context "kind-$MONITORING" --namespace monitoring)
TH=(helm --kube-context "kind-$TARGET" --namespace monitoring)

# The proxy and agent images up-local builds from this checkout's jars and loads into the clusters, the other demos'
# tags, so they share the images. up instead installs the charts with their own images, pambrose/prometheus-{proxy,agent}
# at the latest release, which each cluster pulls from Docker Hub.
PROXY_IMAGE=prometheus-proxy-demo/proxy:local
AGENT_IMAGE=prometheus-proxy-demo/agent:local
RELEASED_IMAGES=(pambrose/prometheus-proxy pambrose/prometheus-agent)
# The newest release that lacks something the demo shows (the proxy's screens page, and an agent dashboard that lets
# the page frame it, which 4.7.1 brings). up runs the release the docs give (make site-version) once it's later than
# this and its images are on Docker Hub, and otherwise builds the proxy and agent from this checkout, as up-local
# does, so nothing here changes with a release. When the demo comes to use something no release has yet, set this to
# the latest release on Docker Hub.
NEEDS_RELEASE_AFTER=4.7.0
# The chart values that choose the proxy's and the agent's images, which up and up-local set.
PROXY_IMAGE_VALUES=()
AGENT_IMAGE_VALUES=()
# The kind release the node image in kind/*.yaml was built for.
MIN_KIND_VERSION=0.33.0
# The services `make up` deploys (k8s/services.yaml); part 3 adds search (k8s/search.yaml).
SERVICES=(orders payments shipping)

# The host port a pp-monitoring NodePort is published on, from the node container's port mappings, or nothing, as
# when the cluster isn't there.
published_port() {
  docker port "$MONITORING-control-plane" "$1/tcp" 2>/dev/null | sed -nE 's/^127\.0\.0\.1:([0-9]+)$/\1/p' | head -1 ||
    true
}

# A host port kind publishes a pp-monitoring NodePort on (kind/pp-monitoring.yaml): its variable's value, read as kind
# creates the cluster, or, with the variable unset, the port the running cluster publishes, so a port moved when the
# clusters were created stays moved for every command after; or else the default.
# cluster_port <the variable's value> <NodePort> <default>
cluster_port() {
  local port="$1"
  [[ -n "$port" ]] || port="$(published_port "$2")"
  echo "${port:-$3}"
}
DASHBOARD_PORT="$(cluster_port "${KUBERNETES_DEMO_DASHBOARD_PORT:-}" 30094 8294)"
PROMETHEUS_PORT="$(cluster_port "${KUBERNETES_DEMO_PROMETHEUS_PORT:-}" 30090 9290)"
PROXY_PORT="$(cluster_port "${KUBERNETES_DEMO_PROXY_PORT:-}" 30080 8280)"
# This demo's page on the docs site, which shows its layout and what each part of its tour shows. The demo points only
# there, never to a raw Markdown file, which isn't readable without a viewer.
PAGE_URL="https://prometheus-proxy.com/kubernetes-demo/"
DASHBOARD_URL="http://localhost:$DASHBOARD_PORT/dashboard"
PROMETHEUS_URL="http://localhost:$PROMETHEUS_PORT"
PROXY_URL="http://localhost:$PROXY_PORT"
# The host port `demo.sh agent-dashboard` forwards to the agent's dashboard with kubectl port-forward, held only while
# it runs: pp-target publishes no port, and the agent chart leaves the dashboard off the agent's Service.
AGENT_DASHBOARD_PORT="${KUBERNETES_DEMO_AGENT_DASHBOARD_PORT:-8295}"
AGENT_DASHBOARD_URL="http://localhost:$AGENT_DASHBOARD_PORT/dashboard"
# The proxy's screens page, which holds the proxy's dashboard, the agent's, and Prometheus in one browser tab
# (config/proxy.conf's screens). The tour links into it: ${SCREENS_URL}#<screen>, #<screen>/<view>, or
# #<screen>+<screen> for two side by side. No part of the tour stops the proxy.
SCREENS_URL="http://localhost:$DASHBOARD_PORT/screens"
# The proxy's dashboard port's root, which leads to the screens page: the short address make up prints.
SCREENS_HOME_URL="http://localhost:$DASHBOARD_PORT/"
# Where a browser on this machine reaches each of the page's screens, which the proxy chart's env gives the proxy
# (KUBERNETES_DEMO_*_URL, which config/proxy.conf reads): the agent's dashboard at the port agent-dashboard forwards,
# and Prometheus at the one kind publishes, so a port moved with KUBERNETES_DEMO_*_PORT for make up moves its tab too.
PROXY_ENV_VALUES=(
  --set "env[0].name=KUBERNETES_DEMO_AGENT_URL" --set-string "env[0].value=$AGENT_DASHBOARD_URL"
  --set "env[1].name=KUBERNETES_DEMO_PROMETHEUS_URL" --set-string "env[1].value=$PROMETHEUS_URL"
)

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
die() {
  echo "Error: $*" >&2
  exit 1
}

# The width text is wrapped to: the terminal's, from 60 to 110 columns, or 100 with no terminal to ask.
text_width() {
  local cols
  cols="$({ stty size </dev/tty | cut -d' ' -f2; } 2>/dev/null || true)"
  [[ "$cols" =~ ^[0-9]+$ ]] || cols=100
  ((cols >= 60)) || cols=60
  echo $((cols < 110 ? cols : 110))
}

# Prints text wrapped to that width, its first line after $1 and the rest after $2, which is as wide as $1 shows:
# wrap <first> <rest> <text>... It breaks lines only between words, and gives a word longer than a line, such as a
# Prometheus URL, a line of its own, unbroken, so a terminal still opens it as a link.
wrap() {
  local first="$1" rest="$2" line
  while IFS= read -r line; do
    printf '%s%s\n' "$first" "$line"
    first="$rest"
  done < <(awk -v width=$(($(text_width) - ${#rest})) '{
      for (i = 1; i <= NF; i++) {
        if (len > 0 && len + 1 + length($i) > width) { print out; out = ""; len = 0 }
        out = len > 0 ? out " " $i : $i
        len += (len > 0 ? 1 : 0) + length($i)
      }
    }
    END { if (len > 0) print out }' <<<"${*:3}")
}

# What a scenario changed: a heading, then each thing to look for as a point of its own, its arguments joined.
watch_for() { printf '\033[36m%s\033[0m\n' "What to watch:"; }
point() { wrap "  - " "    " "$*"; }
# How a scenario does what it does, under its own heading, before what to watch for.
how_it_works() {
  printf '\033[36m%s\033[0m\n' "How it works:"
  wrap "  " "  " "$*"
}
# The command that undoes a scenario, or comes after it: follow_up <what> <command>
follow_up() { printf '\033[36m%s:\033[0m %s\n' "$1" "$2"; }

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [argument]

Lifecycle:
  up                       Create both kind clusters and install the demo, with the proxy and agent released on Docker Hub
  up-local                 The same, with the proxy and agent built from this checkout (needs a JDK 17 or later)
  down                     Delete both kind clusters
  status                   Show both clusters' pods and Services, the Helm releases, and the URLs
  open                     Open the proxy's screens page, which holds its dashboard, the agent's, and Prometheus
  agent-dashboard          Forward the agent's dashboard to localhost:$AGENT_DASHBOARD_PORT with kubectl port-forward
  logs [name]              Follow a workload's logs: agent (the default), proxy, prometheus, targets-sync, or a service
  tour [part]              Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

Scenarios:
  clusters                 Show that pp-target exposes nothing, each cluster's Helm release, and Prometheus's one job
  add-service              Deploy the search service, its Service annotated prometheus-proxy/path
  remove-service           Delete it again
  delete-agent-pod         Delete the agent's pod; its Deployment starts another
  change-config            Upgrade the agent chart with a changed main config, which replaces the agent's pod
  restore-config           Upgrade it with the original config again

Services: ${SERVICES[*]} (search after add-service)
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy Kubernetes demo"
  echo "  All screens:      $SCREENS_HOME_URL"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo "  kubectl:          kubectl --context kind-$MONITORING ...   kubectl --context kind-$TARGET ..."
  echo
  wrap "" "" "The agent's dashboard, one of the screens, answers while $0 agent-dashboard runs."
  echo
  wrap "" "" "Demo overview: ${PAGE_URL}#what-this-demo-shows"
  echo
  echo "Start the demo with: $MAKE_NAME tour, or $0 tour"
}

# Whether version $1 (x.y.z) is at least $2. bash 3.2, macOS's, has no sort -V everywhere, so field by field.
version_at_least() {
  local IFS=. i
  local -a have want
  read -r -a have <<<"$1"
  read -r -a want <<<"$2"
  for i in 0 1 2; do
    ((${have[i]:-0} > ${want[i]:-0})) && return 0
    ((${have[i]:-0} < ${want[i]:-0})) && return 1
  done
  return 0
}

# Whether version $1 (x.y.z) is later than $2.
version_after() { [[ "$1" != "$2" ]] && version_at_least "$1" "$2"; }

# The release the docs give: the latest, or, from a version bump until the release is out, the next, whose images
# aren't on Docker Hub yet.
latest_release() { "$MAKE" -s --no-print-directory -C "$REPO_DIR" site-version; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not on the PATH; $2"; }

check_tools() {
  local kind_version helm_version
  need docker "install Docker: https://docs.docker.com/get-started/get-docker/"
  docker info >/dev/null 2>&1 || die "Docker isn't running"
  need kind "install kind $MIN_KIND_VERSION or later: https://kind.sigs.k8s.io/"
  need kubectl "install kubectl: https://kubernetes.io/docs/tasks/tools/"
  need helm "install Helm 3 or 4: https://helm.sh/docs/intro/install/"
  # Without curl, the wait for each service would retry until its deadline and then blame the service.
  need curl "install curl; the demo uses it to wait for each service to answer"
  kind_version="$(kind version | sed -nE 's/^kind v([0-9]+\.[0-9]+\.[0-9]+).*/\1/p')"
  [[ -n "$kind_version" ]] || die "can't read kind's version from '$(kind version)'"
  version_at_least "$kind_version" "$MIN_KIND_VERSION" ||
    die "kind $kind_version is older than $MIN_KIND_VERSION, the release the demo's node image is built for"
  helm_version="$(helm version --template '{{.Version}}' 2>/dev/null || true)"
  [[ "$helm_version" =~ ^v[34]\. ]] || die "the demo needs Helm 3 or 4, not '$helm_version'"
}

# Building the proxy and agent runs Gradle, which needs a JDK 17 or later: the one JAVA_HOME names, as the Gradle
# wrapper takes it, or else the java on the PATH. Checked before anything is built, so a missing or old one is named
# up front.
check_jdk() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version
  command -v "$java" >/dev/null 2>&1 ||
    die "building the proxy and agent from this checkout takes a JDK 17 or later;" \
      "set JAVA_HOME to one, or get one at https://adoptium.net"
  version="$("$java" -version 2>&1 | sed -nE '1s/.*version "(1\.)?([0-9]+).*/\2/p')"
  if ! [[ "$version" =~ ^[0-9]+$ ]] || ((version < 17)); then
    die "building the proxy and agent from this checkout takes a JDK 17 or later, not Java ${version:-of an unknown" \
      "version} ($java)"
  fi
}

cluster_exists() { kind get clusters 2>/dev/null | grep -qx "$1"; }

# Creates a cluster from its kind config, with the KUBERNETES_DEMO_*_PORT variables in place of the host ports the
# config tags with their names. kind makes the new cluster kubectl's current context; the demo names its context on
# every command, so it puts back the one that was current, leaving a kubectl run elsewhere pointed where it was.
create_cluster() {
  local name="$1" rendered previous
  previous="$(kubectl config current-context 2>/dev/null || true)"
  rendered="$(mktemp)"
  sed -E \
    -e "s/^( *hostPort: )[0-9]+( *# KUBERNETES_DEMO_DASHBOARD_PORT)$/\1$DASHBOARD_PORT\2/" \
    -e "s/^( *hostPort: )[0-9]+( *# KUBERNETES_DEMO_PROMETHEUS_PORT)$/\1$PROMETHEUS_PORT\2/" \
    -e "s/^( *hostPort: )[0-9]+( *# KUBERNETES_DEMO_PROXY_PORT)$/\1$PROXY_PORT\2/" \
    "$DEMO_DIR/kind/$name.yaml" >"$rendered"
  bold "Creating the $name cluster"
  if ! kind create cluster --name "$name" --config "$rendered" --wait 2m; then
    rm -f "$rendered"
    die "kind couldn't create $name. If a port is taken, run '$MAKE_NAME down', then move the port as the Quick Start" \
      "on the demo's page says: ${PAGE_URL}#running-it"
  fi
  rm -f "$rendered"
  if [[ -n "$previous" ]]; then
    kubectl config use-context "$previous" >/dev/null
  else
    kubectl config unset current-context >/dev/null
  fi
}

# A running pp-monitoring keeps the ports it was created with, so a variable set to another port can't move one; say
# so, rather than print URLs that don't answer. The agent dashboard's port isn't one of them: kubectl port-forward
# holds it only while agent-dashboard runs.
check_ports() {
  local entry node_port want variable port
  for entry in 30094:"$DASHBOARD_PORT":KUBERNETES_DEMO_DASHBOARD_PORT \
    30090:"$PROMETHEUS_PORT":KUBERNETES_DEMO_PROMETHEUS_PORT 30080:"$PROXY_PORT":KUBERNETES_DEMO_PROXY_PORT; do
    IFS=: read -r node_port want variable <<<"$entry"
    port="$(published_port "$node_port")"
    [[ "$port" == "$want" ]] ||
      die "$MONITORING publishes port ${port:-none} where $variable asks for $want; unset $variable to keep" \
        "the cluster's port, or run '$MAKE_NAME down' first to move it"
  done
}

# The image ID behind a tag, or nothing.
image_id() { docker image inspect -f '{{.Id}}' "$1" 2>/dev/null || true; }

build_images() {
  local old_ids id
  check_jdk
  docker buildx version >/dev/null 2>&1 || die "docker buildx is missing; the demo builds its images with it"
  bold "Building the proxy and agent from this checkout"
  "$MAKE" --no-print-directory -C "$REPO_DIR" jars
  # A build from changed code makes new images and leaves the previous ones untagged; remember them to remove
  # afterwards. One another demo still runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "$PROXY_IMAGE" "$AGENT_IMAGE" 2>/dev/null || true)"
  bold "Building $PROXY_IMAGE and $AGENT_IMAGE from the jars"
  # --load, so the image lands in Docker's own store whichever builder is selected; kind loads it from there. Without
  # --provenance=false, the build's provenance attestation, which carries its time, gave the same jars a new image ID
  # on every build, so each `make up` loaded the images again and replaced the pods.
  local dockerfile image
  for image in "$PROXY_IMAGE" "$AGENT_IMAGE"; do
    dockerfile="$REPO_DIR/etc/docker/$(basename "${image%:*}").Dockerfile"
    docker buildx build --load --quiet --provenance=false -t "$image" -f "$dockerfile" "$REPO_DIR" >/dev/null
  done
  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done
}

# Creates or updates a ConfigMap from files; succeeds only if an existing one changed, so its users can be restarted:
# a pod doesn't rerun a script, or reread a config, that changed under it.
# apply_configmap <kubectl...> -- <namespace> <name> <file>...
apply_configmap() {
  local -a kc=()
  local namespace name result file
  while [[ "$1" != -- ]]; do
    kc+=("$1")
    shift
  done
  shift
  namespace="$1" name="$2"
  shift 2
  local -a from=()
  for file in "$@"; do from+=("--from-file=$file"); done
  result="$("${kc[@]}" -n "$namespace" create configmap "$name" "${from[@]}" --dry-run=client -o yaml |
    "${kc[@]}" apply -f -)"
  [[ "$result" == *" configured" ]]
}

# The agent token, in the agent-token Secret of both clusters' monitoring namespace: the one either cluster already
# has, so a cluster created again matches the other, or a new random one.
ensure_token() {
  local token="" kc
  for kc in "kind-$MONITORING" "kind-$TARGET"; do
    [[ -n "$token" ]] || token="$(kubectl --context "$kc" -n monitoring get secret agent-token \
      -o go-template='{{.data.token | base64decode}}' 2>/dev/null || true)"
  done
  [[ -n "$token" ]] || token="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
  for kc in "kind-$MONITORING" "kind-$TARGET"; do
    printf '%s' "$token" |
      kubectl --context "$kc" -n monitoring create secret generic agent-token --from-file=token=/dev/stdin \
        --dry-run=client -o yaml | kubectl --context "$kc" apply -f - >/dev/null
  done
}

wait_for() {
  local url="$1" name="$2" deadline=$((SECONDS + 120))
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    ((SECONDS < deadline)) || die "$name did not answer within 120s; see '$0 status'. It was asked at $url"
    sleep 2
  done
}

# The proxy's /discovery, whole, or nothing. Taken whole before grep reads it: grep -q stops at its match, and under
# pipefail a curl it cut off would fail the check.
discovery() { curl -fsS "$PROXY_URL/discovery" 2>/dev/null || true; }
discovers() { grep -qF "\"/$1\"" <<<"$(discovery)"; }

# The agent's pods that aren't on their way out, one a line.
agent_pods() {
  "${TK[@]}" -n monitoring get pods -l app.kubernetes.io/instance=prometheus-agent \
    -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}}{{"\n"}}{{end}}{{end}}'
}
agent_pod() { agent_pods | head -1; }

restarts() {
  "${TK[@]}" -n monitoring get pod "$1" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || true
}

# Whether the dashboard lists a connected agent by this name with at least one path. Each agent's row carries its
# name and path count as data attributes (agentListInner in ProxyDashboardHtml.kt), and the closing quote keeps one
# name from matching another that starts with it.
lists_agent() {
  local html
  html="$(curl -fsS "$DASHBOARD_URL" 2>/dev/null)" || return 1
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" |
    grep -qvF 'data-path-count="0"'
}

# Whether a scrape of a path through the proxy succeeds, as Prometheus's would.
scrapes() { curl -fsS -m 3 -o /dev/null "$PROXY_URL/$1" 2>/dev/null; }

# Waits for the agent's replacement after its pod $1 was deleted or replaced at second $2, and reports how long it
# took: until the new pod's agent is on the dashboard with its paths, and how long a scrape of orders_metrics through
# the proxy failed meanwhile, checked every half second.
watch_replacement() {
  local old="$1" start="$2" deadline=$((SECONDS + 180)) new="" registered_at="" failures=0 pod
  while ((SECONDS < deadline)); do
    if ! scrapes orders_metrics; then
      failures=$((failures + 1))
    elif [[ -n "$new" ]]; then
      break
    fi
    if [[ -z "$new" ]]; then
      for pod in $(agent_pods); do
        if [[ "$pod" != "$old" ]] && lists_agent "$pod"; then
          new="$pod"
          registered_at=$((SECONDS - start))
        fi
      done
    fi
    sleep 0.5
  done
  [[ -n "$new" ]] || die "no new agent pod registered within 180s; see '$0 logs agent' and '$0 status'"
  bold "$new is on the dashboard with its paths, ${registered_at}s after"
  if ((failures == 0)); then
    echo "  No scrape of orders_metrics through the proxy failed."
  else
    echo "  Scrapes of orders_metrics through the proxy failed for about $(((failures + 1) / 2))s."
  fi
}

# The agent's main config as installed: the agent.conf of the ConfigMap the chart renders from its config value.
installed_agent_config() {
  "${TK[@]}" -n monitoring get configmap prometheus-agent -o go-template='{{index .data "agent.conf"}}'
}

# Upgrades the agent chart with its current values but this config file, unless it's installed already, and reports
# the pod's replacement: the config changes the chart's checksum/config pod annotation, and so the pod. The command
# is printed with the config's description, $2, since the file may be a temporary one.
# switch_agent_config <file> <description>
switch_agent_config() {
  local config="$1" old start
  if [[ "$(installed_agent_config)" == "$(cat "$config")" ]]; then
    echo "  The agent runs that config already, so nothing changes."
    return 1
  fi
  old="$(agent_pod)"
  [[ -n "$old" ]] || die "the agent has no pod; see '$0 status'"
  start=$SECONDS
  printf '\033[36m$ %s \\\n    %s\033[0m\n' \
    "helm --kube-context kind-$TARGET --namespace monitoring upgrade prometheus-agent ../../charts/prometheus-agent" \
    "--reuse-values --set-file config=$2"
  "${TH[@]}" upgrade prometheus-agent "$REPO_DIR/charts/prometheus-agent" --reuse-values --set-file "config=$config" \
    >/dev/null
  watch_replacement "$old" "$start"
}

install_monitoring() {
  bold "Installing Prometheus and the proxy chart in $MONITORING"
  if apply_configmap "${MK[@]}" -- monitoring prometheus-config "$DEMO_DIR/prometheus/prometheus.yml"; then
    # Prometheus doesn't notice a changed config file on its own.
    "${MK[@]}" -n monitoring rollout restart deployment/prometheus >/dev/null
  fi
  "${MH[@]}" upgrade --install prometheus-proxy "$REPO_DIR/charts/prometheus-proxy" \
    -f "$DEMO_DIR/values/proxy.yaml" --set-file "config=$DEMO_DIR/config/proxy.conf" "${PROXY_IMAGE_VALUES[@]}" \
    "${PROXY_ENV_VALUES[@]}" --wait --timeout 5m >/dev/null
  "${MK[@]}" -n monitoring rollout status deployment/prometheus --timeout 180s >/dev/null
}

install_target() {
  local node_ip node_port service
  # The agent dials the proxy's gRPC NodePort on pp-monitoring's node, at its address on Docker's kind network, which
  # every kind node is on.
  node_ip="$(docker inspect -f '{{with index .NetworkSettings.Networks "kind"}}{{.IPAddress}}{{end}}' \
    "$MONITORING-control-plane")"
  node_port="$("${MK[@]}" -n monitoring get service prometheus-proxy-agents -o jsonpath='{.spec.ports[0].nodePort}')"
  [[ -n "$node_ip" && -n "$node_port" ]] || die "can't find the proxy's gRPC NodePort on $MONITORING"

  bold "Installing the services, targets-sync, and the agent chart in $TARGET"
  if ! "${TK[@]}" -n monitoring get configmap agent-targets >/dev/null 2>&1; then
    "${TK[@]}" create -f "$DEMO_DIR/k8s/agent-targets.yaml" >/dev/null
  fi
  if apply_configmap "${TK[@]}" -- monitoring targets-sync-script "$DEMO_DIR/sync/targets_sync.py"; then
    "${TK[@]}" -n monitoring rollout restart deployment/targets-sync >/dev/null
  fi
  if apply_configmap "${TK[@]}" -- apps services-app "$DEMO_DIR/app/services_app.py"; then
    "${TK[@]}" -n apps rollout restart deployment >/dev/null
  fi
  "${TK[@]}" apply -f "$DEMO_DIR/k8s/services.yaml" >/dev/null
  "${TH[@]}" upgrade --install prometheus-agent "$REPO_DIR/charts/prometheus-agent" \
    -f "$DEMO_DIR/values/agent.yaml" --set-file "config=$DEMO_DIR/config/agent.conf" \
    --set "proxy.hostname=$node_ip:$node_port" "${AGENT_IMAGE_VALUES[@]}" \
    --wait --timeout 5m >/dev/null
  "${TK[@]}" -n monitoring rollout status deployment/targets-sync --timeout 180s >/dev/null
  for service in "${SERVICES[@]}"; do
    "${TK[@]}" -n apps rollout status "deployment/$service" --timeout 180s >/dev/null
  done
}

# Checks the ports and the tools, before anything is built, pulled, or created.
check_up() {
  local port
  for port in "$DASHBOARD_PORT" "$PROMETHEUS_PORT" "$PROXY_PORT" "$AGENT_DASHBOARD_PORT"; do
    [[ "$port" =~ ^[0-9]+$ ]] || die "'$port' isn't a port number"
  done
  check_tools
}

# The proxy and agent of the release the docs give, unless it predates what the demo shows or isn't on Docker Hub yet.
cmd_up() {
  local release image answer
  check_up
  release="$(latest_release)" || die "can't tell the latest release from the docs (make site-version)"
  if ! version_after "$release" "$NEEDS_RELEASE_AFTER"; then
    wrap "" "" "The latest release, $release, predates this demo, so it runs the proxy and agent built from this" \
      "checkout, which takes a JDK 17 or later."
    start_demo local
    return
  fi
  # A cluster that can't pull an image only says so as its pod waits, so check that both are there first. Docker Hub's
  # answer that it has no such image means the release isn't out yet, as from a version bump until its release; the
  # images in this Docker don't count, since each cluster pulls its own.
  for image in "${RELEASED_IMAGES[@]}"; do
    answer="$(docker manifest inspect "$image:$release" 2>&1 >/dev/null)" && continue
    if [[ "$answer" == *"no such manifest"* ]]; then
      wrap "" "" "Release $release, which the docs give, isn't on Docker Hub yet, so the demo runs the proxy and" \
        "agent built from this checkout, which takes a JDK 17 or later."
      start_demo local
      return
    fi
    die "can't find $image:$release on Docker Hub; check the network, or run '$MAKE_NAME up-local' to build the" \
      "images from this checkout"
  done
  bold "Installing the demo with the proxy and agent of release $release, from Docker Hub"
  # The charts' own images, at the release rather than the chart's appVersion, which is the same once it's bumped.
  PROXY_IMAGE_VALUES=(--set-string "image.tag=$release")
  AGENT_IMAGE_VALUES=(--set-string "image.tag=$release")
  start_demo
}

cmd_up_local() {
  check_up
  start_demo local
}

# Creates the clusters and installs the demo, or brings a running one up to date, with the images up chose
# (start_demo) or with ones built from this checkout (start_demo local).
start_demo() {
  local name built="${1:-}"
  if [[ "$built" == local ]]; then
    build_images
    # Loaded into the clusters, so the nodes never pull them, with an annotation naming each image's ID, so a rebuilt
    # image replaces its pod.
    PROXY_IMAGE_VALUES=(--set "image.repository=${PROXY_IMAGE%:*}" --set-string "image.tag=${PROXY_IMAGE#*:}"
      --set image.pullPolicy=Never --set-string "podAnnotations.prometheus-proxy-demo/image=$(image_id "$PROXY_IMAGE")")
    AGENT_IMAGE_VALUES=(--set "image.repository=${AGENT_IMAGE%:*}" --set-string "image.tag=${AGENT_IMAGE#*:}"
      --set image.pullPolicy=Never --set-string "podAnnotations.prometheus-proxy-demo/image=$(image_id "$AGENT_IMAGE")")
  fi

  for name in "$MONITORING" "$TARGET"; do
    cluster_exists "$name" || create_cluster "$name"
  done
  check_ports
  if [[ "$built" == local ]]; then
    # Each image goes only to the cluster that runs it. kind skips an image whose ID the node already has, but with
    # Docker's containerd image store the IDs never match, so every up loads both again, which takes a few seconds.
    kind load docker-image "$PROXY_IMAGE" --name "$MONITORING"
    kind load docker-image "$AGENT_IMAGE" --name "$TARGET"
  fi

  # Each cluster's own objects first: their namespaces hold the token's Secrets. Prometheus and targets-sync wait for
  # the ConfigMaps install_monitoring and install_target create.
  "${MK[@]}" apply -f "$DEMO_DIR/k8s/monitoring.yaml" >/dev/null
  "${TK[@]}" apply -f "$DEMO_DIR/k8s/target.yaml" >/dev/null
  ensure_token
  install_monitoring
  install_target

  wait_for "$SCREENS_URL" "The proxy's screens page"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  local deadline=$((SECONDS + 120)) service
  for service in "${SERVICES[@]}"; do
    until discovers "${service}_metrics"; do
      ((SECONDS < deadline)) || die "${service}_metrics isn't in the proxy's /discovery after 120s; see '$0 logs'"
      sleep 2
    done
  done
  print_urls
}

cmd_down() {
  local name
  for name in "$MONITORING" "$TARGET"; do
    if cluster_exists "$name"; then
      kind delete cluster --name "$name"
    fi
  done
}

cmd_status() {
  local name
  for name in "$MONITORING" "$TARGET"; do
    cluster_exists "$name" || echo "$name: not created; '$MAKE_NAME up' creates it"
  done
  if cluster_exists "$MONITORING"; then
    echo
    bold "$MONITORING"
    "${MK[@]}" -n monitoring get pods,services
    "${MH[@]}" list
  fi
  if cluster_exists "$TARGET"; then
    echo
    bold "$TARGET"
    "${TK[@]}" -n monitoring get pods,services
    "${TK[@]}" -n apps get pods,services
    "${TH[@]}" list
  fi
  print_urls
}

cmd_open() {
  if command -v open >/dev/null 2>&1; then
    open "$SCREENS_HOME_URL"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$SCREENS_HOME_URL"
  else
    print_urls
  fi
}

cmd_logs() {
  local name="${1:-agent}"
  case "$name" in
  proxy) "${MK[@]}" -n monitoring logs --follow --tail 50 deployment/prometheus-proxy ;;
  prometheus) "${MK[@]}" -n monitoring logs --follow --tail 50 deployment/prometheus ;;
  agent) "${TK[@]}" -n monitoring logs --follow --tail 50 deployment/prometheus-agent ;;
  targets-sync) "${TK[@]}" -n monitoring logs --follow --tail 50 deployment/targets-sync ;;
  orders | payments | shipping | search) "${TK[@]}" -n apps logs --follow --tail 50 "deployment/$name" ;;
  *) die "unknown workload '$name'; expected agent, proxy, prometheus, targets-sync, or a service" ;;
  esac
}

# Forwards a host port to the agent's dashboard, in the foreground, as the agent chart's notes tell an operator to:
# pp-target publishes no port, and the chart leaves the dashboard off the agent's Service. kubectl port-forward goes
# through the API server to one pod, so once that pod is replaced it fails at the next request, though it goes on
# listening until then.
cmd_agent_dashboard() {
  local port
  [[ "$AGENT_DASHBOARD_PORT" =~ ^[0-9]+$ ]] || die "'$AGENT_DASHBOARD_PORT' isn't a port number"
  cluster_exists "$TARGET" || die "the demo isn't running; start it with '$MAKE_NAME up' in demos/kubernetes"
  # The dashboard's container port, which the chart adds, named dashboard, only with dashboard.enabled on.
  port="$("${TK[@]}" -n monitoring get deployment prometheus-agent \
    -o jsonpath='{.spec.template.spec.containers[0].ports[?(@.name=="dashboard")].containerPort}')" ||
    die "the agent isn't installed in $TARGET; start the demo with '$MAKE_NAME up'"
  [[ -n "$port" ]] || die "the agent's dashboard is off; '$MAKE_NAME up' installs the agent with it on"
  bold "Forwarding localhost:$AGENT_DASHBOARD_PORT to port $port, the agent's dashboard, in its pod in $TARGET"
  wrap "  " "  " "Open the agent's dashboard in the screens page, whose agent tab turns green once this runs:" \
    "${SCREENS_URL}#agent"
  wrap "  " "  " "This runs until you press Ctrl-C. It reaches one pod, so once the agent's pod is replaced, as" \
    "delete-agent-pod and change-config do, it fails at the next request. Then run $0 agent-dashboard again, to" \
    "reach the new pod."
  if ! show kubectl --context "kind-$TARGET" -n monitoring port-forward deploy/prometheus-agent \
    "$AGENT_DASHBOARD_PORT:$port"; then
    echo
    wrap "" "" "The port-forward ended. If the agent's pod was replaced, run $0 agent-dashboard again to reach the" \
      "new one; if port $AGENT_DASHBOARD_PORT is taken, set KUBERNETES_DEMO_AGENT_DASHBOARD_PORT to another."
    exit 1
  fi
}

# Prints a command, then runs it, so the audience sees what was asked of kubectl or helm.
show() {
  printf '\033[36m$ %s\033[0m\n' "$*"
  "$@"
}

cmd_clusters() {
  bold "$TARGET: every Service is ClusterIP, reachable only inside the cluster; kind maps no port of it to the host" \
    "but its API server's, for kubectl"
  show kubectl --context "kind-$TARGET" get services --all-namespaces
  echo
  bold "Each cluster's Helm release: the proxy chart outside the firewall, the agent chart behind it"
  show helm --kube-context "kind-$MONITORING" list --all-namespaces
  show helm --kube-context "kind-$TARGET" list --all-namespaces
  echo
  bold "Prometheus's scrape config: one job, which finds every target through the proxy"
  "${MK[@]}" -n monitoring get configmap prometheus-config -o go-template='{{index .data "prometheus.yml"}}' |
    sed -n '/^scrape_configs:/,$p' | grep -v '^ *#'
  echo
  how_it_works "This only reads, and changes nothing. It runs kubectl get services across every namespace of" \
    "$TARGET, and helm list across every namespace of each cluster, each naming its cluster's context. Then it" \
    "prints the scrape_configs section of Prometheus's config, without its comments, from the prometheus-config" \
    "ConfigMap in $MONITORING, which Prometheus mounts."
  watch_for
  point "Every Service in $TARGET is ClusterIP, so nothing in it can be reached from outside the cluster. The" \
    "agent opened its connection from $TARGET to the proxy's NodePort in $MONITORING; nothing connected in."
  point "Each cluster runs one of the charts the project ships, given a values file and a config file, and the" \
    "agent's chart the proxy's address."
  point "Prometheus's one job asks the proxy's /discovery what to scrape, so its config names no target in $TARGET."
  point "On the proxy dashboard the agent is listed under its pod's name, $(agent_pod): ${SCREENS_URL}#proxy"
  point "The agent's Service lists only its metrics port, 8083. Its own dashboard, on 8095 in its pod, is on no" \
    "Service, so only kubectl reaches it: $0 agent-dashboard forwards it to localhost:$AGENT_DASHBOARD_PORT, where" \
    "the screens page's agent tab finds it."
}

# Deploys search, then creates its annotated Service once its pod is ready, as a team would deploy an app and then
# expose it: the Service is what targets-sync acts on, and created with the pod still starting, its first scrapes
# would time out. The Service has no labels of its own, so the first apply's selector leaves it out.
cmd_add_service() {
  local start deadline agent
  if "${TK[@]}" -n apps get service search >/dev/null 2>&1; then
    echo "  search is deployed already; $0 remove-service removes it."
    return
  fi
  agent="$(agent_pod)"
  "${TK[@]}" apply -f "$DEMO_DIR/k8s/search.yaml" -l app.kubernetes.io/name=search >/dev/null
  "${TK[@]}" -n apps rollout status deployment/search --timeout 120s >/dev/null
  start=$SECONDS
  (cd "$DEMO_DIR" && show kubectl --context "kind-$TARGET" apply -f k8s/search.yaml)
  bold "search's Service is annotated prometheus-proxy/path: search_metrics"
  deadline=$((SECONDS + 120))
  until discovers search_metrics; do
    ((SECONDS < deadline)) || die "search_metrics isn't in the proxy's /discovery after 120s; see '$0 logs targets-sync'"
    sleep 0.5
  done
  echo "  search_metrics is in the proxy's /discovery $((SECONDS - start))s after the Service was created."
  echo "  The agent is still $agent, restarted $(restarts "$agent") times: nothing restarted."
  how_it_works "k8s/search.yaml holds a Deployment and a ClusterIP Service for a search service in the apps" \
    "namespace, the Service annotated prometheus-proxy/path: search_metrics. This creates the Deployment first and" \
    "waits for it, then the Service, so the time above is the Service's alone. targets-sync, a small controller this" \
    "demo runs in the $TARGET cluster, watches the Services in apps and writes each annotated one into the agent's" \
    "targets ConfigMap, agent-targets. The kubelet copies a changed ConfigMap into the pods that mount it every" \
    "syncFrequency, a minute by default, which this demo lowers to 5 seconds. The agent re-reads the file every 5" \
    "seconds too, and registers each new path, so the two waits make the time above up to about 10 seconds."
  watch_for
  point "On the proxy dashboard's Paths view, search_metrics appears with source 'disc'. The agent's pod is the" \
    "same one, with no restarts, and no other path missed a scrape: ${SCREENS_URL}#proxy/paths"
  point "On the agent's dashboard, with $0 agent-dashboard running, search_metrics joins the Paths view," \
    "discovered and registered, the Connection view counts four discovery entries, and the Activity view adds a" \
    "registered line for it: ${SCREENS_URL}#agent"
  point "Prometheus finds search_metrics at a discovery refresh, every 5 seconds, and scrapes it through the proxy," \
    "up to about 10 seconds after the path reached /discovery. Open its targets page to see it:" \
    "${SCREENS_URL}#prometheus/targets"
  follow_up "To undo it, run" "$0 remove-service"
}

# Removes search's annotation first, and deletes search once the agent has let its path go, as a team would retire an
# app: deleted with the path still registered, its scrapes would fail until the agent heard.
cmd_remove_service() {
  local start deadline
  if ! "${TK[@]}" -n apps get service search >/dev/null 2>&1; then
    "${TK[@]}" delete -f "$DEMO_DIR/k8s/search.yaml" --ignore-not-found >/dev/null
    echo "  search isn't deployed."
    return
  fi
  start=$SECONDS
  show kubectl --context "kind-$TARGET" -n apps annotate service search prometheus-proxy/path-
  deadline=$((SECONDS + 120))
  while discovers search_metrics; do
    ((SECONDS < deadline)) || die "search_metrics is still in the proxy's /discovery after 120s"
    sleep 0.5
  done
  echo "  search_metrics left the proxy's /discovery $((SECONDS - start))s after the annotation was removed."
  "${TK[@]}" delete -f "$DEMO_DIR/k8s/search.yaml" --ignore-not-found >/dev/null
  bold "search deleted from $TARGET"
  how_it_works "This removes the prometheus-proxy/path annotation from search's Service with kubectl annotate." \
    "targets-sync drops the Service from agent-targets, the kubelet copies the change into the agent's pod, and the" \
    "agent unregisters the path. Only once the path has left the proxy's /discovery does this delete search's" \
    "Deployment and Service: deleting a Service whose path is still registered would fail its scrapes until the" \
    "agent heard."
  watch_for
  point "The agent unregistered the path, again without restarting, and Prometheus drops the target at its next" \
    "discovery refresh."
  point "Prometheus's last scrape of search fails. The agent lets the path go before Prometheus's next discovery" \
    "refresh drops the target, so the scrape that lands in between gets a 404 from the proxy, and" \
    "up{job=\"search\"} ends at 0. The proxy logs it as WARN Invalid path request /search_metrics, which" \
    "$0 logs proxy shows."
  point "On the agent's dashboard, search_metrics leaves the Paths view, and the Activity view adds an unregistered" \
    "line for it."
}

cmd_delete_agent_pod() {
  local old start
  old="$(agent_pod)"
  [[ -n "$old" ]] || die "the agent has no pod; see '$0 status'"
  start=$SECONDS
  show kubectl --context "kind-$TARGET" -n monitoring delete pod "$old" --wait=false
  watch_replacement "$old" "$start"
  how_it_works "This deletes the agent's pod with kubectl delete pod. The agent runs in a Deployment, from the" \
    "prometheus-agent chart, so Kubernetes starts a replacement pod as soon as the old one goes. Meanwhile this" \
    "scrapes /orders_metrics through the proxy every half second, which is how it measures the gap above."
  watch_for
  point "The new pod's agent connected out to the proxy and registered every path again, under its own pod's name," \
    "and the old agent left the dashboard as it shut down."
  point "A Prometheus scrape that landed in the gap measured above failed, and the next one succeeded."
  point "In Prometheus, each target's agent label is the pod's name, so the new pod starts new series, as a pod" \
    "label would."
  point "If $0 agent-dashboard was running, it fails at the next request, with error: lost connection to pod," \
    "since a port-forward reaches one pod, the old one, and the screens page's agent tab turns red. Run it again:" \
    "the tab reloads, and the new pod's Activity view starts with its own connect and registrations, since each" \
    "agent keeps its activity in memory, while the proxy dashboard's Activity view saw both pods."
}

cmd_change_config() {
  local config
  config="$(mktemp)"
  cat "$DEMO_DIR/config/agent.conf" "$DEMO_DIR/config/agent-change.conf" >"$config"
  bold "Upgrading the prometheus-agent chart with config/agent-change.conf added to its main config"
  if switch_agent_config "$config" "<(cat config/agent.conf config/agent-change.conf)"; then
    how_it_works "This runs helm upgrade on the prometheus-agent chart with config/agent.conf followed by" \
      "config/agent-change.conf as the agent's config. In HOCON a later setting overrides an earlier one, and" \
      "agent-change.conf turns on the agent's JVM metrics. The chart stamps the agent's pod with a checksum of its" \
      "config, so the new config changes the pod's template, and the Deployment replaces the pod. The chart's" \
      "strategy is Recreate, which stops the old pod before starting the new one, since both would register the" \
      "same paths."
    watch_for
    point "Scrapes failed for the gap measured above, between the old pod stopping and the new one registering its" \
      "paths."
    point "The new pod's agent_metrics carries the JVM's metrics. In Prometheus," \
      "count({job=\"prometheus-agent\", __name__=~\"jvm_.*|process_.*\"}) found no series before, and finds about" \
      "80 within about 10 seconds, at Prometheus's next scrape of the new pod."
    point "Compare part 3, where the targets changed and nothing restarted. That's why the agent chart keeps its" \
      "targets in a ConfigMap of their own, which the agent re-reads in place."
    point "If $0 agent-dashboard was running, it fails at the next request, since it reached the old pod; run it" \
      "again. The new pod's dashboard is on too, since --reuse-values keeps the chart's dashboard.enabled from" \
      "values/agent.yaml."
    follow_up "To undo it, run" "$0 restore-config"
  fi
  rm -f "$config"
}

cmd_restore_config() {
  bold "Upgrading the prometheus-agent chart with config/agent.conf alone"
  if switch_agent_config "$DEMO_DIR/config/agent.conf" config/agent.conf; then
    how_it_works "This runs the same helm upgrade with config/agent.conf alone, so the config's checksum changes" \
      "back, and the Deployment replaces the agent's pod again."
    watch_for
    point "Scrapes failed again for the gap measured above: putting a config back costs a restart, as changing it" \
      "did."
    point "The new pod's agent_metrics carries no JVM metrics. In Prometheus," \
      "count({job=\"prometheus-agent\", __name__=~\"jvm_.*|process_.*\"}) finds no series again, once the old pod's" \
      "target has gone, a few seconds after the replacement."
    point "If $0 agent-dashboard was running, it fails at the next request once more; run it again to reach the" \
      "new pod."
  fi
}

# The tour: the presenter's script (README.md), one part at a time. It numbers each part's steps, and waits for Enter
# after each one, and before it runs a scenario.
TOUR_FROM=1
TOUR_STEP=0
# Whether the last thing the tour printed was a pause, which leaves an empty line of its own.
AFTER_PAUSE=0

# Waits for Enter, then leaves an empty line. At the end of input, as when the tour is piped, it doesn't wait.
pause() {
  read -r -p "$(printf '\033[33m> %s\033[0m ' "$1")" _ || true
  echo
  AFTER_PAUSE=1
}

# Starts a new block of the tour's output with an empty line, unless a pause has just left one.
blank_line() {
  ((AFTER_PAUSE)) || echo
  AFTER_PAUSE=0
}

# Waits for Enter before a new part, or the tour's end, when a scenario's output came last, so it isn't printed
# straight after that output: wait_after_output <prompt>
wait_after_output() {
  if ((!AFTER_PAUSE)); then
    echo
    pause "$1"
  fi
}

# Lists the parts from the one the tour starts at, with about how long each takes and in all, then waits:
# tour_contents <the demo's name>
tour_contents() {
  local n entry total=0
  for ((n = TOUR_FROM; n <= ${#TOUR_PARTS[@]}; n++)); do
    entry="${TOUR_PARTS[n - 1]}"
    total=$((total + ${entry%% *}))
  done
  echo
  if ((TOUR_FROM == 1)); then
    bold "A tour of the $1: ${#TOUR_PARTS[@]} parts, about $total minutes"
  else
    bold "A tour of the $1, from part $TOUR_FROM of ${#TOUR_PARTS[@]}: about $total minutes"
  fi
  for ((n = TOUR_FROM; n <= ${#TOUR_PARTS[@]}; n++)); do
    entry="${TOUR_PARTS[n - 1]}"
    printf '  %2d. %s (%s min)\n' "$n" "${entry#* }" "${entry%% *}"
  done
  echo
  wrap "  " "  " "Each step waits for you to press Enter, so you set the pace. To start from another part, run" \
    "$MAKE_NAME tour PART=<n>, or $0 tour <n>."
  wrap "  " "  " "Demo overview: ${PAGE_URL}#what-this-demo-shows"
  pause "Press Enter to start"
}

# Starts part [n], unless the tour began after it: prints its title, from TOUR_PARTS, and what it shows. Fails for a
# skipped part. part <n> <what it shows>...
part() {
  local n="$1"
  shift
  ((n >= TOUR_FROM)) || return 1
  wait_after_output "Press Enter to go on to part $n"
  TOUR_STEP=0
  blank_line
  bold "Part $n of ${#TOUR_PARTS[@]}: ${TOUR_PARTS[n - 1]#* }"
  wrap "  " "  " "$*"
}

# Prints the part's next step, numbered: what to do, then what it shows, its words joined.
# number_step <what to do> [<what it shows>...]
number_step() {
  local indent
  TOUR_STEP=$((TOUR_STEP + 1))
  indent="$(printf '%*s' $((${#TOUR_STEP} + 4)) '')"
  blank_line
  wrap "  $TOUR_STEP. " "$indent" "$1"
  shift
  (($# == 0)) || wrap "$indent" "$indent" "$*"
}

# A step the presenter takes, such as opening a page, then waits: instruct <what to do> [<what it shows>...]
instruct() {
  number_step "$@"
  pause "Press Enter to continue"
}

# A step that runs a scenario once the presenter presses Enter: step <what it does>... -- <command> [argument]. What
# it does is joined into one paragraph. It runs as ./demo.sh from this directory, so the commands it prints read
# ./demo.sh too, and its stdin is closed, so kubectl can't take the tour's input.
step() {
  local -a text=()
  while [[ "$1" != -- ]]; do
    text+=("$1")
    shift
  done
  shift
  number_step "${text[*]}"
  pause "Press Enter to run ./demo.sh $*"
  (cd "$DEMO_DIR" && ./demo.sh "$@" </dev/null)
  AFTER_PAUSE=0
}

# Puts back what the tour changed, once the presenter presses Enter, so what follows starts as the demo did. It shows
# nothing new, so it has no number, and prints only each command's headlines, its bold lines, as they come; a command
# that fails prints all it said, and ends the tour. put_back <what it puts back> <command>...
put_back() {
  local what="$1" cmd log
  shift
  blank_line
  pause "Press Enter to $what: ./demo.sh $(join_commands "$@")"
  log="$(mktemp)"
  for cmd in "$@"; do
    if ! (cd "$DEMO_DIR" && ./demo.sh "$cmd" </dev/null 2>&1) | tee "$log" |
      { grep --line-buffered $'^\033\\[1m' || true; }; then
      cat "$log"
      rm -f "$log"
      die "./demo.sh $cmd failed, which ends the tour"
    fi
  done
  rm -f "$log"
  echo
  AFTER_PAUSE=1
}

# The commands put_back runs, as it prints them: join_commands <command>...
join_commands() {
  local first="$1"
  shift
  printf '%s' "$first"
  (($# == 0)) || printf ', then ./demo.sh %s' "$@"
}

# Checks the part the tour starts from: check_tour_from <argument>
check_tour_from() {
  TOUR_FROM="${1:-1}"
  if ! [[ "$TOUR_FROM" =~ ^[0-9]+$ ]] || ((TOUR_FROM < 1 || TOUR_FROM > ${#TOUR_PARTS[@]})); then
    die "the part to start from is a number from 1 to ${#TOUR_PARTS[@]}"
  fi
}

# Each part's title, after about how many minutes it takes to show.
TOUR_PARTS=(
  "3 Two clusters, no inbound access"
  "3 The agent's own dashboard"
  "3 Annotate a Service, and Prometheus scrapes it"
  "3 Kubernetes replaces a deleted agent pod"
  "3 A config change and a targets change"
)

# The tour's last words: how to start it again, the other demos, in the order the docs site's Demos page gives them,
# and how to stop this one. tour_ending <this demo's directory under demos/>
tour_ending() {
  local demo
  bold "That's the tour."
  wrap "" "" "To start again from a part, run $MAKE_NAME tour PART=<n>, or $0 tour <n>."
  echo
  bold "The other demos:"
  for demo in basics network nginx kubernetes ktor spring-boot plain-kotlin; do
    [[ "$demo" != "$1" ]] || continue
    case "$demo" in
    basics)
      wrap "  - " "    " "Standalone agents behind a simulated firewall, with the proxy's dashboard, discovery," \
        "eviction, and an identity for each agent, are in the basics demo, in demos/basics."
      ;;
    network)
      wrap "  - " "    " "Proxy failover, filtering at the agent, and tracing, over a simulated WAN, are in the" \
        "network demo, in demos/network."
      ;;
    nginx)
      wrap "  - " "    " "The proxy behind nginx, a reverse proxy that ends the agents' TLS, is in the nginx demo," \
        "in demos/nginx."
      ;;
    kubernetes)
      wrap "  - " "    " "The proxy and agent deployed on Kubernetes with their Helm charts are in the Kubernetes" \
        "demo, in demos/kubernetes, which needs kind, kubectl, and Helm as well as Docker."
      ;;
    ktor)
      wrap "  - " "    " "The agent embedded in a Ktor app through the Ktor plugin, under load, with alerts and a" \
        "deploy, is in the Ktor demo, in demos/ktor."
      ;;
    spring-boot)
      wrap "  - " "    " "The agent embedded in a Spring Boot app written in Java, through the Spring Boot starter," \
        "is in the Spring Boot demo, in demos/spring-boot."
      ;;
    plain-kotlin)
      wrap "  - " "    " "The agent embedded in plain Kotlin programs with no framework or HTTP server, scaled out" \
        "and in, is in the plain Kotlin demo, in demos/plain-kotlin."
      ;;
    esac
  done
  echo
  if [[ "$1" == kubernetes ]]; then
    wrap "" "" "This demo's two kind clusters take a few GB of memory. To delete them and free it, run" \
      "$MAKE_NAME down, or $0 down."
  else
    wrap "" "" "The demos use different ports, so they can run side by side, but each takes memory and CPU." \
      "To stop this one and free its resources, run $MAKE_NAME down, or $0 down."
  fi
}

cmd_tour() {
  check_tour_from "${1:-}"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/kubernetes"
  tour_contents "Kubernetes demo"

  if part 1 "This demo runs the other demos' proxy and agent on Kubernetes, installed with the project's Helm" \
    "charts, on two kind clusters. $MONITORING, outside the firewall, runs the proxy, its dashboard, and" \
    "Prometheus. $TARGET, behind the firewall, runs the services and the agent, and exposes nothing."; then
    step "List what runs in each cluster: $TARGET's Services, each cluster's Helm release, and Prometheus's" \
      "scrape config." -- clusters
    instruct "Open the demo's screens, on the proxy dashboard: ${SCREENS_URL}#proxy" \
      "This is the proxy's screens page, one browser tab that holds every screen in the demo: the proxy's" \
      "dashboard, the agent's, whose tab stays red until part 2 forwards it, and Prometheus. The steps from here" \
      "link into this page, so keep it open, and each link opens in it. One agent is connected, named for its pod:" \
      "the same name kubectl shows. It has four paths: its own metrics, with source 'cfg', from its config, and the" \
      "three services, with source 'disc', from discovery."
    instruct "Open Prometheus's targets page: ${SCREENS_URL}#prometheus/targets" \
      "All four targets are up, each scraped through the proxy."
  fi

  if part 2 "The proxy dashboard shows what the proxy sees. The agent's own dashboard shows its side of the" \
    "firewall: its connection, each path's registration and recent scrapes, and its discovery. $TARGET exposes" \
    "nothing, and the agent chart leaves the dashboard off the agent's Service, so here you reach it as the chart's" \
    "notes tell an operator to, with kubectl port-forward, and keep it open for the parts that follow."; then
    instruct "In a second terminal, in demos/kubernetes, run ./demo.sh agent-dashboard" \
      "It runs kubectl port-forward, which carries localhost:$AGENT_DASHBOARD_PORT through $TARGET's API server to" \
      "port 8095 in the agent's pod, and listens on localhost only. Nothing new is exposed: only someone kubectl" \
      "lets into $TARGET can reach the dashboard. values/agent.yaml turns it on, with the chart's dashboard.enabled."
    instruct "Open the agent's dashboard: ${SCREENS_URL}#agent" \
      "Its tab in the screens page turned green once the forward began. The Paths view lists the agent's four" \
      "paths, each registered with the proxy: agent_metrics, static, from its config, and the three services," \
      "discovered, with the cluster-local URLs the agent scrapes. The dots beside each are its recent scrapes, one" \
      "for each scrape Prometheus sent through the proxy, green for a success."
    instruct "Select /orders_metrics, then press Scrape now." \
      "Recent scrapes lists the path's last scrapes as the agent saw them: when each ran, the target's status, how" \
      "long the fetch took, and the body's size. Scrape now has the agent fetch the target at once, and shows the" \
      "result above the list: its status, its metric families and samples, and the start of the body. The agent" \
      "fetched it from inside $TARGET, the only place its address resolves:" \
      "http://orders.apps.svc.cluster.local:8000/metrics"
    instruct "Open the Connection view: ${SCREENS_URL}#agent/connection" \
      "The agent is connected to the proxy's NodePort on $MONITORING's node, under its pod's name. Under Discovery" \
      "is the file the chart mounts from the agent-targets ConfigMap, /app/discovery/targets.conf, read every 5" \
      "seconds, with three entries and its last read ok. The Settings view, the last tab, shows what the agent" \
      "resolved from config/agent.conf, such as its 3-second scrape timeout: ${SCREENS_URL}#agent/settings"
    instruct "Open the Activity view: ${SCREENS_URL}#agent/activity" \
      "It lists what the agent did since its pod started, newest first. From the bottom: it connected to the proxy," \
      "then registered its static path, and its three discovered ones on a line of their own. Each line is in the" \
      "agent's log too, but the log stays behind the firewall and scrolls away. The agent keeps the list in" \
      "memory, so each new pod starts its own."
  fi

  if part 3 "targets-sync, a small controller in this demo, watches the Services in the apps namespace and writes" \
    "each one annotated prometheus-proxy/path into the agent's targets ConfigMap, which the agent re-reads." \
    "Here you deploy a new service, and see it scraped without restarting anything."; then
    step "Deploy the search service, then create its Service, annotated prometheus-proxy/path: search_metrics." \
      "Put the proxy dashboard's Paths view beside Prometheus's targets before you press Enter, to watch the path" \
      "reach one, then the other: ${SCREENS_URL}#proxy/paths+prometheus/targets" -- add-service
    step "Once you've seen search_metrics on both dashboards and in Prometheus, remove the annotation, then" \
      "delete search once its path has gone." -- remove-service
  fi

  if part 4 "Here you delete the agent's pod, as a failed node or an eviction would, and see Kubernetes replace" \
    "it with nobody touching the agent."; then
    step "Delete the agent's pod." -- delete-agent-pod
    instruct "Open the proxy dashboard's Activity view: ${SCREENS_URL}#proxy/activity" \
      "It shows the old pod's paths leaving and the new pod's arriving."
    instruct "Run ./demo.sh agent-dashboard again, and open the agent's Activity view beside the proxy's." \
      "The port-forward in the second terminal reached the old pod, so it fails at the next request after the" \
      "replacement, with error: lost connection to pod, and the agent's tab turns red; if it's still running, press" \
      "Ctrl-C first. Once the new one runs, the tab reloads, and the new pod's Activity view lists only its own" \
      "start: it connected, and registered every path. The proxy saw both pods, but each agent sees only its own:" \
      "${SCREENS_URL}#proxy/activity+agent/activity"
  fi

  if part 5 "The agent chart keeps the agent's targets apart from its main config. Part 3 changed the targets," \
    "and nothing restarted. Here you change the main config, which only a restart applies."; then
    step "Upgrade the agent chart with a main config that also turns on the agent's JVM metrics." -- change-config
    put_back "upgrade the agent chart with its original config again, which replaces the pod once more" \
      restore-config
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending kubernetes
}

main() {
  local cmd="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
  up) cmd_up ;;
  up-local) cmd_up_local ;;
  down) cmd_down ;;
  status) cmd_status ;;
  open) cmd_open ;;
  logs) cmd_logs "$@" ;;
  agent-dashboard) cmd_agent_dashboard ;;
  tour) cmd_tour "$@" ;;
  clusters) cmd_clusters ;;
  add-service) cmd_add_service ;;
  remove-service) cmd_remove_service ;;
  delete-agent-pod) cmd_delete_agent_pod ;;
  change-config) cmd_change_config ;;
  restore-config) cmd_restore_config ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
