#!/usr/bin/env bash
# Drives the Prometheus Proxy Spring Boot demo (see README.md): starts and stops it, and runs the scenarios its tour
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
# The images the demo runs, under these tags whichever way they came. The proxy's is pulled from Docker Hub (up) or
# built from this checkout (up-local), under the tag the other demos use, so there's one copy of it; the roastery's is
# built either way, against the latest release (up) or this checkout (up-local).
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/spring-boot-app:local)
RELEASED_PROXY_IMAGE=pambrose/prometheus-proxy
# The demo shows what came after this release (the agent's own dashboard, and the Spring Boot starter the roastery runs
# it with), so until a later one is out, up builds everything from this checkout, as up-local does.
NEEDS_RELEASE_AFTER=4.7.0
# The version up-local publishes this checkout's library at, to the local Maven repository, for the roastery's Maven
# build: never a released one, so nothing mistakes it for one.
LOCAL_LIBRARY_VERSION="$(sed -n 's/^version=//p' "$REPO_DIR/gradle.properties")"
LOCAL_LIBRARY_VERSION="${LOCAL_LIBRARY_VERSION%-SNAPSHOT}-spring-boot-demo-SNAPSHOT"
# The secrets the demo's containers read (compose.yml), which up writes the first time it runs.
ENV_FILE="$DEMO_DIR/docker/.env"
CERTS_DIR="$REPO_DIR/testing/certs"

APP_URL="http://localhost:${SPRING_BOOT_DEMO_APP_PORT:-8490}"
DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/dashboard"
# The roastery's agent's own dashboard, which compose.yml turns on from the environment
AGENT_DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_AGENT_DASHBOARD_PORT:-8495}/dashboard"
PROMETHEUS_URL="http://localhost:${SPRING_BOOT_DEMO_PROMETHEUS_PORT:-9490}"
PROXY_URL="http://localhost:${SPRING_BOOT_DEMO_PROXY_PORT:-8480}"
# Prometheus's graph of the batches roasted a minute, by origin: rate(roastery_batches_roasted_total[1m]), with its
# parentheses and brackets percent-encoded, so a terminal takes the whole URL as one link.
GRAPH_URL="$PROMETHEUS_URL/query?g0.expr=rate%28roastery_batches_roasted_total%5B1m%5D%29"

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
  up                 Start the demo: the proxy released on Docker Hub, and the roastery built against that release
  up-local           Start it with the library, the proxy, and the roastery built from this checkout (needs a JDK 17
                     or later, and Maven)
  down               Stop the demo and remove its containers and network
  status             Show the containers, the roastery's state, and the URLs
  open               Open the roastery, the proxy's and the agent's dashboards, and Prometheus in the browser
  logs [service]     Follow the logs (roastery, proxy, prometheus)
  tour [part]        Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

Scenarios:
  settings           Show the agent's settings as Spring Boot bound them, and the agent starting
  peek               Show that /actuator/prometheus isn't served, then read the metrics through the proxy
  health             Show the agent in Actuator's /actuator/health: connected, and to which proxy
  versions           Compare the versions of the agent's libraries the roastery runs, which Spring Boot picked, with
                     the agent's own
  proxy-stop         Stop the proxy: the roastery keeps serving, and its agent's dashboard shows it retrying
  proxy-start        Start it again
  stop-app           Stop the roastery, as a platform does, with SIGTERM
  start-app          Start it again
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy Spring Boot demo"
  echo "  Roastery:           $APP_URL"
  echo "  Proxy dashboard:    $DASHBOARD_URL"
  echo "  Agent dashboard:    $AGENT_DASHBOARD_URL"
  echo "  Prometheus:         $PROMETHEUS_URL"
  echo "  Through the proxy:  $PROXY_URL/roastery_metrics"
  echo
  echo "Scenarios: $0 settings | peek | proxy-stop | stop-app ...  (no arguments for help)"
  echo "Or walk through them all: $MAKE_NAME tour, or $0 tour"
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

wait_for() { wait_until 120 answers "$1" || die "$2 did not answer at $1 within 120s; see '$0 logs'"; }

