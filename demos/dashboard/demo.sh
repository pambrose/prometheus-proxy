#!/usr/bin/env bash
# Drives the Prometheus Proxy dashboard demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows. `make demo-up` and `make demo-down` call the up and down commands. Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
COMPOSE=(docker compose -f "$DEMO_DIR/compose.yml")
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
AGENTS=(agent-east agent-west-1 agent-west-2)

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"
PROXY_URL="http://localhost:${DEMO_PROXY_PORT:-8080}"

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
  up                       Build the images from this checkout's jars and start the demo
  down                     Stop the demo and remove its containers, network, and volume
  status                   Show the containers, the simulated services' state, and the URLs
  open                     Open the dashboard, Grafana, and Prometheus in the browser
  logs [service]           Follow the logs (proxy, agent-east, demo-app, ...)

Scenarios:
  outage [service]         Make a service answer 500 (default: payments)
  slow [service]           Make a service hang past the agent's scrape timeout (default: inventory)
  recover                  Return every service to normal
  discover                 Add the search, recommendations, and notifications services through discovery
  undiscover               Remove the discovered services
  agent-stop [agent]       Stop an agent (default: agent-west-2)
  agent-start [agent]      Start it again
  agent-freeze [agent]     Freeze an agent so it goes silent and the proxy evicts it (default: agent-west-2)
  agent-thaw [agent]       Unfreeze it; it reconnects

Services: orders, payments, inventory, catalog, shipping (and search, recommendations, notifications once discovered)
Agents:   ${AGENTS[*]}
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy dashboard demo"
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  echo "Scenarios: $0 outage | slow | recover | discover | agent-stop | agent-freeze ...  (no arguments for help)"
}

# POSTs to (or, with GET, reads) the simulated services' control API, from inside the demo-app container, so the API
# needs no published port.
control() {
  local method="$1" path="$2"
  "${COMPOSE[@]}" exec -T demo-app python -c '
import sys, urllib.error, urllib.request
try:
    req = urllib.request.Request("http://localhost:8000" + sys.argv[2], method=sys.argv[1])
    print(urllib.request.urlopen(req, timeout=5).read().decode(), end="")
except urllib.error.HTTPError as e:
    sys.exit(f"demo-app refused {sys.argv[2]}: {e.code} {e.read().decode().strip()}")
' "$method" "$path"
}

wait_for() {
  local url="$1" name="$2" deadline=$((SECONDS + 120))
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    ((SECONDS < deadline)) || die "$name did not answer at $url within 120s; see '$0 logs'"
    sleep 2
  done
}

agent_arg() {
  local agent="${1:-agent-west-2}" known
  for known in "${AGENTS[@]}"; do
    if [[ "$agent" == "$known" ]]; then
      echo "$agent"
      return
    fi
  done
  die "unknown agent '$agent'; expected one of: ${AGENTS[*]}"
}

cmd_up() {
  local jar old_ids id
  for jar in prometheus-proxy prometheus-agent; do
    [[ -f "$REPO_DIR/build/libs/$jar.jar" ]] ||
      die "build/libs/$jar.jar is missing; start the demo with 'make demo-up', which builds the jars first"
  done
  command -v docker >/dev/null 2>&1 || die "docker is not on the PATH"
  docker info >/dev/null 2>&1 || die "Docker isn't running"

  # The jars change on every build (their BuildConfig carries the build time), so each start builds new images and
  # leaves the previous ones untagged; remember them to remove afterwards.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"

  "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
  print_urls
}

cmd_down() {
  "${COMPOSE[@]}" down --volumes --remove-orphans
}

cmd_status() {
  "${COMPOSE[@]}" ps
  if [[ -n "$("${COMPOSE[@]}" ps --status running --quiet demo-app 2>/dev/null)" ]]; then
    echo
    bold "Simulated services"
    control GET /control
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
  for url in "$DASHBOARD_URL" "$GRAFANA_URL" "$PROMETHEUS_URL/alerts"; do
    "$opener" "$url"
  done
}

main() {
  local cmd="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  open) cmd_open ;;
  logs) "${COMPOSE[@]}" logs --follow --tail 50 "$@" ;;
  outage)
    local service="${1:-payments}"
    control POST "/control/$service/error" >/dev/null
    bold "$service now answers 500"
    hint "the dashboard's Paths view: $service turns red (upstream_error); in Grafana, its row on" \
      "'Target health' turns DOWN and DemoTargetDown fires after 15s. Undo with: $0 recover"
    ;;
  slow)
    local service="${1:-inventory}"
    control POST "/control/$service/slow" >/dev/null
    bold "$service now hangs past the agent's 3s scrape timeout"
    hint "the dashboard shows $service's scrapes timing out (408, upstream_timed_out) at about 3s;" \
      "the other paths on the same agent keep answering. Undo with: $0 recover"
    ;;
  recover)
    control POST /control/all/ok >/dev/null
    bold "Every service is back to normal"
    hint "the red rows turn green within a scrape or two (5s)."
    ;;
  discover)
    control POST /discovery/on >/dev/null
    bold "search, recommendations, and notifications added to agent-east's discovery file"
    hint "within ~5s they appear on the dashboard as 'disc' paths, Prometheus scrapes them through the proxy's" \
      "service discovery, and Grafana's 'Discovered services' panel fills, with no restart or config change."
    ;;
  undiscover)
    control POST /discovery/off >/dev/null
    bold "The discovered services removed from agent-east's discovery file"
    hint "within ~5s their paths leave the agent and show as 'gone' in the Paths view."
    ;;
  agent-stop)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" stop "$agent"
    bold "$agent stopped"
    hint "it leaves the dashboard's agent list at once. A west agent's partner keeps shipping_metrics up," \
      "since the pair is consolidated. Bring it back with: $0 agent-start $agent"
    ;;
  agent-start)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" start "$agent"
    bold "$agent started"
    hint "it reconnects and registers its paths within a few seconds."
    ;;
  agent-freeze)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" pause "$agent"
    bold "$agent frozen: its connection stays open but it sends nothing"
    hint "its 'idle' time climbs and the 'evicted in' countdown runs. A frozen west agent holds up" \
      "the shared shipping_metrics, since the proxy waits for every agent of a consolidated path. After 30s" \
      "the proxy evicts it, DemoAgentEvicted fires, and its partner carries the path alone." \
      "Unfreeze it with: $0 agent-thaw $agent"
    ;;
  agent-thaw)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" unpause "$agent"
    bold "$agent unfrozen"
    hint "it finds its connection gone, reconnects, and registers its paths again."
    ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
