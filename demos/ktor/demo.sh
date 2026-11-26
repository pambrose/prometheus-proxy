#!/usr/bin/env bash
# Drives the Prometheus Proxy Ktor demo (see README.md): starts and stops it, and runs the scenarios a presenter
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
# The images the demo runs, under these tags whichever way they came. The proxy's is pulled from Docker Hub (up) or
# built from this checkout (up-local), under the tag the other demos use, so there's one copy of it; the coffee shop's
# is built either way, against the latest release (up) or this checkout (up-local).
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/ktor-app:local)
RELEASED_PROXY_IMAGE=pambrose/prometheus-proxy
# The newest release that lacks something the demo shows (the Ktor plugin, and the agent's own dashboard). up runs
# the release the docs give (make site-version) once it's later than this and its proxy image is on Docker Hub,
# and otherwise builds everything from this checkout, as up-local does, so nothing here changes with a release.
# When the demo comes to use something no release has yet, set this to the latest release on Docker Hub.
NEEDS_RELEASE_AFTER=4.7.0
# The secrets the demo's containers read (compose.yml), which up writes the first time it runs.
ENV_FILE="$DEMO_DIR/docker/.env"
CERTS_DIR="$REPO_DIR/testing/certs"
# The services only a scenario starts, so down and status cover them too.
ON_DEMAND=(--profile deploy)

# This demo's page on the docs site, which shows its layout and what each part of its tour shows. The demo points only
# there, never to a raw Markdown file, which isn't readable without a viewer.
PAGE_URL="https://prometheus-proxy.com/ktor-demo/"
APP_URL="http://localhost:${KTOR_DEMO_APP_PORT:-8390}"
DASHBOARD_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/dashboard"
GRAFANA_URL="http://localhost:${KTOR_DEMO_GRAFANA_PORT:-3300}"
PROMETHEUS_URL="http://localhost:${KTOR_DEMO_PROMETHEUS_PORT:-9390}"
PROXY_URL="http://localhost:${KTOR_DEMO_PROXY_PORT:-8380}"
AGENT_ADMIN_URL="http://localhost:${KTOR_DEMO_AGENT_ADMIN_PORT:-8393}"
# The dashboards of the coffee shop's agents: 1.0's, and 1.1's, which runs only after a deploy.
AGENT_DASHBOARD_URL="http://localhost:${KTOR_DEMO_AGENT_DASHBOARD_PORT:-8395}/dashboard"
NEXT_AGENT_DASHBOARD_URL="http://localhost:${KTOR_DEMO_NEXT_AGENT_DASHBOARD_PORT:-8396}/dashboard"
# The proxy's screens page, which holds every screen above in one browser tab (proxy.conf's screens). The tour links
# into it: ${SCREENS_URL}#<screen>, #<screen>/<view>, or #<screen>+<screen> for two side by side. It's the proxy's, so
# while part 8 has the proxy stopped, the tour gives each screen's own address instead.
SCREENS_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/screens"
# The proxy's dashboard port's root, which leads to the screens page: the short address make up prints.
SCREENS_HOME_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/"

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
# One of the agent's admin endpoints and what it answered, the answer's later lines indented under its first:
# endpoint <path> <answer>
endpoint() { awk -v path="$1" '{ printf "  %-13s %s\n", (NR == 1 ? path : ""), $0 }' <<<"$2"; }
# What to do once the agent works, under its own heading, after what to watch for.
once_it_works() {
  printf '\033[36m%s\033[0m\n' "Once the agent works:"
  wrap "  " "  " "$*"
}
# The command that undoes a scenario, or comes after it: follow_up <what> <command>
follow_up() { printf '\033[36m%s:\033[0m %s\n' "$1" "$2"; }

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [argument]

Lifecycle:
  up                 Start the demo: the proxy released on Docker Hub, and the coffee shop built against that release
  up-local           Start it with the library, the proxy, and the coffee shop built from this checkout (needs a JDK 17
                     or later)
  down               Stop the demo and remove its containers and network
  status             Show the containers, the coffee shop's state, and the URLs
  open               Open the coffee shop, the proxy's and the agent's dashboards, Grafana, and Prometheus's alerts in
                     the browser
  logs [service]     Follow the logs (coffee-shop, load-balancer, proxy, prometheus, grafana)
  tour [part]        Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

Scenarios:
  peek               Look for the coffee shop's /metrics, which it doesn't have, then read its metrics through the proxy
  order [drink]      Order a drink at the counter (default: latte)
  rush               Start a rush: customers order three times as often
  baristas <count>   Put this many baristas on shift (1 to 12; the shop opens with 3)
  calm               End the rush and send the extra baristas home
  break-machine      Break the espresso machine, so every espresso drink fails
  fix-machine        Fix it
  deploy             Deploy version 1.1 of the coffee shop beside 1.0, then stop 1.0
  rollback           Put 1.0 back, and remove 1.1
  proxy-stop         Stop the proxy
  proxy-start        Start it again
  debug              Call each of the agent's admin endpoints, and show its /debug page
  secrets            Show where the coffee shop and the proxy get their secrets

Drinks: espresso, latte, cappuccino, mocha, tea, cold-brew
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy Ktor demo"
  echo "  All screens:        $SCREENS_HOME_URL"
  echo "  Through the proxy:  $PROXY_URL/coffee_shop_metrics"
  echo
  wrap "" "" "Demo overview: ${PAGE_URL}#what-this-demo-shows"
  echo
  echo "Start the demo with: $MAKE_NAME tour, or $0 tour"
}