# A service's log lines that match an extended regex, one a line, or nothing: log_lines <service> <regex>
log_lines() { "${COMPOSE[@]}" logs --no-log-prefix "$1" 2>/dev/null | grep -E "$2" || true; }

# How many lines some text has, not counting an empty one.
count_lines() { grep -c . <<<"$1" || true; }

# How many of a service's log lines match an extended regex: log_count <service> <regex>
log_count() { count_lines "$(log_lines "$1" "$2")"; }

# Whether more than $3 of service $1's log lines match the extended regex $2, which it keeps in log_matches.
logged_more() { log_matches="$(log_lines "$1" "$2")" && (($(count_lines "$log_matches") > $3)); }

# Prints a service's latest log line matching an extended regex, once there are more than [after] of them, waiting up
# to 30s; prints nothing if none comes. It reads the log once a second: said <service> <regex> [after]
said() {
  wait_until 30 logged_more "$1" "$2" "${3:-0}" || return 0
  printf '\033[36mThe %s logged:\033[0m %s\n' "$1" "$(tail -1 <<<"$log_matches")"
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

# The latest release: the version the docs give, whose images are on Docker Hub and whose library is on Maven Central.
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

# Building from this checkout runs Gradle for the library and the proxy, and Maven for the roastery, which need a JDK 17
# or later: the one JAVA_HOME names, as both take it, or else the java on the PATH. Checked before anything is built,
# so a missing tool is named up front.
check_build_tools() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version
  command -v "$java" >/dev/null 2>&1 ||
    die "building from this checkout takes a JDK 17 or later; install one, or set JAVA_HOME (https://adoptium.net)"
  version="$("$java" -version 2>&1 | sed -nE '1s/.*version "(1\.)?([0-9]+).*/\2/p')"
  if ! [[ "$version" =~ ^[0-9]+$ ]] || ((version < 17)); then
    die "building from this checkout takes a JDK 17 or later, not Java ${version:-of an unknown version} ($java)"
  fi
  need mvn "install Maven 3.9 or later (https://maven.apache.org/install.html), which builds the roastery"
}

