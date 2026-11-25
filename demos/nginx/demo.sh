#!/usr/bin/env bash
# Drives the Prometheus Proxy nginx demo (see README.md): starts and stops it, and runs the scenarios, most of them by
# changing the config of nginx, the reverse proxy between the agents and the proxy. The Makefile beside it wraps the
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
# or built from this checkout (up-local). The other demos run the same tags, so there's one copy of each. agent-legacy
# runs a pinned image of release 4.6.0 (docker/compose.yml), which compose pulls itself.
IMAGES=(prometheus-proxy-demo/proxy:local prometheus-proxy-demo/agent:local)
# Their releases on Docker Hub, in the same order, which up pulls at the latest release.
RELEASED_IMAGES=(pambrose/prometheus-proxy pambrose/prometheus-agent)
# The demo shows what came after this release, so until a later one is out, up builds the proxy and agent from this
# checkout, as up-local does. When the demo comes to use something unreleased, set this to the latest release.
NEEDS_RELEASE_AFTER=4.7.0

# nginx's config variants (nginx/): the agents' entrance, agents-<variant>.conf, and the dashboard's,
# dashboard-<variant>.conf, each copied into conf.d as agents.conf or dashboard.conf.
AGENTS_VARIANTS=(stock long-timeouts short-timeouts)
DASHBOARD_VARIANTS=(stock upgrade host)
# The setting `nginx-shutdown-timeout` adds, as nginx's main.d holds it, and how long it gives an old worker.
SHUTDOWN_CONF=/etc/nginx/main.d/main-shutdown-timeout.conf
SHUTDOWN_SECS=10
# The stream keepalive interval the proxy runs with unless `keepalive` sets another
# (proxy.internal.streamKeepAliveSecs).
DEFAULT_KEEPALIVE_SECS=30

# This demo's page on the docs site, which shows its layout and code
PAGE_URL="https://prometheus-proxy.com/nginx-demo/"
DASHBOARD_URL="http://localhost:${NGINX_DEMO_DASHBOARD_PORT:-8594}/dashboard"
# The same dashboard, through nginx (nginx/dashboard-*.conf).
NGINX_DASHBOARD_URL="http://localhost:${NGINX_DEMO_NGINX_PORT:-8590}/dashboard"
# agent-current's own dashboard, behind the firewall with it (agent.dashboard in config/agent-current.conf).
AGENT_DASHBOARD_URL="http://localhost:${NGINX_DEMO_AGENT_DASHBOARD_PORT:-8595}/dashboard"
PROMETHEUS_URL="http://localhost:${NGINX_DEMO_PROMETHEUS_PORT:-9590}"
PROXY_URL="http://localhost:${NGINX_DEMO_PROXY_PORT:-8580}"
# Prometheus graphs, over the last 10 minutes: whether each agent's path answered, and how often nginx closed an
# agent's stream.
GRAPH_OPTIONS="g0.tab=graph&g0.range_input=10m"
UP_GRAPH_URL="$PROMETHEUS_URL/query?g0.expr=up%7Bjob%3D~%22storefront%7Cwarehouse%22%7D&$GRAPH_OPTIONS"
CUTS_QUERY="increase(proxy_agent_disconnects_total%7Breason%3D%22stream_terminated%22%7D%5B1m%5D)"
CUTS_GRAPH_URL="$PROMETHEUS_URL/query?g0.expr=$CUTS_QUERY&$GRAPH_OPTIONS"

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
  down                     Stop the demo and remove its containers and network
  status                   Show the containers, nginx's configs, the keepalive interval, and the URLs
  open                     Open the proxy dashboard, directly and through nginx, agent-current's, and Prometheus
  logs [service]           Follow the logs (proxy, nginx, agent-current, agent-legacy, ...)
  tour [part]              Walk through the demo's guided tour, pausing before each step (from part 1, or the one given)

Scenarios:
  nginx-log [n]            Show the last n gRPC calls nginx passed to the proxy (default 12), by agent
  nginx-stock              The agents' entrance with nginx's default stream timeouts, 60 seconds
  nginx-long-timeouts      The same with both stream timeouts raised to a day
  nginx-short-timeouts     The same with both stream timeouts cut to 20 seconds
  nginx-workers            List nginx's processes, the old workers a reload left behind among them
  nginx-shutdown-timeout   Restart nginx with worker_shutdown_timeout set, so a reload reaches connected agents
  keepalive <secs>         Recreate the proxy with this stream keepalive interval (default ${DEFAULT_KEEPALIVE_SECS})
  dashboard-stock          The dashboard's entrance as a plain proxy_pass
  dashboard-upgrade        The same, passing the WebSocket handshake's headers
  dashboard-host           The same, passing the browser's Host header too
  reset                    Put nginx and the proxy back as make up starts them
EOF
}

print_urls() {
  echo
  bold "Prometheus Proxy nginx demo"
  echo "  Proxy dashboard:          $DASHBOARD_URL"
  echo "  The same, through nginx:  $NGINX_DASHBOARD_URL"
  echo "  agent-current:            $AGENT_DASHBOARD_URL"
  echo "  Prometheus:               $PROMETHEUS_URL"
  echo "  Proxy discovery:          $PROXY_URL/discovery"
  echo
  echo "What each part of this demo shows, and why: ${PAGE_URL}#what-this-demo-shows"
  echo
  echo "Scenarios: $0 nginx-log | nginx-long-timeouts | nginx-workers | keepalive 10 | dashboard-host ...  (no" \
    "arguments for help)"
  echo "Or walk through them all: $MAKE_NAME tour, or $0 tour"
}

# Runs a command inside the nginx container, so nothing of nginx's but the dashboard's entrance is published.
nginx_exec() { "${COMPOSE[@]}" exec -T nginx "$@"; }

