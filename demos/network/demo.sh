#!/usr/bin/env bash
# Drives the Prometheus Proxy network demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows, most of them by changing the simulated WAN between the agents and the proxies. The Makefile beside it wraps the
# lifecycle commands (`make up` builds the jars first, then runs `demo.sh up`). Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
AGENTS=(agent-hq agent-branch)
PROXIES=(proxy proxy-standby)
# The WAN's links (config/toxiproxy.json), one per agent per proxy. An agent's are named for its site, the part of its
# name after "agent-": <site>-to-proxy, which it dials first, and <site>-to-standby.
LINKS=(hq-to-proxy hq-to-standby branch-to-proxy branch-to-standby)

# What each scenario sets on a link. The toxics have fixed names, so running a scenario again replaces its own, and
# heal removes them all.
FAR_LATENCY_MS=250   # each way, so a scrape's round trip gains about twice this
FAR_JITTER_MS=50
THIN_RATE_KBS=8      # KB/s from the agent to the proxy
CUT_HOLD_MS=3600000  # how long a cut link holds a close, the agent's included: until heal, in practice

# What a connection through a flaky link carries toward the proxy before the link closes it: about 15 seconds of the
# agent's traffic, which agent-branch's inventory payload makes about 11 KB/s, and agent-hq's small paths about 0.4.
flaky_bytes() { if [[ "$1" == agent-branch ]]; then echo 170000; else echo 5000; fi; }

DASHBOARD_URL="http://localhost:${NETWORK_DEMO_DASHBOARD_PORT:-8194}/dashboard"
STANDBY_DASHBOARD_URL="http://localhost:${NETWORK_DEMO_STANDBY_DASHBOARD_PORT:-8195}/dashboard"
GRAFANA_URL="http://localhost:${NETWORK_DEMO_GRAFANA_PORT:-3100}"
PROMETHEUS_URL="http://localhost:${NETWORK_DEMO_PROMETHEUS_PORT:-9190}"
ZIPKIN_URL="http://localhost:${NETWORK_DEMO_ZIPKIN_PORT:-9511}"
PROXY_URL="http://localhost:${NETWORK_DEMO_PROXY_PORT:-8180}"

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
  down                     Stop the demo and remove its containers and network
  status                   Show the containers, the WAN's links and their toxics, and the URLs
  open                     Open both proxy dashboards, Grafana, and Prometheus in the browser
  logs [service]           Follow the logs (proxy, agent-branch, wan, ...)
  tour [act]               Walk through the presenter's script, pausing before each step (from act 1, or the one given)

Scenarios (the agent defaults to agent-branch):
  far [agent]              Add ${FAR_LATENCY_MS}ms, give or take ${FAR_JITTER_MS}ms, each way on the agent's links
  thin [agent]             Cap both of the agent's links at ${THIN_RATE_KBS} KB/s toward the proxies
  filter                   Restart agent-branch with a filter that drops the inventory's per-item prices
  unfilter                 Restart it without the filter
  proxy-down [proxy]       Stop a proxy; the agents fail over to the other (default: proxy, the primary)
  proxy-up [proxy]         Start it again
  cut [agent]              Silently stop all traffic on the agent's link to the primary, without closing it
  flaky [agent]            Close each connection through the agent's link to the primary after about 15s of traffic
  wan-down                 Take every link down
  wan-up                   Bring every link back
  heal                     Remove every toxic, bring every link and proxy back, and return each agent to the primary

Agents:  ${AGENTS[*]}
Proxies: ${PROXIES[*]}
Links:   ${LINKS[*]}
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy network demo"
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Standby proxy:    $STANDBY_DASHBOARD_URL"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Zipkin:           $ZIPKIN_URL/zipkin/"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  echo "Scenarios: $0 far | thin | filter | proxy-down | cut | flaky | wan-down | heal ...  (no arguments for help)"
  echo "Or walk through them all: $0 tour"
}

# Runs toxiproxy-cli inside the wan container, so its API needs no published port.
wan() { "${COMPOSE[@]}" exec -T wan /toxiproxy-cli "$@"; }

# Whether a link holds a cut: its slow_close toxics keep the closes of the connections through it from crossing. The
# output is taken whole before grep reads it: grep -q stops at its match, and under pipefail a writer it cut off would
# fail the check.
holds_cut() {
  local toxics
  toxics="$(wan inspect "$1")"
  grep -q 'type=slow_close' <<<"$toxics"
}

