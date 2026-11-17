#!/usr/bin/env bash
# Drives the Prometheus Proxy basics demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows. The Makefile beside it wraps the lifecycle commands (`make up` builds the jars first, then runs `demo.sh up`).
# Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
AGENTS=(agent-east agent-west-1 agent-west-2)
# The agent the agent-* scenarios act on when given none.
DEFAULT_AGENT="agent-west-2"
# The services only a scenario starts, so down and status cover them too.
ON_DEMAND=(--profile rogue --profile duplicate)

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"
PROXY_URL="http://localhost:${DEMO_PROXY_PORT:-8080}"

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
# wrap <first> <rest> <text>...
wrap() {
  local first="$1" rest="$2" line
  while IFS= read -r line; do
    printf '%s%s\n' "$first" "${line% }"
    first="$rest"
  done < <(fold -s -w $(($(text_width) - ${#rest})) <<<"${*:3}")
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
  up                       Build the images from this checkout's jars and start the demo
  down                     Stop the demo and remove its containers, network, and volume
  status                   Show the containers, the simulated services' state, and the URLs
  open                     Open the dashboard, Grafana, and Prometheus in the browser
  logs [service]           Follow the logs (proxy, agent-east, demo-app, ...)
  tour [part]              Walk through the presenter's script, pausing before each step (from part 1, or the one given)

Scenarios:
  outage [service]         Make a service answer 500 (default: payments)
  slow [service]           Make a service hang past the agent's scrape timeout (default: inventory)
  recover                  Return every service to normal
  discover                 Add the search, recommendations, and notifications services through discovery
  undiscover               Remove the discovered services
  agent-stop [agent]       Stop an agent (default: $DEFAULT_AGENT)
  agent-start [agent]      Start it again
  agent-freeze [agent]     Freeze an agent so it goes silent and the proxy evicts it (default: $DEFAULT_AGENT)
  agent-thaw [agent]       Unfreeze it; it reconnects
  rogue-agent              Start a contractor's agent that tries to register payments_metrics
  rogue-agent-stop         Stop and remove it
  duplicate-agent          Start a second copy of agent-east, without consolidated mode
  duplicate-agent-stop     Remove it, and restart agent-east so it registers its paths again

Services: orders, payments, inventory, catalog, shipping (and search, recommendations, notifications once discovered)
Agents:   ${AGENTS[*]}
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy basics demo"
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  echo "Scenarios: $0 outage | slow | discover | agent-freeze | rogue-agent ...  (no arguments for help)"
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
  local agent="${1:-$DEFAULT_AGENT}" known
  for known in "${AGENTS[@]}"; do
    if [[ "$agent" == "$known" ]]; then
      echo "$agent"
      return
    fi
  done
  die "unknown agent '$agent'; expected one of: ${AGENTS[*]}"
}

# The agent argument of a command a scenario prints: none for the default agent, so the command reads as the tour's
# next step and the README's command do.
agent_suffix() { [[ "$1" == "$DEFAULT_AGENT" ]] || printf ' %s' "$1"; }

# The other agent of the consolidated west pair, which also serves shipping_metrics, or nothing for agent-east.
partner() {
  case "$1" in
  agent-west-1) echo agent-west-2 ;;
  agent-west-2) echo agent-west-1 ;;
  esac
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

cmd_up() {
  local jar old_ids id
  for jar in prometheus-proxy prometheus-agent; do
    [[ -f "$REPO_DIR/build/libs/$jar.jar" ]] ||
      die "build/libs/$jar.jar is missing; start the demo with 'make up' in demos/basics, which builds the jars"
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
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
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
  wrap "  " "  " "Each step waits for you to press Enter, so you set the pace. Leave about a minute between parts, so" \
    "the graphs catch up. To start from another part, run make tour PART=<n>, or $0 tour <n>."
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
# ./demo.sh too, and its stdin is closed, so docker compose can't take the tour's input.
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

# Checks the part the tour starts from: check_tour_from <argument>
check_tour_from() {
  TOUR_FROM="${1:-1}"
  if ! [[ "$TOUR_FROM" =~ ^[0-9]+$ ]] || ((TOUR_FROM < 1 || TOUR_FROM > ${#TOUR_PARTS[@]})); then
    die "the part to start from is a number from 1 to ${#TOUR_PARTS[@]}"
  fi
}

# Each part's title, after about how many minutes it takes to show.
TOUR_PARTS=(
  "2 How Prometheus scrapes services behind a firewall"
  "4 Agents, paths, and live scrapes on the proxy dashboard"
  "3 Target health and business metrics in Grafana"
  "3 One service fails, another hangs, and the rest keep working"
  "2 A second agent keeps a path up when one stops"
  "2 New services appear without a restart"
  "2 The proxy evicts an agent that goes silent"
  "2 Per-team tokens restrict which paths an agent can register"
  "2 A duplicate agent takes over the paths, and the proxy logs the fix"
)

cmd_tour() {
  check_tour_from "${1:-}"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with 'make up' in demos/basics"
  tour_contents "basics demo"

  if part 1 "Prometheus pulls metrics from the services it monitors, which doesn't work when they sit behind a" \
    "firewall. This part explains how the proxy and its agents get around that."; then
    instruct "The problem: Prometheus can't reach services behind a firewall." \
      "Prometheus collects metrics by pulling them: every few seconds it connects to each service and scrapes its" \
      "metrics endpoint. When the services run behind a firewall that allows no inbound connections, Prometheus" \
      "can't reach any of them. This demo simulates that firewall: its containers share one network, but" \
      "Prometheus is given no targets of its own, so it reaches each service only through the proxy."
    instruct "The solution: the agents connect out to the proxy." \
      "An agent runs inside the firewall, next to the services, and opens a connection out to the proxy over gRPC," \
      "which the firewall allows because it's outbound. Prometheus scrapes the proxy as if it were each service." \
      "The proxy passes each scrape down the agent's connection, and the agent scrapes the service and sends the" \
      "metrics back the same way."
    instruct "What runs in this demo." \
      "Three agents run behind the firewall. agent-east, in the east data center, scrapes the orders, payments," \
      "inventory, and catalog services. agent-west-1 and agent-west-2, in the west data center, are a redundant" \
      "pair that both serve the shipping service. Outside the firewall are the proxy, Prometheus, and Grafana. The" \
      "diagram at the top of demos/basics/README.md shows the same layout."
  fi

  if part 2 "The proxy's dashboard shows every agent connected to it, the paths each one registered, and the" \
    "scrapes passing through them as they happen."; then
    instruct "Open the proxy dashboard: $DASHBOARD_URL" \
      "It opens on the Agents view, which lists the three connected agents, each with its address and the number" \
      "of paths it registered."
    instruct "Click agent-east." \
      "Its details open: its host, how long it has been up, and how long since the proxy last heard from it; the" \
      "paths it registered, with their labels; and its scrapes as they happen, with each one's status, time, and" \
      "size."
    instruct "Find the scrapes of /catalog_metrics in agent-east's list." \
      "Each payload is about 480,000 characters, large enough that the agent sends it to the proxy in chunks."
    instruct "Find /proxy_metrics among agent-east's paths." \
      "These are the proxy's own metrics. They leave the proxy, go through agent-east, and come back through the" \
      "proxy to Prometheus, so the proxy is monitored the same way as everything else."
    instruct "Switch to the Paths view." \
      "It lists every path, with the agent serving it, its target URL, its last scrape, and its status." \
      "/shipping_metrics names one of the west agents followed by +1, because two agents serve it."
    instruct "Look at the icons at the end of each row." \
      "They open the path's job in Prometheus and in Grafana, using the job label the agent registered the path" \
      "with. The next part starts in Grafana."
  fi

  if part 3 "Grafana shows what Prometheus collected through the proxy: the health of every target, and the" \
    "services' own metrics."; then
    instruct "Open Grafana: $GRAFANA_URL" \
      "It opens on the 'Prometheus Proxy Demo' dashboard, with no sign-in needed."
    instruct "Look at the section titled 'Through the firewall'." \
      "It shows the health of every target, the outcome of every scrape, and each path's scrape latency and" \
      "payload size."
    instruct "Look at the section titled 'Business metrics from behind the firewall'." \
      "It shows orders, payments, inventory, and shipping. These are ordinary application metrics, which" \
      "Prometheus collected through the proxy."
    instruct "Note the 'Prometheus Proxy' and 'Prometheus Agents' links at the top of Grafana's dashboard." \
      "They open the dashboards the project ships for operating the proxy and its agents."
    instruct "Open the proxy's service discovery: $PROXY_URL/discovery" \
      "Prometheus's config lists no targets. Instead it asks the proxy what exists, and this is the answer: every" \
      "path the agents registered, with its labels. Each agent's labels set the job."
  fi

  if part 4 "Here you break two of the services, one at a time, and see that the proxy reports each failure" \
    "clearly while the other services keep answering."; then
    instruct "Put the proxy dashboard's Paths view and Grafana side by side." \
      "You'll watch both as the services fail."
    step "Make the payments service answer every scrape with an error." \
      "agent-east scrapes the payments service, at /payments_metrics, so the failure shows on that path in the" \
      "dashboard's Paths view and in agent-east's recent scrapes in its Agents view." -- outage
    step "Once DemoTargetDown has fired, make the inventory service hang, so it never answers." -- slow
    step "Put every service back to normal." -- recover
  fi

  if part 5 "agent-west-1 and agent-west-2 both serve /shipping_metrics in consolidated mode, so the proxy" \
    "merges their answers. Here you stop one, and see the other keep the path up."; then
    step "Stop agent-west-2." -- agent-stop
    step "Start agent-west-2 again." -- agent-start
  fi

  if part 6 "Besides its configured paths, agent-east watches a discovery file and registers whatever services" \
    "it lists. Here you add three services to the file, and see them scraped without restarting anything."; then
    step "Add the search, recommendations, and notifications services to agent-east's discovery file." -- discover
    step "Remove them from the discovery file again." -- undiscover
  fi

  if part 7 "An agent can stay connected but stop answering, when its process hangs or the network drops its" \
    "traffic without closing the connection. Here you freeze agent-west-2, and watch the proxy notice and evict" \
    "it."; then
    step "Freeze agent-west-2: its connection stays open, but it sends nothing." -- agent-freeze
    step "Once the proxy has evicted it, after about 30 seconds, unfreeze agent-west-2." -- agent-thaw
  fi

  if part 8 "The proxy gives each team an identity of its own: each data center's agents connect with their own" \
    "token, and an identity may register only the paths its patterns allow. Here a contractor's agent tries to" \
    "register a path that belongs to another team."; then
    step "Start a contractor's agent, which tries to register /contractor_metrics and /payments_metrics." \
      -- rogue-agent
    step "Stop and remove the contractor's agent." -- rogue-agent-stop
  fi

  if part 9 "Someone deploys agent-east's config a second time by mistake, without consolidated mode. Here you" \
    "see what the proxy does with the two agents, and what it tells the operator."; then
    step "Start a second copy of agent-east, without consolidated mode." -- duplicate-agent
    step "Remove the copy, and restart agent-east so it registers its paths again." -- duplicate-agent-stop
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  bold "That's the tour."
  echo "To start again from a part, run make tour PART=<n>, or $0 tour <n>."
  echo
  bold "The other demos:"
  echo "Proxy failover, filtering at the agent, and tracing are in the network demo, in demos/network."
  echo "The same proxy and agent, deployed on Kubernetes with their Helm charts, are in the Kubernetes demo, in" \
    "demos/kubernetes."
  echo
  echo "The demos use different ports, so they can run side by side, but each takes memory and CPU."
  echo "To stop this one and free its resources, run make down, or $0 down."
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
    bold "$service now answers every scrape with 500"
    how_it_works "The simulated services are one small Python app, demo-app, which serves each service's metrics at" \
      "its own URL and takes orders through a control API. This asks it, through POST /control/$service/error, to" \
      "answer every request for the $service service's metrics with a 500. The agent and the proxy are untouched:" \
      "they pass on what the service answered."
    watch_for
    point "On the proxy dashboard's Paths view, /${service}_metrics turns red with status 500 (upstream_error), and" \
      "so does each of its scrapes in its agent's list of recent scrapes."
    point "In Grafana, $service's row on 'Target health' turns DOWN. After 15 seconds the DemoTargetDown alert fires:" \
      "it shows in the 'Alerts' stat, in the 'Firing alerts' table, and on Prometheus's alerts page."
    point "Every other service keeps answering: one failing target doesn't affect the rest."
    follow_up "To undo it, run" "$0 recover"
    ;;
  slow)
    local service="${1:-inventory}"
    control POST "/control/$service/slow" >/dev/null
    bold "$service now hangs past the agent's 3-second scrape timeout"
    how_it_works "This asks demo-app, through POST /control/$service/slow, to hold each request for the $service" \
      "service's metrics for 10 seconds before answering. The agent stops waiting after its scrapeTimeoutSecs, 3" \
      "seconds, which is shorter than Prometheus's 4-second scrape timeout, so the agent is the one that ends each" \
      "scrape and says why."
    watch_for
    point "The agent gives up on each scrape of $service after 3 seconds and reports status 408" \
      "(upstream_timed_out), so Prometheus gets a clear answer instead of waiting. The dashboard shows each one."
    point "The other paths on the same agent keep answering, and in Grafana, 'Scrape latency' spikes for $service" \
      "alone."
    follow_up "To undo it, run" "$0 recover"
    ;;
  recover)
    control POST /control/all/ok >/dev/null
    bold "Every service is back to normal"
    how_it_works "This asks demo-app, through POST /control/all/ok, to answer every service's metrics normally again."
    watch_for
    point "The red rows turn green again within a scrape or two (5 seconds), on the dashboard and in Grafana."
    ;;
  discover)
    control POST /discovery/on >/dev/null
    bold "search, recommendations, and notifications added to agent-east's discovery file"
    how_it_works "agent-east watches a discovery file, /discovery/targets.json, on a volume it shares with demo-app," \
      "and re-reads it every 5 seconds (agent.discovery in config/agent-east.conf). This asks demo-app, through POST" \
      "/discovery/on, to list three more services in that file, each with its path, URL, and labels. agent-east" \
      "registers each new entry with the proxy as a discovered path, and the proxy's /discovery, which Prometheus" \
      "polls, lists it."
    watch_for
    point "Within about 5 seconds, /search_metrics, /recommendations_metrics, and /notifications_metrics appear on" \
      "the dashboard's Paths view with source 'disc' (discovered), beside the 'cfg' (configured) paths."
    point "Prometheus finds them through the proxy's service discovery and starts scraping them, and Grafana's" \
      "'Discovered services' panel fills."
    point "Nothing restarted, and Prometheus's config didn't change."
    follow_up "To remove them, run" "$0 undiscover"
    ;;
  undiscover)
    control POST /discovery/off >/dev/null
    bold "The discovered services removed from agent-east's discovery file"
    how_it_works "This asks demo-app, through POST /discovery/off, to list no services in agent-east's discovery" \
      "file. When agent-east next re-reads it, it unregisters each discovered path the file no longer lists. The" \
      "paths in its config file are never touched."
    watch_for
    point "Within about 5 seconds agent-east unregisters their paths. On the Paths view, each one that had been" \
      "scraped stays listed as 'gone', so it's clear what left."
    ;;
  agent-stop)
    local agent other
    agent="$(agent_arg "${1:-}")"
    other="$(partner "$agent")"
    "${COMPOSE[@]}" stop "$agent"
    bold "$agent stopped"
    local consolidated=""
    [[ -z "$other" ]] || consolidated="$agent and $other both registered /shipping_metrics with agent.consolidated"
    [[ -z "$other" ]] || consolidated+=" = true, so the proxy keeps serving it while either one is connected."
    how_it_works "This runs docker compose stop $agent, which stops its container. The agent's gRPC connection to" \
      "the proxy closes as it exits, so the proxy removes the agent and its paths at once." "$consolidated"
    watch_for
    point "$agent leaves the dashboard's Agents view at once, and its paths go with it."
    if [[ -n "$other" ]]; then
      point "On the Paths view, /shipping_metrics loses its +1 and names $other alone: the pair is consolidated, so" \
        "$other keeps the path up."
      point "In Grafana, 'Shipping by replica' keeps $other's line flowing, and shipping stays green on" \
        "'Target health'."
    fi
    follow_up "To bring it back, run" "$0 agent-start$(agent_suffix "$agent")"
    ;;
  agent-start)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" start "$agent"
    bold "$agent started"
    how_it_works "This runs docker compose start $agent. The agent starts as it would anywhere: it dials the proxy," \
      "then registers the paths in its config."
    watch_for
    point "It reconnects and registers its paths within a few seconds, and appears on the Agents view again."
    [[ -z "$(partner "$agent")" ]] || point "On the Paths view, /shipping_metrics shows +1 again."
    ;;
  agent-freeze)
    local agent other
    agent="$(agent_arg "${1:-}")"
    other="$(partner "$agent")"
    "${COMPOSE[@]}" pause "$agent"
    bold "$agent frozen: its connection stays open, but it sends nothing"
    how_it_works "This runs docker compose pause $agent, which freezes every process in its container without" \
      "stopping them. The kernel keeps the agent's TCP connection open, so the proxy sees no close, but nothing" \
      "arrives on it: no scrape results and no heartbeats, as from a hung JVM. The proxy's only clue is the silence," \
      "and it evicts an agent it hasn't heard from in proxy.internal.maxAgentInactivitySecs, 30 seconds in this demo."
    watch_for
    point "On the dashboard, click $agent. Its 'idle' time climbs, and its 'evicted in' countdown runs."
    [[ -z "$other" ]] || point "/shipping_metrics stalls while $agent is frozen, because the proxy waits for every" \
      "agent of a consolidated path."
    point "After 30 seconds of silence the proxy evicts $agent, and the DemoAgentEvicted alert fires. Its own path," \
      "/${agent//-/_}_metrics, shows as 'gone' on the Paths view, and the 'Agent Events' panel of Grafana's" \
      "'Prometheus Proxy' dashboard records the eviction."
    [[ -z "$other" ]] || point "$other carries /shipping_metrics alone from then on."
    follow_up "To unfreeze it, run" "$0 agent-thaw$(agent_suffix "$agent")"
    ;;
  agent-thaw)
    local agent
    agent="$(agent_arg "${1:-}")"
    "${COMPOSE[@]}" unpause "$agent"
    bold "$agent unfrozen"
    how_it_works "This runs docker compose unpause $agent, which lets its processes run on from where they stopped," \
      "unaware of the time that passed."
    watch_for
    point "$agent finds that the proxy closed its connection, reconnects, and registers its paths again within a few" \
      "seconds."
    ;;
  rogue-agent)
    "${COMPOSE[@]}" --profile rogue up --detach --no-deps agent-rogue
    bold "agent-rogue started with the contractor identity's token, which allows only contractor_* paths"
    how_it_works "This starts the agent-rogue container, which the compose file keeps in a profile of its own, rogue," \
      "so make up leaves it out. Its config, config/agent-rogue.conf, presents the contractor identity's token, and" \
      "proxy.auth in config/proxy.conf lets that identity register only paths matching contractor_*. The proxy" \
      "checks each path an agent registers against its identity's globs, so it accepts /contractor_metrics and" \
      "refuses /payments_metrics."
    watch_for
    point "agent-rogue appears on the dashboard with the one path its identity allows, /contractor_metrics."
    point "The proxy refuses its /payments_metrics, and logs the refusal; the log line is printed below."
    point "/payments_metrics keeps coming from agent-east, untouched. With one token shared by everyone, the newer" \
      "agent would have taken the path over."
    follow_up "To remove it, run" "$0 rogue-agent-stop"
    proxy_said "denied registration of path /payments_metrics"
    ;;
  rogue-agent-stop)
    "${COMPOSE[@]}" --profile rogue rm --stop --force agent-rogue
    bold "agent-rogue stopped and removed"
    how_it_works "This stops and removes the agent-rogue container. Its connection to the proxy closes as it exits," \
      "so the proxy removes the agent and its path at once."
    watch_for
    point "agent-rogue leaves the dashboard at once, and /contractor_metrics goes with it."
    ;;
  duplicate-agent)
    "${COMPOSE[@]}" --profile duplicate up --detach --no-deps agent-east-copy
    bold "agent-east-copy started: agent-east's config, deployed a second time without consolidated mode"
    how_it_works "This starts the agent-east-copy container, which the compose file keeps in a profile of its own," \
      "duplicate. Its config, config/agent-duplicate.conf, includes agent-east's under another name, so it presents" \
      "the same token and registers the same paths, without consolidated mode, as a copy deployed by mistake would." \
      "A path that isn't consolidated has one agent, so the proxy gives each one to the newer registration."
    watch_for
    point "The copy registers the same paths with the same identity, and the newer registration takes each one: the" \
      "dashboard shows agent-east-copy with agent-east's paths, and agent-east with 0."
    point "The two settle. agent-east stays connected without paths rather than reconnecting to take them back," \
      "which used to make two such agents swap the paths every few seconds."
    point "The proxy's log line, printed below, names both agents and gives the fix: set agent.consolidated = true" \
      "on each, if both should serve the paths."
    follow_up "To undo it, run" "$0 duplicate-agent-stop"
    proxy_said "keeps its connection without paths"
    ;;
  duplicate-agent-stop)
    "${COMPOSE[@]}" --profile duplicate rm --stop --force agent-east-copy
    "${COMPOSE[@]}" restart agent-east
    bold "agent-east-copy removed, and agent-east restarted"
    how_it_works "This stops and removes the agent-east-copy container, and its paths leave with it. agent-east kept" \
      "its connection without paths and doesn't take them back on its own, so this then restarts it with docker" \
      "compose restart, and it registers the paths in its config as it starts."
    watch_for
    point "The paths left with the copy. agent-east, which kept none, registers them again as it starts, within a" \
      "few seconds."
    ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
