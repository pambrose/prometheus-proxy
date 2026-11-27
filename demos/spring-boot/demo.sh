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
# built from this checkout (up-local), under the tag the other demos use, so there's one copy of it; the roastery's is
# built either way, against the latest release (up) or this checkout (up-local).
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/spring-boot-app:local)
RELEASED_PROXY_IMAGE=pambrose/prometheus-proxy
# The newest release that lacks something the demo shows (the proxy's screens page, and an agent dashboard that lets
# the page frame it, which 4.7.1 brings). up runs the release the docs give (make site-version) once it's later than
# this and its proxy image is on Docker Hub, and otherwise builds everything from this checkout, as up-local does, so
# nothing here changes with a release. When the demo comes to use something no release has yet, set this to the
# latest release on Docker Hub.
NEEDS_RELEASE_AFTER=4.7.0
# The version up-local publishes this checkout's library at, to the local Maven repository, for the roastery's Maven
# build: never a released one, so nothing mistakes it for one.
LOCAL_LIBRARY_VERSION="$(sed -n 's/^version=//p' "$REPO_DIR/gradle.properties")"
LOCAL_LIBRARY_VERSION="${LOCAL_LIBRARY_VERSION%-SNAPSHOT}-spring-boot-demo-SNAPSHOT"
# The secrets the demo's containers read (compose.yml), which up writes the first time it runs, to the file it saves
# the moved ports in (PORTS_FILE).
ENV_FILE="$DEMO_DIR/docker/.env"
CERTS_DIR="$REPO_DIR/testing/certs"

# This demo's page on the docs site, which shows its layout and what each part of its tour shows. The demo points only
# there, never to a raw Markdown file, which isn't readable without a viewer.
PAGE_URL="https://prometheus-proxy.com/spring-boot-demo/"
APP_URL="http://localhost:${SPRING_BOOT_DEMO_APP_PORT:-8490}"
# The roastery's agent's own dashboard, which compose.yml turns on from the environment
AGENT_DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_AGENT_DASHBOARD_PORT:-8495}/dashboard"
PROMETHEUS_URL="http://localhost:${SPRING_BOOT_DEMO_PROMETHEUS_PORT:-9490}"
PROXY_URL="http://localhost:${SPRING_BOOT_DEMO_PROXY_PORT:-8480}"
# The proxy's screens page, which holds the roastery's status page, the proxy's and the agent's dashboards, and
# Prometheus on its graph of the batches roasted, in one browser tab (proxy.conf's screens). The tour links into it:
# ${SCREENS_URL}#<screen>, #<screen>/<view>, or #<screen>+<screen> for two side by side. It's the proxy's, so while
# part 4 has the proxy stopped, the proxy-stop scenario gives the agent dashboard's own address instead.
SCREENS_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/screens"
# The proxy's dashboard port's root, which leads to the screens page: the short address make up prints.
SCREENS_HOME_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/"

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
  up                 Start the demo: the proxy released on Docker Hub, and the roastery built against that release
  up-local           Start it with the library, the proxy, and the roastery built from this checkout (needs a JDK 17
                     or later, and Maven)
  down               Stop the demo and remove its containers and network
  status             Show the containers, the roastery's state, and the URLs
  open               Open the proxy's screens page, which holds the roastery, both dashboards, and Prometheus
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
  echo "  All screens:        $SCREENS_HOME_URL"
  echo "  Through the proxy:  $PROXY_URL/roastery_metrics"
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

# Building from this checkout runs Gradle for the library and the proxy, and Maven for the roastery, which need a JDK 17
# or later: the one JAVA_HOME names, as both take it, or else the java on the PATH. Checked before anything is built,
# so a missing tool is named up front.
check_build_tools() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version
  command -v "$java" >/dev/null 2>&1 ||
    die "building from this checkout takes a JDK 17 or later; set JAVA_HOME to one, or get one at https://adoptium.net"
  version="$("$java" -version 2>&1 | sed -nE '1s/.*version "(1\.)?([0-9]+).*/\2/p')"
  if ! [[ "$version" =~ ^[0-9]+$ ]] || ((version < 17)); then
    die "building from this checkout takes a JDK 17 or later, not Java ${version:-of an unknown version} ($java)"
  fi
  need mvn "install Maven 3.9 or later, which builds the roastery: https://maven.apache.org/install.html"
}

