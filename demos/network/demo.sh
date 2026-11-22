#!/usr/bin/env bash
# Drives the Prometheus Proxy network demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows, most of them by changing the simulated WAN between the agents and the proxies. The Makefile beside it wraps the
# lifecycle commands (`make up` runs `demo.sh up`). Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
# The make that runs the repository's targets: the one that ran this script, which the Makefile beside it exports as
# MAKE, or, run directly, make, or else gmake, the name GNU make has on some systems. MAKE_NAME is the one the
# messages tell the reader to type.
MAKE="${MAKE:-$(command -v make || command -v gmake || echo make)}"
MAKE_NAME="${MAKE##*/}"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
# The proxy and agent images the demo runs, under these tags whichever way they came: pulled from Docker Hub (up)
# or built from this checkout (up-local). The other demos run the same tags, so there's one copy of each.
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
# Their releases on Docker Hub, in the same order, which up pulls at the latest release.
RELEASED_IMAGES=(pambrose/prometheus-proxy pambrose/prometheus-agent)
# The demo shows what came after this release, so until a later one is out, up builds the proxy and agent from this
# checkout, as up-local does. When the demo comes to use something unreleased, set this to the latest release.
NEEDS_RELEASE_AFTER=4.6.1
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
# Each agent's own dashboard, behind the firewall with it (agent.dashboard in config/agent-*.conf).
HQ_DASHBOARD_URL="http://localhost:${NETWORK_DEMO_HQ_DASHBOARD_PORT:-8196}/dashboard"
BRANCH_DASHBOARD_URL="http://localhost:${NETWORK_DEMO_BRANCH_DASHBOARD_PORT:-8197}/dashboard"
GRAFANA_URL="http://localhost:${NETWORK_DEMO_GRAFANA_PORT:-3100}"
PROMETHEUS_URL="http://localhost:${NETWORK_DEMO_PROMETHEUS_PORT:-9190}"
ZIPKIN_URL="http://localhost:${NETWORK_DEMO_ZIPKIN_PORT:-9511}"
PROXY_URL="http://localhost:${NETWORK_DEMO_PROXY_PORT:-8180}"

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
  up                       Start the demo with the proxy and agent released on Docker Hub
  up-local                 Start it with the proxy and agent built from this checkout (needs a JDK 17 or later)
  down                     Stop the demo and remove its containers and network
  status                   Show the containers, the WAN's links and their toxics, and the URLs
  open                     Open both proxy dashboards, agent-branch's dashboard, Grafana, and Prometheus in the browser
  logs [service]           Follow the logs (proxy, agent-branch, wan, ...)
  tour [part]              Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

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
  echo "  agent-hq:         $HQ_DASHBOARD_URL"
  echo "  agent-branch:     $BRANCH_DASHBOARD_URL"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Zipkin:           $ZIPKIN_URL/zipkin/"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  echo "Scenarios: $0 far | thin | filter | proxy-down | cut | flaky | wan-down | heal ...  (no arguments for help)"
  echo "Or walk through them all: $MAKE_NAME tour, or $0 tour"
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

# An agent's own dashboard, at its base URL.
agent_dashboard() { if [[ "$1" == agent-hq ]]; then echo "$HQ_DASHBOARD_URL"; else echo "$BRANCH_DASHBOARD_URL"; fi; }

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

# Whether version $1 (x.y.z) is later than $2. bash 3.2, macOS's, has no sort -V everywhere, so field by field.
version_after() {
  local IFS=. i
  local -a have want
  read -r -a have <<<"$1"
  read -r -a want <<<"$2"
  for i in 0 1 2; do
    ((${have[i]:-0} > ${want[i]:-0})) && return 0
    ((${have[i]:-0} < ${want[i]:-0})) && return 1
  done
  return 1
}

