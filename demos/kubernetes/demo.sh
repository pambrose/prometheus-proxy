#!/usr/bin/env bash
# Drives the Prometheus Proxy Kubernetes demo (see README.md): creates and deletes its two kind clusters, installs the
# proxy and agent charts in them, and runs the scenarios a presenter shows. The Makefile beside it wraps the lifecycle
# commands (`make up` builds the jars first, then runs `demo.sh up`). Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"

# The two clusters: pp-monitoring, outside the firewall, runs the proxy, its dashboard, and Prometheus; pp-target,
# behind it, runs the services, the agent, and targets-sync. kind names each one's kubectl context kind-<name>.
MONITORING=pp-monitoring
TARGET=pp-target
MK=(kubectl --context "kind-$MONITORING")
TK=(kubectl --context "kind-$TARGET")
# The proxy and the agent are each in their cluster's monitoring namespace; the services are in pp-target's apps.
MH=(helm --kube-context "kind-$MONITORING" --namespace monitoring)
TH=(helm --kube-context "kind-$TARGET" --namespace monitoring)

# Built from this checkout's jars, as the other demos build them, so they share the images.
PROXY_IMAGE=prometheus-proxy-demo/proxy:local
AGENT_IMAGE=prometheus-proxy-demo/agent:local
# The kind release the node image in kind/*.yaml was built for.
MIN_KIND_VERSION=0.33.0
# The services `make up` deploys (k8s/services.yaml); act 2 adds search (k8s/search.yaml).
SERVICES=(orders payments shipping)

# The host ports kind publishes pp-monitoring's NodePorts on (kind/pp-monitoring.yaml), read as it creates the cluster.
DASHBOARD_PORT="${KUBERNETES_DEMO_DASHBOARD_PORT:-8294}"
PROMETHEUS_PORT="${KUBERNETES_DEMO_PROMETHEUS_PORT:-9290}"
PROXY_PORT="${KUBERNETES_DEMO_PROXY_PORT:-8280}"
DASHBOARD_URL="http://localhost:$DASHBOARD_PORT/dashboard"
PROMETHEUS_URL="http://localhost:$PROMETHEUS_PORT"
PROXY_URL="http://localhost:$PROXY_PORT"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
hint() { printf '\033[36mWatch:\033[0m %s\n' "$*"; }
die() {
  echo "Error: $*" >&2
  exit 1
}

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [argument]

Lifecycle:
  up                       Build the images from this checkout's jars, create both kind clusters, and install the demo
  down                     Delete both kind clusters
  status                   Show both clusters' pods and Services, the Helm releases, and the URLs
  open                     Open the proxy dashboard and Prometheus's targets in the browser
  logs [name]              Follow a workload's logs: agent (the default), proxy, prometheus, targets-sync, or a service
  tour [act]               Walk through the presenter's script, pausing before each step (from act 1, or the one given)

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
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo "  kubectl:          kubectl --context kind-$MONITORING ...   kubectl --context kind-$TARGET ..."
  echo
  echo "Scenarios: $0 clusters | add-service | delete-agent-pod | change-config ...  (no arguments for help)"
  echo "Or walk through them all: $0 tour"
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

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not on the PATH; $2"; }

check_tools() {
  local kind_version helm_version
  need docker "the demo needs Docker"
  docker info >/dev/null 2>&1 || die "Docker isn't running"
  docker buildx version >/dev/null 2>&1 || die "docker buildx is missing; the demo builds its images with it"
  need kind "install kind $MIN_KIND_VERSION or later (https://kind.sigs.k8s.io/)"
  need kubectl "install kubectl (https://kubernetes.io/docs/tasks/tools/)"
  need helm "install Helm 3 or 4 (https://helm.sh/docs/intro/install/)"
  kind_version="$(kind version | sed -nE 's/^kind v([0-9]+\.[0-9]+\.[0-9]+).*/\1/p')"
  [[ -n "$kind_version" ]] || die "can't read kind's version from '$(kind version)'"
  version_at_least "$kind_version" "$MIN_KIND_VERSION" ||
    die "kind $kind_version is older than $MIN_KIND_VERSION, the release the demo's node image is built for"
  helm_version="$(helm version --template '{{.Version}}' 2>/dev/null || true)"
  [[ "$helm_version" =~ ^v[34]\. ]] || die "the demo needs Helm 3 or 4, not '$helm_version'"
}

cluster_exists() { kind get clusters 2>/dev/null | grep -qx "$1"; }