# Whether a service's container is running.
running() { [[ -n "$("${COMPOSE[@]}" ps --status running --quiet "$1" 2>/dev/null)" ]]; }

# The variant of an entrance (agents or dashboard) that's in place, or "changed by hand", compared in one exec in the
# container: conf_in_use <kind>
conf_in_use() {
  local -a variants
  if [[ "$1" == agents ]]; then variants=("${AGENTS_VARIANTS[@]}"); else variants=("${DASHBOARD_VARIANTS[@]}"); fi
  # shellcheck disable=SC2016 # $1 and $v are the container shell's
  nginx_exec sh -c 'kind="$1"; shift
    for v in "$@"; do
      if cmp -s "/etc/nginx/demo/$kind-$v.conf" "/etc/nginx/conf.d/$kind.conf"; then echo "$v"; exit; fi
    done
    echo "changed by hand"' sh "$1" "${variants[@]}"
}

# The variant file of an entrance in place, or that it was changed by hand: conf_file <kind>
conf_file() {
  local variant
  variant="$(conf_in_use "$1")"
  if [[ "$variant" == "changed by hand" ]]; then
    echo "conf.d/$1.conf, changed by hand"
  else
    echo "nginx/$1-$variant.conf"
  fi
}

# Whether nginx runs with worker_shutdown_timeout: `nginx-shutdown-timeout` puts the file in place and restarts nginx
# in one go, so the file being there means the running nginx read it. The exec prints the answer rather than failing
# without the file, since in a terminal Docker follows a failed exec with a hint of its own, before any headline.
shutdown_timeout_set() { [[ "$(nginx_exec sh -c "[ -f $SHUTDOWN_CONF ] && echo set || true")" == set ]]; }

# How many of nginx's workers are old ones, shutting down after a reload, holding connections they opened before it.
old_workers() { nginx_exec ps -o args | grep -c 'worker process is shutting down' || true; }

# Puts a variant of an entrance in place and reloads nginx, checking the config first, then waits until no worker from
# before the reload takes new connections, in one exec in the container. nginx -s reload only signals nginx's master,
# which starts new workers and then tells the old ones to stop taking connections: a request made as it returns can
# still reach an old worker, under the config before. The wait ends once every worker titled "worker process" is a new
# one, the old ones having exited or turned "worker process is shutting down" (old_workers), or fails after 10 seconds.
# use_conf <kind> <variant>
use_conf() {
  local status=0
  # shellcheck disable=SC2016 # the variables are the container shell's
  nginx_exec sh -c 'workers() { ps -o pid,args | sed -n "s/^ *\([0-9][0-9]*\) nginx: worker process\$/\1/p"; }
    before=" $(workers | tr "\n" " ")"
    cp "/etc/nginx/demo/$1-$2.conf" "/etc/nginx/conf.d/$1.conf" && nginx -t -q && nginx -s reload || exit 1
    i=0
    while [ "$i" -lt 100 ]; do
      now="$(workers)"
      new=1
      for pid in $now; do
        case "$before" in *" $pid "*) new=0 ;; esac
      done
      [ -n "$now" ] && [ "$new" = 1 ] && exit 0
      sleep 0.1
      i=$((i + 1))
    done
    exit 2' sh "$1" "$2" 2>/dev/null || status=$?
  case "$status" in
  0) ;;
  2) die "nginx didn't move to nginx/$1-$2.conf within 10 seconds; see '$0 logs nginx'" ;;
  *) die "nginx refused nginx/$1-$2.conf; see '$0 logs nginx'" ;;
  esac
}

# The sentence that opens a config scenario's how it works: copies_sentence <kind> <variant>
copies_sentence() { echo "This copies nginx/$1-$2.conf in as nginx's conf.d/$1.conf and reloads nginx."; }

# Restarts nginx and waits for it to pass requests to the proxy again. A restart closes every agent's connection, and
# ends any old worker a reload left. Compose's progress lines, which go to stderr, stay quiet, so a scenario's headline
# comes first.
restart_nginx() {
  "${COMPOSE[@]}" --progress quiet restart nginx >/dev/null
  wait_for "$NGINX_DASHBOARD_URL" "The proxy dashboard, through nginx,"
}

# What a reload of the agents' entrance does to the agents connected at the time, as one point, by whether nginx runs
# with worker_shutdown_timeout: reload_point <0 or 1>
reload_point() {
  if (($1)); then
    point "Within ${SHUTDOWN_SECS} seconds, nginx's old workers close the agents' connections, and each agent" \
      "reconnects at once, to a new worker, under this config: each shows a disconnect and a connect in the proxy's" \
      "Activity view, $DASHBOARD_URL/activity"
  else
    point "nginx's old workers keep the connections they have, under the config they opened with: an agent connected" \
      "now meets this config only when its connection ends, which for an agent whose keepalives keep its streams" \
      "open is never. '$0 nginx-shutdown-timeout' changes that."
  fi
}

# The keepalive interval the running proxy was started with, from its command line (docker/compose.yml).
keepalive_secs() {
  local cmd
  cmd="$(docker inspect -f '{{join .Config.Cmd " "}}' "$("${COMPOSE[@]}" ps --quiet proxy)" 2>/dev/null || true)"
  if [[ "$cmd" =~ streamKeepAliveSecs=([0-9]+) ]]; then echo "${BASH_REMATCH[1]}"; else echo unknown; fi
}

# The same, as a number for a scenario to compare: the default, which compose starts the proxy with, if it can't tell.
keepalive_number() {
  local secs
  secs="$(keepalive_secs)"
  [[ "$secs" =~ ^[0-9]+$ ]] || secs="$DEFAULT_KEEPALIVE_SECS"
  echo "$secs"
}

