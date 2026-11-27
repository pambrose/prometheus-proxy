#!/usr/bin/env bash
# Drives the Prometheus Proxy plain Kotlin demo (see README.md): starts and stops it, and runs the scenarios its tour
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
# built from this checkout (up-local), under the tag the other demos use, so there's one copy of it; the oven's is
# built either way, against the latest release (up) or this checkout (up-local).
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/plain-kotlin-app:local)
RELEASED_PROXY_IMAGE=pambrose/prometheus-proxy
# The newest release that lacks something the demo shows (the proxy's screens page, which 4.7.1 brings). up runs the
# release the docs give (make site-version) once it's later than this and its proxy image is on Docker Hub, and
# otherwise builds everything from this checkout, as up-local does, so nothing here changes with a release. When the
# demo comes to use something no release has yet, set this to the latest release on Docker Hub.
NEEDS_RELEASE_AFTER=4.7.0
# The services only a scenario starts, so down and status cover them too.
ON_DEMAND=(--profile misconfigured)
# The most ovens `scale` runs.
MAX_OVENS=5

# This demo's page on the docs site, which shows its layout and what each part of its tour shows. The demo points only
# there, never to a raw Markdown file, which isn't readable without a viewer.
PAGE_URL="https://prometheus-proxy.com/plain-kotlin-demo/"
DASHBOARD_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/dashboard"
GRAFANA_URL="http://localhost:${PLAIN_KOTLIN_DEMO_GRAFANA_PORT:-3600}"
PROMETHEUS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROMETHEUS_PORT:-9690}"
PROXY_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROXY_PORT:-8680}"
# The proxy's screens page, which holds the proxy's dashboard, Grafana, and Prometheus in one browser tab (proxy.conf's
# screens). The tour links into it: ${SCREENS_URL}#<screen>, #<screen>/<view>, or #<screen>+<screen> for two side by
# side. No part of the tour stops the proxy.
SCREENS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/screens"
# The proxy's dashboard port's root, which leads to the screens page: the short address make up prints.
SCREENS_HOME_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/"

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
# A log line a scenario found, under a heading: logged <who> <line>
logged() { printf '\033[36m%s logged:\033[0m %s\n' "$1" "$2"; }

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [argument]

Lifecycle:
  up                 Start the demo: the proxy released on Docker Hub, and the oven built against that release
  up-local           Start it with the library, the proxy, and the oven built from this checkout (needs a JDK 17 or
                     later)
  down               Stop the demo and remove its containers and network
  status             Show the containers, the ovens and their paths, the queue, and the URLs
  open               Open the proxy's screens page, which holds its dashboard, Grafana, and Prometheus
  logs [service]     Follow the logs (oven, redis, counter, proxy, prometheus, grafana)
  tour [part]        Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

Scenarios:
  sockets            Show that the oven publishes no port and listens on none, and that Prometheus scrapes it anyway
  metrics            Read the oven's metrics, and its agent's, through the proxy, each at its own path
  rush               A rush: 500 more orders, ten a second
  scale <count>      Run this many ovens (1 to $MAX_OVENS; the bakery opens with 1)
  no-proxy           Start an oven with no proxy set, show its log, and stop it
  bad-cert           Start an oven whose PROMETHEUS_PROXY_CERT isn't a certificate, show its log, and stop it
  wrong-token        Start an oven whose token the proxy refuses, show its log, and stop it
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy plain Kotlin demo"
  echo "  All screens:            $SCREENS_HOME_URL"
  echo "  The proxy's discovery:  $PROXY_URL/discovery"
  echo
  wrap "" "" "Demo overview: ${PAGE_URL}#what-this-demo-shows"
  echo
  echo "Start the demo with: $MAKE_NAME tour, or $0 tour"
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

# Fails unless the demo is running, naming how to start it.
require_demo() {
  answers "$PROXY_URL/discovery" || die "the demo isn't running; start it with '$MAKE_NAME up' in demos/plain-kotlin"
}