# Writes the secrets the containers read, the first time the demo starts: the proxy's TLS certificate and key, as
# base64, the certificate of the CA that signed it, which the roastery's agent trusts, and a token the agent presents.
# It adds them to docker/.env, which holds any ports up saved too (save_ports), unless the file has them already.
# The certificates are the repository's test ones (testing/certs), public, so this suits a demo on a laptop and nothing
# else; the Cloud Platforms page shows how to make your own.
write_env() {
  grep -q '^AGENT_TOKEN=' "$ENV_FILE" 2>/dev/null && return
  b64() { base64 <"$CERTS_DIR/$1" | tr -d '\n'; }
  random_hex() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }
  (
    umask 077
    cat >>"$ENV_FILE" <<EOF
# Written by demos/spring-boot/demo.sh, and read by docker compose (compose.yml): these secrets, and any host ports
# make up saved, moved with the SPRING_BOOT_DEMO_*_PORT variables. Not committed. Delete a port's line to put it back
# to its default, or the whole file, and the next make up writes a new one, with a new token.
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
    wrap "" "" "The latest release, $release, predates this demo, so it builds the library, the proxy, and the" \
      "roastery from this checkout, which takes a JDK 17 or later, and Maven."
    start_demo
  elif ! published "$release" "$RELEASED_PROXY_IMAGE"; then
    wrap "" "" "Release $release, which the docs give, isn't published yet, so the demo builds the library, the" \
      "proxy, and the roastery from this checkout, which takes a JDK 17 or later, and Maven."
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
  local old_ids id
  # The images the demo ran, to remove below once nothing tags them: each build makes new ones. One another demo still
  # runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"
  write_env
  save_ports

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
  "${COMPOSE[@]}" --progress quiet kill --signal SIGHUP prometheus >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  wait_for "$APP_URL/actuator/health" "The roastery"
  wait_for "$SCREENS_URL" "The proxy's screens page"
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
  if command -v open >/dev/null 2>&1; then
    open "$SCREENS_HOME_URL"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$SCREENS_HOME_URL"
  else
    print_urls
  fi
}

# A library's version in a jar file name from the roastery's lib/, such as grpc-core-1.84.1.jar: lib_version
# <listing> <artifact> [major], where major, if given, picks the version starting with it, as Jackson 2's beside 3's.
lib_version() {
  sed -nE "s/^$2-(${3:-[0-9]}[0-9A-Za-z.]*)\.jar$/\1/p" <<<"$1" | head -1
}

# A library's version in the repository's version catalog, which the agent is built with: catalog_version <key>
catalog_version() { sed -nE "s/^$1 *= *\"([^\"]+)\".*/\1/p" "$REPO_DIR/gradle/libs.versions.toml"; }

# A version's first three numbers, as version_after compares them: 4.2.21.Final gives 4.2.21.
version_numbers() { sed -nE 's/^([0-9]+)\.([0-9]+)(\.([0-9]+))?.*/\1.\2.\4/p' <<<"$1"; }

# How the roastery's version of a library compares with the agent's: older, the same, or newer, or nothing when either
# is missing. compared <the agent's> <the roastery's>
compared() {
  local agents roasterys
  agents="$(version_numbers "$1")"
  roasterys="$(version_numbers "$2")"
  if [[ -z "$agents" || -z "$roasterys" ]]; then
    return 0
  elif version_after "$agents" "$roasterys"; then
    echo older
  elif version_after "$roasterys" "$agents"; then
    echo newer
  else
    echo the same
  fi
}

# One row of the versions table, ending with how the roastery's version compares with the agent's:
# row <library> <the agent's> <the roastery's>
row() { printf '  %-24s %-14s %-16s %s\n' "$1" "$2" "$3" "$(compared "$2" "$3")"; }