# POSTs to the coffee shop's own knobs, on its published port, and prints its answer: shop <path>. When the shop
# refuses, its answer says why, and that's the error; with no answer, the shop isn't there.
shop() {
  local answer
  if answer="$(curl -s --fail-with-body -X POST "$APP_URL$1")"; then
    echo "$answer"
  elif [[ -n "$answer" ]]; then
    die "the coffee shop refused $1: $answer"
  else
    die "the coffee shop didn't answer $1; is the demo running? See '$0 status'"
  fi
}

# Runs a command every second until it succeeds, for up to the given seconds; fails if it never does:
# wait_until <seconds> <command>...
wait_until() {
  local deadline=$((SECONDS + $1))
  shift
  until "$@"; do
    ((SECONDS < deadline)) || return 1
    sleep 1
  done
}

answers() { curl -fsS -o /dev/null "$1" 2>/dev/null; }

wait_for() { wait_until 120 answers "$1" || die "$2 did not answer within 120s; see '$0 logs'. It was asked at $1"; }

# Whether the coffee shop's address, its load balancer, answers from version $1.
serves_version() { curl -fsS "$APP_URL/status" 2>/dev/null | grep -Eq "^Version: +$1\$"; }

wait_for_version() {
  wait_until 30 serves_version "$1" || die "the coffee shop didn't answer as version $1 within 30s; see '$0 logs'"
}

# Whether a service's container is running, an on-demand one's included: running <service>
running() { [[ -n "$("${COMPOSE[@]}" "${ON_DEMAND[@]}" ps --quiet --status running "$1" 2>/dev/null)" ]]; }

# The proxy's log lines that match an extended regex, one a line, or nothing.
proxy_lines() { "${COMPOSE[@]}" logs --no-log-prefix proxy 2>/dev/null | grep -E "$1" || true; }

# How many lines some text has, not counting an empty one.
count_lines() { grep -c . <<<"$1" || true; }

# How many of the proxy's log lines match an extended regex.
proxy_count() { count_lines "$(proxy_lines "$1")"; }

# Whether more than $2 of the proxy's log lines match the extended regex $1, which it keeps in proxy_matches.
proxy_logged_more() { proxy_matches="$(proxy_lines "$1")" && (($(count_lines "$proxy_matches") > $2)); }

# Prints the proxy's latest log line matching an extended regex, once there are more than [after] of them, waiting up
# to 30s; prints nothing if none comes. It reads the log once a second: proxy_said <regex> [after]
proxy_said() {
  wait_until 30 proxy_logged_more "$1" "${2:-0}" || return 0
  printf '\033[36mThe proxy logged:\033[0m %s\n' "$(tail -1 <<<"$proxy_matches")"
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

# The release the docs give: the latest, or, from a version bump until the release is out, the next, whose proxy
# image and library aren't published yet. make release publishes the library to Maven Central before it pushes
# the images to Docker Hub, so a release whose proxy image is there has its library there too.
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

# Building from this checkout runs Gradle, which needs a JDK 17 or later: the one JAVA_HOME names, as the Gradle wrapper
# takes it, or else the java on the PATH. Checked before anything is built, so a missing or old one is named up front.
check_jdk() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version
  command -v "$java" >/dev/null 2>&1 ||
    die "building from this checkout takes a JDK 17 or later; set JAVA_HOME to one, or get one at https://adoptium.net"
  version="$("$java" -version 2>&1 | sed -nE '1s/.*version "(1\.)?([0-9]+).*/\2/p')"
  if ! [[ "$version" =~ ^[0-9]+$ ]] || ((version < 17)); then
    die "building from this checkout takes a JDK 17 or later, not Java ${version:-of an unknown version} ($java)"
  fi
}

# Writes the secrets the containers read, the first time the demo starts: the proxy's TLS certificate and key, as
# base64, the certificate of the CA that signed it, which the coffee shop's agent trusts, and a token the agent
# presents. The certificates are the repository's test ones (testing/certs), public, so this suits a demo on a laptop
# and nothing else; the Cloud Platforms page shows how to make your own.
write_env() {
  grep -q '^AGENT_TOKEN=' "$ENV_FILE" 2>/dev/null && return
  b64() { base64 <"$CERTS_DIR/$1" | tr -d '\n'; }
  random_hex() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }
  (
    umask 077
    cat >>"$ENV_FILE" <<EOF
# Written by demos/ktor/demo.sh, and read by docker compose (compose.yml): these secrets, and any host ports make up
# saved, moved with the KTOR_DEMO_*_PORT variables. Not committed. Delete a port's line to put it back to its default,
# or the whole file, and the next make up writes a new one, with a new token.
CERT_CHAIN_PEM=$(b64 server1.pem)
PRIVATE_KEY_PEM=$(b64 server1.key)
PROXY_CA_PEM=$(b64 ca.pem)
AGENT_TOKEN=$(random_hex)
EOF
  )
}

# Tags a release's proxy image from Docker Hub as the demo's, pulling it only if Docker doesn't have it: a release's
# images don't change, and the demo then starts again without a network connection. pull_proxy <version>
pull_proxy() {
  local released="$RELEASED_PROXY_IMAGE:$1"
  if ! docker image inspect "$released" >/dev/null 2>&1; then
    docker pull --quiet "$released" >/dev/null ||
      die "can't pull $released; check the network, or run '$MAKE_NAME up-local' to build from this checkout"
  fi
  docker tag "$released" "${IMAGES[0]}"
}