# The latest release: the version the docs give, whose images are on Docker Hub.
latest_release() { "$MAKE" -s --no-print-directory -C "$REPO_DIR" site-version; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not on the PATH; $2"; }

# The tools up and up-local need, checked before anything is pulled, built, or started. Without curl, the wait for
# each service would retry for two minutes and then blame the service.
check_tools() {
  need docker "install Docker (https://docs.docker.com/get-started/get-docker/)"
  docker info >/dev/null 2>&1 || die "Docker isn't running"
  docker compose version >/dev/null 2>&1 ||
    die "the demo needs Docker Compose v2, the docker compose plugin (https://docs.docker.com/compose/install/)"
  need curl "install curl; the demo uses it to wait for each service to answer"
}

# Building the proxy and agent runs Gradle, which needs a JDK 17 or later: the one JAVA_HOME names, as the Gradle
# wrapper takes it, or else the java on the PATH. Checked before anything is built, so a missing or old one is named
# up front.
check_jdk() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version
  command -v "$java" >/dev/null 2>&1 ||
    die "building the proxy and agent from this checkout takes a JDK 17 or later;" \
      "install one, or set JAVA_HOME (https://adoptium.net)"
  version="$("$java" -version 2>&1 | sed -nE '1s/.*version "(1\.)?([0-9]+).*/\2/p')"
  if ! [[ "$version" =~ ^[0-9]+$ ]] || ((version < 17)); then
    die "building the proxy and agent from this checkout takes a JDK 17 or later, not Java ${version:-of an unknown" \
      "version} ($java)"
  fi
}

# Tags a release's proxy and agent images from Docker Hub as the demo's, pulling one only if Docker doesn't have it: a
# release's images don't change, and the demo then starts again without a network connection. pull_images <version>
pull_images() {
  local i released
  for i in 0 1; do
    released="${RELEASED_IMAGES[i]}:$1"
    if ! docker image inspect "$released" >/dev/null 2>&1; then
      docker pull --quiet "$released" >/dev/null ||
        die "can't pull $released; check the network, or run '$MAKE_NAME up-local' to build the images" \
          "from this checkout"
    fi
    docker tag "$released" "${IMAGES[i]}"
  done
}

# The proxy and agent of the latest release, unless it predates what the demo shows.
cmd_up() {
  local release
  check_tools
  release="$(latest_release)" || die "can't tell the latest release from the docs (make site-version)"
  if version_after "$release" "$NEEDS_RELEASE_AFTER"; then
    start_demo "$release"
  else
    wrap "" "" "The latest release, $release, predates this demo, so it runs the proxy and agent built from this" \
      "checkout, which takes a JDK 17 or later."
    start_demo
  fi
}

cmd_up_local() {
  check_tools
  start_demo
}

