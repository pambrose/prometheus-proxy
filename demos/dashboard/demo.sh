#!/usr/bin/env bash
# Drives the Prometheus Proxy dashboard demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows. `make demo-up` and `make demo-down` call the up and down commands. Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
COMPOSE=(docker compose -f "$DEMO_DIR/compose.yml")
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
AGENTS=(agent-east agent-west-1 agent-west-2)
PROXIES=(proxy proxy-standby)
# The services only a scenario starts, so down and status cover them too.
ON_DEMAND=(--profile rogue --profile duplicate)

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
STANDBY_DASHBOARD_URL="http://localhost:${DEMO_STANDBY_DASHBOARD_PORT:-8095}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"
ZIPKIN_URL="http://localhost:${DEMO_ZIPKIN_PORT:-9411}"
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
  tour [act]               Walk through the presenter's script, pausing before each step (from act 1, or the one given)

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
  proxy-down [proxy]       Stop a proxy; its agents fail over to the other (default: proxy, the primary)
  proxy-up [proxy]         Start it again
  filter                   Restart agent-east with a filter that drops the catalog's per-item prices
  unfilter                 Restart it without the filter
  rogue-agent              Start a contractor's agent that tries to register payments_metrics
  rogue-agent-stop         Stop and remove it
  duplicate-agent          Start a second copy of agent-east, without consolidated mode
  duplicate-agent-stop     Remove it, and restart agent-east so it registers its paths again

Services: orders, payments, inventory, catalog, shipping (and search, recommendations, notifications once discovered)
Agents:   ${AGENTS[*]}
Proxies:  ${PROXIES[*]}
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy dashboard demo"
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Standby proxy:    $STANDBY_DASHBOARD_URL"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Zipkin:           $ZIPKIN_URL/zipkin/"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  echo "Scenarios: $0 outage | slow | discover | agent-freeze | proxy-down | filter | rogue-agent ...  (no arguments for help)"
  echo "Or walk through them all: $0 tour"
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

proxy_arg() {
  local proxy="${1:-proxy}" known
  for known in "${PROXIES[@]}"; do
    if [[ "$proxy" == "$known" ]]; then
      echo "$proxy"
      return
    fi
  done
  die "unknown proxy '$proxy'; expected one of: ${PROXIES[*]}"
}

# Prints the proxy's latest log line matching an extended regex, waiting up to 30s for one; prints nothing if none comes.
proxy_said() {
  local deadline=$((SECONDS + 30)) line=""
  until [[ -n "$line" ]] || ((SECONDS >= deadline)); do
    sleep 2
    line="$("${COMPOSE[@]}" logs --no-log-prefix proxy 2>/dev/null | grep -E "$1" | tail -1 || true)"
  done
  [[ -z "$line" ]] || printf '\033[36mThe proxy logged:\033[0m %s\n' "$line"
}

# Restarts agent-east on a config file in config/: compose recreates the container when AGENT_CONFIG changes.
restart_east() {
  DEMO_EAST_CONFIG="$1" "${COMPOSE[@]}" up --detach --no-deps agent-east
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
  # Compose recreates a container when its own config changes, not when a file it mounts does, and neither Prometheus
  # nor Grafana reliably notices a changed file on its own. Reload Prometheus's config and rules (SIGHUP), and restart
  # Grafana for its dashboards, so a demo started from an older checkout runs this one's.
  "${COMPOSE[@]}" kill --signal SIGHUP prometheus >/dev/null
  "${COMPOSE[@]}" restart grafana >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$STANDBY_DASHBOARD_URL" "The standby proxy's dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
  wait_for "$ZIPKIN_URL/health" "Zipkin"
  print_urls
}

cmd_down() {
  # With the on-demand services' profiles, so one left running goes too.
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" down --volumes --remove-orphans
}