# The host port a pp-monitoring NodePort is published on, from the node container's port mappings, or nothing.
published_port() {
  docker port "$MONITORING-control-plane" "$1/tcp" 2>/dev/null | sed -nE 's/^127\.0\.0\.1:([0-9]+)$/\1/p' | head -1
}

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
    die "kind couldn't create $name; if a port is taken, move it (see README.md) and run 'make down' first"
  fi
  rm -f "$rendered"
  if [[ -n "$previous" ]]; then
    kubectl config use-context "$previous" >/dev/null
  else
    kubectl config unset current-context >/dev/null
  fi
}

# A running pp-monitoring keeps the ports it was created with; say so, rather than print URLs that don't answer.
check_ports() {
  local port want node_port
  for node_port in 30094:"$DASHBOARD_PORT" 30090:"$PROMETHEUS_PORT" 30080:"$PROXY_PORT"; do
    want="${node_port#*:}"
    port="$(published_port "${node_port%%:*}")"
    [[ "$port" == "$want" ]] ||
      die "$MONITORING publishes port ${port:-none} where $want was asked for; 'make down' first to move it"
  done
}

# The image ID behind a tag, or nothing.
image_id() { docker image inspect -f '{{.Id}}' "$1" 2>/dev/null || true; }

build_images() {
  local old_ids id
  # The jars change on every build (their BuildConfig carries the build time), so each build makes new images and
  # leaves the previous ones untagged; remember them to remove afterwards. One another demo still runs is in use, and
  # stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "$PROXY_IMAGE" "$AGENT_IMAGE" 2>/dev/null || true)"
  bold "Building $PROXY_IMAGE and $AGENT_IMAGE from this checkout's jars"
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
    ((SECONDS < deadline)) || die "$name did not answer at $url within 120s; see '$0 status'"
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
    -f "$DEMO_DIR/values/proxy.yaml" --set-file "config=$DEMO_DIR/config/proxy.conf" \
    --set-string "podAnnotations.prometheus-proxy-demo/image=$(image_id "$PROXY_IMAGE")" \
    --wait --timeout 5m >/dev/null
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
    --set "proxy.hostname=$node_ip:$node_port" \
    --set-string "podAnnotations.prometheus-proxy-demo/image=$(image_id "$AGENT_IMAGE")" \
    --wait --timeout 5m >/dev/null
  "${TK[@]}" -n monitoring rollout status deployment/targets-sync --timeout 180s >/dev/null
  for service in "${SERVICES[@]}"; do
    "${TK[@]}" -n apps rollout status "deployment/$service" --timeout 180s >/dev/null
  done
}

cmd_up() {
  local jar name port
  for jar in prometheus-proxy prometheus-agent; do
    [[ -f "$REPO_DIR/build/libs/$jar.jar" ]] ||
      die "build/libs/$jar.jar is missing; start the demo with 'make up' in demos/kubernetes, which builds the jars"
  done
  for port in "$DASHBOARD_PORT" "$PROMETHEUS_PORT" "$PROXY_PORT"; do
    [[ "$port" =~ ^[0-9]+$ ]] || die "'$port' isn't a port number"
  done
  check_tools
  build_images

  for name in "$MONITORING" "$TARGET"; do
    cluster_exists "$name" || create_cluster "$name"
  done
  check_ports
  # Each image goes only to the cluster that runs it. kind skips an image the node already has.
  kind load docker-image "$PROXY_IMAGE" --name "$MONITORING"
  kind load docker-image "$AGENT_IMAGE" --name "$TARGET"

  # Each cluster's own objects first: their namespaces hold the token's Secrets. Prometheus and targets-sync wait for
  # the ConfigMaps install_monitoring and install_target create.
  "${MK[@]}" apply -f "$DEMO_DIR/k8s/monitoring.yaml" >/dev/null
  "${TK[@]}" apply -f "$DEMO_DIR/k8s/target.yaml" >/dev/null
  ensure_token
  install_monitoring
  install_target

  wait_for "$DASHBOARD_URL" "The proxy dashboard"
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
    cluster_exists "$name" || echo "$name: not created; 'make up' creates it"
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
  local opener url
  if command -v open >/dev/null 2>&1; then
    opener=open
  elif command -v xdg-open >/dev/null 2>&1; then
    opener=xdg-open
  else
    print_urls
    return
  fi
  for url in "$DASHBOARD_URL" "$PROMETHEUS_URL/targets"; do
    "$opener" "$url"
  done
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
  hint "the agent dialed out of $TARGET to the proxy's NodePort in $MONITORING; nothing dialed in. On the" \
    "dashboard, $DASHBOARD_URL, it is listed under its pod's name, $(agent_pod)."
}

