#!/usr/bin/env bash
# Drives the Prometheus Proxy basics demo (see README.md): starts and stops it, and runs the scenarios a presenter
# shows. The Makefile beside it wraps the lifecycle commands (`make up` runs `demo.sh up`).
# Run with no arguments for the list.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
# The make that runs the repository's targets: the one that ran this script, which the Makefile beside it exports as
# MAKE, or, run directly, make, or else gmake, the name GNU make has on some systems. MAKE_NAME is the one the
# messages tell the reader to type.
MAKE="${MAKE:-$(command -v make || command -v gmake || echo make)}"
MAKE_NAME="${MAKE##*/}"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
# Where up keeps the host ports moved with the *_PORT variables (docker/compose.yml's list), so each later command, and
# docker compose, which reads this file on its own, uses them with no variable set again. Not committed. Delete a
# port's line to go back to its default.
PORTS_FILE="$DEMO_DIR/docker/.env"

# Takes each port saved in docker/.env that the environment doesn't set, so the URLs below match what compose
# publishes. A variable set on the command line wins, and up saves it in turn.
load_saved_ports() {
  local name value
  [[ -f "$PORTS_FILE" ]] || return 0
  while IFS='=' read -r name value || [[ -n "$name" ]]; do
    if [[ "$name" =~ ^[A-Z0-9_]+_PORT$ && -z "${!name:-}" ]]; then
      export "$name=$value"
    fi
  done <"$PORTS_FILE"
}
load_saved_ports