cmd_versions() {
  local libs
  libs="$("${COMPOSE[@]}" exec -T roastery ls /app/lib 2>/dev/null)" ||
    die "the roastery isn't running; start the demo with '$MAKE_NAME up'"
  bold "The agent's libraries, and the versions Spring Boot $(lib_version "$libs" spring-boot) picked:"
  printf '  %-24s %-14s %s\n' "Library" "The agent's" "The roastery's"
  row "kotlinx-serialization" "$(catalog_version serialization)" "$(lib_version "$libs" kotlinx-serialization-json-jvm)"
  row "Jackson 2" "$(catalog_version jackson)" "$(lib_version "$libs" jackson-databind 2)"
  row "gRPC" "$(catalog_version grpc)" "$(lib_version "$libs" grpc-core)"
  row "Netty" "$(catalog_version netty)" "$(lib_version "$libs" netty-handler)"
  row "netty-tcnative" "$(catalog_version tcnative)" "$(lib_version "$libs" netty-tcnative-classes)"
  row "Kotlin" "$(catalog_version kotlin)" "$(lib_version "$libs" kotlin-stdlib)"
  row "Prometheus client" "$(catalog_version prometheus)" "$(lib_version "$libs" prometheus-metrics-model)"
  how_it_works "Spring Boot's dependency management, which the roastery's pom takes from spring-boot-starter-parent," \
    "sets the version of every library it knows, the agent's dependencies among them, whatever version the agent" \
    "asks for, so some run older than the agent's own. The agent runs with these, as the roastery shows here and its" \
    "RoasteryAgentTest checks on every pull request. Were one to break it, the pom sets that library to the agent's" \
    "version with the property Spring Boot names it by, such as prometheus-client.version, grpc-java.version, or" \
    "netty.version, which Spring Boot's Version Properties appendix lists for every library it manages. An app on" \
    "Spring Boot 4.0 or 4.1 sets two of them, as the Spring Boot Starter page says. netty-tcnative has none, since" \
    "Netty's BOM sets it: the pom would pin netty-tcnative-classes and netty-tcnative-boringssl-static, with each" \
    "native's classifier, in its own dependencyManagement."
  watch_for
  point "Each row ends with how the roastery's version compares with the agent's: older, the same, or newer."
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
  "3 The proxy goes away, and the roastery stays healthy"
  "2 A graceful shutdown takes the agent down first"
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
    instruct "Open the demo's screens, on the roastery's status page: ${SCREENS_URL}#roastery" \
      "This is the proxy's screens page, one browser tab that holds every screen in the demo: the proxy's" \
      "dashboard, the roastery's status page, its agent's dashboard, and Prometheus. The steps from here link into" \
      "this page, so keep it open, and each link opens in it. The status page shows the batches roasted by origin," \
      "the stock, and what it has shipped."
    step "Show the agent's settings as Spring Boot bound them, and the agent starting." -- settings
    instruct "Open the proxy dashboard: ${SCREENS_URL}#proxy" \
      "Its Agents view lists one agent, roastery-1.0, named after the app's version from Spring Boot's build" \
      "info, with one path, roastery_metrics, so the dashboard says which build of the app is reporting. The agent" \
      "connected to the proxy over TLS, and registered it."
    step "Ask Actuator's health how the agent is doing." -- health
  fi

  if part 2 "The agent reads the roastery's metrics from its registry, inside the JVM, rather than over HTTP, so" \
    "the roastery serves no metrics endpoint, and has no password or Spring Security to set up for one."; then
    step "Try Actuator's /actuator/prometheus, then read the roastery's metrics through the proxy." -- peek
    instruct "Open Prometheus's graph of the batches roasted in the last minute, by origin: ${SCREENS_URL}#prometheus" \
      "Each line counts one origin's batches in the minute before each point, about 30 in all, a batch every two" \
      "seconds. Prometheus scrapes the proxy at /roastery_metrics, and the instance label names the roastery."
  fi

  if part 3 "Spring Boot sets the versions of the libraries it knows, which include some of the agent's own." \
    "Here you see which versions the roastery runs, against the ones the agent is built with."; then
    step "Compare the versions." -- versions
  fi

  if part 4 "A platform takes an app out of service, or restarts it, when its health check fails. The starter's" \
    "health indicator reports the agent UNKNOWN, not DOWN, when it loses the proxy, so a proxy outage never fails" \
    "the roastery's health. Here you stop the proxy, and watch Actuator's health and the agent's own dashboard, which" \
    "runs inside the roastery. The demo turns the dashboard on from the environment, with" \
    "PROMETHEUSPROXY_AGENT_DASHBOARD_PORT, which Spring Boot binds to the starter's" \
    "prometheus-proxy.agent.dashboard.port, so application.yml needn't change."; then
    instruct "Open the roastery agent's dashboard: ${SCREENS_URL}#agent" \
      "Its Paths view lists the agent's one path, /roastery_metrics, registered, its target in-process, with a strip" \
      "of its recent scrapes. Select it and press Scrape now, the circular arrow after its name: the agent reads the" \
      "registry at once, and shows the metrics as Prometheus would get them. Then keep the dashboard open on its" \
      "Connection view, which reads connected, to proxy:50051. The screens page is the proxy's, so while the proxy" \
      "is down, a link into it can't load, but the page you have open keeps working, with the agent's dashboard in" \
      "it: ${SCREENS_URL}#agent/connection"
    step "Stop the proxy, as when a proxy host goes away." -- proxy-stop
    step "Ask Actuator's health how the roastery and its agent are doing, with the proxy gone." -- health
    step "Start the proxy again." -- proxy-start
  fi

  if part 5 "A platform stops an app with SIGTERM, on a deploy or a scale-down. Spring Boot shuts down" \
    "gracefully, and the agent, a lifecycle bean in the last phase, stops first."; then
    step "Stop the roastery, and watch its agent leave the proxy dashboard's Agents view as the agent's own" \
      "dashboard stops with it. Open the two side by side before you press Enter: ${SCREENS_URL}#proxy+agent" \
      -- stop-app
    step "Start it again." -- start-app
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending spring-boot
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
    bold "The roastery logged its agent starting, with the settings Spring Boot bound:"
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
    bold "Its metrics, read through the proxy: $PROXY_URL/roastery_metrics"
    # head stops reading early, so pipefail would see grep's broken pipe as a failure.
    curl -fsS "$PROXY_URL/roastery_metrics" | grep -E '^roastery_(batches_roasted_total|roasted_stock)' | head -6 |
      sed 's/^/  /' || true
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
    local json agent_status
    json="$(curl -fsS "$APP_URL/actuator/health")" ||
      die "the demo may not be running; '$0 status' shows whether it is. The roastery doesn't answer at $APP_URL"
    bold "The agent's component of Actuator's /actuator/health:"
    sed -nE 's/.*("prometheusAgent":\{"details":\{[^}]*\},"status":"[A-Z_]+"\}).*/  \1/p' <<<"$json"
    # The app's own status, the last in the JSON
    bold "The app's health: $(sed -nE 's/.*"status":"([A-Z_]+)"\}$/\1/p' <<<"$json")"
    how_it_works "The starter's health indicator reads the agent's EmbeddedAgentInfo, isConnected and" \
      "proxyEndpoint, each time Actuator asks: UP while the agent is connected to a proxy that can scrape through" \
      "it, and UNKNOWN when it's off or reconnecting. Not DOWN: the roastery keeps serving without its agent, so a" \
      "lost proxy leaves the app's health UP, and a platform that checks it keeps sending it requests."
    watch_for
    # The agent's own status, inside its component
    agent_status="$(sed -nE 's/.*"prometheusAgent":\{"details":\{[^}]*\},"status":"([A-Z_]+)"\}.*/\1/p' <<<"$json")"
    if [[ "$agent_status" == UNKNOWN ]]; then
      point "The agent's component reads UNKNOWN while it has no proxy, and the app's health stays UP, so a" \
        "platform that checks it keeps the roastery in service. Once the proxy is back ($0 proxy-start), the" \
        "component is UP again."
    else
      point "With the proxy stopped ($0 proxy-stop), the component turns UNKNOWN within a few seconds while the" \
        "app stays UP; once the proxy is back ($0 proxy-start), it's UP again."
    fi
    ;;
  versions) cmd_versions ;;
  proxy-stop)
    "${COMPOSE[@]}" stop proxy
    bold "The proxy has stopped"
    how_it_works "This runs docker compose stop proxy. The roastery's agent loses its connection and retries every" \
      "few seconds, in the background, while the roastery keeps roasting and serving. The proxy's dashboard and" \
      "screens page went with the proxy, but the agent's dashboard runs inside the roastery, so it still answers," \
      "and shows what the agent sees from its side of the connection: a screens page you have open keeps showing" \
      "it, and the links below give its own address."
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
    # The links below open the proxy's screens page, so they wait until it answers.
    wait_for "$SCREENS_URL" "The proxy's screens page"
    bold "The proxy has started"
    how_it_works "This runs docker compose start proxy. The roastery's agent, still retrying, connects within a few" \
      "seconds, and registers its path again. Nothing in the roastery was restarted or told."
    watch_for
    point "The agent dashboard's Connection view reads connected again, and the endpoint's error is gone:" \
      "${SCREENS_URL}#agent/connection"
    point "Its Activity view adds connected, then registered, for /roastery_metrics:" \
      "${SCREENS_URL}#agent/activity"
    point "On its Paths view, /roastery_metrics is registered again, and its scrapes resume now that Prometheus" \
      "reaches the proxy."
    point "roastery-1.0 is back on the proxy dashboard's Agents view: ${SCREENS_URL}#proxy"
    point "Once the agent has reconnected, Actuator's health shows its component UP again: $0 health"
    ;;
  stop-app)
    local stops
    stops="$(log_count proxy "Removed path /roastery_metrics")"
    "${COMPOSE[@]}" stop roastery
    bold "The roastery has stopped"
    how_it_works "This runs docker compose stop roastery, which sends the app SIGTERM, as a platform does. Spring" \
      "Boot stops its lifecycle beans from the last phase down, so the agent stops first: it disconnects from the" \
      "proxy, which drops its path and stops sending scrapes before Spring Boot starts shutting down the rest of the" \
      "app."
    watch_for
    point "roastery-1.0 leaves the proxy dashboard's Agents view, and its path goes with it."
    said proxy "Removed path /roastery_metrics" "$stops"
    point "The agent's dashboard, which runs in the roastery, stops with the agent: its page says it's reconnecting," \
      "and its pane in the screens page that it isn't answering, until the roastery is back."
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