# Adds a toxic to a link, replacing one of the same name: add_toxic <link> <name> <upstream|downstream> <type>
# [key=value ...]. Upstream is from the agent to the proxy, downstream from the proxy to the agent. A link that holds a
# cut takes no more: Toxiproxy 2.13.0 panics adding a toxic to a link with a connection whose close it is holding, and
# drops every connection as it restarts (see reset_wan).
add_toxic() {
  local link="$1" name="$2" stream="$3" type="$4" attribute
  local attributes=()
  [[ "$type" == slow_close ]] || ! holds_cut "$link" || die "$link is cut; run '$0 heal' first"
  shift 4
  for attribute in "$@"; do attributes+=(--attribute "$attribute"); done
  wan toxic remove --toxicName "$name" "$link" >/dev/null 2>&1 || true
  wan toxic add --type "$type" --toxicName "$name" "--$stream" "${attributes[@]}" "$link" >/dev/null
}

# Removes every toxic from a link. inspect prints one line per toxic, its name first, and nothing when there are none.
remove_toxics() {
  local link="$1" name
  for name in $(wan inspect "$link" | awk '{print $1}'); do
    wan toxic remove --toxicName "$name" "$link" >/dev/null
  done
}

# Enables or disables a link. The CLI can only toggle one, so this toggles only a link in the other state; list prints
# each link's name, listen address, upstream, and state, tab-separated.
set_link() {
  local link="$1" want="$2" state
  state="$(wan list | awk -v link="$link" '$1 == link {print $4}')"
  [[ -n "$state" ]] || die "the WAN has no link named $link"
  [[ "$state" == "$want" ]] || wan toggle "$link" >/dev/null
}

# Disables a link for a moment, which closes every connection through it, then enables it again.
reset_link() {
  set_link "$1" disabled
  sleep 1
  set_link "$1" enabled
}

# Deletes a link and creates it again as it was, on the port it listened on, to the same proxy: every connection through
# it closes, and its toxics go.
recreate_link() {
  local link="$1" listen upstream
  read -r listen upstream < <(wan list | awk -v link="$link" '$1 == link {print $2, $3}')
  [[ -n "$upstream" ]] || die "the WAN has no link named $link"
  wan delete "$link" >/dev/null
  wan create --listen "0.0.0.0:${listen##*:}" --upstream "$upstream" "$link" >/dev/null
}

# Every link enabled, with no toxics. A link a cut held closes on is created again rather than cleared: removing its
# slow_close toxics leaves the connections whose close they held stuck in it, and Toxiproxy 2.13.0 panics the next
# time a toxic is added to that link. The connections it closes are dead already: the agent gave up on them.
reset_wan() {
  local link
  for link in "${LINKS[@]}"; do
    if holds_cut "$link"; then
      recreate_link "$link"
    else
      remove_toxics "$link"
    fi
    set_link "$link" enabled
  done
}

wait_for() {
  local url="$1" name="$2" deadline=$((SECONDS + 120))
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    ((SECONDS < deadline)) || die "$name did not answer at $url within 120s; see '$0 logs'"
    sleep 2
  done
}