# How a keepalive interval compares with a stream timeout, both in seconds: off, sooner (a quiet stream's keepalive
# comes before the timeout would end it), same (the two race), or later (the timeout ends a quiet stream first). A
# timeout of 0, an entrance changed by hand, counts as unknown. keepalive_vs <keepalive secs> <timeout secs>
keepalive_vs() {
  if (($1 == 0)); then
    echo off
  elif (($2 == 0)); then
    echo unknown
  elif (($1 < $2)); then
    echo sooner
  elif (($1 == $2)); then
    echo same
  else
    echo later
  fi
}

# The stream timeouts of a variant of the agents' entrance, in seconds, or 0 for one changed by hand:
# timeout_of <variant>
timeout_of() {
  case "$1" in
  stock) echo 60 ;;
  long-timeouts) echo 86400 ;;
  short-timeouts) echo 20 ;;
  *) echo 0 ;;
  esac
}

# The stream timeouts of a variant of the agents' entrance, and the file that sets them, for a sentence:
# timeouts_phrase <variant>
timeouts_phrase() {
  case "$1" in
  stock) echo "nginx's stream timeouts, the default 60 seconds under nginx/agents-stock.conf" ;;
  long-timeouts) echo "nginx's stream timeouts, a day in nginx/agents-long-timeouts.conf" ;;
  short-timeouts) echo "nginx's stream timeouts, 20 seconds in nginx/agents-short-timeouts.conf" ;;
  *) echo "nginx's stream timeouts, in an agents' entrance changed by hand" ;;
  esac
}

# The line agent-current logs as it registers, saying how often it sends stream keepalives, the first since a time
# (RFC 3339, UTC), waiting up to 30 seconds for it to reconnect: keepalive_line_since <time>. Prints nothing if it
# hasn't by then.
keepalive_line_since() {
  local deadline=$((SECONDS + 30)) line
  while ((SECONDS < deadline)); do
    line="$("${COMPOSE[@]}" logs --no-log-prefix --since "$1" agent-current 2>/dev/null |
      sed -nE 's/.*(Sending (no )?stream keepalives[^[]*[^[ ]).*/\1/p' | tail -1)"
    if [[ -n "$line" ]]; then
      echo "$line"
      return
    fi
    sleep 1
  done
}

# Recreates the proxy with a keepalive interval, unless it runs with that one, and waits for its dashboard. nginx
# resolves the proxy's name as it loads its config, and a recreated proxy can get another address, so nginx restarts
# too. It stops first, and starts again once the new proxy answers, so each agent's connection closes once, and each
# agent reconnects once, through an nginx that has the new proxy's address. Left running, nginx would pass the agents'
# reconnects to the proxy's old address, which the new proxy often gets, and its restart would close them again.
# Compose's progress lines stay quiet, as restart_nginx's do. start_proxy <secs>; fails if the proxy ran with that
# interval.
start_proxy() {
  [[ "$(keepalive_secs)" != "$1" ]] || return 1
  "${COMPOSE[@]}" --progress quiet stop nginx >/dev/null
  NGINX_DEMO_KEEPALIVE_SECS="$1" "${COMPOSE[@]}" --progress quiet up --detach --no-deps proxy >/dev/null
  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  "${COMPOSE[@]}" --progress quiet start nginx >/dev/null
  wait_for "$NGINX_DASHBOARD_URL" "The proxy dashboard, through nginx,"
}

# Puts nginx and the proxy back as `up` starts them: nginx's stock configs and no worker_shutdown_timeout, then the
# default keepalive. nginx restarts once, at most: with a recreated proxy, which restarts it, or if anything differed
# or an old worker was left, which the restart ends.
reset_demo() {
  local restart=0 kind
  for kind in agents dashboard; do
    if [[ "$(conf_in_use "$kind")" != stock ]]; then
      nginx_exec cp "/etc/nginx/demo/$kind-stock.conf" "/etc/nginx/conf.d/$kind.conf"
      restart=1
    fi
  done
  if shutdown_timeout_set; then
    nginx_exec rm -f "$SHUTDOWN_CONF"
    restart=1
  fi
  (($(old_workers) == 0)) || restart=1
  if ! start_proxy "$DEFAULT_KEEPALIVE_SECS" && ((restart)); then
    restart_nginx
  fi
}

# Prints nginx's last n gRPC calls from its access log, oldest first, with each agent's address replaced by its name.
# An access log line is written when a call ends, so a stream shows only when it ends. nginx_log <n>
nginx_log() {
  local -a names=()
  local agent ip
  for agent in agent-current agent-legacy; do
    ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
      "$("${COMPOSE[@]}" ps --quiet "$agent")" 2>/dev/null || true)"
    [[ -z "$ip" ]] || names+=("$ip=$agent")
  done
  # Access log lines only, which start with the client's address: an error log line names the request too.
  "${COMPOSE[@]}" logs --no-log-prefix --no-color --tail 2000 nginx |
    { grep -E '^[0-9a-f.:]+ - [^ ]+ \[[^]]+\] "POST /ProxyService/' || true; } |
    tail -n "$1" |
    awk -v names="${names[*]:-}" '
      BEGIN { n = split(names, pairs, " "); for (i = 1; i <= n; i++) { split(pairs[i], kv, "="); who[kv[1]] = kv[2] } }
      {
        time = substr($4, 14, 8)
        call = $7; sub(/^\/ProxyService\//, "", call)
        printf "  %s  %-14s %-45s %s\n", time, ($1 in who ? who[$1] : $1), call, $9
      }'
}