# The ovens running, one a line, in compose's order: <number> <host name>. An oven's host name is its container's
# short ID, which docker takes wherever it takes a container.
ovens() {
  local -a ids=()
  local id
  while IFS= read -r id; do
    [[ -n "$id" ]] && ids+=("$id")
  done < <("${COMPOSE[@]}" ps --quiet oven 2>/dev/null || true)
  ((${#ids[@]} > 0)) || return 0
  docker inspect -f '{{index .Config.Labels "com.docker.compose.container-number"}} {{.Config.Hostname}}' \
    "${ids[@]}" | sort -n
}

# The first oven's host name, which names its agent, oven-<host name>, and its paths.
first_oven() {
  local oven
  oven="$(ovens | head -1)"
  [[ -n "$oven" ]] || die "no oven is running; run '$0 scale 1'"
  awk '{ print $2 }' <<<"$oven"
}

# The paths the proxy's service discovery lists, one a line, without their leading slash.
discovered_paths() {
  curl -fsS "$PROXY_URL/discovery" 2>/dev/null | grep -oE '"__metrics_path__": *"/[^"]*"' |
    sed -E 's/^"__metrics_path__": *"\/(.*)"$/\1/' | sort || true
}

# How many of the paths an oven's metrics are at (not its agent's) the proxy's service discovery lists.
discovered_ovens() { discovered_paths | grep -cE '^oven_[^_]+_metrics$' || true; }

# A query's one value in Prometheus, as an integer, or 0 when it has none.
prom_number() {
  local value
  value="$(curl -fsS --get --data-urlencode "query=$1" "$PROMETHEUS_URL/api/v1/query" 2>/dev/null |
    grep -oE '"value":\[[0-9.]+,"[^"]*"\]' | head -1 | sed -E 's/.*,"([^"]*)"\]$/\1/' || true)"
  printf '%.0f\n' "${value:-0}"
}

# How many ovens Prometheus scrapes: the oven job's targets it has an up for.
scraped_ovens() { prom_number 'count(up{job="oven"})'; }

# How many ovens' agents Prometheus scrapes: the oven-agent job's targets it has an up for.
scraped_agents() { prom_number 'count(up{job="oven-agent"})'; }

# Whether the counts are as given: has_count <want> <command>...
has_count() {
  local want="$1"
  shift
  [[ "$("$@")" == "$want" ]]
}

# Prometheus's oven targets, with each one's scrape URL and whether its last scrape succeeded, from its targets API.
print_targets() {
  curl -fsS "$PROMETHEUS_URL/api/v1/targets?state=active" 2>/dev/null |
    grep -oE '"scrapeUrl":"[^"]*"|"health":"[a-z]*"' | sed -E 's/^"[a-zA-Z]+":"(.*)"$/\1/' | paste - - |
    grep '/oven_' | sort | awk -F'\t' '{ printf "  %-56s %s\n", $1, $2 }' || true
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
    wrap "" "" "The latest release, $release, predates this demo, so it builds the library, the proxy, and the oven" \
      "from this checkout, which takes a JDK 17 or later."
    start_demo
  elif ! published "$release" "$RELEASED_PROXY_IMAGE"; then
    wrap "" "" "Release $release, which the docs give, isn't published yet, so the demo builds the library, the" \
      "proxy, and the oven from this checkout, which takes a JDK 17 or later."
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
# with ones built from this checkout (start_demo). It runs one oven, whatever a scenario scaled them to.
start_demo() {
  local old_ids id grafana_before
  save_ports
  # The images the demo ran, to remove below once nothing tags them: each build makes new ones. One another demo still
  # runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"
  # The Grafana container of a demo already running, if any, which has to be restarted to read changed files.
  grafana_before="$("${COMPOSE[@]}" ps --quiet grafana 2>/dev/null || true)"

  if [[ $# -gt 0 ]]; then
    bold "Starting the demo with the proxy of release $1, from Docker Hub, and the oven built against it"
    pull_proxy "$1"
    # The first build downloads Gradle's dependencies, which takes a few minutes; Docker keeps them for the next.
    PLAIN_KOTLIN_DEMO_APP_BUILD=compiled PLAIN_KOTLIN_DEMO_PROXY_VERSION="$1" "${COMPOSE[@]}" build oven
    "${COMPOSE[@]}" up --detach --wait --wait-timeout 180
  else
    check_jdk
    bold "Building the proxy, and the oven against the library, from this checkout"
    # The proxy's fat jar, which its image copies; the oven takes the library from the checkout itself.
    "$REPO_DIR/gradlew" --quiet -p "$REPO_DIR" proxyJar
    "$REPO_DIR/gradlew" --quiet -p "$DEMO_DIR/app" -PlocalProxy installDist
    PLAIN_KOTLIN_DEMO_APP_BUILD=prebuilt "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180
  fi
  # Compose recreates a container when its own config changes, not when a file it mounts does, and neither Prometheus
  # nor Grafana reliably notices a changed file on its own. Reload Prometheus's config (SIGHUP), and restart a Grafana
  # that was already running for its dashboards, so a demo started from an older checkout runs this one's. A Grafana
  # just created read them as they are.
  "${COMPOSE[@]}" --progress quiet kill --signal SIGHUP prometheus >/dev/null
  if [[ -n "$grafana_before" && "$grafana_before" == "$("${COMPOSE[@]}" ps --quiet grafana)" ]]; then
    "${COMPOSE[@]}" --progress quiet restart grafana >/dev/null
  fi
  # The bakery opens with no orders waiting, whatever an earlier run's rush left in a Redis that kept running.
  "${COMPOSE[@]}" exec -T redis redis-cli DEL orders >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$PROXY_URL/discovery" "The proxy"
  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  wait_for "$GRAFANA_URL/api/health" "Grafana"
  wait_for "$SCREENS_URL" "The proxy's screens page"
  wait_until 60 has_count 1 discovered_ovens ||
    die "the oven's agent didn't register its paths with the proxy within 60s; see '$0 logs oven'"
  print_urls
}

cmd_down() {
  # With the on-demand services' profiles, so one left running goes too.
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" down --remove-orphans
}

cmd_status() {
  local number host path waiting
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" ps
  if answers "$PROXY_URL/discovery"; then
    echo
    bold "The ovens, and the paths their agents registered with the proxy"
    while read -r number host; do
      printf '  oven %s, oven-%s:' "$number" "$host"
      for path in $(discovered_paths | grep -E "^oven_${host}_" || true); do printf ' /%s' "$path"; done
      echo
    done < <(ovens)
    waiting="$("${COMPOSE[@]}" exec -T redis redis-cli LLEN orders 2>/dev/null | tr -d '\r' || true)"
    echo
    bold "Waiting orders: ${waiting:-unknown}"
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

# The sockets scenario: what the oven publishes, what its JVM has open, and Prometheus's targets for it.
# The oven's JVM's sockets, one a line: "listening <tcp|udp|unix>", "connected <remote port, in hex>", or, for a
# socket in its container that isn't the JVM's, "other <local address, in hex>". The JVM is the container's process 1,
# whose open files name each socket's inode; /proc/net's tables list every socket in the container's network
# namespace, by inode, with a TCP socket's state, 0A listening and 01 connected, and a Unix socket's flags, 00010000
# for one that accepts connections. A UDP socket the JVM held would be one it reads from.
# jvm_sockets <oven's host name>
jvm_sockets() {
  # shellcheck disable=SC2016 # $t is the container's shell's
  docker exec "$1" sh -c \
    'ls -l /proc/1/fd; for t in tcp tcp6 udp udp6 unix; do echo "== $t"; cat "/proc/net/$t"; done' | awk '
      /socket:\[/ { match($0, /socket:\[[0-9]+\]/); jvm[substr($0, RSTART + 8, RLENGTH - 9)] = 1; next }
      /^== / { table = $2; next }
      table ~ /^tcp/ && ($10 in jvm) && $4 == "0A" { print "listening tcp" }
      table ~ /^tcp/ && ($10 in jvm) && $4 == "01" { split($3, remote, ":"); print "connected", remote[2] }
      table ~ /^tcp/ && !($10 in jvm) && $4 == "0A" { split($2, local, ":"); print "other", local[1] }
      table ~ /^udp/ && ($10 in jvm) { print "listening udp" }
      table == "unix" && ($7 in jvm) && $4 == "00010000" { print "listening unix" }'
}

cmd_sockets() {
  local host published sockets listening remote port hex
  require_demo
  host="$(first_oven)"
  published="$(docker port "$host" 2>/dev/null || true)"
  bold "The oven publishes no port"
  echo "  docker port $host: ${published:-(nothing)}"
  echo
  sockets="$(jvm_sockets "$host")"
  listening="$(awk '$1 == "listening" { print $2 }' <<<"$sockets" | sort | uniq -c |
    awk '{ printf ", %s %s", $1, $2 }')"
  bold "What the oven's JVM has open, from /proc in its container"
  if [[ -n "$listening" ]]; then
    printf '  %-12s %s\n' "Listening:" "${listening#, } sockets"
  else
    printf '  %-12s %s\n' "Listening:" "nothing: no TCP port, UDP socket, or Unix socket"
  fi
  remote="Connected:"
  while read -r hex; do
    port=$((16#$hex))
    case "$port" in
    50051) printf '  %-12s %s\n' "$remote" "out to the proxy's agent port, $port" ;;
    6379) printf '  %-12s %s\n' "$remote" "out to Redis, on port $port" ;;
    *) printf '  %-12s %s\n' "$remote" "to port $port" ;;
    esac
    remote=""
  done < <(awk '$1 == "connected" { print $2 }' <<<"$sockets" | sort -u)
  if grep -q '^other 0B00007F$' <<<"$sockets"; then
    wrap "  " "  " "The container's one listener is Docker's DNS resolver, on 127.0.0.11, which Docker runs in every" \
      "container on a compose network, and which isn't the oven's."
  fi
  echo
  bold "Prometheus scrapes it anyway, through the proxy"
  print_targets
  how_it_works "The oven is a plain Kotlin program with no HTTP server, and compose publishes no port for it. Its" \
    "agent connects out to the proxy, over gRPC, and registers two paths named after the container," \
    "oven_${host}_metrics, the oven's metrics, and oven_${host}_agent_metrics, the agent's own. For each scrape," \
    "the proxy asks the agent over that connection, and the agent reads the oven's registry in-process. Prometheus's" \
    "config names no oven: it finds both paths in the proxy's service discovery, at $PROXY_URL/discovery"
  watch_for
  point "Nothing listens: the oven's only connections are the ones it opened, to the proxy and to Redis."
  point "Prometheus's targets page lists the oven's path under the oven job, and its agent's under oven-agent," \
    "each with the proxy's address, proxy:8080, as the host: ${SCREENS_URL}#prometheus/targets"
}

# Shows what the proxy serves at a path: a few of its lines, an extended regex, and how many samples it has with each
# of two prefixes. show_path <path> <title> <regex> <lines> <prefix> <other prefix>
show_path() {
  local body
  body="$(curl -fsS "$PROXY_URL/$1")" || die "the proxy didn't serve /$1"
  bold "$2: $PROXY_URL/$1"
  grep -E "$3" <<<"$body" | head -n "$4" | sed 's/^/  /' || true
  echo "  ..."
  echo "  $(grep -c "^$5" <<<"$body" || true) $5 samples, and $(grep -c "^$6" <<<"$body" || true) $6 samples"
}

# The metrics scenario: the oven's metrics and its agent's, each at its own path through the proxy.
cmd_metrics() {
  local host
  require_demo
  host="$(first_oven)"
  show_path "oven_${host}_metrics" "The oven's metrics" '^bakery_(orders_baked_total|queue_depth)' 6 bakery_ agent_
  echo
  show_path "oven_${host}_agent_metrics" "Its agent's own" '^agent_(connected|paths|scrape_request_count_total)' 4 \
    agent_ bakery_
  how_it_works "The oven's Counter, Gauge, and Histogram are the Prometheus Java client's, in its default registry," \
    "which the agent serves with source(path, registry). The agent keeps its own metrics in that registry too, and" \
    "agentMetricsAt(path) serves them at a path of their own, so a source of the default registry leaves them out:" \
    "each series is served once, at one path, and the agent opens no metrics port for them."
  watch_for
  point "The oven's path has no agent_ series, and the agent's no bakery_ series."
  point "Prometheus scrapes them as two jobs, oven and oven-agent. Grafana's Bakery dashboard reads the first, and" \
    "the Prometheus Agents dashboard, linked from its top bar, the second: ${SCREENS_URL}#grafana"
}

cmd_rush() {
  require_demo
  "${COMPOSE[@]}" exec --detach counter sh /counter.sh rush 500
  bold "A rush: 500 more orders, ten a second, for 50 seconds"
  how_it_works "This runs the counter's rush (counter/counter.sh rush 500) beside its trickle, in the counter's" \
    "container: it pushes ten orders a second onto the Redis queue. An oven bakes about two a second, so with one," \
    "the orders pile up."
  watch_for
  point "In Grafana's 'Waiting orders, and ovens', the backlog climbs, by about nine a second while the rush lasts:" \
    "${SCREENS_URL}#grafana"
  point "One oven, at about two a second against the counter's one and a half, would take a quarter of an hour to" \
    "clear what's left once the rush ends. 'Time each oven spends baking' sits at 100%."
  follow_up "To add two ovens, run" "$0 scale 3"
}

# Scales the ovens up to the count given, and waits for the proxy and Prometheus to have them: scale_up <count>
scale_up() {
  local count="$1"
  "${COMPOSE[@]}" up --detach --no-recreate --no-deps --scale "oven=$count" oven
  wait_until 60 has_count "$count" discovered_ovens ||
    die "the new ovens' agents didn't register their paths within 60s; see '$0 logs oven'"
  bold "$count ovens; each one's agent has registered its paths with the proxy"
  # Both of each new oven's targets, so none is listed before its first scrape, with its health unknown
  wait_until 30 has_count "$count" scraped_ovens || true
  wait_until 30 has_count "$count" scraped_agents || true
  print_targets
  how_it_works "This runs docker compose up --scale oven=$count. Each new oven is the same image, whose host name" \
    "is its new container's ID: its agent registers oven_<id>_metrics and oven_<id>_agent_metrics with the proxy," \
    "whose service discovery lists them at once. Prometheus reads it every 5 seconds, at $PROXY_URL/discovery"
  watch_for
  point "Prometheus's targets page lists each oven under the oven job, with no change to its config:" \
    "${SCREENS_URL}#prometheus/targets"
  point "In Grafana, 'Orders baked / sec, by oven' gains a series for each new oven, and the backlog in 'Waiting" \
    "orders, and ovens' drains: three ovens bake about six orders a second, against the counter's one and a half."
  point "The proxy dashboard's Agents view lists an oven-<id> agent for each: ${SCREENS_URL}#proxy"
}

# Stops the ovens past the count given, one SIGTERM each, as a scale-in does, and shows how they went:
# scale_down <count>
scale_down() {
  local count="$1" number host up remaining="the remaining ovens' paths"
  local -a hosts=()
  ((count > 1)) || remaining="the remaining oven's two paths"
  while read -r number host; do
    ((number > count)) || continue
    hosts+=("$host")
  done < <(ovens)
  # As a platform's scale-in does: SIGTERM, and up to 30 seconds to stop
  docker stop --time 30 "${hosts[@]}" >/dev/null
  for host in "${hosts[@]}"; do
    bold "oven-$host stopped. Its log, from the SIGTERM:"
    # The oven's lines, then its agent's, the agent without its full description in braces
    docker logs "$host" 2>&1 |
      grep -E 'Stopping|oven is off|Disconnected from proxy|Terminated Agent' |
      sed -E -e 's/Agent\{[^}]*\}/Agent{...}/' -e 's/^/  /'
  done
  echo
  if has_count "$count" discovered_ovens; then
    bold "The proxy's service discovery dropped their paths as their agents disconnected"
  fi
  wait_until 30 has_count "$count" scraped_ovens || true
  bold "Prometheus's targets now:"
  print_targets
  for host in "${hosts[@]}"; do
    up="$(prom_number "min_over_time(up{job=\"oven\",instance=\"oven-$host\"}[1m])")"
    if [[ "$up" == 0 ]]; then
      echo "  oven-$host's last scrape recorded up 0: it came after its agent had gone, before discovery dropped it"
    else
      echo "  oven-$host's scrapes all succeeded: discovery dropped it before a scrape found its path gone"
    fi
  done
  docker rm "${hosts[@]}" >/dev/null
  how_it_works "This stops each oven numbered above $count with docker stop, which sends it a SIGTERM, as" \
    "Kubernetes or ECS does on a scale-in, and the JVM runs its shutdown hooks. The oven's, in main, tells the oven" \
    "to stop, and waits for main to return: the oven finishes the order it's baking, takes no more, and returns," \
    "and use { } then stops the agent. It disconnects from the proxy, which drops its paths at once. Prometheus's" \
    "next discovery refresh, within 5 seconds, drops the oven's targets. A scrape that comes between the agent's" \
    "disconnect and that refresh finds no path and records up 0, as a terminating pod's can on Kubernetes."
  watch_for
  point "Each stopped oven's log, above, shows the oven finishing the order it was baking, then its agent" \
    "disconnecting and stopping. No order taken from the queue is lost."
  point "The proxy's service discovery, which Prometheus follows, lists only $remaining: $PROXY_URL/discovery"
  point "The proxy dashboard's Paths view lists the stopped ovens' paths as 'gone', and keeps them for an hour" \
    "(proxy.dashboard.departedPathRetentionSecs), so it's clear what left: ${SCREENS_URL}#proxy/paths"
  point "In Grafana, 'Scrape up, by oven' can show that one 0 for a stopped oven, then its series ends."
}

cmd_scale() {
  local count="${1:-}" running
  if ! [[ "$count" =~ ^[0-9]+$ ]] || ((count < 1 || count > MAX_OVENS)); then
    die "give the number of ovens, from 1 to $MAX_OVENS"
  fi
  require_demo
  running="$(ovens | grep -c . || true)"
  if ((count > running)); then
    scale_up "$count"
  elif ((count < running)); then
    scale_down "$count"
  elif ((count == 1)); then
    bold "1 oven is running already"
  else
    bold "$count ovens are running already"
  fi
}

# A misconfigured oven's log: oven_log <service>
oven_log() { "${COMPOSE[@]}" "${ON_DEMAND[@]}" logs --no-log-prefix "$1" 2>/dev/null || true; }

# Whether a misconfigured oven has logged its agent's line and its first order: oven_logged <service> <regex>
oven_logged() {
  local log
  log="$(oven_log "$1")"
  grep -qE "$2" <<<"$log" && grep -q "Baked the first order" <<<"$log"
}

# The no-proxy, bad-certificate, and wrong-token ovens: starts one of them and waits for its agent's line, an extended
# regex, and its first order. misconfigured_oven <service> <regex>
misconfigured_oven() {
  require_demo
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" up --detach --no-deps --force-recreate "$1" 2>/dev/null
  wait_until 60 oven_logged "$1" "$2" || die "$1 didn't log what was expected within 60s; see '$0 logs $1'"
}

# Prints a misconfigured oven's lines: its agent's, an extended regex, and its first order.
# show_oven_lines <service> <regex>
show_oven_lines() {
  local log
  log="$(oven_log "$1")"
  logged "The oven" "$(grep -E "$2" <<<"$log" | head -1)"
  logged "Then it" "$(grep "Baked the first order" <<<"$log" | head -1)"
}

# Stops and removes a misconfigured oven: remove_oven <service>
remove_oven() {
  "${COMPOSE[@]}" "${ON_DEMAND[@]}" rm --stop --force "$1" >/dev/null 2>&1
}

cmd_no_proxy() {
  local pattern="No proxy is set"
  misconfigured_oven oven-no-proxy "$pattern"
  bold "An oven with no proxy set bakes, with its agent off"
  show_oven_lines oven-no-proxy "$pattern"
  remove_oven oven-no-proxy
  how_it_works "This starts oven-no-proxy, the same image with PROMETHEUS_PROXY unset, as on a developer's machine," \
    "then stops it. fromEnvironment() leaves the proxy unset, so startIfConfigured() doesn't start the agent: it logs" \
    "one INFO line, returns null, and use { } runs the oven all the same."
  watch_for
  point "The oven bakes from the shared queue as any other does; its metrics just go nowhere."
}

cmd_bad_cert() {
  local pattern="could not start"
  misconfigured_oven oven-bad-cert "$pattern"
  bold "An oven whose agent can't start bakes, with its agent off"
  show_oven_lines oven-bad-cert "$pattern"
  remove_oven oven-bad-cert
  how_it_works "This starts oven-bad-cert, with PROMETHEUS_PROXY_CERT set to text that isn't a certificate, then" \
    "stops it. fromEnvironment() reads it as the certificate the agent trusts the proxy by, which the agent rejects" \
    "as it starts. startIfConfigured() logs why, at ERROR, without the PEM itself, and returns null, so the oven" \
    "bakes without its agent. start() would have thrown instead, for a program that can't run without it."
  watch_for
  point "The line names the variable the agent rejected and the setting it went to, so it's fixed with a deploy," \
    "not a debugger."
}

cmd_wrong_token() {
  local pattern="refused the agent's token" before refusals errors
  before="$(prom_number 'sum(proxy_agent_auth_failures_total)')"
  misconfigured_oven oven-wrong-token "$pattern"
  # The agent retries every few seconds: watch it for a while, to see that only the first refusal is logged above DEBUG
  sleep 20
  errors="$(oven_log oven-wrong-token | grep -c "$pattern" || true)"
  bold "An oven whose token the proxy refuses bakes, while its agent retries"
  show_oven_lines oven-wrong-token "$pattern"
  remove_oven oven-wrong-token
  sleep 6
  refusals=$(($(prom_number 'sum(proxy_agent_auth_failures_total)') - before))
  echo
  wrap "  " "  " "In about 25 seconds, the proxy refused the agent $refusals times (proxy_agent_auth_failures_total)," \
    "and the oven logged $errors of them at ERROR."
  how_it_works "This starts oven-wrong-token, with a PROMETHEUS_AGENT_TOKEN the proxy doesn't accept, watches it" \
    "for 20 seconds, then stops it. Its agent starts, since nothing about it is wrong until it connects, so" \
    "startIfConfigured() returns it. The proxy refuses the token (UNAUTHENTICATED): the agent logs the first" \
    "refusal at ERROR, naming where the token is set, and the ones after at DEBUG, and keeps retrying, in the" \
    "background, should the proxy's token change. The oven bakes throughout."
  watch_for
  point "The proxy dashboard's Activity view counts the refusals on one line, bad token, an unknown agent token" \
    "from the oven's address: ${SCREENS_URL}#proxy/activity"
}

# The tour: the demo's guided tour (README.md), one part at a time. It numbers each part's steps, and waits for Enter
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

# A step the reader takes, such as opening a page, then waits: instruct <what to do> [<what it shows>...]
instruct() {
  number_step "$@"
  pause "Press Enter to continue"
}

# A step that runs a scenario once the reader presses Enter: step <what it does>... -- <command> [argument]. What it
# does is joined into one paragraph. It runs as ./demo.sh from this directory, so the commands it prints read
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
  "2 A worker with no port at all"
  "2 The metrics in Grafana"
  "4 A rush, and two more ovens clear it"
  "2 Scaling in stops ovens cleanly"
  "2 No proxy, no outage"
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
  require_demo
  tour_contents "plain Kotlin demo"

  if part 1 "A worker, such as a queue consumer, a scheduler, or a daemon, often has no HTTP server at all, so" \
    "there's nothing for Prometheus to scrape. Here each oven is a plain Kotlin program, with the agent embedded" \
    "in it, which reads the oven's metrics in-process and sends them out to the proxy."; then
    instruct "What runs in this demo, as the diagram at the top of its page draws it: ${PAGE_URL}" \
      "A bakery's oven, a plain Kotlin program with no framework and no HTTP server, bakes the orders a counter" \
      "pushes onto a Redis queue, about one and a half a second. The prometheus-proxy agent runs inside the oven's" \
      "JVM. Beside the proxy are Prometheus and Grafana, as on a proxy host."
    step "Look for a port on the oven, and at what its JVM has open." -- sockets
    instruct "Read the code that does it, the oven's main: demos/plain-kotlin/app/src/main/kotlin/bakery/Main.kt" \
      "fromEnvironment() reads the proxy and the token from PROMETHEUS_PROXY and PROMETHEUS_AGENT_TOKEN." \
      "source() serves the oven's registry, the Prometheus Java client's default one, and agentMetricsAt() the" \
      "agent's own metrics, each at a path named after the oven's container. startIfConfigured().use { } runs the" \
      "oven whether or not the agent started, and stops the agent after it. On a SIGTERM, the shutdown hook above" \
      "it stops the oven and keeps the JVM up until main returns: the oven finishes the order it's baking, then" \
      "use { } stops the agent. The Worker's Code, on the demo's page, walks through it:" \
      "${PAGE_URL}#the-workers-code"
  fi

  if part 2 "Prometheus scrapes the proxy at the paths the oven's agent registered, and Grafana shows what it" \
    "collected."; then
    instruct "Open the demo's screens, on Grafana: ${SCREENS_URL}#grafana" \
      "This is the proxy's screens page, one browser tab that holds every screen in the demo: the proxy's" \
      "dashboard, Grafana, and Prometheus. The steps from here link into this page, so keep it open, and each link" \
      "opens in it. Grafana opens on the Bakery dashboard, with no sign-in needed. Its top row is the bakery now:" \
      "ovens scraped, agents connected, waiting orders, orders baked a minute, and the bake time. Below it are each" \
      "oven's orders, the backlog against the number of ovens, bake times, and how much of its time each oven spends" \
      "baking. The 'Prometheus Agents' link at the top opens the dashboard the project ships for its agents, which" \
      "shows the ovens' embedded agents, the oven-agent job, like any other, so they need no tooling of their own."
    step "Read the oven's metrics and its agent's through the proxy, each at its own path." -- metrics
  fi

  if part 3 "One oven bakes about two orders a second, which keeps up with the counter's one and a half. Here a" \
    "rush of 500 orders comes in over 50 seconds, and you watch the backlog climb, then add two ovens and watch" \
    "them clear it."; then
    step "Keep Grafana's Bakery dashboard open, to watch 'Waiting orders, and ovens' and 'Orders baked / sec, by" \
      "oven', and start a rush: ${SCREENS_URL}#grafana" -- rush
    step "Once the backlog has passed 200, add two ovens, and watch their paths join the proxy dashboard's Paths view" \
      "while the backlog drains in Grafana. Open the two side by side before you press Enter:" \
      "${SCREENS_URL}#proxy/paths+grafana" -- scale 3
    instruct "Open Prometheus's targets: ${SCREENS_URL}#prometheus/targets" \
      "The oven job lists three ovens now, each a target of its own, found through the proxy's service discovery," \
      "with no change to Prometheus's config. In about two minutes, the backlog in Grafana is gone."
  fi

  if part 4 "A scale-in sends each oven it stops a SIGTERM, as Kubernetes or ECS does. The oven finishes the order" \
    "it's baking and takes no more, then its agent stops and disconnects from the proxy, which drops its paths."; then
    step "Once the backlog has cleared, go back to one oven." -- scale 1
    instruct "Open the proxy's service discovery: $PROXY_URL/discovery" \
      "It lists only the remaining oven's two paths, so Prometheus, which follows it, scrapes that oven alone now."
    instruct "Open the proxy dashboard's Paths view: ${SCREENS_URL}#proxy/paths" \
      "It still lists the stopped ovens' four paths, marked 'gone', and its status bar counts them as departed: the" \
      "view keeps a departed path for an hour, so it's clear what left."
    instruct "Open the dashboard's Activity view: ${SCREENS_URL}#proxy/activity" \
      "It lists each stopped oven's agent as disconnected: Connection closed; released 2 paths."
  fi

  if part 5 "An oven's metrics aren't worth an outage. startIfConfigured() starts the agent only where a proxy is" \
    "set, and logs a start that fails rather than throwing, so the oven bakes either way. Here three ovens start" \
    "with the agent's settings wrong, one at a time, and each bakes."; then
    step "Start an oven with no proxy set." -- no-proxy
    step "Start an oven whose PROMETHEUS_PROXY_CERT isn't a certificate." -- bad-cert
    step "Start an oven whose token the proxy refuses." -- wrong-token
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending plain-kotlin
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
  sockets) cmd_sockets ;;
  metrics) cmd_metrics ;;
  rush) cmd_rush ;;
  scale) cmd_scale "$@" ;;
  no-proxy) cmd_no_proxy ;;
  bad-cert) cmd_bad_cert ;;
  wrong-token) cmd_wrong_token ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