# The release the docs give, its proxy and library, unless it predates what the demo shows or isn't published yet.
cmd_up() {
  local release
  check_tools
  release="$(latest_release)" || die "can't tell the latest release from the docs (make site-version)"
  if ! version_after "$release" "$NEEDS_RELEASE_AFTER"; then
    wrap "" "" "The latest release, $release, predates this demo, so it builds the library, the proxy, and the coffee" \
      "shop from this checkout, which takes a JDK 17 or later."
    start_demo
  elif ! published "$release" "$RELEASED_PROXY_IMAGE"; then
    wrap "" "" "Release $release, which the docs give, isn't published yet, so the demo builds the library, the" \
      "proxy, and the coffee shop from this checkout, which takes a JDK 17 or later."
    start_demo
  else
    start_demo "$release"
  fi
}

cmd_up_local() {
  check_tools
  start_demo
}

# Starts the demo, or brings a running one up to date, with a release's proxy and library (start_demo <version>) or
# with ones built from this checkout (start_demo).
start_demo() {
  local old_ids id grafana_before
  # The images the demo ran, to remove below once nothing tags them: each build makes new ones. One another demo still
  # runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"
  # The Grafana container of a demo already running, if any, which has to be restarted to read changed files.
  grafana_before="$("${COMPOSE[@]}" ps --quiet grafana 2>/dev/null || true)"
  write_env
  save_ports

  if [[ $# -gt 0 ]]; then
    bold "Starting the demo with the proxy of release $1, from Docker Hub, and the coffee shop built against it"
    pull_proxy "$1"
    # The first build downloads Gradle's dependencies, which takes a few minutes; Docker keeps them for the next.
    KTOR_DEMO_APP_BUILD=compiled KTOR_DEMO_PROXY_VERSION="$1" "${COMPOSE[@]}" build coffee-shop
    "${COMPOSE[@]}" up --detach --wait --wait-timeout 180
  else
    check_jdk
    bold "Building the proxy, and the coffee shop against the library, from this checkout"
    # The proxy's fat jar, which its image copies; the coffee shop takes the library from the checkout itself.
    "$REPO_DIR/gradlew" --quiet -p "$REPO_DIR" proxyJar
    "$REPO_DIR/gradlew" --quiet -p "$DEMO_DIR/app" -PlocalProxy installDist
    KTOR_DEMO_APP_BUILD=prebuilt "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180
  fi
  # Compose recreates a container when its own config changes, not when a file it mounts does, and neither Prometheus
  # nor Grafana reliably notices a changed file on its own. Reload Prometheus's config and rules (SIGHUP), and restart
  # a Grafana that was already running for its dashboards, so a demo started from an older checkout runs this one's. A
  # Grafana just created read them as they are.
  "${COMPOSE[@]}" --progress quiet kill --signal SIGHUP prometheus >/dev/null
  if [[ -n "$grafana_before" && "$grafana_before" == "$("${COMPOSE[@]}" ps --quiet grafana)" ]]; then
    "${COMPOSE[@]}" --progress quiet restart grafana >/dev/null
  fi

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$APP_URL/healthz" "The coffee shop"
  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$AGENT_DASHBOARD_URL" "The coffee shop agent's dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
  wait_for "$SCREENS_URL" "The proxy's screens page"
  print_urls
}

cmd_down() {
  # With the on-demand services' profiles, so one left running goes too.
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" down --remove-orphans
}

cmd_status() {
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" ps
  if answers "$APP_URL/healthz"; then
    echo
    bold "The coffee shop"
    curl -fsS "$APP_URL/status"
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
  "2 An app with the agent embedded, and no port open for Prometheus"
  "3 The agent's own dashboard, served from inside the app"
  "3 The coffee shop's metrics in Grafana"
  "2 A rush fills the queue, and more baristas drain it"
  "2 The espresso machine breaks, and an alert fires"
  "3 A deploy hands the path to the new version"
  "2 The agent's admin endpoints, for debugging it"
  "4 The proxy goes away, and the shop keeps serving"
  "2 The secrets come from the environment"
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
  answers "$APP_URL/healthz" ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/ktor"
  tour_contents "Ktor demo"

  if part 1 "An app on a platform such as ECS, Cloud Run, or Azure Container Apps can't be scraped by a Prometheus" \
    "outside the platform without opening its metrics to the internet. Here the agent runs inside the app instead," \
    "and connects out to the proxy."; then
    instruct "What runs in this demo, as the diagram at the top of its page draws it: ${PAGE_URL}" \
      "The coffee shop is a Ktor app, with built-in customers who order over HTTP on their own. The" \
      "prometheus-proxy agent runs inside it, started from the app's own code: one container, one JVM. Outside it" \
      "are the proxy," \
      "Prometheus, and Grafana, as on a proxy host in the cloud, and a load balancer in front of the coffee shop," \
      "its one address, as a platform has."
    instruct "Open the demo's screens, on the coffee shop's status page: ${SCREENS_URL}#coffee-shop" \
      "This is the proxy's screens page, one browser tab that holds every screen in the demo: the proxy's" \
      "dashboard, the coffee shop, its agent's dashboard, 1.1's (whose tab stays red until part 6's deploy starts" \
      "it), the agent's /debug page, Prometheus, and Grafana. The steps from here link into this page, so keep it" \
      "open, and each link opens in it. The status page shows the queue, the baristas on shift, and the drinks" \
      "served so far, and refreshes every two seconds."
    step "Look for the coffee shop's /metrics, then read its metrics through the proxy." \
      -- peek
    instruct "Open the proxy dashboard: ${SCREENS_URL}#proxy" \
      "Its Agents view lists one agent, coffee-shop-1.0, named after the app's version, with two paths: the coffee" \
      "shop's metrics, and the agent's own. The agent connected to the proxy over TLS, and registered them. The" \
      "version in its name is what makes part 6's deploy, from 1.0 to 1.1, plain on the dashboard."
  fi

  if part 2 "The proxy's dashboard shows only what reaches the proxy. The agent serves a dashboard of its own," \
    "from inside the app, that shows its side: its connection, each path's registration, its scrapes as it saw" \
    "them, and what it did, in order. The coffee shop's environment turns it on, with nothing in the app's code."; then
    instruct "Open the coffee shop agent's dashboard: ${SCREENS_URL}#agent" \
      "Its Paths view lists the agent's two paths, each with a green light; select one and its header reads" \
      "registered: the proxy routes its scrapes to this agent. Beside each are the status of its last scrape and its" \
      "recent scrapes, a strip of dots, green for a success, which fills as Prometheus scrapes every five seconds."
    instruct "Select coffee_shop_metrics." \
      "Its target reads in-process: the agent reads the app's metrics registry itself, with no URL, port, or" \
      "password. Recent scrapes lists the last ones Prometheus asked for, newest first: when each ran, the status," \
      "how long the agent's read took, and the body's size and whether the agent gzipped it."
    instruct "Press Scrape now." \
      "The agent reads the coffee shop's registry at once, from inside the app, and shows what came back: the" \
      "status, the size, the metric families and samples, and the start of the body, as Prometheus would get it. It" \
      "works whether or not the proxy has the path, so when a path doesn't scrape, it tells you whether the agent can" \
      "read the app's metrics. It leaves no trace in Recent scrapes or the agent's metrics."
    instruct "Open the dashboard's Connection view: ${SCREENS_URL}#agent/connection" \
      "It reads connected, to proxy:50051, the one proxy endpoint the coffee shop gives its agent, over TLS. In" \
      "part 8, when the proxy goes away, this view says why the agent can't connect. The Settings view, the last" \
      "tab, lists the settings the agent resolved, its token shown only as set: ${SCREENS_URL}#agent/settings"
    instruct "Open the dashboard's Activity view: ${SCREENS_URL}#agent/activity" \
      "It lists what the agent did, newest first: registered, the two paths on one line, above connected, to" \
      "proxy:50051, from when the app started. If the agent tried before the proxy was listening, a connect failed" \
      "sits below them. The search box and Problems only narrow the list."
    instruct "Turn it on from the environment, or in code." \
      "The coffee shop's environment sets DASHBOARD_ENABLED=true for both versions, and DASHBOARD_PORT, 8395 for" \
      "1.0 and 8396 for 1.1 (docker/compose.yml), which the agent reads itself, so turning it off, or on again," \
      "takes a restart and no new build. In code, dashboard(port = 8395) in the plugin's agent { } does the same," \
      "though the environment overrides it. It has no login unless DASHBOARD_PASSWORD sets one, so the demo publishes" \
      "it on 127.0.0.1, and on a platform its port would stay on the internal network."
  fi

  if part 3 "Prometheus scrapes the proxy at the paths the agent registered, and Grafana shows what it" \
    "collected."; then
    instruct "Open Grafana, and look at the top section, 'The coffee shop, scraped through its embedded agent':" \
      "${SCREENS_URL}#grafana" \
      "Grafana opens on the 'Coffee Shop' dashboard, with no sign-in needed. The section's first row is the shop" \
      "right now: drinks served a minute, the queue, the baristas, the rush, the espresso" \
      "machine, whether Prometheus's last scrape through the proxy succeeded, and whether the agent is connected" \
      "to the proxy. Below them are drinks by type, orders by outcome, the queue against the baristas, brew time," \
      "and the order counter's HTTP statuses."
    instruct "Look at the section titled 'The app and its runtime'." \
      "These are Ktor's HTTP latency and the JVM's heap, from Micrometer, as any Ktor app has, and how long each" \
      "scrape through the proxy took. Nothing in the app had to change for Prometheus to get them. The 'Prometheus" \
      "Agents' link at the top opens the dashboard the project ships for its agents, which shows the embedded agent" \
      "like any other, so it needs no tooling of its own."
  fi

  if part 4 "The shop's customers order about one and a half drinks a second, which three baristas keep up" \
    "with. Here a rush triples the orders, and you watch the queue grow, then drain once more baristas are on" \
    "shift."; then
    step "Start a rush, and watch the queue on the coffee shop's status page and in Grafana. Open the two side by" \
      "side before you press Enter: ${SCREENS_URL}#coffee-shop+grafana" -- rush
    step "Once the queue has passed 30 and CoffeeQueueBacklog has fired, put eight baristas on shift." -- baristas 8
    put_back "end the rush, and send the extra baristas home" calm
  fi

  if part 5 "Every espresso drink needs the espresso machine. Here it breaks, so those orders fail, and the" \
    "coffee shop's error rate fires an alert in Prometheus."; then
    step "Break the espresso machine." -- break-machine
    put_back "fix the machine, once CoffeeShopErrors has fired" fix-machine
  fi

  if part 6 "A deploy starts the new version beside the old one, then stops the old. Each runs its own agent," \
    "which registers the same path. Here you deploy version 1.1, and watch the path move to it with no gap in the" \
    "metrics."; then
    step "Deploy version 1.1, and watch the version the shop answers from on its status page, and the agents on the" \
      "proxy dashboard's Agents view; Grafana's tab shows the graphs go on. Open the two side by side before you" \
      "press Enter: ${SCREENS_URL}#coffee-shop+proxy" -- deploy
    instruct "Open version 1.1's agent dashboard, its tab green now: ${SCREENS_URL}#agent-1-1" \
      "Each version's agent serves its own dashboard, from inside its own app. 1.1's lists the two paths registered," \
      "with Prometheus's scrapes arriving, and its Activity view shows it connecting and registering them. 1.0's" \
      "stopped with its app. Until then, it showed the paths as retrying, taken over by coffee-shop-1.1: the proxy" \
      "tells an agent when it gives one of its paths to another, and the agent takes the path back if the other" \
      "goes away first, as a failed deploy's new version does."
    put_back "put version 1.0 back, so the tour can be run again" rollback
  fi

  if part 7 "When an embedded agent doesn't connect, or a path doesn't scrape, its admin endpoints show what" \
    "it's doing. The coffee shop's environment turns them on with ADMIN_ENABLED, and its /debug page with" \
    "DEBUG_ENABLED, with nothing in its code."; then
    step "Call each of the agent's admin endpoints, and read its /debug page." -- debug
    instruct "Open the agent's /debug page: ${SCREENS_URL}#debug" \
      "It's there because the coffee shop's environment sets DEBUG_ENABLED=true, beside ADMIN_ENABLED=true. Keep" \
      "it open: in the next part, the proxy goes away, and the page shows it. As with the dashboard, the environment" \
      "turns them off once the agent works, or on again later, with a restart and no new build; ADMIN_PORT moves" \
      "them from 8093. In code, setting(\"admin.enabled\", true) and setting(\"admin.debugEnabled\", true) do the" \
      "same, though the environment overrides them."
  fi

  if part 8 "A health check that restarted the app whenever the proxy was down would turn a proxy outage into an" \
    "app outage. The agent's /healthcheck reports the agent itself, not its connection, and the app never waits on" \
    "its agent. Here the proxy goes away: the shop keeps serving and stays healthy while its metrics stop, then" \
    "resume on their own, and the agent's dashboard and /debug page show it losing the proxy and finding it" \
    "again."; then
    step "Stop the proxy." -- proxy-stop
    instruct "Open the Connection view of the agent's dashboard on its own: $AGENT_DASHBOARD_URL/connection" \
      "The screens page is the proxy's, so while the proxy is down, a link into it can't load; the page you have" \
      "open keeps working, and these steps give each screen's own address. Once the agent's first try to reconnect" \
      "has failed, it reads failing, and under Proxy endpoints, proxy:50051 shows the error the last try got. A" \
      "proxy that's down, a wrong port, a refused token, and a TLS mismatch each show there, which makes it the" \
      "first place to look when an agent won't connect. On the Paths view, both paths read pending. The tab's title" \
      "counts the failing connection as a problem, so a dashboard in a background tab still says so."
    instruct "Open the agent's /debug page on its own: $AGENT_ADMIN_URL/debug" \
      "Its AgentId is empty: the agent has lost its connection, and has no ID until the proxy gives it one" \
      "again. ProxyHost is the proxy it keeps trying. Its /healthcheck still reads healthy, since it reports the" \
      "agent's own state, not its connection: $AGENT_ADMIN_URL/healthcheck"
    step "Once CoffeeShopDown has fired, start the proxy again." -- proxy-start
    instruct "Open the Activity view of the agent's dashboard: ${SCREENS_URL}#agent/activity" \
      "Its newest lines, read from the bottom up, tell the outage in order: disconnected, when the proxy went," \
      "connect failed, with the error, then connected and the two paths registered once the proxy was back. The" \
      "agent logs each of these too, but the feed keeps them together, in order. Check Problems only, and the" \
      "connect failed lines are all that's left. The /debug page's AgentId is back too, the one the proxy gave the" \
      "agent when it reconnected: ${SCREENS_URL}#debug"
  fi

  if part 9 "The coffee shop and the proxy hold a token and TLS certificates, and neither reads them from a" \
    "file: the setup a platform that passes secrets as environment variables needs."; then
    step "Show where the coffee shop and the proxy get their secrets." -- secrets
    instruct "Read the website's Cloud Platforms page: https://prometheus-proxy.com/cloud-platforms/" \
      "It sets up the same thing for real: the app on ECS, Cloud Run, or Azure Container Apps, and the proxy on" \
      "AWS, GCP, Azure, DigitalOcean, or Railway, with your own certificate and token."
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending ktor
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
  logs) "${COMPOSE[@]}" "${ON_DEMAND[@]}" logs --follow --tail 50 "$@" ;;
  tour) cmd_tour "$@" ;;
  peek)
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "$APP_URL/metrics")"
    bold "The coffee shop has no /metrics: it answers $code"
    echo
    bold "Through the proxy, $PROXY_URL/coffee_shop_metrics answers with the metrics:"
    # head stops reading early, so pipefail would see grep's broken pipe as a failure.
    curl -fsS "$PROXY_URL/coffee_shop_metrics" | grep -E '^coffee_(orders_total|queue_depth|baristas)' | head -6 ||
      true
    echo "  ..."
    how_it_works "The coffee shop serves no metrics endpoint at all. Its embedded agent, which the app installs" \
      "with the Ktor plugin, reads the app's metrics registry in-process for each scrape, and sends the metrics to" \
      "the proxy over its own connection. Prometheus scrapes the proxy at the path the agent registered," \
      "/coffee_shop_metrics, and needs no password, port, or way into the app."
    watch_for
    point "Past these lines are Ktor's HTTP metrics and the JVM's, which Micrometer keeps in the same registry, so" \
      "the one path carries them all. Open it in a browser to see the whole page: $PROXY_URL/coffee_shop_metrics"
    point "The agent's own metrics, its connection and its scrapes, come through the proxy too, at a path of their" \
      "own that the app's code adds with agentMetricsAt: $PROXY_URL/coffee_shop_agent_metrics"
    ;;
  order)
    local drink="${1:-latte}" reply code
    reply="$(curl -sS -w '%{http_code}' -X POST "$APP_URL/orders?drink=$drink")" ||
      die "the coffee shop didn't answer; is the demo running? See '$0 status'"
    # The status code is the last three characters, after the answer's own newline.
    code="${reply: -3}"
    reply="${reply%???}"
    bold "The counter answered $code: ${reply%$'\n'}"
    how_it_works "This sends POST /orders?drink=$drink to the coffee shop, through its load balancer: the request its" \
      "built-in customers send about one and a half times a second. The counter queues the order for a barista with" \
      "a 202, or turns it away: with a 429 while the queue is full, or a 503 for an espresso drink while the machine" \
      "is broken."
    watch_for
    point "The app answers on its own: with the proxy stopped ($0 proxy-stop), an order still goes through, since" \
      "the app's requests never wait on its agent."
    point "A queued order is counted under Served on the status page once a barista has brewed it: $APP_URL"
    ;;
  rush)
    shop "/admin/rush?on=true" >/dev/null
    bold "A rush: customers now order three times as often"
    how_it_works "This asks the coffee shop, through POST /admin/rush?on=true, to have its customers order about" \
      "four and a half drinks a second, more than its three baristas can brew. Orders wait in the queue, which" \
      "holds 40; past that, the counter turns customers away with a 429."
    watch_for
    point "On the status page and in Grafana's 'Queue and baristas', the queue climbs."
    point "Once it has passed 30 for 15 seconds, CoffeeQueueBacklog fires on Prometheus's alerts page:" \
      "${SCREENS_URL}#prometheus/alerts"
    point "When the queue is full, 'Orders / sec, by outcome' shows rejected orders, and 'Order requests / sec, by" \
      "status' shows the counter's 429s."
    follow_up "To drain the queue, run" "$0 baristas 8"
    ;;
  baristas)
    local count="${1:-}"
    [[ "$count" =~ ^[0-9]+$ ]] || die "give the number of baristas, from 1 to 12"
    # 10# reads a count such as 08 as decimal, where bash would take it for octal.
    ((10#$count >= 1 && 10#$count <= 12)) || die "the coffee shop takes from 1 to 12 baristas, not $count"
    count=$((10#$count))
    shop "/admin/baristas?count=$count" >/dev/null
    bold "$count baristas on shift"
    how_it_works "This asks the coffee shop, through POST /admin/baristas?count=$count, to have $count baristas brew" \
      "the queue's orders at once. One sent home finishes the drink in hand first."
    watch_for
    point "'Baristas' reads $count, and with enough of them the queue drains, and 'Drinks served / sec' rises to meet" \
      "the orders."
    follow_up "To end the rush and send the extra baristas home, run" "$0 calm"
    ;;
  calm)
    shop "/admin/rush?on=false" >/dev/null
    shop "/admin/baristas?count=3" >/dev/null
    bold "The rush is over, and three baristas are on shift again"
    how_it_works "This asks the coffee shop to end the rush (POST /admin/rush?on=false) and keep three baristas" \
      "(POST /admin/baristas?count=3), as it opened."
    watch_for
    point "The orders fall back to about one and a half a second, the queue empties, and CoffeeQueueBacklog" \
      "resolves."
    ;;
  break-machine)
    shop "/admin/machine?broken=true" >/dev/null
    bold "The espresso machine is broken"
    how_it_works "This asks the coffee shop, through POST /admin/machine?broken=true, to fail every order that needs" \
      "the espresso machine, the espresso, latte, cappuccino, and mocha, with a 503. The tea and the cold brew are" \
      "still served."
    watch_for
    point "'Espresso' turns red, and 'Orders / sec, by outcome' shows failed orders, about three of every four," \
      "and 'Order requests / sec, by status' shows the counter's 503s."
    point "CoffeeShopErrors fires within about half a minute, not at once: its rule reads the failed orders' share" \
      "over a 30-second window, which takes a few scrapes to cross one in ten, and then has to stay above it for 10" \
      "seconds. Watch it go from pending to firing on Prometheus's alerts page: ${SCREENS_URL}#prometheus/alerts"
    point "The proxy, the agent, and the scrapes are untouched: the metrics report the failure, they don't fail."
    follow_up "To fix it, run" "$0 fix-machine"
    ;;
  fix-machine)
    shop "/admin/machine?broken=false" >/dev/null
    bold "The espresso machine is fixed"
    how_it_works "This asks the coffee shop, through POST /admin/machine?broken=false, to serve espresso drinks again."
    watch_for
    point "The failed orders stop, 'Espresso' turns green, and CoffeeShopErrors resolves."
    ;;
  deploy)
    local overwrites next_ran=0
    if ! running coffee-shop; then
      if running coffee-shop-next; then
        bold "Version 1.1 already serves the shop, and version 1.0 has stopped: there's nothing to deploy"
        follow_up "To put 1.0 back, run" "$0 rollback"
      else
        bold "Neither version of the coffee shop is running, so there's nothing to deploy beside"
        follow_up "To start version 1.0 again, run" "$0 rollback"
      fi
      return 0
    fi
    running coffee-shop-next && next_ran=1
    overwrites="$(proxy_count "Overwriting path /coffee_shop_metrics")"
    bold "Deploying version 1.1 beside version 1.0"
    "${COMPOSE[@]}" --profile deploy up --detach --no-deps --wait coffee-shop-next
    # A 1.1 that starts now takes the paths from 1.0's agent, which the proxy logs; one already running has them.
    if ((!next_ran)); then
      proxy_said "Overwriting path /coffee_shop_metrics" "$overwrites"
    fi
    "${COMPOSE[@]}" stop coffee-shop
    wait_for_version 1.1
    bold "Version 1.1 serves the shop and its paths, and version 1.0 has stopped"
    how_it_works "This starts coffee-shop-next, version 1.1 of the same image, which the compose file keeps in a" \
      "profile of its own, deploy. Its agent registers the same paths as 1.0's, with the same token, and the proxy" \
      "gives each path to the newer registration, as the log line above shows, and tells 1.0's agent. That agent" \
      "stays connected without paths, ready to take them back should 1.1 go away, until its app stops, which this" \
      "then does, as the end of a rolling deploy would. The load balancer in front of the shop, its one address," \
      "sends requests to 1.0 while it runs, and to 1.1 once it has stopped."
    watch_for
    point "1.1 is a new process, so its counts start from zero, and its queue, baristas, and machine are as the shop" \
      "opened: the knobs now act on it. The coffee shop's page, at the same address, now reads Version 1.1:" \
      "${SCREENS_URL}#coffee-shop"
    point "On the proxy dashboard's Agents view, coffee-shop-1.1 has the two paths, and coffee-shop-1.0 goes once it" \
      "stops."
    point "Version 1.1's agent has a dashboard of its own, inside its app, which lists the two paths registered:" \
      "${SCREENS_URL}#agent-1-1"
    point "The proxy told 1.0's agent it gave the paths to 1.1: until 1.0 stopped, its dashboard showed them as" \
      "retrying, taken over by coffee-shop-1.1. Had 1.1 stopped first, as a failed deploy's does, 1.0's agent would" \
      "have taken them back within seconds. Its dashboard stopped with its app, and answers again after a rollback:" \
      "${SCREENS_URL}#agent"
    point "In Grafana, the graphs go on without a gap: Prometheus scraped the path through 1.0's agent, then 1.1's," \
      "and rate() takes the counters starting again in its stride."
    follow_up "To put 1.0 back, run" "$0 rollback"
    ;;
  rollback)
    local overwrites old_ran=0 next_ran=0
    running coffee-shop && old_ran=1
    running coffee-shop-next && next_ran=1
    if ((old_ran && !next_ran)); then
      bold "There's nothing to roll back: version 1.0 serves the shop, and version 1.1 isn't running"
      follow_up "To deploy version 1.1, run" "$0 deploy"
      return 0
    fi
    overwrites="$(proxy_count "Overwriting path /coffee_shop_metrics")"
    "${COMPOSE[@]}" up --detach --no-deps --wait coffee-shop
    # Only a 1.0 that starts while 1.1 runs takes the paths from 1.1's agent, which the proxy logs.
    if ((!old_ran && next_ran)); then
      proxy_said "Overwriting path /coffee_shop_metrics" "$overwrites"
    fi
    "${COMPOSE[@]}" --profile deploy rm --stop --force coffee-shop-next
    wait_for_version 1.0
    bold "Version 1.0 serves the shop and its paths again, and version 1.1 is removed"
    how_it_works "This starts coffee-shop again, whose agent takes the paths back the same way, then stops and" \
      "removes coffee-shop-next. The load balancer sends requests to 1.0 again as soon as it answers."
    watch_for
    point "The coffee shop's page reads Version 1.0 again, starting from zero."
    point "coffee-shop-1.0 has the paths again on the Agents view, and coffee-shop-1.1 is gone."
    point "1.0's agent dashboard answers again, starting over, since 1.0 is a new process, and 1.1's has gone with" \
      "its container: ${SCREENS_URL}#agent"
    ;;
  proxy-stop)
    "${COMPOSE[@]}" stop proxy
    bold "The proxy has stopped"
    how_it_works "This runs docker compose stop proxy. The coffee shop's agent loses its connection and retries every" \
      "few seconds, in the background: the app's requests never wait on it."
    watch_for
    point "The coffee shop keeps serving: its status page counts drinks as before, and an order still goes through:" \
      "$0 order"
    point "Grafana's 'Scrape' turns Down, 'Agent' reads Disconnected, and its graphs stop, since Prometheus" \
      "can't reach the proxy." \
      "Within about half a minute, a failed scrape and then the alert's 15 seconds, CoffeeShopDown fires on" \
      "Prometheus's alerts page: $PROMETHEUS_URL/alerts"
    point "The coffee shop's log shows its agent retrying: $0 logs coffee-shop"
    point "The agent's /debug page shows an empty AgentId, and the proxy it keeps trying, while its /healthcheck" \
      "stays healthy: $0 debug"
    point "On the agent's dashboard, the Connection view reads failing, with the error the agent's last try to reach" \
      "proxy:50051 got, and the Paths view has both paths pending: $AGENT_DASHBOARD_URL/connection"
    follow_up "To bring it back, run" "$0 proxy-start"
    ;;
  proxy-start)
    "${COMPOSE[@]}" start proxy
    bold "The proxy has started"
    how_it_works "This runs docker compose start proxy. The coffee shop's agent, still retrying, connects within a" \
      "few seconds, and registers its paths again. Nothing in the app was restarted or told."
    watch_for
    point "coffee-shop-1.0 reappears on the proxy dashboard's Agents view, with its two paths."
    point "Grafana's graphs resume, with a gap for the time the proxy was down, and CoffeeShopDown resolves."
    point "The agent's /debug page shows an AgentId again, the one the proxy gave it when it reconnected:" \
      "${SCREENS_URL}#debug"
    point "On the agent's dashboard, the Connection view reads connected again, and the Activity view tells the" \
      "outage in order: disconnected, connect failed, then connected and the two paths registered:" \
      "${SCREENS_URL}#agent/activity"
    ;;
  debug)
    local health threads
    answers "$AGENT_ADMIN_URL/ping" ||
      die "the coffee shop's agent didn't answer on its admin port; is version 1.0 running? See '$0 status'"
    # Each health check's name and result, from the JSON, which also holds a timestamp and a duration for each.
    health="$(curl -fsS "$AGENT_ADMIN_URL/healthcheck" | grep -oE '"[a-z_]+":\{"healthy":(true|false)' |
      sed -E -e 's/^"([a-z_]+)":\{"healthy":/\1: /' -e 's/: true$/: healthy/' -e 's/: false$/: unhealthy/' || true)"
    # Each thread in the dump starts with its name in quotes.
    threads="$(curl -fsS "$AGENT_ADMIN_URL/threaddump" | grep -c '^"' || true)"
    bold "What each of the agent's admin endpoints answered, on $AGENT_ADMIN_URL"
    endpoint /ping "$(curl -fsS "$AGENT_ADMIN_URL/ping")"
    endpoint /version "$(curl -fsS "$AGENT_ADMIN_URL/version" |
      sed -E 's/^.*"version":"([^"]*)".*"release_date":"([^"]*)".*"build_time":"([^"]*)".*$/\1, released \2, built \3/')"
    endpoint /healthcheck "$health"
    endpoint /threaddump "$threads threads: the coffee shop's JVM, the app's as well as the agent's"
    endpoint /debug "the page below, which DEBUG_ENABLED adds"
    echo
    bold "The agent's /debug page, at $AGENT_ADMIN_URL/debug"
    # awk ends the page's last line, which has no newline of its own, and leaves its blank lines empty.
    curl -fsS "$AGENT_ADMIN_URL/debug" | awk '{ print ($0 == "" ? "" : "  " $0) }'
    how_it_works "The agent in the coffee shop reads ADMIN_ENABLED=true from the container's environment" \
      "(docker/compose.yml), which turns on its admin endpoints, and DEBUG_ENABLED=true, which adds /debug; without" \
      "it, the admin port serves the other four. Nothing in the app's code sets them. They answer on port 8093 in" \
      "the container, which the demo publishes on 127.0.0.1."
    watch_for
    point "/debug is the agent as it sees itself: its name, the ID the proxy gave it when it connected, which is" \
      "empty while it isn't connected, the proxy it's connected to or trying, and each path with the URL the agent" \
      "scrapes, or in-process for the app's registry. When a path doesn't scrape, its target here is the first thing" \
      "to check."
    point "/healthcheck is the agent's own health, not its connection: it stays healthy while the proxy is down." \
      "To see /debug tell them apart, run $0 proxy-stop, then $0 debug again."
    point "/ping is for a liveness probe, and /version tells you which release of the agent the app runs."
    once_it_works "Take ADMIN_ENABLED and DEBUG_ENABLED out of the app's environment, and restart it. The admin port" \
      "has no authentication, and /threaddump shows every thread in the app."
    ;;
  secrets)
    local proxy_id
    bold "The coffee shop's settings for its agent, by name (their values stay in the container):"
    "${COMPOSE[@]}" exec -T coffee-shop printenv | cut -d= -f1 | { grep -E '^PROMETHEUS_' || true; } |
      sort | sed 's/^/  /'
    bold "What the coffee shop's container holds, besides the JRE: the app, and no config file"
    "${COMPOSE[@]}" exec -T coffee-shop ls /app | sed 's/^/  /'
    proxy_id="$("${COMPOSE[@]}" ps --quiet proxy)"
    bold "The proxy's secrets, by name:"
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$proxy_id" | cut -d= -f1 |
      { grep -E '^(AGENT_TOKEN|CERT_CHAIN_PEM|PRIVATE_KEY_PEM)$' || true; } | sed 's/^/  /'
    how_it_works "The coffee shop builds its agent's settings in code from its environment, in the Ktor plugin's" \
      "agent { } block, so the token and the certificate it trusts never sit in a file" \
      "(app/src/main/kotlin/coffeeshop/PrometheusAgent.kt). The proxy is the stock image, which" \
      "has no shell to write a variable to a file, and takes its TLS certificate and key as PEM, base64-encoded, in" \
      "CERT_CHAIN_PEM and PRIVATE_KEY_PEM. demo.sh wrote all of them to docker/.env, which docker compose reads; on a" \
      "cloud platform they're the service's variables."
    watch_for
    point "The proxy logs only whether each PEM setting is set, never its value, as the line below shows."
    proxy_said "privateKeyPem: \(set\)"
    ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