wait_for() {
  local url="$1" name="$2" deadline=$((SECONDS + 120))
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    ((SECONDS < deadline)) || die "$name did not answer within 120s; see '$0 logs'. It was asked at $url"
    sleep 2
  done
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
  save_ports
  # The images the demo ran, to remove below once nothing tags them: a build makes new ones, and a release's tags
  # replace a build's. One another demo still runs is in use, and stays.
  old_ids="$(docker image inspect -f '{{.Id}}' "${IMAGES[@]}" 2>/dev/null || true)"

  # The default keepalive interval, whatever a scenario last set, so compose recreates a proxy that runs another.
  unset NGINX_DEMO_KEEPALIVE_SECS
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
  # Compose recreates a container when its own config changes, not when a file it mounts does, and Prometheus doesn't
  # notice a changed file on its own: reload its config (SIGHUP), so a demo started from an older checkout runs this
  # one's.
  "${COMPOSE[@]}" --progress quiet kill --signal SIGHUP prometheus >/dev/null

  for id in $old_ids; do
    if [[ -z "$(docker image inspect -f '{{join .RepoTags ""}}' "$id" 2>/dev/null)" ]]; then
      docker image rm "$id" >/dev/null 2>&1 || true
    fi
  done

  # A demo that was already running keeps nginx's configs: start this one from the stock ones.
  reset_demo

  wait_for "$DASHBOARD_URL" "The proxy dashboard"
  wait_for "$NGINX_DASHBOARD_URL" "The proxy dashboard, through nginx,"
  wait_for "$AGENT_DASHBOARD_URL" "agent-current's dashboard"
  wait_for "$PROMETHEUS_URL/-/ready" "Prometheus"
  print_urls
}

cmd_down() {
  "${COMPOSE[@]}" down --remove-orphans
}