cmd_status() {
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" ps
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

# The tour: the presenter's script (README.md), one act at a time, pausing for Enter before each step.
TOUR_FROM=1

# Waits for Enter. At the end of input, as when the tour is piped, it doesn't wait.
pause() { read -r -p "$(printf '\033[33m> %s\033[0m ' "$1")" _ || true; }

# Starts act [n], unless the tour began after it: prints its title and what to show, then waits. Fails for a skipped act.
act() {
  local n="$1" title="$2" line
  shift 2
  ((n >= TOUR_FROM)) || return 1
  echo
  bold "Act $n: $title"
  for line in "$@"; do printf '  %s\n' "$line"; done
  pause "Enter to continue"
}

# Runs a scenario once the presenter presses Enter. Its stdin is closed, so docker compose can't take the tour's input.
step() {
  pause "Enter to run: demo.sh $*"
  "$DEMO_DIR/demo.sh" "$@" </dev/null
}

cmd_tour() {
  TOUR_FROM="${1:-1}"
  [[ "$TOUR_FROM" =~ ^([1-9]|1[0-2])$ ]] || die "the act to start from is a number from 1 to 12"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null || die "the demo isn't running; start it with 'make demo-up'"

  act 1 "The problem, and the shape of the answer" \
    "Prometheus pulls metrics, but the services sit behind a firewall that allows no inbound connections." \
    "The agents inside connect out to the proxy over gRPC, and Prometheus scrapes the proxy as if it were each" \
    "service." || true
  act 2 "The proxy dashboard" \
    "Open $DASHBOARD_URL. Agents: three connected, each with its paths. Click agent-east for its" \
    "registered paths and its scrapes as they happen; then switch to Paths: every path, its agent, its target," \
    "and its last scrape. /shipping_metrics shows agent-west-2 +1: two agents serve it." || true
  act 3 "Grafana" \
    "Open $GRAFANA_URL: the health of every target through the proxy, and the business metrics collected" \
    "from behind the firewall. Prometheus's config lists no targets: it asks the proxy." || true
  if act 4 "A service fails, and recovers" "Payments starts answering 500, then inventory hangs."; then
    step outage
    step slow
    step recover
  fi
  if act 5 "Redundancy" "agent-west-2 stops; its consolidated partner keeps shipping_metrics up."; then
    step agent-stop
    step agent-start
  fi
  if act 6 "New services appear without a restart" "Three services join through agent-east's discovery file."; then
    step discover
    step undiscover
  fi
  if act 7 "An agent that goes silent" "agent-west-2 freezes; the proxy evicts it after 30 seconds of silence."; then
    step agent-freeze
    step agent-thaw
  fi
  if act 8 "The proxy isn't a single point of failure" "The primary proxy stops; the agents fail over to the standby."; then
    step proxy-down
    step proxy-up
    pause "Wait until prometheus-proxy is green in Grafana, then press Enter"
    step proxy-down proxy-standby
    step proxy-up proxy-standby
  fi
  if act 9 "Filtering before the WAN" "agent-east restarts with a filter that drops the catalog's price list."; then
    step filter
    step unfilter
  fi
  if act 10 "One proxy, several teams" "A contractor's agent reaches for payments_metrics, and is refused."; then
    step rogue-agent
    step rogue-agent-stop
  fi
  if act 11 "A misconfiguration, handled calmly" \
    "A second copy of agent-east starts, without consolidated mode."; then
    step duplicate-agent
    step duplicate-agent-stop
  fi
  act 12 "Following a call through the firewall" \
    "Open $ZIPKIN_URL/zipkin/ and run a query for agent-west-2, which act 5 restarted. Each trace is one gRPC call," \
    "the agent's and the proxy's sides of it in one span: connectagent, registeragent, and a registerpath for" \
    "each path; and, as a connection closes, its scrape streams, lasting as long as it did." || true

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
  logs) "${COMPOSE[@]}" logs --follow --tail 50 "$@" ;;
  tour) cmd_tour "$@" ;;
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
  proxy-down)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" stop "$proxy"
    bold "$proxy stopped"
    if [[ "$proxy" == proxy ]]; then
      hint "within seconds every agent fails over to proxy-standby, with no manual step: its dashboard," \
        "$STANDBY_DASHBOARD_URL, shows each one 'via proxy-standby:50051 (2 of 2)', marked failed over. In" \
        "Grafana every service stays up, served through the standby; only prometheus-proxy, the primary's own" \
        "metrics, goes down, and DemoTargetDown fires for it. Bring it back with: $0 proxy-up"
    else
      hint "agents connected to the standby return to the head of their list, the primary." \
        "Bring it back with: $0 proxy-up proxy-standby"
    fi
    ;;
  proxy-up)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" start "$proxy"
    bold "$proxy started"
    if [[ "$proxy" == proxy ]]; then
      hint "the primary is back, but connected agents stay on the standby until their connection drops; an agent" \
        "returns to the head of its list only when it reconnects. Once prometheus-proxy is green again in Grafana" \
        "(the agents have looked the primary up again by then), send them back with: $0 proxy-down proxy-standby," \
        "then $0 proxy-up proxy-standby"
    else
      hint "the standby is ready again, with no agents until the primary goes."
    fi
    ;;
  filter)
    restart_east agent-east-filtered.conf
    bold "agent-east restarted with a filter that drops catalog_item_price_dollars"
    hint "the agent drops the family before gzip and chunking, so the catalog payload falls from about 480,000" \
      "characters to under half that: watch agent-east's Recent scrapes on the dashboard, and 'Payload size' in" \
      "Grafana. The paths come back once the agent has registered them again. Undo with: $0 unfilter"
    ;;
  unfilter)
    restart_east agent-east.conf
    bold "agent-east restarted without the filter"
    hint "the catalog payload returns to about 480,000 characters."
    ;;
  rogue-agent)
    "${COMPOSE[@]}" --profile rogue up --detach --no-deps agent-rogue
    bold "agent-rogue started with the contractor identity's token, which covers only contractor_* paths"
    hint "it registers contractor_metrics, and the proxy refuses its payments_metrics; payments keeps coming" \
      "from agent-east. The dashboard lists agent-rogue with its one path. Stop it with: $0 rogue-agent-stop"
    proxy_said "denied registration of path /payments_metrics"
    ;;
  rogue-agent-stop)
    "${COMPOSE[@]}" --profile rogue rm --stop --force agent-rogue
    bold "agent-rogue stopped and removed"
    hint "it leaves the dashboard at once; its contractor_metrics path goes with it."
    ;;
  duplicate-agent)
    "${COMPOSE[@]}" --profile duplicate up --detach --no-deps agent-east-copy
    bold "agent-east-copy started: agent-east's config, deployed a second time without consolidated mode"
    hint "the newer registration takes each path, so agent-east-copy serves agent-east's static paths, and" \
      "agent-east stays connected with 0 paths on the dashboard. The two settle, rather than taking the paths from" \
      "each other every few seconds. Undo with: $0 duplicate-agent-stop"
    proxy_said "keeps its connection without paths"
    ;;
  duplicate-agent-stop)
    "${COMPOSE[@]}" --profile duplicate rm --stop --force agent-east-copy
    "${COMPOSE[@]}" restart agent-east
    bold "agent-east-copy removed, and agent-east restarted"
    hint "the paths went with the copy, and agent-east, which kept none, registers them again as it starts."
    ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