# Saves in docker/.env each port variable compose.yml reads that the environment sets, in place of its old line,
# keeping the file's other lines: save_ports
save_ports() {
  local name saved=""
  local -a lines=()
  while IFS= read -r name; do
    if [[ -n "${!name:-}" ]]; then
      lines+=("$name=${!name}")
      saved+="|$name"
    fi
  done < <(grep -oE '\$\{[A-Z0-9_]+_PORT:-' "$DEMO_DIR/docker/compose.yml" | sed 's/^\${//; s/:-$//' | sort -u)
  ((${#lines[@]})) || return 0
  (
    umask 077
    {
      # The file's other lines; grep finds none when every line is a port saved again, which isn't a failure
      [[ ! -f "$PORTS_FILE" ]] || grep -vE "^(${saved#|})=" "$PORTS_FILE" || true
      printf '%s\n' "${lines[@]}"
    } >"$PORTS_FILE.new"
    mv "$PORTS_FILE.new" "$PORTS_FILE"
  )
}
# The proxy and agent images the demo runs, under these tags whichever way they came: pulled from Docker Hub (up)
# or built from this checkout (up-local). The other demos run the same tags, so there's one copy of each.
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
# Their releases on Docker Hub, in the same order, which up pulls at the latest release.
RELEASED_IMAGES=(pambrose/prometheus-proxy pambrose/prometheus-agent)
# The newest release that lacks something the demo shows. up runs the release the docs give (make site-version)
# once it's later than this and its images are on Docker Hub, and otherwise builds the proxy and agent from this
# checkout, as up-local does, so nothing here changes with a release. When the demo comes to use something no
# release has yet, set this to the latest release on Docker Hub.
NEEDS_RELEASE_AFTER=4.6.0
# The dashboard password the login scenario gives the proxy. A demo's, in a public file, as Grafana's admin password
# is: never use it anywhere else.
LOGIN_PASSWORD="admin"
AGENTS=(agent-east agent-west-1 agent-west-2)
# The agent the agent-* scenarios act on when given none.
DEFAULT_AGENT="agent-west-2"
# The services only a scenario starts, so down and status cover them too.
ON_DEMAND=(--profile rogue --profile duplicate)

# This demo's page on the docs site, which shows its layout and what each part of its tour shows. The demo points only
# there, never to a raw Markdown file, which isn't readable without a viewer.
PAGE_URL="https://prometheus-proxy.com/basics-demo/"
DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"
PROXY_URL="http://localhost:${DEMO_PROXY_PORT:-8080}"
# Each agent's own dashboard, on a port of its own (agent.dashboard in its config, published in docker/compose.yml).
EAST_DASHBOARD_URL="http://localhost:${DEMO_AGENT_EAST_DASHBOARD_PORT:-8095}/dashboard"
WEST_1_DASHBOARD_URL="http://localhost:${DEMO_AGENT_WEST_1_DASHBOARD_PORT:-8096}/dashboard"
WEST_2_DASHBOARD_URL="http://localhost:${DEMO_AGENT_WEST_2_DASHBOARD_PORT:-8097}/dashboard"
ROGUE_DASHBOARD_URL="http://localhost:${DEMO_AGENT_ROGUE_DASHBOARD_PORT:-8098}/dashboard"
COPY_DASHBOARD_URL="http://localhost:${DEMO_AGENT_EAST_COPY_DASHBOARD_PORT:-8099}/dashboard"

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
  up                       Start the demo with the proxy and agent released on Docker Hub
  up-local                 Start it with the proxy and agent built from this checkout (needs a JDK 17 or later)
  down                     Stop the demo and remove its containers, network, and volume
  status                   Show the containers, the simulated services' state, and the URLs
  open                     Open the proxy's and agent-east's dashboards, Grafana, and Prometheus in the browser
  logs [service]           Follow the logs (proxy, agent-east, demo-app, ...)
  tour [part]              Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

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
  proxy-down               Stop the proxy, so every agent loses it
  proxy-up                 Start it again; the agents reconnect
  duplicate-agent          Start a second copy of agent-east, without consolidated mode
  duplicate-agent-stop     Remove it; agent-east takes its paths back
  login                    Restart the proxy with the dashboard's login on (admin, password $LOGIN_PASSWORD)
  login-off                Restart it with the login off

Services: orders, payments, inventory, catalog, shipping (and search, recommendations, notifications once discovered)
Agents:   ${AGENTS[*]}
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy basics demo"
  echo "  Proxy dashboard:  $DASHBOARD_URL"
  echo "  Agent dashboards: $EAST_DASHBOARD_URL  (agent-east)"
  echo "                    $WEST_1_DASHBOARD_URL  (agent-west-1)"
  echo "                    $WEST_2_DASHBOARD_URL  (agent-west-2)"
  echo "  Grafana:          $GRAFANA_URL"
  echo "  Prometheus:       $PROMETHEUS_URL"
  echo "  Proxy discovery:  $PROXY_URL/discovery"
  echo
  wrap "" "" "What each part of this demo shows, and why: ${PAGE_URL}#what-this-demo-shows"
  echo
  echo "Scenarios: $0 outage | slow | discover | agent-freeze | rogue-agent ...  (no arguments for help)"
  echo "Or walk through them all: $MAKE_NAME tour, or $0 tour"
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

# Whether $1 answers with a status below 400, as curl -f would count it. With login as $2, a 401 counts too: the
# dashboard sends it a request without a session once ./demo.sh login has turned its login on, so the proxy is up all
# the same. answers <url> [login]
answers() {
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" || return 1
  ((code < 400)) || [[ ${2:-} == login && $code == 401 ]]
}

# Waits up to 2 minutes for a URL to answer (see answers): wait_for <url> <what it is> [login]
wait_for() {
  local url="$1" name="$2" accept="${3:-}" deadline=$((SECONDS + 120))
  until answers "$url" "$accept"; do
    ((SECONDS < deadline)) || die "$name did not answer within 120s; see '$0 logs'. It was asked at $url"
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

# The release the docs give: the latest, or, from a version bump until the release is out, the next, whose images
# aren't on Docker Hub yet.
latest_release() { "$MAKE" -s --no-print-directory -C "$REPO_DIR" site-version; }

# Whether a release's images are published: published <version> <image>... Docker has them already, since a
# release's images don't change and a demo started again without a network connection uses them, or Docker Hub has
# them. Only Docker Hub's answer that it has no such image says no, as from a version bump until its release is out;
# when Docker Hub can't be reached, the pull says so.
published() {
  local release="$1" image answer
  shift
  for image in "$@"; do
    docker image inspect "$image:$release" >/dev/null 2>&1 && continue
    answer="$(docker manifest inspect "$image:$release" 2>&1 >/dev/null)" && continue
    [[ "$answer" != *"no such manifest"* ]] || return 1
  done
}

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not on the PATH; $2"; }

# The tools up and up-local need, checked before anything is pulled, built, or started. Without curl, the wait for
# each service would retry for two minutes and then blame the service.
check_tools() {
  need docker "install Docker: https://docs.docker.com/get-started/get-docker/"
  docker info >/dev/null 2>&1 || die "Docker isn't running"
  docker compose version >/dev/null 2>&1 ||
    die "the demo needs Docker Compose v2, the docker compose plugin: https://docs.docker.com/compose/install/"
  need curl "install curl; the demo uses it to wait for each service to answer"
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

# The proxy and agent of the release the docs give, unless it predates what the demo shows or isn't on Docker Hub yet.
cmd_up() {
  local release
  check_tools
  release="$(latest_release)" || die "can't tell the latest release from the docs (make site-version)"
  if ! version_after "$release" "$NEEDS_RELEASE_AFTER"; then
    wrap "" "" "The latest release, $release, predates this demo, so it runs the proxy and agent built from this" \
      "checkout, which takes a JDK 17 or later."
    start_demo
  elif ! published "$release" "${RELEASED_IMAGES[@]}"; then
    wrap "" "" "Release $release, which the docs give, isn't on Docker Hub yet, so the demo runs the proxy and agent" \
      "built from this checkout, which takes a JDK 17 or later."
    start_demo
  else
    start_demo "$release"
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
  save_ports
  # The images the demo ran, to remove below once nothing tags them: a build from changed code makes new ones, and a
  # release's tags replace a build's. One another demo still runs is in use, and stays.
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
  # Grafana for its dashboards, so a demo started from an older checkout runs this one's.
  "${COMPOSE[@]}" --progress quiet kill --signal SIGHUP prometheus >/dev/null
  "${COMPOSE[@]}" --progress quiet restart grafana >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$DASHBOARD_URL" "The proxy dashboard" login
  wait_for "$EAST_DASHBOARD_URL" "agent-east's dashboard"
  wait_for "$WEST_1_DASHBOARD_URL" "agent-west-1's dashboard"
  wait_for "$WEST_2_DASHBOARD_URL" "agent-west-2's dashboard"
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
  for url in "$DASHBOARD_URL" "$EAST_DASHBOARD_URL" "$GRAFANA_URL" "$PROMETHEUS_URL/alerts"; do
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
    "the graphs catch up. To start from another part, run $MAKE_NAME tour PART=<n>, or $0 tour <n>."
  wrap "  " "  " "What each part shows, and why: ${PAGE_URL}#what-this-demo-shows"
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
  "2 How Prometheus scrapes services behind a firewall"
  "4 Agents, paths, and live scrapes on the proxy dashboard"
  "3 Target health and business metrics in Grafana"
  "3 An outage and a timeout"
  "2 Two agents, one path"
  "2 New services, no restart"
  "2 A silent agent is evicted"
  "2 Per-team tokens"
  "4 A refused path, a lost proxy"
  "2 A duplicate agent"
  "2 A login for the dashboard"
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
  answers "$DASHBOARD_URL" login ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/basics"
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
    instruct "What runs in this demo, as the diagram at the top of its page draws it: ${PAGE_URL}" \
      "Three agents run behind the firewall. agent-east, in the east data center, scrapes the orders, payments," \
      "inventory, and catalog services. agent-west-1 and agent-west-2, in the west data center, are a redundant" \
      "pair that both serve the shipping service. Outside the firewall are the proxy, Prometheus, and Grafana."
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
      "Each payload is about 480,000 characters, large enough that the agent sends it to the proxy in chunks, so" \
      "however large a service's metrics, no one gRPC message has to hold them."
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
    instruct "Open Grafana, and look at the section titled 'Through the firewall': $GRAFANA_URL" \
      "Grafana opens on the 'Prometheus Proxy Demo' dashboard, with no sign-in needed. The section shows the health" \
      "of every target, the outcome of every scrape, and each path's scrape latency and payload size."
    instruct "Look at the section titled 'Business metrics from behind the firewall'." \
      "It shows orders, payments, inventory, and shipping. These are ordinary application metrics, which" \
      "Prometheus collected through the proxy."
    instruct "Note the 'Prometheus Proxy' and 'Prometheus Agents' links at the top of Grafana's dashboard." \
      "They open the dashboards the project ships for operating the proxy and its agents."
    instruct "Open the proxy's service discovery: $PROXY_URL/discovery" \
      "Prometheus's config lists no targets. Instead it asks the proxy what exists, and this is the answer: every" \
      "path the agents registered, with its labels. Each path's labels set the job."
  fi

  if part 4 "Here you break two of the services, one at a time, and see that the proxy reports each failure" \
    "clearly while the other services keep answering."; then
    step "Put the proxy dashboard's Paths view beside Grafana, then make the payments service answer every scrape" \
      "with an error." \
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
    instruct "Search the proxy dashboard's Activity view for agent-rogue: $DASHBOARD_URL/activity?q=agent-rogue" \
      "It lists agent-rogue connected, its /contractor_metrics registered, and its /payments_metrics rejected, with" \
      "the proxy's reason. The Agents and Paths views can't show a refusal, since the proxy added nothing."
  fi

  if part 9 "The proxy's dashboard shows only what reaches the proxy. A path the proxy refused, or a proxy an agent" \
    "can't reach, shows on the agent's side, so each agent in this demo serves a dashboard of its own, on a port of" \
    "its own. Here you read agent-rogue's refusal on its own dashboard, then stop the proxy and watch agent-east" \
    "lose it and get it back."; then
    instruct "Open agent-rogue's dashboard: $ROGUE_DASHBOARD_URL" \
      "It opens on the Paths view, which lists every path the agent has, each with a light that's green while the" \
      "proxy routes the path's scrapes to this agent. /contractor_metrics is green. /payments_metrics is red, tagged" \
      "refused: the refusal the proxy's Activity view showed, seen from the agent's side."
    instruct "Click /payments_metrics." \
      "Its details open. Under 'Refused by the proxy' is the proxy's reason: Agent identity 'contractor' is not" \
      "authorized to register path /payments_metrics. Asking again wouldn't change the answer, so the agent doesn't" \
      "try the path again until it reconnects, as 'Next try' says."
    instruct "Switch to the Activity view." \
      "It lists what has happened to the agent, newest first. Its entries show the agent connecting to the proxy," \
      "registering /contractor_metrics, and being refused /payments_metrics, with the same reason. Checking Problems" \
      "only leaves just the refusal."
    put_back "stop and remove the contractor's agent" rogue-agent-stop
    instruct "Open agent-east's dashboard on its Connection view: $EAST_DASHBOARD_URL/connection" \
      "agent-east is connected to proxy:50051, its one proxy endpoint. Below that is its discovery file. The settings" \
      "it resolved are on its Settings view: $EAST_DASHBOARD_URL/settings"
    step "Stop the proxy, as an outage or a bad deploy would. The proxy's dashboard goes with it, but agent-east's" \
      "keeps answering, since it runs on the agent." -- proxy-down
    instruct "On agent-east's dashboard, open the Paths view, click /orders_metrics, then Scrape now." \
      "Scrape now is the circular arrow after the path's name in its details. The agent fetches the orders service" \
      "at once and shows the result: status 200, the metric families and samples, and the start of the body" \
      "Prometheus would get. The target is fine; what's broken is the way to the proxy. Scrape now works whether or" \
      "not the proxy has the path, so it checks a target even while the agent can't connect."
    step "Start the proxy again." -- proxy-up
  fi

  if part 10 "Someone deploys agent-east's config a second time by mistake, without consolidated mode. Here you" \
    "see what the proxy does with the two agents, and what it tells the operator."; then
    step "Start a second copy of agent-east, without consolidated mode." -- duplicate-agent
    step "Remove the copy, and watch agent-east take its paths back." -- duplicate-agent-stop
  fi

  if part 11 "The proxy's dashboard can ask for a login, with a password from the proxy's environment. Here you" \
    "turn it on, sign in and out, and turn it off again. It comes last because it restarts the proxy."; then
    step "Restart the proxy with a password for its dashboard." -- login
    instruct "Reload the dashboard: $DASHBOARD_URL" \
      "It shows the login page instead. Sign in as admin, with the password $LOGIN_PASSWORD, and it opens the page" \
      "you asked for, with the agents reconnected. Log out, the exit icon at the right end of the top bar, ends your" \
      "session and brings the login page back."
    put_back "restart the proxy without the password" login-off
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending basics
}

# Restarts only the proxy, with the dashboard password given, or with none, which turns its login off. Compose
# recreates the container for the changed environment; the agents reconnect on their own. restart_proxy <password>
restart_proxy() {
  DEMO_DASHBOARD_PASSWORD="$1" "${COMPOSE[@]}" up --detach --no-deps --no-build proxy
  wait_for "$DASHBOARD_URL" "The proxy dashboard" login
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
    point "In Grafana, the $service row on 'Target health' turns DOWN. After 15 seconds the DemoTargetDown alert" \
      "fires: it shows in the 'Alerts' stat, in the 'Firing alerts' table, and on Prometheus's alerts page."
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
    point "$agent leaves the dashboard's Agents view at once. Each path that only it served stays on the Paths view," \
      "listed as 'gone' for an hour (proxy.dashboard.departedPathRetentionSecs), so it's clear what left."
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
    if [[ -n "$other" ]]; then
      point "After 30 seconds of silence the proxy evicts $agent, and the DemoAgentEvicted alert fires. Its own path," \
        "/${agent//-/_}_metrics, shows as 'gone' on the Paths view, and Grafana's 'Agent disconnects at the proxy," \
        "by reason' shows it as an eviction."
      point "$other carries /shipping_metrics alone from then on."
    else
      point "After 30 seconds of silence the proxy evicts agent-east, and every path it served, the six in its config" \
        "and any it discovered, shows as 'gone' on the Paths view. The proxy dashboard's Activity view lists the" \
        "eviction as a problem, with how many paths the proxy released."
      point "The proxy's own metrics reach Prometheus through agent-east's /proxy_metrics path, so they stop with it." \
        "The DemoAgentEvicted alert and Grafana's panels of the proxy's metrics, such as 'Agent disconnects at the" \
        "proxy, by reason', can't update while agent-east is gone. Thaw it within a minute of the eviction and they" \
        "catch up: the alert fires, and the panel shows the eviction. The alert's rule looks back two minutes and the" \
        "panel five, so a longer freeze leaves both without it; freeze agent-west-2 instead to see the alert fire."
      point "Meanwhile DemoTargetDown fires for agent-east's jobs, and ProxyDown goes pending: Prometheus reads" \
        "up{job=\"prometheus-proxy\"} through agent-east too, so it sees 0 though the proxy itself is up."
    fi
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
    point "agent-rogue's own dashboard shows the refusal from its side: /payments_metrics tagged refused, and the" \
      "proxy's reason once you select it, at $ROGUE_DASHBOARD_URL"
    point "Grafana's 'Path rejections at the proxy, by cause' counts the refusal as not_authorized."
    follow_up "To remove it, run" "$0 rogue-agent-stop"
    proxy_said "denied registration of path /payments_metrics"
    ;;
  rogue-agent-stop)
    "${COMPOSE[@]}" --profile rogue rm --stop --force agent-rogue
    bold "agent-rogue stopped and removed"
    how_it_works "This stops and removes the agent-rogue container. Its connection to the proxy closes as it exits," \
      "so the proxy removes the agent and its path at once."
    watch_for
    point "agent-rogue leaves the dashboard at once. /contractor_metrics stays on the Paths view as 'gone' for an" \
      "hour (proxy.dashboard.departedPathRetentionSecs), and leaves the proxy's /discovery at once."
    ;;
  proxy-down)
    "${COMPOSE[@]}" stop proxy
    bold "The proxy stopped: every agent has lost it"
    how_it_works "This runs docker compose stop proxy, which stops the proxy's container, as an outage or a bad" \
      "deploy would. Each agent's gRPC connection closes as the proxy exits, and the agent tries to connect again" \
      "every 3 seconds (agent.internal.reconnectPauseSecs) until the proxy is back. An agent's dashboard runs on the" \
      "agent, so it keeps answering, and shows what the agent sees."
    watch_for
    point "Within a few seconds, agent-east's Connection view turns from connected to failing, and proxy:50051 shows" \
      "the error the agent got trying it, at $EAST_DASHBOARD_URL/connection"
    point "Its Activity view lists disconnected, then connect failed, which carries the same error. The feed lists" \
      "the failure once, not each retry."
    point "On its Paths view, every path's light turns red, tagged pending: with no proxy, none is registered."
    point "The proxy's dashboard doesn't answer, and Prometheus can't reach the proxy either: in Grafana, every" \
      "target turns DOWN on 'Target health'."
    follow_up "To start it again, run" "$0 proxy-up"
    ;;
  proxy-up)
    "${COMPOSE[@]}" start proxy
    wait_for "$DASHBOARD_URL" "The proxy dashboard" login
    bold "The proxy started again"
    how_it_works "This runs docker compose start proxy, which starts the stopped container again, as it was. A new" \
      "proxy holds nothing from the old one: each agent connects on its next try and registers its paths again, and" \
      "the proxy's dashboard starts its histories over."
    watch_for
    point "Within a few seconds, agent-east's Connection view turns back to connected, and on its Paths view every" \
      "path's light turns green again: each one is registered."
    point "Its Activity view lists connected, then its paths registered."
    point "The agents are back on the proxy's dashboard, each with its paths, and Grafana's targets turn green again."
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
    point "The two settle. agent-east stays connected without paths and waits, rather than the two taking the paths" \
      "from each other every few seconds."
    point "The proxy told agent-east. Its own dashboard lists its six paths as retrying, each taken over by" \
      "agent-east-copy, and its Activity view has the takeover, at $EAST_DASHBOARD_URL"
    point "The proxy's log line, printed below, names both agents by agentId and gives the fix: set" \
      "agent.consolidated = true on each, if both should serve the paths."
    point "The copy's own dashboard lists the six paths, each with a green light, since the proxy accepted every one," \
      "at $COPY_DASHBOARD_URL"
    follow_up "To undo it, run" "$0 duplicate-agent-stop"
    proxy_said "keeps its connection without paths"
    ;;
  duplicate-agent-stop)
    "${COMPOSE[@]}" --profile duplicate rm --stop --force agent-east-copy
    bold "agent-east-copy removed"
    how_it_works "This stops and removes the agent-east-copy container, and its paths leave with it. The proxy tells" \
      "agent-east, which was waiting for them, that no agent serves them any more."
    watch_for
    point "agent-east takes its six paths back within seconds, on the connection it kept, with no restart: its" \
      "dashboard lists them registered again, and the proxy's shows agent-east serving them."
    ;;
  "" | help | -h | --help) usage ;;
  login)
    restart_proxy "$LOGIN_PASSWORD"
    bold "The dashboard now asks for a login: admin, with the password $LOGIN_PASSWORD"
    how_it_works "This restarts the proxy with DASHBOARD_PASSWORD in its environment, from DEMO_DASHBOARD_PASSWORD" \
      "in docker/compose.yml, which turns on the dashboard's login; the username is admin unless DASHBOARD_USERNAME" \
      "sets another. The password comes from the environment rather than config/proxy.conf, so it never sits in a" \
      "file. A restart clears what the proxy holds in memory: the agents reconnect within a few seconds and" \
      "register their paths again, and the dashboard's histories start over."
    watch_for
    point "Reloading the dashboard shows its login page. Signing in opens the page you asked for, and Log out, the" \
      "exit icon at the right end of the top bar, signs you out."
    point "A wrong password shows 'Invalid username or password', and the proxy logs it at WARN, at most once a" \
      "minute for each address. To see it, run $0 logs proxy"
    point "A script sends the login as HTTP Basic Auth instead, and the same curl without -u gets a 401: curl -u" \
      "admin:$LOGIN_PASSWORD $DASHBOARD_URL/paths"
    point "The dashboard serves plain HTTP, so anywhere but a laptop, put TLS in front of it."
    follow_up "To turn the login off again, run" "$0 login-off"
    ;;
  login-off)
    restart_proxy ""
    bold "The dashboard's login is off again"
    how_it_works "This restarts the proxy without DASHBOARD_PASSWORD, so the dashboard is open again, as the rest of" \
      "the demo runs it, on 127.0.0.1 only. The agents reconnect within a few seconds."
    watch_for
    point "Reloading the dashboard opens it without a login."
    ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