# Writes the secrets the containers read, the first time the demo starts: the proxy's TLS certificate and key, as
# base64, the certificate of the CA that signed it, which the roastery's agent trusts, and a token the agent presents.
# The certificates are the repository's test ones (testing/certs), public, so this suits a demo on a laptop and nothing
# else; the Cloud Platforms page shows how to make your own.
write_env() {
  [[ -f "$ENV_FILE" ]] && return
  b64() { base64 <"$CERTS_DIR/$1" | tr -d '\n'; }
  random_hex() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }
  (
    umask 077
    cat >"$ENV_FILE" <<EOF
# Written by demos/spring-boot/demo.sh, and read by docker compose (compose.yml). Not committed: delete it, and the
# next make up writes a new one, with a new token.
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

# The latest release's proxy and library, unless it predates what the demo shows.
cmd_up() {
  local release
  check_tools
  release="$(latest_release)" || die "can't tell the latest release from the docs (make site-version)"
  if version_after "$release" "$NEEDS_RELEASE_AFTER"; then
    start_demo "$release"
  else
    wrap "" "" "The latest release, $release, predates this demo, so it builds the library, the proxy, and the" \
      "roastery from this checkout, which takes a JDK 17 or later, and Maven."
    start_demo
  fi
}

cmd_up_local() {
  check_tools
  start_demo
}

# Starts the demo, or brings a running one up to date, with a release's proxy and library (start_demo <version>) or
# with ones built from this checkout (start_demo).
start_demo() {
  local old_ids id
  # The images the demo ran, to remove below once nothing tags them: each build makes new ones. One another demo still
  # runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"
  write_env

  if [[ $# -gt 0 ]]; then
    bold "Starting the demo with the proxy of release $1, from Docker Hub, and the roastery built against it"
    pull_proxy "$1"
    # The first build downloads Maven's dependencies, which takes a few minutes; Docker keeps them for the next.
    SPRING_BOOT_DEMO_APP_BUILD=compiled SPRING_BOOT_DEMO_PROXY_VERSION="$1" "${COMPOSE[@]}" build roastery
    "${COMPOSE[@]}" up --detach --wait --wait-timeout 180
  else
    check_build_tools
    bold "Building the proxy, and the roastery against the library, from this checkout"
    # The proxy's fat jar, which its image copies, and the library and its Spring Boot starter, which the roastery's
    # Maven build takes from the local Maven repository, as an app on Maven Central's would; the library's API docs,
    # which the build doesn't read, are left out.
    "$REPO_DIR/gradlew" --quiet -p "$REPO_DIR" -PoverrideVersion="$LOCAL_LIBRARY_VERSION" \
      proxyJar publishToMavenLocal -x dokkaGeneratePublicationHtml
    mvn --batch-mode --quiet -f "$DEMO_DIR/app" -Dprometheus-proxy.version="$LOCAL_LIBRARY_VERSION" \
      -Dmaven.test.skip=true package
    SPRING_BOOT_DEMO_APP_BUILD=prebuilt "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180
  fi
  # Compose recreates a container when its own config changes, not when a file it mounts does: reload Prometheus's
  # config (SIGHUP), so a demo started from an older checkout runs this one's.
  "${COMPOSE[@]}" kill --signal SIGHUP prometheus >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$APP_URL/actuator/health" "The roastery"
  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$AGENT_DASHBOARD_URL" "The agent's dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  print_urls
}

cmd_down() {
  "${COMPOSE[@]}" down --remove-orphans
}

cmd_status() {
  "${COMPOSE[@]}" ps
  if answers "$APP_URL/actuator/health"; then
    echo
    bold "The roastery"
    curl -fsS "$APP_URL/"
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
  for url in "$APP_URL" "$DASHBOARD_URL" "$AGENT_DASHBOARD_URL" "$GRAPH_URL"; do
    "$opener" "$url"
  done
}

# A library's version in a jar file name from the roastery's lib/, such as grpc-core-1.84.1.jar: lib_version
# <listing> <artifact> [major], where major, if given, picks the version starting with it, as Jackson 2's beside 3's.
lib_version() {
  sed -nE "s/^$2-(${3:-[0-9]}[0-9A-Za-z.]*)\.jar$/\1/p" <<<"$1" | head -1
}

# A library's version in the repository's version catalog, which the agent is built with: catalog_version <key>
catalog_version() { sed -nE "s/^$1 *= *\"([^\"]+)\".*/\1/p" "$REPO_DIR/gradle/libs.versions.toml"; }

# One row of the versions table: row <library> <the agent's> <the roastery's>
row() { printf '  %-24s %-14s %s\n' "$1" "$2" "$3"; }

cmd_versions() {
  local libs
  libs="$("${COMPOSE[@]}" exec -T roastery ls /app/lib 2>/dev/null)" ||
    die "the roastery isn't running; start the demo with '$MAKE_NAME up'"
  bold "The agent's libraries: the versions it's built with, and the ones the roastery runs, which Spring Boot" \
    "$(lib_version "$libs" spring-boot) picked"
  row "Library" "The agent's" "The roastery's"
  row "kotlinx-serialization" "$(catalog_version serialization)" "$(lib_version "$libs" kotlinx-serialization-json-jvm)"
  row "Jackson 2" "$(catalog_version jackson)" "$(lib_version "$libs" jackson-databind 2)"
  row "Logback" "$(catalog_version logback)" "$(lib_version "$libs" logback-classic)"
  row "gRPC" "$(catalog_version grpc)" "$(lib_version "$libs" grpc-core)"
  row "Netty" "$(catalog_version netty)" "$(lib_version "$libs" netty-handler)"
  row "Kotlin" "$(catalog_version kotlin)" "$(lib_version "$libs" kotlin-stdlib)"
  how_it_works "Spring Boot's dependency management, which the roastery's pom takes from spring-boot-starter-parent," \
    "sets the version of every library it knows, the agent's dependencies among them, whatever version the agent" \
    "asks for. The agent runs with these, as the roastery shows here and its RoasteryAgentTest checks on every pull" \
    "request. Were one to break it, a property in the pom sets that library back: kotlin-serialization.version," \
    "jackson-2-bom.version, logback.version, or grpc-java.version."
  watch_for
  point "The roastery's image holds its dependencies as jars in /app/lib, the layout Spring Boot's own extract" \
    "command makes, so this reads their versions from their names: $0 logs roastery shows no error from any."
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
  "2 A Spring Boot app with the agent embedded"
  "2 The app's metrics, read in-process, through the proxy"
  "2 What Spring Boot's dependency management picked"
  "3 The agent's own dashboard, with the proxy stopped and started"
  "2 A graceful shutdown takes the agent down first"
)

cmd_tour() {
  check_tour_from "${1:-}"
  answers "$APP_URL/actuator/health" ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/spring-boot"
  tour_contents "Spring Boot demo"

  if part 1 "The roastery is a Spring Boot app, written in Java, that roasts the coffee shop's beans. The" \
    "prometheus-proxy agent runs inside it, started by the project's Spring Boot starter from a few properties in" \
    "application.yml, with no code of the roastery's own."; then
    instruct "What runs in this demo." \
      "The roastery, one container and one JVM, with the agent inside it, and outside it the proxy and" \
      "Prometheus, as on a proxy host in the cloud. The roastery roasts a batch of beans every two seconds and" \
      "ships some to the coffee shop every five, and counts both in Micrometer meters."
    instruct "Open the roastery: $APP_URL" \
      "Its status page shows the batches roasted by origin, the stock, and what it has shipped."
    step "Show the agent's settings as Spring Boot bound them, and the agent starting." -- settings
    instruct "Open the proxy dashboard: $DASHBOARD_URL" \
      "Its Agents view lists one agent, roastery-1.0, named after the app's version from Spring Boot's build" \
      "info, with one path, roastery_metrics. The agent connected to the proxy over TLS, and registered it."
    step "Ask Actuator's health how the agent is doing." -- health
  fi

  if part 2 "The agent reads the roastery's metrics from its registry, inside the JVM, rather than over HTTP, so" \
    "the roastery serves no metrics endpoint, and has no password or Spring Security to set up for one."; then
    step "Try Actuator's /actuator/prometheus, then read the roastery's metrics through the proxy." -- peek
    instruct "Open Prometheus's graph of the batches roasted a minute, by origin:" \
      "$GRAPH_URL" \
      "Prometheus scrapes the proxy at /roastery_metrics, and the instance label names the roastery."
  fi

  if part 3 "Spring Boot sets the versions of the libraries it knows, which include some of the agent's own." \
    "Here you see which versions the roastery runs, against the ones the agent is built with."; then
    step "Compare the versions." -- versions
  fi

  if part 4 "The agent serves a dashboard of its own, from inside the roastery, which shows the agent's side of" \
    "its connection: whether it's connected, whether the proxy took its path, and the scrapes it answered. The demo" \
    "turns it on from the environment, with PROMETHEUSPROXY_AGENT_DASHBOARD_PORT, which Spring Boot binds to the" \
    "starter's prometheus-proxy.agent.dashboard.port, so application.yml needn't change."; then
    instruct "Open the roastery agent's dashboard: $AGENT_DASHBOARD_URL" \
      "Its Paths view lists the agent's one path, /roastery_metrics, registered, with a green light. Under its" \
      "target, in-process, are the status of its last scrape and a strip of its recent ones, a new one every five" \
      "seconds as Prometheus scrapes the proxy."
    instruct "Select /roastery_metrics, then press Scrape now." \
      "The path's page shows its target, in-process, and its Recent scrapes, newest first: each one's status," \
      "how long the agent took to read the registry, and the size of what it read. Scrape now has the agent read" \
      "the registry at once, and shows the status, the metric families and samples, and the start of the" \
      "roastery's metrics as Prometheus would get them."
    instruct "Open the Activity view: $AGENT_DASHBOARD_URL/activity" \
      "It lists what has happened to the agent, newest first: registered, for /roastery_metrics, and under it" \
      "connected, to the proxy, from when the roastery started."
    instruct "Open the Connection view, and keep it open: $AGENT_DASHBOARD_URL/connection" \
      "It reads connected, and lists the agent's one proxy endpoint, proxy:50051, with no error. The settings the" \
      "agent resolved are on the Settings view, the last tab."
    step "Stop the proxy, as when a proxy host goes away." -- proxy-stop
    step "Start the proxy again." -- proxy-start
  fi

  if part 5 "A platform stops an app with SIGTERM, on a deploy or a scale-down. Spring Boot shuts down" \
    "gracefully, and the agent, a lifecycle bean in the last phase, stops first."; then
    instruct "Keep the proxy dashboard's Agents view open." \
      "You'll watch the roastery's agent leave it."
    step "Stop the roastery." -- stop-app
    step "Start it again." -- start-app
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  bold "That's the tour."
  echo "To start again from a part, run $MAKE_NAME tour PART=<n>, or $0 tour <n>."
  echo
  bold "The other demos:"
  echo "An app with the agent embedded under load, in Grafana, with alerts, a deploy that hands its path to the new"
  echo "version, the proxy going away, and its secrets, is the coffee shop in the embedded demo, in demos/embedded."
  echo "Standalone agents behind a firewall, with the proxy's dashboard, are in the basics demo, in demos/basics."
  echo "Proxy failover, filtering at the agent, and tracing are in the network demo, in demos/network."
  echo "The proxy behind nginx, a reverse proxy that ends the agents' TLS, is in the nginx demo, in demos/nginx."
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
  settings)
    bold "The roastery logged its agent's settings, as Spring Boot bound them, and the agent starting:"
    # The latest start's three lines, as the log keeps every start's, and the agent registers its path again on each
    # reconnect (proxy-start)
    log_lines roastery "Starting the Prometheus agent|Prometheus agent .* started|Registered .* as /" |
      awk '/Starting the Prometheus agent/ { n = 0 } n++ < 3' | tail -3 | sed -E 's/^.* : //; s/^/  /'
    how_it_works "The Spring Boot starter binds the prometheus-proxy.agent properties from application.yml, whose" \
      "values read the environment: PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, PROMETHEUS_PROXY_CERT, and" \
      "PROMETHEUS_PROXY_AUTHORITY, the names the coffee shop uses. Its PrometheusProxyAgent, a SmartLifecycle bean," \
      "builds the agent's settings from them with EmbeddedAgentConfig.builder(), names the agent after the app and" \
      "its build's version, serves the app's metrics registry at roastery_metrics, and starts the agent once the" \
      "app is up."
    watch_for
    point "The token and the certificate show only as (set): their values never reach the log."
    point "The path's target is in-process: the agent reads the registry, not a URL."
    ;;
  peek)
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "$APP_URL/actuator/prometheus")"
    bold "The roastery's /actuator/prometheus answers $code"
    echo
    bold "Through the proxy, $PROXY_URL/roastery_metrics answers with the metrics:"
    # head stops reading early, so pipefail would see grep's broken pipe as a failure.
    curl -fsS "$PROXY_URL/roastery_metrics" | grep -E '^roastery_(batches_roasted_total|roasted_stock)' | head -6 ||
      true
    echo "  ..."
    how_it_works "The roastery exposes only Actuator's health, so /actuator/prometheus isn't there to guard. For each" \
      "scrape, the agent reads the app's PrometheusRegistry bean, where Micrometer keeps the roastery's meters, in" \
      "the format Prometheus asked for, and sends the metrics to the proxy over its own connection. Prometheus" \
      "scrapes the proxy at the path the agent registered, /roastery_metrics."
    watch_for
    point "The metrics are the ones /actuator/prometheus would serve: the roastery's meters, and the JVM's and" \
      "Tomcat's that Spring Boot binds."
    ;;
  health)
    local json
    json="$(curl -fsS "$APP_URL/actuator/health")" ||
      die "the roastery isn't answering at $APP_URL; is the demo running? See '$0 status'"
    bold "The agent's component of Actuator's /actuator/health:"
    sed -nE 's/.*("prometheusAgent":\{"details":\{[^}]*\},"status":"[A-Z_]+"\}).*/  \1/p' <<<"$json"
    # The app's own status, the last in the JSON
    bold "The app's health: $(sed -nE 's/.*"status":"([A-Z_]+)"\}$/\1/p' <<<"$json")"
    how_it_works "The starter's health indicator reads the agent's EmbeddedAgentInfo, isConnected and" \
      "proxyEndpoint, each time Actuator asks: UP while the agent is connected to a proxy that can scrape through" \
      "it, and UNKNOWN when it's off or reconnecting. Not DOWN: the roastery keeps serving without its agent, so a" \
      "lost proxy leaves the app's health UP, and a platform that checks it keeps sending it requests."
    watch_for
    point "With the proxy stopped ($0 proxy-stop), the component turns UNKNOWN within a few seconds while the" \
      "app stays UP; once the proxy is back ($0 proxy-start), it's UP again."
    ;;
  versions) cmd_versions ;;
  proxy-stop)
    "${COMPOSE[@]}" stop proxy
    bold "The proxy has stopped"
    how_it_works "This runs docker compose stop proxy. The roastery's agent loses its connection and retries every" \
      "few seconds, in the background, while the roastery keeps roasting and serving. The proxy's dashboard went" \
      "with the proxy, but the agent's own runs inside the roastery, so it still answers, and shows what the agent" \
      "sees from its side of the connection."
    watch_for
    point "The agent dashboard's Connection view turns failing within a few seconds, and shows the error the agent" \
      "got connecting to proxy:50051: $AGENT_DASHBOARD_URL/connection"
    point "Its Activity view adds disconnected, then connect failed, which isn't listed again as each retry fails" \
      "the same way: $AGENT_DASHBOARD_URL/activity"
    point "On its Paths view, /roastery_metrics turns pending, and its scrapes stop, since Prometheus can't reach the" \
      "proxy. Scrape now, which doesn't need the proxy, still reads the roastery's metrics: $AGENT_DASHBOARD_URL"
    point "Actuator's health shows the agent's component UNKNOWN, while the roastery's own health stays UP:" \
      "$0 health"
    follow_up "To bring it back, run" "$0 proxy-start"
    ;;
  proxy-start)
    "${COMPOSE[@]}" start proxy
    bold "The proxy has started"
    how_it_works "This runs docker compose start proxy. The roastery's agent, still retrying, connects within a few" \
      "seconds, and registers its path again. Nothing in the roastery was restarted or told."
    watch_for
    point "The agent dashboard's Connection view reads connected again, and the endpoint's error is gone:" \
      "$AGENT_DASHBOARD_URL/connection"
    point "Its Activity view adds connected, then registered, for /roastery_metrics:" \
      "$AGENT_DASHBOARD_URL/activity"
    point "On its Paths view, /roastery_metrics is registered again, and its scrapes resume now that Prometheus" \
      "reaches the proxy."
    point "roastery-1.0 is back on the proxy dashboard's Agents view: $DASHBOARD_URL"
    point "Once the agent has reconnected, Actuator's health shows its component UP again: $0 health"
    ;;
  stop-app)
    local stops
    stops="$(log_count proxy "Removed path /roastery_metrics")"
    "${COMPOSE[@]}" stop roastery
    bold "The roastery has stopped"
    how_it_works "This runs docker compose stop roastery, which sends the app SIGTERM, as a platform does. Spring" \
      "Boot stops its lifecycle beans from the last phase down, so the agent stops first: it disconnects from the" \
      "proxy, which drops its path, and no scrape reaches the web server once it starts draining its requests."
    watch_for
    point "roastery-1.0 leaves the proxy dashboard's Agents view, and its path goes with it."
    said proxy "Removed path /roastery_metrics" "$stops"
    point "The agent's dashboard, which runs in the roastery, stops with the agent: its page says it's reconnecting" \
      "until the roastery is back."
    point "In the roastery's log, the agent stopped before Tomcat began its graceful shutdown:"
    log_lines roastery "Stopping the Prometheus agent|Commencing graceful shutdown|Graceful shutdown complete" |
      tail -3 | sed -E 's/^.* : //; s/^/    /'
    follow_up "To bring it back, run" "$0 start-app"
    ;;
  start-app)
    "${COMPOSE[@]}" start roastery
    wait_for "$APP_URL/actuator/health" "The roastery"
    bold "The roastery has started"
    how_it_works "This runs docker compose start roastery. Spring Boot starts the web server, then the agent," \
      "which connects to the proxy and registers its path again."
    watch_for
    point "roastery-1.0 is back on the proxy dashboard's Agents view, with its path."
    ;;
  "" | help | -h | --help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