# Starts the demo, or brings a running one up to date, with a release's proxy and agent (start_demo <version>) or
# with ones built from this checkout (start_demo).
start_demo() {
  local old_ids id
  # The images the demo ran, to remove below once nothing tags them: each build makes new ones, since the jars'
  # BuildConfig carries the build time, and a release's tags replace a build's. One the basics demo still runs is in
  # use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"

  if [[ $# -gt 0 ]]; then
    bold "Starting the demo with the proxy and agent of release $1, from Docker Hub"
    pull_images "$1"
    "${COMPOSE[@]}" up --detach --wait --wait-timeout 180
  else
    check_jdk
    bold "Building the proxy and agent from this checkout"
    "$MAKE" --no-print-directory -C "$REPO_DIR" jars
    "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180
  fi
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
  wait_for "$HQ_DASHBOARD_URL" "agent-hq's dashboard"
  wait_for "$BRANCH_DASHBOARD_URL" "agent-branch's dashboard"
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
  for url in "$DASHBOARD_URL" "$STANDBY_DASHBOARD_URL" "$BRANCH_DASHBOARD_URL" "$GRAFANA_URL" \
    "$PROMETHEUS_URL/alerts"; do
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
  how_it_works "This removes every toxic from every link, recreating a link that holds a cut, enables every link, and" \
    "starts any proxy that's stopped. An agent goes back to the head of its endpoints list only when a connection it" \
    "registered on drops, so for each agent on the standby it disables that agent's link to the standby for a second," \
    "which closes the connection, and the agent reconnects through its link to the primary."
  watch_for
  # Each list may be empty, which bash 3.2, macOS's, takes as unset under set -u unless expanded this way.
  for agent in ${returned[@]+"${returned[@]}"}; do
    point "$agent reconnects through $(primary_link "$agent") within a few seconds, and leaves the standby's" \
      "dashboard. On its own dashboard, the Connection view drops the 'failed over' tag and clears the error" \
      "against $(primary_link "$agent"), and the Activity view adds disconnected, back to primary, and connected:" \
      "$(agent_dashboard "$agent")/activity"
  done
  for agent in ${lost[@]+"${lost[@]}"}; do
    point "$agent hasn't connected to either proxy in 20 seconds. To see why, run: $0 logs $agent"
  done
  if ((${#returned[@]} + ${#lost[@]} == 0)); then
    point "Every agent is on the primary already, and its scrapes are back to normal within a scrape or two (5" \
      "seconds)."
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
  wrap "  " "  " "Each step waits for you to press Enter, so you set the pace. Leave about a minute between parts, so" \
    "the graphs catch up. To start from another part, run $MAKE_NAME tour PART=<n>, or $0 tour <n>."
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
  "4 Two agents reach the proxy over a simulated WAN"
  "2 A high-latency link slows scrapes, but nothing fails"
  "4 A low-bandwidth link fails scrapes; filtering at the agent fixes it"
  "4 The primary proxy stops, and the agents fail over to the standby"
  "4 A link goes silent; the agent detects it and fails over"
  "3 A link that keeps dropping: the agent reconnects, traced in Zipkin"
  "3 The whole WAN goes down: an alert fires, and the agents reconnect"
)

cmd_tour() {
  check_tour_from "${1:-}"
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/network"
  tour_contents "network demo"

  if part 1 "This demo runs the basics demo's proxy and agents with a simulated WAN between them, so you can" \
    "change the network the agents connect through. It builds on the basics demo, so start with that one if" \
    "you haven't seen it."; then
    instruct "The setup: two agents, two proxies, and a WAN between them." \
      "Two agents run behind the firewall: agent-hq, at headquarters, with two small services, and agent-branch, at" \
      "a branch office, with a large inventory payload and a small shipping service. Each agent has two links" \
      "through the WAN, one to the primary proxy and one to its standby, and dials the primary first. The" \
      "scenarios change agent-branch's links and leave agent-hq's alone, so agent-hq is the steady reference."
    instruct "Open the primary proxy's dashboard: $DASHBOARD_URL" \
      "Two agents are connected. Click agent-hq: it's connected 'via hq-to-proxy:50051 (1 of 2)', its link to the" \
      "primary, the first of its two endpoints. agent-branch is connected 'via branch-to-proxy:50053 (1 of 2)'."
    instruct "Open the standby proxy's dashboard: $STANDBY_DASHBOARD_URL" \
      "It has no agents. The standby serves nothing until an agent can't reach the primary."
    instruct "Open agent-branch's own dashboard: $BRANCH_DASHBOARD_URL" \
      "Each agent serves a dashboard of its own, from behind the firewall, showing what only the agent can see. The" \
      "Paths view lists agent-branch's three paths, each registered with the proxy, with a green light and its last" \
      "scrapes under its URL. Click the Connection tab: agent-branch is connected through branch-to-proxy:50053, and" \
      "under Proxy endpoints its two links are listed in failover order, the one in use tagged 'in use'. The later" \
      "parts come back to this view and its Activity tab. agent-hq's dashboard is at $HQ_DASHBOARD_URL"
    instruct "Open Grafana: $GRAFANA_URL" \
      "Every target is up on 'Target health', and 'Scrape time through the WAN, by agent' shows the two agents side" \
      "by side, each well under a tenth of a second."
    instruct "Each of the next parts ends by healing the WAN." \
      "./demo.sh heal removes every change to the WAN, brings back every link and proxy, and returns any agent on" \
      "the standby to the primary, so each part starts from a healthy network."
  fi

  if part 2 "A far-away site adds a round trip's delay to every scrape. Here you add a quarter of a second each" \
    "way to agent-branch's links."; then
    step "Add ${FAR_LATENCY_MS} ms, give or take ${FAR_JITTER_MS} ms, each way to agent-branch's links." -- far
    step "Once you've seen agent-branch's scrape time step up in Grafana, remove the delay." -- heal
  fi

  if part 3 "A thin link can't carry a large payload in time. Here you limit agent-branch's links to" \
    "${THIN_RATE_KBS} KB/s toward the proxies, watch its scrapes fail, and then fix them by filtering at the" \
    "agent."; then
    step "Limit agent-branch's links to ${THIN_RATE_KBS} KB/s toward the proxies." -- thin
    step "Once its paths have failed in Grafana, after about 15 seconds, restart agent-branch with a filter that" \
      "drops the inventory's per-item price list, which no dashboard uses." -- filter
    step "Remove the bandwidth limit." -- heal
    step "Restart agent-branch without the filter, to bring back the full payload." -- unfilter
  fi

  if part 4 "Here you stop the primary proxy. Its connections close, so both agents know at once, and move to" \
    "the standby with nobody touching them."; then
    instruct "Open agent-branch's Connection view beside the standby's dashboard: $BRANCH_DASHBOARD_URL/connection" \
      "The proxies can show an agent only once it has connected to them. This view shows the agent's side of the" \
      "failover as it happens: the endpoint it's on or trying, and the last error it got connecting to each."
    step "Stop the primary proxy." -- proxy-down
    step "Start the primary proxy again." -- proxy-up
    step "Send the agents back to the primary: heal closes each agent's connection through its link to the standby" \
      "once, so the agent reconnects through its link to the primary." -- heal
  fi

  if part 5 "Unlike part 4, nothing closes here. agent-branch's link to the primary stops passing anything, the" \
    "agent's goodbye included, which is what a stateful firewall or NAT does when it drops a connection. Neither" \
    "end is told. It takes about 50 seconds to play out; the basics demo's frozen agent showed the proxy's" \
    "side of an eviction, and this shows the agent's."; then
    instruct "Open agent-branch's Connection view beside the primary's dashboard: $BRANCH_DASHBOARD_URL/connection" \
      "The primary's dashboard shows what the proxy hears from agent-branch, and the Connection view what the agent" \
      "tries. agent-branch is connected through branch-to-proxy:50053, its link to the primary."
    step "Silence agent-branch's link to the primary, without closing it." -- cut
    step "Once agent-branch is on the standby's dashboard, after about 50 seconds, restore the link and send it" \
      "back to the primary." -- heal
  fi

  if part 6 "Here agent-branch's link to the primary closes its connection about every 15 seconds. The agent" \
    "reconnects each time, and Zipkin shows every reconnect."; then
    step "Make agent-branch's link to the primary close each connection after about 15 seconds of traffic." \
      -- flaky
    step "Once you've looked at the traces in Zipkin, restore the link." -- heal
  fi

  if part 7 "Here every link goes down at once, as if the WAN failed, and then comes back."; then
    step "Take every link down." -- wan-down
    step "Once NetworkNoAgentsConnected fires in Grafana, after about 20 seconds, bring every link back." -- wan-up
    step "Return any agent that landed on the standby to the primary." -- heal
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  bold "That's the tour."
  echo "To start again from a part, run $MAKE_NAME tour PART=<n>, or $0 tour <n>."
  echo
  bold "The other demo:"
  echo "The same proxy and agent, deployed on Kubernetes with their Helm charts, are in the Kubernetes demo, in" \
    "demos/kubernetes, which needs kind, kubectl, and Helm as well as Docker."
  echo
  echo "The demos use different ports, so they can run side by side, but each takes memory and CPU."
  echo "To stop this one and free its resources, run $MAKE_NAME down, or $0 down."
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
  logs) "${COMPOSE[@]}" logs --follow --tail 50 "$@" ;;
  tour) cmd_tour "$@" ;;
  far)
    local agent link
    agent="$(agent_arg "${1:-}")"
    for link in "$(primary_link "$agent")" "$(standby_link "$agent")"; do
      add_toxic "$link" far-up upstream latency "latency=$FAR_LATENCY_MS" "jitter=$FAR_JITTER_MS"
      add_toxic "$link" far-down downstream latency "latency=$FAR_LATENCY_MS" "jitter=$FAR_JITTER_MS"
    done
    bold "$agent's links now add ${FAR_LATENCY_MS} ms, give or take ${FAR_JITTER_MS} ms, each way"
    how_it_works "Each agent reaches each proxy only through a link of Toxiproxy, the wan service, which relays the" \
      "connection and can be told to misbehave. This adds a latency toxic to both directions of $agent's two links," \
      "$(primary_link "$agent") and $(standby_link "$agent"), which holds every piece of data for" \
      "${FAR_LATENCY_MS} ms, give or take ${FAR_JITTER_MS}, before passing it on."
    watch_for
    point "In Grafana's 'Scrape time through the WAN, by agent' panel, $agent's line steps up from under 0.1" \
      "seconds to about 0.5: one round trip more. $(other_agent "$agent")'s line stays flat."
    point "In $agent's 'Recent scrapes' on the proxy's dashboard, every scrape still succeeds."
    point "Nothing fails, because the agent's connection is already open: a scrape needs no new handshake through" \
      "the firewall, so it costs one round trip and nothing more."
    follow_up "To undo it, run" "$0 heal"
    ;;
  thin)
    local agent link
    agent="$(agent_arg "${1:-}")"
    for link in "$(primary_link "$agent")" "$(standby_link "$agent")"; do
      add_toxic "$link" thin-up upstream bandwidth "rate=$THIN_RATE_KBS"
    done
    bold "$agent's links now carry ${THIN_RATE_KBS} KB/s toward the proxies"
    how_it_works "Each agent reaches each proxy only through a link of Toxiproxy, the wan service, which relays the" \
      "connection and can be told to misbehave. This adds a bandwidth toxic to $agent's two links," \
      "$(primary_link "$agent") and $(standby_link "$agent"), in the direction toward the proxies only, which passes" \
      "at most ${THIN_RATE_KBS} KB a second. Scrape requests still reach the agent at full speed; its answers crawl" \
      "back."
    watch_for
    if [[ "$agent" == agent-branch ]]; then
      point "The inventory payload is about 56 KB gzipped, which takes 7 seconds on this link, and Prometheus" \
        "gives up after 4."
      point "Every path shares the link, so they back up behind the inventory: within about 15 seconds the shipping" \
        "service and the agent's own metrics fail too, and at about 30 seconds NetworkTargetDown fires for all three."
      point "agent-branch stays connected the whole time. Its answers keep arriving, too late for Prometheus, and" \
        "each one tells the proxy the agent is alive, so on the proxy's dashboard its 'idle' time stays near zero" \
        "and nothing is evicted: a saturated link is slow, not dead."
      point "agent-branch's own dashboard shows the other side. On its Paths view every path's last scrape is a" \
        "success, fetched from its target in a fraction of a second: the targets are fine, and the time goes on the" \
        "link. Open it at $BRANCH_DASHBOARD_URL"
      point "agent-hq, on its own links, is untouched."
      follow_up "Next, run" "$0 filter"
    else
      point "$agent's payloads are small, so they still fit: its scrapes take a little longer, and none fails."
      follow_up "To undo it, run" "$0 heal"
    fi
    ;;
  filter)
    restart_branch agent-branch-filtered.conf
    bold "agent-branch restarted with a filter that drops inventory_item_price_dollars"
    how_it_works "This recreates agent-branch's container with config/agent-branch-filtered.conf in place of" \
      "config/agent-branch.conf. The new config adds an agent.filters entry for /inventory_metrics that drops the" \
      "inventory_item_price_dollars metric family from every scrape, at the agent, before it compresses the payload" \
      "and sends it across the WAN."
    watch_for
    point "The agent drops that metric family before it compresses and chunks the payload. In agent-branch's" \
      "'Recent scrapes' on the proxy's dashboard, the inventory falls from about 496,000 characters to about" \
      "199,000, 21 KB gzipped."
    point "On the thin link each scrape now takes about 3 seconds, inside Prometheus's 4, so every path is green" \
      "again within about 5 seconds. Grafana's 'Filter bytes saved at the agent' climbs to about 60 KB/s: what never" \
      "crosses the WAN costs nothing to send, store, or query."
    point "The restarted agent connects at once. Its old connection stays on the proxy dashboard's agent list, with" \
      "no paths, for about 20 seconds more, until what it had queued drains through the thin link."
    point "agent-branch's own dashboard reconnects to the restarted agent by itself. On the inventory path's page it" \
      "shows the filter's rules and how many lines and bytes it has dropped so far, and its Recent scrapes give each" \
      "body's size before and after the filter: $BRANCH_DASHBOARD_URL/paths/inventory_metrics"
    follow_up "To remove the filter, run" "$0 unfilter"
    ;;
  unfilter)
    restart_branch agent-branch.conf
    bold "agent-branch restarted without the filter"
    how_it_works "This recreates agent-branch's container with config/agent-branch.conf, which has no filter."
    watch_for
    point "The inventory payload returns to about 496,000 characters in agent-branch's 'Recent scrapes' on the" \
      "proxy's dashboard."
    ;;
  proxy-down)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" stop "$proxy"
    bold "$proxy stopped"
    how_it_works "This runs docker compose stop $proxy, which shuts the proxy down. Its connections close as it" \
      "exits, and an agent whose connection closed dials the next endpoint in its agent.proxy.endpoints list."
    watch_for
    if [[ "$proxy" == proxy ]]; then
      point "The primary's connections close, so both agents know at once, and fail over within about 4 seconds" \
        "with nobody touching them."
      point "The standby's dashboard shows each agent 'via' its link to the standby, '(2 of 2)', marked" \
        "'failed over': $STANDBY_DASHBOARD_URL"
      point "agent-branch's own dashboard shows the agent's side, which no proxy can. On its Connection view it reads" \
        "connecting, then failing, as its connect through branch-to-proxy fails, and then connected through" \
        "branch-to-standby, marked 'failed over'. Under Proxy endpoints, branch-to-proxy keeps the error its connect" \
        "got: a proxy that's down shows there, not only in the agent's log. The view is at" \
        "$BRANCH_DASHBOARD_URL/connection"
      point "Its Activity view lists what the agent went through, newest first: disconnected, connect failed on" \
        "branch-to-proxy, failed over from branch-to-proxy to branch-to-standby, connected, and registered, one line" \
        "for its three paths. agent-hq's dashboard shows the same for its own links: $HQ_DASHBOARD_URL/activity"
      point "In Grafana, every service is up again on 'Target health' within about 15 seconds, scraped through the" \
        "standby, and 'Connected agents, by proxy' moves to the standby. Only prometheus-proxy, the primary's own" \
        "metrics, stays down, and NetworkTargetDown fires for it alone."
      follow_up "To bring it back, run" "$0 proxy-up"
    else
      point "The standby had no agents, so nothing moves. Any agent connected to it would return to the primary."
      follow_up "To bring it back, run" "$0 proxy-up proxy-standby"
    fi
    ;;
  proxy-up)
    local proxy
    proxy="$(proxy_arg "${1:-}")"
    "${COMPOSE[@]}" start "$proxy"
    bold "$proxy started"
    how_it_works "This runs docker compose start $proxy. Starting a proxy draws no agent to it: an agent changes" \
      "proxies only when its own connection drops."
    watch_for
    if [[ "$proxy" == proxy ]]; then
      point "The primary is back, but the agents stay on the standby: an agent goes back to the head of its list" \
        "only when its connection drops."
      point "agent-branch's Connection view doesn't change. The agent stays on branch-to-standby, and branch-to-proxy" \
        "keeps its error, since only a connect through it clears that: $BRANCH_DASHBOARD_URL/connection"
      follow_up "To send them back to the primary, run" "$0 heal"
    else
      point "The standby is ready again, and has no agents until one can't connect to the primary."
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
    how_it_works "This adds four Toxiproxy toxics to $link, $agent's link to the primary. Two timeout toxics with a" \
      "timeout of 0 drop all data both ways without closing the connection, and two slow_close toxics hold any close" \
      "for an hour, the agent's own included. To both ends the connection stays open but silent, as when a firewall" \
      "drops a connection without telling either side, so only timeouts can find it: the agent's heartbeat and" \
      "connect deadlines, and the proxy's eviction of an agent it hasn't heard from in 30 seconds."
    watch_for
    point "Nothing tells the agent. Within about 10 seconds its scrapes start timing out, and on the primary's" \
      "dashboard its 'idle' time climbs and its 'evicted in' countdown runs."
    point "The agent's heartbeat fails three times, with a 5-second deadline each, so at about 20 seconds it gives" \
      "up on the connection. It dials the head of its list again, the same dead link, where the connect waits out" \
      "its 30-second deadline."
    point "$agent's own Connection view shows that wait: from about 20 seconds it reads connecting, with" \
      "$(primary_link "$agent") tagged 'trying', for the 30 seconds the connect takes to give up. Then that endpoint" \
      "gets its error, DEADLINE_EXCEEDED, and the agent fails over. The view is at" \
      "$(agent_dashboard "$agent")/connection"
    point "Meanwhile the primary, which hears nothing, evicts the agent at about 30 seconds, and NetworkAgentEvicted" \
      "fires."
    point "At about 50 seconds the agent connects through $(standby_link "$agent"), and the standby's dashboard" \
      "shows it '(2 of 2)', 'failed over', while the primary is still up. $(other_agent "$agent") is untouched" \
      "throughout."
    point "$agent's Activity view keeps the whole sequence, newest first: disconnected, connect failed on" \
      "$(primary_link "$agent"), failed over to $(standby_link "$agent"), connected, and registered. The proxies" \
      "saw only the eviction and the new connection: $(agent_dashboard "$agent")/activity"
    follow_up "To undo it, run" "$0 heal"
    ;;
  flaky)
    local agent link bytes
    agent="$(agent_arg "${1:-}")"
    link="$(primary_link "$agent")"
    bytes="$(flaky_bytes "$agent")"
    add_toxic "$link" flaky upstream limit_data "bytes=$bytes"
    bold "$link now closes each connection once it has carried $((bytes / 1000)) KB toward the proxy"
    how_it_works "This adds a Toxiproxy limit_data toxic to $link, $agent's link to the primary, which closes each" \
      "connection through it once that connection has carried $((bytes / 1000)) KB toward the proxy. The count" \
      "starts over with each new connection, so the link keeps dropping the agent at about the same interval."
    watch_for
    point "That's about 15 seconds of $agent's traffic. Each time the link closes, the agent reconnects within" \
      "milliseconds and stays on the primary: a connection that registered and then dropped retries from the head" \
      "of the list."
    point "In Grafana, 'Agent connects, by agent' climbs. Most scrapes succeed; the one in flight as a connection" \
      "drops fails as agent_disconnected in 'Scrape outcomes at the proxies'."
    point "On $agent's own Connection view, the line under its state counts its connects, one more with each drop," \
      "and the agent stays on $(primary_link "$agent"), with no 'failed over' tag. Its Activity view shows each drop" \
      "as disconnected, then connected and registered: $(agent_dashboard "$agent")/activity"
    point "Every proxy and agent reports a span to Zipkin for each gRPC call between them, under its own service" \
      "name. In Zipkin, the search is serviceName = $agent. Open this link, which fills that search in, and click" \
      "Run Query: $ZIPKIN_URL/zipkin/?serviceName=$agent"
    point "Each reconnect is a connectagent trace, followed by registeragent and a registerpath for each path." \
      "Zipkin joins the agent's side and the proxy's side of each call into one span, so the trace names both."
    follow_up "To undo it, run" "$0 heal"
    ;;
  wan-down)
    local link
    for link in "${LINKS[@]}"; do set_link "$link" disabled; done
    bold "Every link is down"
    how_it_works "This disables all four of Toxiproxy's links. A disabled link closes every connection through it and" \
      "refuses new ones, so both agents lose both proxies at once, as in a WAN outage. The proxies, Prometheus, and" \
      "Grafana keep running on their side of the WAN."
    watch_for
    point "Both agents leave both proxies' dashboards at once, and retry every 3 seconds, alternating between their" \
      "two links."
    point "Their paths leave the proxies' /discovery, so within about 15 seconds their targets vanish from" \
      "Prometheus rather than staying red: on Grafana's 'Target health' their rows go red for a scrape or two, then" \
      "stop."
    point "Prometheus scrapes the proxies directly, on its own side of the WAN, so it still sees them. The 'Agents'" \
      "stat reads 0, and at about 20 seconds NetworkNoAgentsConnected fires."
    point "The proxies can't show an agent that can't reach them, but its own dashboard can. On agent-branch's" \
      "Connection view the state reads failing, and the endpoint it's trying alternates between its two links, each" \
      "with the error from its last connect: $BRANCH_DASHBOARD_URL/connection"
    point "Its Activity view records disconnected, then connect failed once for each link, since a repeat of the" \
      "same failure isn't listed again, and failed over, which repeats on each pass back through its link to the" \
      "primary, counted on one line rather than listed each time: $BRANCH_DASHBOARD_URL/activity"
    follow_up "To bring the links back, run" "$0 wan-up"
    ;;
  wan-up)
    local link
    for link in "${LINKS[@]}"; do set_link "$link" enabled; done
    bold "Every link is back"
    how_it_works "This enables all four links again, and each agent's next retry connects through whichever of its" \
      "links it tries next."
    watch_for
    point "Within a few seconds each agent reconnects, through whichever of its links it tries next, so one may" \
      "land on the standby."
    point "agent-branch's Connection view reads connected again, marked 'failed over' if it reached its link to the" \
      "standby. Only the endpoint it connected through loses its error; the other keeps its error until a connect" \
      "through it succeeds: $BRANCH_DASHBOARD_URL/connection"
    point "Their targets return, NetworkNoAgentsConnected resolves, and the graphs keep a gap where nothing could" \
      "be scraped."
    follow_up "To return any agent on the standby to the primary, run" "$0 heal"
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