agent_arg() {
  local agent="${1:-agent-branch}" known
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

# An agent's link to the primary, and to the standby.
primary_link() { echo "${1#agent-}-to-proxy"; }
standby_link() { echo "${1#agent-}-to-standby"; }

# The agent a scenario on this one leaves alone.
other_agent() { if [[ "$1" == agent-hq ]]; then echo agent-branch; else echo agent-hq; fi; }

# Whether a proxy's container is running.
running() { [[ -n "$("${COMPOSE[@]}" ps --status running --quiet "$1" 2>/dev/null)" ]]; }

# Whether the dashboard at a base URL lists a connected agent by this name. Each agent's row carries its name as a
# data attribute (agentListInner in ProxyDashboardHtml.kt), and the closing quote keeps one name from matching another
# that starts with it. The page is taken whole first, as in holds_cut.
lists_agent() {
  local html
  html="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -qF "data-agent-name=\"$2\"" <<<"$html"
}

# Restarts agent-branch on a config file in config/: compose recreates the container when AGENT_CONFIG changes.
restart_branch() {
  NETWORK_DEMO_BRANCH_CONFIG="$1" "${COMPOSE[@]}" up --detach --no-deps agent-branch
}

cmd_up() {
  local jar old_ids id
  for jar in prometheus-proxy prometheus-agent; do
    [[ -f "$REPO_DIR/build/libs/$jar.jar" ]] ||
      die "build/libs/$jar.jar is missing; start the demo with 'make up' in demos/network, which builds the jars"
  done
  command -v docker >/dev/null 2>&1 || die "docker is not on the PATH"
  docker info >/dev/null 2>&1 || die "Docker isn't running"

  # The jars change on every build (their BuildConfig carries the build time), so each start builds new images and
  # leaves the previous ones untagged; remember them to remove afterwards. One the dashboard demo still runs is in use,
  # and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"

  "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180
  # Compose recreates a container when its own config changes, not when a file it mounts does, and neither Prometheus
  # nor Grafana reliably notices a changed file on its own. Reload Prometheus's config and rules (SIGHUP), and restart
  # Grafana for its dashboard, so a demo started from an older checkout runs this one's.
  "${COMPOSE[@]}" kill --signal SIGHUP prometheus >/dev/null
  "${COMPOSE[@]}" restart grafana >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  # A demo that was already running keeps its WAN: start this one with every link up and none degraded.
  reset_wan

  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$STANDBY_DASHBOARD_URL" "The standby proxy's dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
  wait_for "$ZIPKIN_URL/health" "Zipkin"
  print_urls
}

cmd_down() {
  "${COMPOSE[@]}" down --remove-orphans
}

cmd_status() {
  local link toxics
  "${COMPOSE[@]}" ps
  if running wan; then
    echo
    bold "The WAN's links"
    for link in "${LINKS[@]}"; do
      toxics="$(wan inspect "$link" | awk '{printf "%s%s", sep, $1; sep = ", "}')"
      printf '  %-18s %-9s %s\n' "$link" "$(wan list | awk -v link="$link" '$1 == link {print $4}')" \
        "${toxics:-no toxics}"
    done
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
  for url in "$DASHBOARD_URL" "$STANDBY_DASHBOARD_URL" "$GRAFANA_URL" "$PROMETHEUS_URL/alerts"; do
    "$opener" "$url"
  done
}

# Prints which proxy an agent is connected to, primary or standby, waiting up to 20s for it to connect to one: after
# wan-up, or a heal that closed a connect attempt through a cut link, it is between connections for a few seconds.
# Prints nothing if it doesn't connect in that time.
connected_to() {
  local agent="$1" deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    if lists_agent "$DASHBOARD_URL" "$agent"; then
      echo primary
      return
    elif lists_agent "$STANDBY_DASHBOARD_URL" "$agent"; then
      echo standby
      return
    fi
    sleep 1
  done
}

cmd_heal() {
  local proxy agent returned=() lost=()
  reset_wan
  for proxy in "${PROXIES[@]}"; do
    running "$proxy" || "${COMPOSE[@]}" start "$proxy"
  done
  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$STANDBY_DASHBOARD_URL" "The standby proxy's dashboard"
  # An agent returns to the head of its list only when a connection it registered on drops, so close its connection
  # to the standby once, through the link, and it reconnects through its link to the primary.
  for agent in "${AGENTS[@]}"; do
    case "$(connected_to "$agent")" in
    standby)
      reset_link "$(standby_link "$agent")"
      returned+=("$agent")
      ;;
    primary) ;;
    *) lost+=("$agent") ;;
    esac
  done
  bold "Every link is up with no toxics, and both proxies are running"
  # Each list may be empty, which bash 3.2, macOS's, takes as unset under set -u unless expanded this way.
  for agent in ${returned[@]+"${returned[@]}"}; do
    hint "$agent reconnects through $(primary_link "$agent") within a few seconds, and leaves the standby's dashboard."
  done
  for agent in ${lost[@]+"${lost[@]}"}; do
    hint "$agent hasn't connected to either proxy in 20s; see '$0 logs $agent'"
  done
  if ((${#returned[@]} + ${#lost[@]} == 0)); then
    hint "every agent is on the primary already; its scrapes are back to normal within a scrape or two (5s)."
  fi
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
# `./demo.sh heal`, as the README's do. Its stdin is closed, so docker compose can't take the tour's input.
step() {
  pause "Enter to run: demo.sh $*"
  (cd "$DEMO_DIR" && ./demo.sh "$@" </dev/null)
}

cmd_tour() {
  TOUR_FROM="${1:-1}"
  [[ "$TOUR_FROM" =~ ^[1-7]$ ]] || die "the act to start from is a number from 1 to 7"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with 'make up' in demos/network"

  act 1 "The link" \
    "The proxy and agents of the dashboard demo, with a simulated WAN between them: four links, one per agent per" \
    "proxy. Open $DASHBOARD_URL: two agents, agent-hq and agent-branch, each 'via' its link to the" \
    "primary (1 of 2). Open $GRAFANA_URL: every target up, and the two agents' scrape times side by side." || true
  if act 2 "Far away" "agent-branch's links gain a quarter of a second each way; nothing fails."; then
    step far
    pause "Watch agent-branch's scrape time step up in Grafana, then press Enter"
    step heal
  fi
  if act 3 "A thin pipe, and filtering at the agent" \
    "agent-branch's links carry ${THIN_RATE_KBS} KB/s: too little for its inventory payload, which backs up the link." \
    "The agent stays connected, slow but alive. Then a filter drops what no one uses before it crosses, and" \
    "everything fits again."; then
    step thin
    pause "Wait until its paths fail in Grafana (about 15s), its idle time near 0, then press Enter"
    step filter
    step heal
    step unfilter
  fi
  if act 4 "The proxy goes away" \
    "The primary proxy stops; its connections close, so both agents fail over at once."; then
    step proxy-down
    step proxy-up
    step heal
  fi
  if act 5 "The link silently dies" \
    "agent-branch's link to the primary stops passing anything, the agent's goodbye included, without closing:" \
    "nothing tells either end. It takes about 50 seconds to play out; see the dashboard demo's frozen agent for the" \
    "proxy's side of an eviction."; then
    step cut
    pause "Wait until agent-branch is on the standby's dashboard (about 50s), then press Enter"
    step heal
  fi
  if act 6 "A flaky link, followed in Zipkin" \
    "agent-branch's link to the primary drops its connection about every 15 seconds."; then
    step flaky
    pause "Open $ZIPKIN_URL/zipkin/, run a query for agent-branch, then press Enter"
    step heal
  fi
  if act 7 "The WAN goes down, and comes back" "Every link goes down, then comes back."; then
    step wan-down
    pause "Wait for NetworkNoAgentsConnected in Grafana (about 20s), then press Enter"
    step wan-up
    step heal
  fi

  echo
  bold "That's the tour. $0 tour <act> starts again from an act."
  echo "The same proxy and agent, deployed on Kubernetes with their Helm charts, are in the Kubernetes demo: see" \
    "demos/kubernetes."
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
  far)
    local agent link
    agent="$(agent_arg "${1:-}")"
    for link in "$(primary_link "$agent")" "$(standby_link "$agent")"; do
      add_toxic "$link" far-up upstream latency "latency=$FAR_LATENCY_MS" "jitter=$FAR_JITTER_MS"
      add_toxic "$link" far-down downstream latency "latency=$FAR_LATENCY_MS" "jitter=$FAR_JITTER_MS"
    done
    bold "$agent's links now add ${FAR_LATENCY_MS}ms, give or take ${FAR_JITTER_MS}ms, each way"
    hint "in Grafana's 'Scrape time through the WAN', $agent's line steps up from under 0.1s to about 0.5s: each" \
      "scrape gains one round trip, and nothing more. Nothing fails: the connection is already open, so a scrape" \
      "needs no new handshake through the firewall. Undo with: $0 heal"
    ;;
  thin)
    local agent link
    agent="$(agent_arg "${1:-}")"
    for link in "$(primary_link "$agent")" "$(standby_link "$agent")"; do
      add_toxic "$link" thin-up upstream bandwidth "rate=$THIN_RATE_KBS"
    done
    bold "$agent's links now carry ${THIN_RATE_KBS} KB/s toward the proxies"
    if [[ "$agent" == agent-branch ]]; then
      hint "the inventory payload, about 56 KB gzipped, needs 7s on this link, and Prometheus gives up at 4s. Every" \
        "path shares the link, so it backs up behind the inventory, and within about 15s shipping and the agent's" \
        "own metrics fail too; at about 30s NetworkTargetDown fires for all three. agent-branch stays connected:" \
        "its answers keep arriving, too late for Prometheus, and each tells the proxy it's alive, so its idle time" \
        "on the dashboard stays near 0 and nothing is evicted. agent-hq, on its own links, is untouched." \
        "Next: $0 filter"
    else
      hint "$agent's payloads are small, so they still fit: its scrapes take a little longer. Undo with: $0 heal"
    fi
    ;;
  filter)
    restart_branch agent-branch-filtered.conf
    bold "agent-branch restarted with a filter that drops inventory_item_price_dollars"
    hint "the agent drops the family before gzip and chunking, so the inventory payload falls from about 496,000" \
      "characters to about 199,000 (21 KB gzipped) in agent-branch's Recent scrapes. On the thin link each takes" \
      "about 3s, inside Prometheus's 4s, and every path is green again within about 5s. Until what the old" \
      "connection had queued drains through the link, about 20s, the dashboard lists it too, with no paths." \
      "Grafana's 'Filter bytes saved at the agent' climbs to about 60 KB/s. Undo with: $0 unfilter"
    ;;
  unfilter)
    restart_branch agent-branch.conf
    bold "agent-branch restarted without the filter"
    hint "the inventory payload returns to about 496,000 characters."
    ;;
  proxy-down)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" stop "$proxy"
    bold "$proxy stopped"
    if [[ "$proxy" == proxy ]]; then
      hint "its connections close, so both agents know at once and fail over within about 4s, with no manual step:" \
        "the standby's dashboard, $STANDBY_DASHBOARD_URL, shows each one 'via <site>-to-standby (2 of 2)', marked" \
        "failed over. Within about 15s every service is up again in Grafana, scraped through the standby; only" \
        "prometheus-proxy, the primary's own metrics, stays down, and NetworkTargetDown fires for it alone." \
        "Bring it back with: $0 proxy-up"
    else
      hint "the standby had no agents, so nothing moves; agents connected to it would return to the primary." \
        "Bring it back with: $0 proxy-up proxy-standby"
    fi
    ;;
  proxy-up)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" start "$proxy"
    bold "$proxy started"
    if [[ "$proxy" == proxy ]]; then
      hint "the primary is back, but the agents stay on the standby: an agent returns to the head of its list only" \
        "when its connection drops. Send them back with: $0 heal"
    else
      hint "the standby is ready again, with no agents until one can't connect to the primary."
    fi
    ;;
  cut)
    local agent link
    agent="$(agent_arg "${1:-}")"
    link="$(primary_link "$agent")"
    add_toxic "$link" cut-up upstream timeout timeout=0
    add_toxic "$link" cut-down downstream timeout timeout=0
    add_toxic "$link" cut-hold-up upstream slow_close "delay=$CUT_HOLD_MS"
    add_toxic "$link" cut-hold-down downstream slow_close "delay=$CUT_HOLD_MS"
    bold "$link now passes nothing either way, and closes nothing"
    hint "the agent isn't told: its scrapes start timing out within 10s, and its idle time climbs on the primary's" \
      "dashboard. Its heartbeat fails three times, with a 5s deadline each, so at about 20s the agent gives up on the" \
      "connection and dials the head of its list again, the same dead link, where the connect waits out its 30s" \
      "deadline. Meanwhile, at about 30s, the primary evicts it, and NetworkAgentEvicted fires. At about 50s it" \
      "connects through $(standby_link "$agent"): (2 of 2) on the standby's dashboard. $(other_agent "$agent") is" \
      "untouched. Undo with: $0 heal"
    ;;
  flaky)
    local agent link bytes
    agent="$(agent_arg "${1:-}")"
    link="$(primary_link "$agent")"
    bytes="$(flaky_bytes "$agent")"
    add_toxic "$link" flaky upstream limit_data "bytes=$bytes"
    bold "$link now closes each connection once it has carried $((bytes / 1000)) KB toward the proxy"
    hint "that's about 15s of $agent's traffic, so it reconnects every 15s or so, within milliseconds, and stays on" \
      "the primary: a connection that registered and then dropped retries from the head of the list. Grafana's" \
      "'Agent connects' climbs, and most scrapes succeed; the one in flight as a connection drops fails as" \
      "agent_disconnected. In Zipkin, $ZIPKIN_URL/zipkin/, run a query for $agent: each reconnect is a" \
      "connectagent trace, then registeragent and a registerpath per path. Undo with: $0 heal"
    ;;
  wan-down)
    local link
    for link in "${LINKS[@]}"; do set_link "$link" disabled; done
    bold "Every link is down"
    hint "both agents leave both dashboards at once, and retry every 3s, alternating between their two links." \
      "Their paths leave the proxies' /discovery, so within about 15s their targets vanish from Prometheus rather" \
      "than staying red: their rows on Grafana's 'Target health' go red for a scrape or two, then stop. The proxies" \
      "are scraped directly, so Prometheus still sees them: at about 20s NetworkNoAgentsConnected fires. Bring the" \
      "links back with: $0 wan-up"
    ;;
  wan-up)
    local link
    for link in "${LINKS[@]}"; do set_link "$link" enabled; done
    bold "Every link is back"
    hint "within a few seconds each agent reconnects, through whichever of its links it tries next, so one may land" \
      "on the standby. Their targets return, NetworkNoAgentsConnected resolves, and the graphs keep a gap where" \
      "nothing could be scraped. Return any agent on the standby with: $0 heal"
    ;;
  heal) cmd_heal ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