# Deploys search, then creates its annotated Service once its pod is ready, as a team would deploy an app and then
# expose it: the Service is what targets-sync acts on, and created with the pod still starting, its first scrapes
# would time out. The Service has no labels of its own, so the first apply's selector leaves it out.
cmd_add_service() {
  local start deadline agent
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
  hint "targets-sync saw the Service and wrote the agent's targets ConfigMap; the kubelet copied it into the agent's" \
    "pod, and the agent registered the path. The dashboard's Paths view shows search_metrics with source disc, and" \
    "Prometheus scrapes it within 5s, through the proxy: $PROMETHEUS_URL/targets. Undo with: $0 remove-service"
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
  hint "the agent unregistered the path, again without restarting, and Prometheus drops the target at its next" \
    "discovery refresh."
}

cmd_delete_agent_pod() {
  local old start
  old="$(agent_pod)"
  [[ -n "$old" ]] || die "the agent has no pod; see '$0 status'"
  start=$SECONDS
  show kubectl --context "kind-$TARGET" -n monitoring delete pod "$old" --wait=false
  watch_replacement "$old" "$start"
  hint "the Deployment started a new pod as soon as the old one was deleted. The new agent connected out to the" \
    "proxy and registered every path again, under its own name; the old one left the dashboard as it shut down."
}

cmd_change_config() {
  local config
  config="$(mktemp)"
  cat "$DEMO_DIR/config/agent.conf" "$DEMO_DIR/config/agent-change.conf" >"$config"
  bold "Upgrading the prometheus-agent chart with config/agent-change.conf added to its main config"
  if switch_agent_config "$config" "<(cat config/agent.conf config/agent-change.conf)"; then
    hint "the config changed the chart's checksum/config annotation, so Kubernetes replaced the pod, and the" \
      "chart's strategy: Recreate stopped the old one before starting the new: the gap. Compare act 2, where the" \
      "targets changed and nothing restarted. The new pod's agent_metrics carries the JVM's metrics, so" \
      "count({job=\"prometheus-agent\"}) climbs in Prometheus. Undo with: $0 restore-config"
  fi
  rm -f "$config"
}

cmd_restore_config() {
  bold "Upgrading the prometheus-agent chart with config/agent.conf alone"
  switch_agent_config "$DEMO_DIR/config/agent.conf" config/agent.conf || true
}

# The tour: the presenter's script (README.md), one act at a time, pausing for Enter before each step.
TOUR_FROM=1

# Waits for Enter. At the end of input, as when the tour is piped, it doesn't wait.
pause() { read -r -p "$(printf '\033[33m> %s\033[0m ' "$1")" _ || true; }

# Starts act [n], unless the tour began after it: prints its title and what to show, then waits. Fails for a skipped
# act.
act() {
  local n="$1" title="$2" line
  shift 2
  ((n >= TOUR_FROM)) || return 1
  echo
  bold "Act $n: $title"
  for line in "$@"; do printf '  %s\n' "$line"; done
  pause "Enter to continue"
}

# Runs a scenario once the presenter presses Enter, from this directory, so the commands its hint prints read
# `./demo.sh restore-config`, as the README's do. Its stdin is closed, so kubectl can't take the tour's input.
step() {
  pause "Enter to run: demo.sh $*"
  (cd "$DEMO_DIR" && ./demo.sh "$@" </dev/null)
}

cmd_tour() {
  TOUR_FROM="${1:-1}"
  [[ "$TOUR_FROM" =~ ^[1-4]$ ]] || die "the act to start from is a number from 1 to 4"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with 'make up' in demos/kubernetes"

  if act 1 "Two clusters, one outbound link" \
    "The proxy and agent of the other demos, deployed with their Helm charts: the proxy, its dashboard, and" \
    "Prometheus in $MONITORING; the services and the agent in $TARGET, which exposes nothing."; then
    step clusters
    pause "Open $DASHBOARD_URL: the agent, named for its pod, connected from $TARGET. Then press Enter"
  fi
  if act 2 "Annotate a Service, and Prometheus scrapes it" \
    "targets-sync writes the agent's targets from every Service annotated prometheus-proxy/path. Deploy one."; then
    step add-service
    pause "Show search_metrics on the dashboard's Paths view (source disc) and in Prometheus, then press Enter"
    step remove-service
  fi
  if act 3 "Kubernetes replaces the agent" \
    "Delete the agent's pod; its Deployment starts another, which reconnects and registers under its own name."; then
    step delete-agent-pod
    pause "Show the dashboard's Activity view: the old pod disconnecting, the new one connecting. Then press Enter"
  fi
  if act 4 "A config change restarts the agent; a targets change doesn't" \
    "A helm upgrade changes the agent's main config: Kubernetes replaces the pod, with a short gap."; then
    step change-config
    pause "Compare act 2, where nothing restarted. Then press Enter"
    step restore-config
  fi

  echo
  bold "That's the tour. $0 tour <act> starts again from an act."
}

main() {
  local cmd="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  open) cmd_open ;;
  logs) cmd_logs "$@" ;;
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