cmd_status() {
  "${COMPOSE[@]}" ps
  if running nginx; then
    echo
    bold "nginx"
    echo "  The agents' entrance:     $(conf_file agents)"
    echo "  The dashboard's entrance: $(conf_file dashboard)"
    if shutdown_timeout_set; then
      echo "  worker_shutdown_timeout:  ${SHUTDOWN_SECS}s"
    else
      echo "  worker_shutdown_timeout:  not set"
    fi
    echo "  Old workers left:         $(old_workers)"
  fi
  if running proxy; then
    echo "  The proxy's keepalive:    $(keepalive_secs)s"
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
  for url in "$DASHBOARD_URL" "$NGINX_DASHBOARD_URL" "$AGENT_DASHBOARD_URL" "$UP_GRAPH_URL"; do
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
  wrap "  " "  " "Each step waits for you to press Enter, so you set the pace. To start from another part, run" \
    "$MAKE_NAME tour PART=<n>, or $0 tour <n>."
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
  "4 Two agents reach the proxy through nginx, over TLS"
  "5 nginx's stream timeouts cut off an agent from 4.6.0 every minute"
  "8 A shorter stream timeout, and a reload that doesn't reach connected agents"
  "4 The dashboard behind nginx"
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
  curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
    die "the demo isn't running; start it with '$MAKE_NAME up' in demos/nginx"
  tour_contents "nginx demo"
  # Each part's text assumes the demo as make up starts it, which a scenario run before the tour may have changed.
  reset_demo

  if part 1 "This demo puts nginx between the agents and the proxy, as a reverse proxy that ends the agents' TLS," \
    "and in front of the proxy's dashboard. It builds on the basics demo, so start with that one if you haven't" \
    "seen it."; then
    instruct "The setup: two agents, nginx, and the proxy." \
      "Two agents run behind the firewall: agent-current, of this release, beside a web storefront, and" \
      "agent-legacy, of release 4.6.0, beside a warehouse. Each dials nginx at nginx.test.google.fr:443 over TLS." \
      "nginx ends the TLS and passes each gRPC call on to the proxy's agent port, which only nginx can reach." \
      "Prometheus scrapes both agents' paths through the proxy every 5 seconds."
    instruct "Open the proxy dashboard: $DASHBOARD_URL" \
      "Both agents are connected, each listed at the address Unknown. nginx relays each agent's calls over" \
      "connections of its own, so the proxy can't watch an agent's connection, as its transport filter does, and" \
      "both the proxy and the agents turn the filter off. The proxy then learns an agent has gone when the agent's" \
      "stream of scrape requests ends, and ties each agent's calls to the identity its token names, so each agent" \
      "has a token of its own. Click agent-current: its host is storefront-01, which the agent reports itself."
    step "Show the last gRPC calls nginx passed to the proxy, by agent." -- nginx-log
    instruct "Open agent-current's own dashboard, and its Connection view: $AGENT_DASHBOARD_URL/connection" \
      "agent-current is connected to nginx.test.google.fr:443, using TLS (no mutual auth). The agent checks nginx's" \
      "certificate against the name it dials: the certificate names *.test.google.fr, and the agent trusts the CA" \
      "that signed it, which its config names. agent-legacy, of 4.6.0, has no dashboard: the agent dashboard came" \
      "later."
  fi

  if part 2 "An agent keeps several gRPC streams open to the proxy, and nginx closes a stream that carries nothing" \
    "for 60 seconds. Here you see what that does to an agent from before the keepalives, and how raising" \
    "nginx's timeouts fixes it. It matters if the proxy or any agent you run behind a reverse proxy is on 4.6.0" \
    "or earlier, as while you upgrade one before the other: keepalives flow only when both ends are newer."; then
    instruct "Open the proxy dashboard's Activity view: $DASHBOARD_URL/activity" \
      "About once a minute, agent-legacy disconnects, with the reason 'Stream terminated (transport filter" \
      "disabled)', and connects again a few seconds later. agent-current doesn't. An agent's stream for results sent" \
      "in chunks carries something only when a result is over 32 KB gzipped, so it's quiet even while Prometheus" \
      "scrapes the agent every 5 seconds. agent-current and the proxy send a keepalive on each stream after 30" \
      "seconds of quiet. agent-legacy predates them, and the proxy, seeing it doesn't take them, sends it none."
    instruct "Open Prometheus's graph of how often nginx closed an agent's stream: $CUTS_GRAPH_URL" \
      "It climbs to about one a minute. A scrape that lands while agent-legacy reconnects finds its path gone, and" \
      "fails: up{job=\"warehouse\"} dips to 0 now and then, $UP_GRAPH_URL"
    step "Raise both of nginx's stream timeouts to a day, and reload nginx." -- nginx-long-timeouts
    instruct "Watch the proxy dashboard's Activity view for a minute or two: $DASHBOARD_URL/activity" \
      "agent-legacy is cut off once more, by the worker it's on, which keeps the old timeouts. It reconnects to a" \
      "new worker, under the day-long timeouts, and then stays connected: no more disconnects from it."
  fi

  if part 3 "Some load balancers close a quiet connection sooner than nginx's 60 seconds. Here nginx's timeouts" \
    "come down to 20 seconds, under the 30 seconds after which the keepalives start, and you see why a reload of" \
    "nginx doesn't reach an agent that's connected, and what does."; then
    step "Cut both of nginx's stream timeouts to 20 seconds, and reload nginx." -- nginx-short-timeouts
    step "List nginx's processes." -- nginx-workers
    step "Set nginx's worker_shutdown_timeout to ${SHUTDOWN_SECS} seconds, and restart nginx to bring it in." \
      -- nginx-shutdown-timeout
    instruct "Watch agent-current's Activity view for a minute: $AGENT_DASHBOARD_URL/activity" \
      "Now under the 20-second timeouts, agent-current disconnects about every 20 seconds, and connects again at" \
      "once. Two of its streams go quiet for 20 seconds before their keepalives come, at 30: its stream for results" \
      "in chunks, which nginx ends with 408, from client_body_timeout, and the proxy's stream of path notices to" \
      "it, which nginx ends with 504, from grpc_read_timeout. Each disconnect's reason is the 504."
    step "Recreate the proxy with a keepalive interval of 10 seconds, under nginx's 20." -- keepalive 10
    instruct "Watch agent-current's Activity view for a minute: $AGENT_DASHBOARD_URL/activity" \
      "After its reconnect to the new proxy, agent-current stays connected: a keepalive every 10 seconds keeps each" \
      "of its streams from going quiet for nginx's 20. agent-legacy, which sends none, is still cut off about every" \
      "20 seconds, in the proxy's Activity view."
    put_back "put nginx and the proxy back as the demo started them" reset
  fi

  if part 4 "The proxy's dashboard updates live, over a WebSocket. Here you put it behind nginx, as you would to" \
    "end its TLS there, and see what nginx has to pass on for the WebSocket to open."; then
    instruct "Open the dashboard through nginx: $NGINX_DASHBOARD_URL" \
      "The page loads, then the status bar's live indicator turns into a blinking red 'reconnecting...' that" \
      "never clears, and nothing on the page moves. nginx's entrance for it is a plain proxy_pass, which passes" \
      "each request on as HTTP/1.0 and drops the headers that ask to turn a request into a WebSocket, Upgrade and" \
      "Connection. The dashboard can't take the request as a WebSocket handshake, and answers 400. Compare the" \
      "dashboard reached directly, which updates every second: $DASHBOARD_URL"
    step "Pass the WebSocket handshake's headers, over HTTP/1.1." -- dashboard-upgrade
    step "Pass the Host header the browser sent too." -- dashboard-host
  fi

  wait_after_output "Press Enter to finish the tour"
  blank_line
  tour_ending nginx
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
  nginx-log)
    local count="${1:-12}"
    [[ "$count" =~ ^[0-9]+$ ]] || die "the number of calls to show is a number, not '$count'"
    bold "The last $count gRPC calls nginx passed to the proxy, oldest first"
    nginx_log "$count"
    how_it_works "This reads nginx's access log, which nginx writes a line to as each request ends, and every gRPC" \
      "call is a request. It names each agent by the address its calls come from."
    watch_for
    point "An agent connects with three calls: connectAgentWithTransportFilterDisabled, which gives it its agent ID," \
      "registerAgent, which tells the proxy about it, and registerPath, for each of its paths."
    point "Its streams, readRequestsFromProxy, writeResponsesToProxy, writeChunkedResponsesToProxy, and, for an agent" \
      "of this release, readPathNoticesFromProxy, stay open while it's connected, so a line for one appears only" \
      "when it ends: when its connection drops, or when nginx closes it."
    point "A stream nginx closed for its timeout ends with 408, from client_body_timeout, on a stream the agent" \
      "sends results on, or 504, from grpc_read_timeout, on one the proxy sends on: scrape requests, or path" \
      "notices. The agent's other streams then end too, and it connects again a few seconds later."
    point "A stream that ends with 413 passed nginx's limit on a request body, client_max_body_size, 1 MB by" \
      "default. nginx counts all an agent sends on a stream as one body, and a stream of results stays open while" \
      "the agent is connected, so every entrance in nginx/ turns the limit off with client_max_body_size 0."
    point "An agent nobody scrapes sends sendHeartBeat every 5 seconds, a call of its own, so the proxy knows it's" \
      "alive. An agent that's scraped doesn't need to."
    follow_up "To see every request as it comes, run" "$0 logs nginx"
    ;;
  nginx-stock)
    local shutdown=0 ka why
    use_conf agents stock
    shutdown_timeout_set && shutdown=1
    ka="$(keepalive_number)"
    bold "nginx's agents' entrance has its default stream timeouts again, 60 seconds"
    how_it_works "$(copies_sentence agents stock)" \
      "It sets neither grpc_read_timeout, for the streams the proxy sends on, scrape requests and path notices, nor" \
      "client_body_timeout, for the streams the agent sends results on, so both are nginx's default, 60 seconds."
    watch_for
    reload_point "$shutdown"
    case "$(keepalive_vs "$ka" 60)" in
    sooner)
      point "Under these timeouts, agent-current, whose keepalives come after $ka seconds, stays connected, and" \
        "agent-legacy, with nothing on its stream for results in chunks, is cut off every minute, once its" \
        "connection is with a worker under them."
      ;;
    same)
      point "Under these timeouts, agent-legacy, with nothing on its stream for results in chunks, is cut off every" \
        "minute, once its connection is with a worker under them. agent-current's keepalives come after 60" \
        "seconds, just as these timeouts would end a quiet stream, so whether it's cut off too is a race."
      ;;
    *)
      why="agent-current's keepalives come after $ka seconds"
      ((ka > 0)) || why="the proxy runs with its keepalives off"
      point "Under these timeouts, both agents are cut off every minute, once their connections are with a worker" \
        "under them: agent-legacy has nothing on its stream for results in chunks, and $why."
      follow_up "To keep agent-current connected, run" "$0 keepalive $DEFAULT_KEEPALIVE_SECS"
      ;;
    esac
    ;;
  nginx-long-timeouts)
    local shutdown=0 before within
    before="$(conf_in_use agents)"
    use_conf agents long-timeouts
    shutdown_timeout_set && shutdown=1
    bold "nginx's agents' entrance now closes a stream only after a day without anything on it"
    how_it_works "$(copies_sentence agents long-timeouts)" \
      "It raises grpc_read_timeout, for the streams the proxy sends on, scrape requests and path notices, and" \
      "client_body_timeout, for the streams the agent sends results on, to a day. For an agent or a proxy from" \
      "4.6.0 or earlier, it's the fix short of upgrading both: they send nothing on a stream between results."
    watch_for
    reload_point "$shutdown"
    if ((shutdown)); then
      point "agent-legacy then stays connected: the proxy's Activity view shows no more disconnects from it," \
        "$DASHBOARD_URL/activity"
    elif [[ "$before" == long-timeouts ]]; then
      point "These timeouts were in place already, so the reload changes nothing for agent-legacy: once its" \
        "connection is with a worker under them, it stays connected, and the proxy's Activity view shows no more" \
        "disconnects from it, $DASHBOARD_URL/activity"
    elif [[ "$before" == "changed by hand" ]]; then
      point "agent-legacy keeps the connection it has only until the timeouts of the worker it's on end a quiet" \
        "stream. It then reconnects to a new worker, under these timeouts, and stays connected: the proxy's" \
        "Activity view shows no more disconnects from it after that, $DASHBOARD_URL/activity"
    else
      within="a minute"
      [[ "$before" != short-timeouts ]] || within="20 seconds"
      point "agent-legacy is cut off once more, within $within, by the worker it's on, which keeps the old" \
        "timeouts. It reconnects to a new worker, under these timeouts, and then stays connected: the proxy's" \
        "Activity view shows no more disconnects from it, $DASHBOARD_URL/activity"
    fi
    point "Prometheus's graph of nginx closing an agent's stream settles at 0 within a minute or two:" \
      "$CUTS_GRAPH_URL"
    ;;
  nginx-short-timeouts)
    local shutdown=0 before ka vs against
    before="$(conf_in_use agents)"
    use_conf agents short-timeouts
    shutdown_timeout_set && shutdown=1
    ka="$(keepalive_number)"
    vs="$(keepalive_vs "$ka" 20)"
    case "$vs" in
    off) against=". The proxy runs with its stream keepalives off, so nothing keeps a quiet stream open." ;;
    sooner) against=", longer than the $ka seconds after which the proxy and agent-current send a keepalive." ;;
    same) against=", the same as the $ka seconds after which the proxy and agent-current send a keepalive." ;;
    *) against=", under the $ka seconds after which the proxy and agent-current send a keepalive." ;;
    esac
    bold "nginx's agents' entrance now closes a stream after 20 seconds without anything on it"
    how_it_works "$(copies_sentence agents short-timeouts)" \
      "It cuts grpc_read_timeout, for the streams the proxy sends on, scrape requests and path notices, and" \
      "client_body_timeout, for the streams the agent sends results on, to 20 seconds$against"
    watch_for
    reload_point "$shutdown"
    if [[ "$vs" == sooner ]]; then
      point "agent-current stays connected, whichever worker it's on: a keepalive every $ka seconds keeps each of" \
        "its streams from going quiet for 20, so these timeouts never end one. Its own Activity view shows no" \
        "disconnect from a timeout: $AGENT_DASHBOARD_URL/activity"
      if ((shutdown)); then
        point "agent-legacy, which sends no keepalives, is cut off about every 20 seconds, and a scrape that lands" \
          "while it reconnects fails, so its path dips in Prometheus: $UP_GRAPH_URL"
      fi
    elif ((shutdown)); then
      case "$vs" in
      off)
        point "Then both agents are cut off about every 20 seconds, agent-current included: with the keepalives" \
          "off, its streams go as quiet as agent-legacy's. Its own Activity view shows each disconnect, with the" \
          "reason: $AGENT_DASHBOARD_URL/activity"
        ;;
      same)
        point "Then agent-legacy is cut off about every 20 seconds. agent-current's keepalives come after 20" \
          "seconds, just as these timeouts would end a quiet stream, so whether it's cut off too is a race. Its own" \
          "Activity view shows any disconnect, with the reason: $AGENT_DASHBOARD_URL/activity"
        ;;
      *)
        point "Then both agents are cut off about every 20 seconds, agent-current included: its stream for results" \
          "in chunks, and the proxy's stream of path notices to it, go quiet for 20 seconds before their keepalives" \
          "come, at $ka. Its own Activity view shows each disconnect, with the reason: $AGENT_DASHBOARD_URL/activity"
        ;;
      esac
      point "A scrape that lands while an agent reconnects fails, so both paths dip in Prometheus: $UP_GRAPH_URL"
      follow_up "Next, run" "$0 keepalive 10"
    elif [[ "$(keepalive_vs "$ka" "$(timeout_of "$before")")" == sooner ]]; then
      point "agent-current isn't cut off, though its keepalives come after $ka seconds: its connection is with an" \
        "old worker, under the timeouts it opened with. Its own Activity view stays quiet:" \
        "$AGENT_DASHBOARD_URL/activity"
    else
      point "agent-current keeps the connection it has only until the timeouts of the worker it's on end a quiet" \
        "stream. It then reconnects to a new worker, under these timeouts, and is cut off about every 20 seconds." \
        "Its own Activity view shows each disconnect: $AGENT_DASHBOARD_URL/activity"
    fi
    ((shutdown)) || follow_up "To see the old workers, run" "$0 nginx-workers"
    ;;
  nginx-workers)
    local processes old
    processes="$(nginx_exec ps -o pid,etime,args | grep -E 'PID|nginx:')"
    old="$(grep -c 'worker process is shutting down' <<<"$processes" || true)"
    bold "nginx's processes"
    awk '{ print "  " $0 }' <<<"$processes"
    how_it_works "This lists the processes in the nginx container. A reload starts new workers under the new config," \
      "and tells the old ones to take no new connections and to exit once the ones they have end. An old worker" \
      "keeps those connections under the config they opened with."
    watch_for
    if ((old > 0)); then
      point "$old old worker(s), 'shutting down', left from an earlier reload: they hold the agents' connections" \
        "from before it, and keep them under the old config."
      point "An agent's connection doesn't end: agent-current and the proxy keep each of its streams open with a" \
        "keepalive. So these workers never exit, each reload leaves more, and no reload's change reaches an agent" \
        "that was connected before it."
      if ! shutdown_timeout_set; then
        follow_up "To change that, run" "$0 nginx-shutdown-timeout"
      fi
    else
      point "No old workers: every connection is with a worker that started on the config in place."
    fi
    ;;
  nginx-shutdown-timeout)
    if shutdown_timeout_set; then
      bold "nginx already runs with worker_shutdown_timeout ${SHUTDOWN_SECS}s, so nothing changed"
      how_it_works "nginx/main-shutdown-timeout.conf, which sets worker_shutdown_timeout, is already in the main.d" \
        "directory nginx's main config includes, and nginx was restarted to bring it in."
      watch_for
      point "A reload reaches every agent within ${SHUTDOWN_SECS} seconds, at the cost of one reconnect each."
    else
      nginx_exec cp /etc/nginx/demo/main-shutdown-timeout.conf "$SHUTDOWN_CONF"
      restart_nginx
      bold "nginx now runs with worker_shutdown_timeout ${SHUTDOWN_SECS}s"
      how_it_works "This copies nginx/main-shutdown-timeout.conf, which sets worker_shutdown_timeout, into the main.d" \
        "directory nginx's main config includes, and restarts nginx. After a reload, an old worker now closes the" \
        "connections it still has after ${SHUTDOWN_SECS} seconds, and exits. A worker takes the setting from the" \
        "config it started with, so a reload that brought it in couldn't end the old workers already waiting; the" \
        "restart does."
      watch_for
      point "The restart closes every connection, so each agent reconnects once, within a few seconds, to a worker" \
        "under the config in place: the proxy's Activity view shows a disconnect and a connect for each," \
        "$DASHBOARD_URL/activity"
      point "Every agent now runs under the agents' entrance in place, $(conf_file agents)."
      point "From now on, a reload reaches every agent within ${SHUTDOWN_SECS} seconds, at the cost of one reconnect" \
        "each, which a scrape can land in."
    fi
    ;;
  keepalive)
    local secs="${1:-}" since line entrance timeouts
    [[ "$secs" =~ ^[0-9]+$ ]] || die "keepalive takes the number of seconds, as in '$0 keepalive 10'"
    since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if ! start_proxy "$secs"; then
      bold "The proxy already runs with a keepalive interval of ${secs} seconds, so nothing changed"
      follow_up "To see the proxy's settings, open" "$DASHBOARD_URL/settings"
      return
    fi
    line="$(keepalive_line_since "$since")"
    # nginx restarted, so every agent's connection is under the agents' entrance in place.
    entrance="$(conf_in_use agents)"
    timeouts="$(timeouts_phrase "$entrance")"
    if ((secs == 0)); then
      bold "The proxy now runs with its stream keepalives off"
    else
      bold "The proxy now sends a keepalive on a stream after ${secs} seconds without anything on it"
    fi
    how_it_works "This stops nginx, recreates the proxy with proxy.internal.streamKeepAliveSecs set to $secs, on its" \
      "command line (docker/compose.yml), and starts nginx again once the new proxy answers, so nginx looks up the" \
      "new proxy's address. The proxy uses the interval on the streams it sends on, and gives it to each agent as it" \
      "registers, for the streams the agent sends on, so it's set in one place for both directions. Keep it under" \
      "the shortest idle timeout between the agents and the proxy; 0 turns the keepalives off."
    watch_for
    point "Stopping nginx closes every agent's connection, and each agent reconnects once, to the new proxy, when" \
      "nginx starts again."
    if [[ -n "$line" ]]; then
      point "agent-current's log says how often it now sends keepalives: $line"
    else
      point "agent-current hasn't reconnected yet; its log will say how often it sends keepalives. To see it, run" \
        "$0 logs agent-current"
    fi
    case "$(keepalive_vs "$secs" "$(timeout_of "$entrance")")" in
    off)
      if [[ "$entrance" == long-timeouts ]]; then
        point "With the keepalives off, agent-current's streams go as quiet as agent-legacy's, but $timeouts," \
          "leave both connected."
      else
        point "With the keepalives off, agent-current's streams go as quiet as agent-legacy's, and $timeouts, cut it" \
          "off as often: its Activity view shows each disconnect, $AGENT_DASHBOARD_URL/activity"
      fi
      ;;
    sooner)
      point "agent-current stays connected: a keepalive every $secs seconds keeps each of its streams from going" \
        "quiet as long as $timeouts. Its Activity view shows no more disconnects, $AGENT_DASHBOARD_URL/activity"
      ;;
    same)
      point "agent-current's keepalives come just as $timeouts, would end a quiet stream, so whether nginx cuts it" \
        "off is a race. Its Activity view shows any disconnect, $AGENT_DASHBOARD_URL/activity"
      ;;
    later)
      point "agent-current's keepalives come too late for $timeouts: nginx ends its quiet streams first, and cuts it" \
        "off as often as agent-legacy. Its Activity view shows each disconnect, $AGENT_DASHBOARD_URL/activity"
      ;;
    *)
      point "The agents' entrance was changed by hand: agent-current stays connected under stream timeouts longer" \
        "than ${secs} seconds, and is cut off under shorter ones. Its Activity view shows each disconnect," \
        "$AGENT_DASHBOARD_URL/activity"
      ;;
    esac
    if [[ "$entrance" == long-timeouts ]]; then
      # With the keepalives off, the point above already says both stay connected.
      ((secs == 0)) || point "agent-legacy, from 4.6.0, sends no keepalives, and stays connected only because" \
        "nginx's stream timeouts are a day long, in nginx/agents-long-timeouts.conf."
    else
      point "agent-legacy, from 4.6.0, sends no keepalives, so nginx's stream timeouts still cut it off: the proxy's" \
        "Activity view shows each disconnect, $DASHBOARD_URL/activity"
    fi
    if ((secs != DEFAULT_KEEPALIVE_SECS)); then
      follow_up "To put the default back, run" "$0 keepalive $DEFAULT_KEEPALIVE_SECS"
    fi
    ;;
  dashboard-stock)
    use_conf dashboard stock
    bold "nginx's entrance for the dashboard is a plain proxy_pass again"
    how_it_works "$(copies_sentence dashboard stock)" \
      "nginx passes each request on as HTTP/1.0 and drops the Upgrade and Connection headers, so the dashboard's" \
      "WebSocket handshake reaches it as a plain request, which it answers with 400."
    watch_for
    point "Reload the dashboard through nginx: it shows a blinking red 'reconnecting...', and nothing on it moves:" \
      "$NGINX_DASHBOARD_URL"
    ;;
  dashboard-upgrade)
    use_conf dashboard upgrade
    bold "nginx's entrance for the dashboard now passes the WebSocket handshake's headers"
    how_it_works "$(copies_sentence dashboard upgrade)" \
      "It passes requests on over HTTP/1.1, with the Upgrade header and a Connection header of upgrade on a" \
      "handshake. The Host header is still nginx's own default, the upstream's name, proxy:8094, while the" \
      "browser's Origin header names the address it opened, localhost:${NGINX_DEMO_NGINX_PORT:-8590}. The dashboard" \
      "takes a WebSocket, and any POST, only from a page of its own, an Origin that matches the Host, so another" \
      "site's page can't use it, and refuses this one with 403."
    watch_for
    point "Reload the dashboard through nginx: it's still 'reconnecting...': $NGINX_DASHBOARD_URL"
    point "The proxy logs each refusal, 'Refused a dashboard request from origin', naming the address the browser" \
      "opened, and the setting that would allow it, proxy.dashboard.allowedOrigins. To see it, run $0 logs proxy"
    point "In the Paths view, click the scrape button, the circular arrow beside /storefront_metrics: the result" \
      "says 'Origin not allowed', since the button sends a POST: $NGINX_DASHBOARD_URL/paths"
    follow_up "Next, run" "$0 dashboard-host"
    ;;
  dashboard-host)
    use_conf dashboard host
    bold "nginx's entrance for the dashboard now passes the browser's Host header too"
    how_it_works "$(copies_sentence dashboard host)" \
      "It adds proxy_set_header Host \$http_host, the Host header as the browser sent it, port included (nginx's" \
      "\$host drops the port), so the browser's Origin matches it. Listing the address in" \
      "proxy.dashboard.allowedOrigins, as the proxy's log suggested, works too, and suits an nginx that must pass" \
      "another Host."
    watch_for
    point "Reload the dashboard through nginx: the live indicator stops blinking, and the page updates every" \
      "second, as it does reached directly: $NGINX_DASHBOARD_URL"
    point "The scrape button in the Paths view works through nginx too, and shows the start of what Prometheus" \
      "gets from /storefront_metrics."
    point "Here nginx passes the dashboard on over plain HTTP. If it ended TLS in front of the dashboard, the" \
      "browser's Origin would start with https, and a login's session cookie would be marked Secure, so the browser" \
      "would send it only over TLS."
    ;;
  reset)
    reset_demo
    bold "nginx and the proxy are as make up starts them"
    how_it_works "This puts nginx's stock configs back, removes worker_shutdown_timeout, and restarts nginx if" \
      "anything differed or an old worker was left; and recreates the proxy if its keepalive interval wasn't the" \
      "default, ${DEFAULT_KEEPALIVE_SECS} seconds."
    watch_for
    point "Each agent reconnects once if nginx or the proxy restarted, and agent-legacy is cut off every minute" \
      "again, under nginx's default timeouts."
    ;;
  "" | -h | --help | help) usage ;;
  *)
    usage >&2
    exit 1
    ;;
  esac
}

main "$@"
