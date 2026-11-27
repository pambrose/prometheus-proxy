#!/usr/bin/env bash
# Retakes the screenshots on the website's nginx Demo page (website/prometheus-proxy/docs/nginx-demo.md) from the
# running demo: it plays the page's scenarios, captures the dashboards and the proxy's screens page in headless Chrome
# at three times the pixel density, each a whole window, and writes lossless WebP to
# website/prometheus-proxy/docs/images/nginx-demo/:
# <name>.webp in the dark theme and <name>-light.webp in the light one, which the page shows by the reader's theme. It
# puts the demo back as `make up` starts it when it finishes, or when it fails.
#
# `make screenshots`, in demos/nginx, builds the demo from this checkout and starts it first, so the screenshots show
# the dashboard as it is in the code. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`),
# and `cwebp`.
#
# One setting, for running it again and again while checking a change; the default takes the site's screenshots:
#   WAIT_SECS=<n>       how long each wait for the demo to reach a scene's state may take before the script fails
#                       (default 150)
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/nginx-demo"
# Where `make up` keeps the host ports moved with the *_PORT variables, as demo.sh reads them.
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

DASHBOARD_URL="http://localhost:${NGINX_DEMO_DASHBOARD_PORT:-8594}/dashboard"
NGINX_DASHBOARD_URL="http://localhost:${NGINX_DEMO_NGINX_PORT:-8590}/dashboard"
AGENT_DASHBOARD_URL="http://localhost:${NGINX_DEMO_AGENT_DASHBOARD_PORT:-8595}/dashboard"
PROMETHEUS_URL="http://localhost:${NGINX_DEMO_PROMETHEUS_PORT:-9590}"
SCREENS_URL="http://localhost:${NGINX_DEMO_DASHBOARD_PORT:-8594}/screens"

WAIT_SECS="${WAIT_SECS:-150}"

# Every screenshot is a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, rather than a strip cut to its
# content, so each fills the same space on the website's page and in the lightbox that opens it full size. THEMES are
# the themes each is taken in.
WIDTH=1280
HEIGHT=800
# The screens page is taken in a wider window of the same shape, so each of Split's two panes is about as wide as a
# dashboard needs.
SCREENS_WIDTH=1680
SCREENS_HEIGHT=1050
THEMES=(dark light)
# SCALE is the pixel density each is taken at, device pixels to the CSS pixel: at 3, the website's lightbox, which
# opens a screenshot at up to one and a half times its window's size, has a pixel for each of a Retina screen's
# (website/prometheus-proxy/docs/javascripts/screenshots.js), so its text stays sharp.
SCALE=3
SHOTS=(agents legacy-cut short-timeouts screens dashboard-reconnecting dashboard-live)

# A screenshot's file name in a theme: the dark one keeps the plain name.
named() { if [[ "$2" == dark ]]; then echo "$1"; else echo "$1-$2"; fi; }

die() {
  echo "Error: $*" >&2
  exit 1
}
step() { printf '\033[1m%s\033[0m\n' "$*"; }

find_chrome() {
  local candidate
  for candidate in "${CHROME:-}" "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    google-chrome google-chrome-stable chromium chromium-browser; do
    [[ -n "$candidate" ]] || continue
    if [[ -x "$candidate" ]] || command -v "$candidate" >/dev/null 2>&1; then
      echo "$candidate"
      return
    fi
  done
  die "Chrome or Chromium not found; set CHROME to its path"
}

[[ "$WAIT_SECS" =~ ^[1-9][0-9]*$ ]] || die "WAIT_SECS must be a whole number of seconds above 0, not '$WAIT_SECS'"
CHROME_BIN="$(find_chrome)"
command -v magick >/dev/null 2>&1 || die "ImageMagick 7 ('magick') is not on the PATH"
command -v cwebp >/dev/null 2>&1 || die "cwebp is not on the PATH"
curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
  die "the demo isn't running at $DASHBOARD_URL; start it with 'make up-local' in demos/nginx"

WORK="$(mktemp -d)"

# Puts the demo back as `make up` starts it, whatever state a failure left it in.
restore() {
  "$DEMO" reset >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap restore EXIT

# Waits until a command succeeds, checking every 2 seconds, or fails after WAIT_SECS with what it was waiting for.
wait_until() {
  local what="$1" deadline=$((SECONDS + WAIT_SECS))
  shift
  until "$@"; do
    ((SECONDS < deadline)) || die "timed out after ${WAIT_SECS}s waiting for $what; WAIT_SECS raises the limit"
    sleep 2
  done
}

# Whether a page, by URL, matches an extended regex. The page is taken whole before grep reads it: grep -q stops at its
# match, and under pipefail a curl it cut off would fail the check.
page_has() {
  local body
  body="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -Eq "$2" <<<"$body"
}

# The id of the connected agent with this name on the proxy dashboard, or nothing. Each agent's row carries its id and
# name as data attributes (agentListInner in ProxyDashboardHtml.kt).
agent_id() {
  local html
  html="$(curl -fsS "$DASHBOARD_URL" 2>/dev/null)" || return 0
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" |
    sed -nE 's/.* data-agent="([^"]*)".*/\1/p' | head -1 || true
}

# How many times the proxy has seen an agent's stream end since it started, as Prometheus last scraped it, or nothing.
streams_closed() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode 'query=proxy_agent_disconnects_total{reason="stream_terminated"}' 2>/dev/null |
    sed -nE 's/.*"value":\[[0-9.]+,"([0-9]+)".*/\1/p'
}

# Whether a count, from a function that prints one or nothing, is at least this much: at_least <function> <n>
at_least() {
  local count
  count="$("$1")"
  [[ -n "$count" ]] && ((count >= $2))
}

# How many disconnects a dashboard's Activity view lists, agent-current's unless another dashboard is given, or
# nothing. A row's event cell (ActivityTable.kt) reads disconnected: in a tag on an agent's dashboard, where it's a
# notice, and as plain text on the proxy's. disconnects [dashboard url]
disconnects() {
  local body
  body="$(curl -fsS "${1:-$AGENT_DASHBOARD_URL}/activity" 2>/dev/null)" || return 0
  { grep -o '>disconnected<' <<<"$body" || true; } | wc -l | tr -d ' '
}

# How many disconnects the proxy's Activity view lists, or nothing.
proxy_disconnects() { disconnects "$DASHBOARD_URL"; }

# Whether both agents are connected to the proxy.
both_connected() { [[ -n "$(agent_id agent-current)" && -n "$(agent_id agent-legacy)" ]]; }

# Captures a page at WIDTH x HEIGHT, or the size given, at SCALE, in a theme: the dashboard follows the browser's
# preferred color scheme. The timing is Chrome's: --virtual-time-budget=<ms>, the virtual time the page gets to load
# and run, or --timeout=<ms>, real time, for a page waiting on something virtual time skips past, such as a
# WebSocket's answer. capture <url> <png> <theme> <timing> [width height]
capture() {
  local url="$1" png="$2" theme="$3" timing="$4" width="${5:-$WIDTH}" height="${6:-$HEIGHT}" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor="$SCALE" --blink-settings=preferredColorScheme=$scheme \
    --window-size="$width,$height" "$timing" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A page in each theme: shoot <name> <url> [timing]
shoot() {
  local theme
  for theme in "${THEMES[@]}"; do
    capture "$2" "$WORK/$(named "$1" "$theme").png" "$theme" "${3:---virtual-time-budget=3000}"
  done
}

# The proxy's screens page in each theme, opened on a link (its part after the #), a whole SCREENS_WIDTH x
# SCREENS_HEIGHT window. Its panes are dashboards: Prometheus draws no graph in virtual time, and the page doesn't get
# past its first render in real time, since Chrome takes the shot once the page has loaded.
# shoot_screens <name> <link>
shoot_screens() {
  local theme
  for theme in "${THEMES[@]}"; do
    capture "$SCREENS_URL#$2" "$WORK/$(named "$1" "$theme").png" "$theme" --virtual-time-budget=5000 \
      "$SCREENS_WIDTH" "$SCREENS_HEIGHT"
  done
}

# Whether a dashboard shot's live indicator reads reconnecting...: red, where the status bar's indicator starts when it
# does (65 x 15 CSS pixels at 722,17, right of the tabs), and where "live", which is shorter, leaves the bar empty. The
# page renders as live, and turns red once its WebSocket's first handshake fails; with virtual time, a capture came
# before that as often as not, so these take real time, and are checked.
shows_reconnecting() {
  local region="$((65 * SCALE))x$((15 * SCALE))+$((722 * SCALE))+$((17 * SCALE))"
  [[ "$(magick "$1" -crop "$region" -format '%[fx:mean.r - mean.g > 0.02]' info:)" == 1 ]]
}

# The dashboard through nginx's stock entrance, in each theme, captured again, up to 5 times, until its indicator
# reads reconnecting...: shoot_reconnecting <name> <url>
shoot_reconnecting() {
  local theme png _
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$1" "$theme").png"
    for _ in 1 2 3 4 5; do
      capture "$2" "$png" "$theme" --timeout=4000
      shows_reconnecting "$png" && continue 2
    done
    die "the $theme capture of $2 never showed the dashboard reconnecting"
  done
}

step "Putting the demo back as make up starts it"
"$DEMO" reset >/dev/null
wait_until "both agents connected" both_connected

step "Part 1: Two agents reach the proxy through nginx, over TLS"
current="$(agent_id agent-current)"
wait_until "agent-current's recent scrapes" page_has "$DASHBOARD_URL/agents/$current" 'data-scrape-count="([1-9][0-9])"'
shoot agents "$DASHBOARD_URL/agents/$current"

step "Part 2: nginx's stream timeouts cut off an agent from 4.6.0 every minute"
# Two cuts, a minute apart, so the Activity view shows the pattern, not a one-off. Counted from a value Prometheus
# scraped after the reset's reconnects, which part 1's wait for ten scrapes leaves well behind.
closed="$(streams_closed)"
[[ -n "$closed" ]] || die "Prometheus has no proxy_agent_disconnects_total"
wait_until "nginx to close agent-legacy's stream twice" at_least streams_closed $((closed + 2))
shoot legacy-cut "$DASHBOARD_URL/activity"

step "Part 3: A shorter stream timeout, and a reload that doesn't reach connected agents"
"$DEMO" nginx-shutdown-timeout >/dev/null
wait_until "both agents back after nginx's restart" both_connected
before="$(disconnects)"
[[ -n "$before" ]] || die "agent-current's dashboard doesn't answer at $AGENT_DASHBOARD_URL"
"$DEMO" nginx-short-timeouts >/dev/null
# Within 10 seconds the old worker closes agent-current's connection, and the agent moves to a worker under the
# 20-second timeouts, which then cut it off about every 20 seconds: that disconnect and three cuts, so its Activity
# view shows the pattern.
wait_until "nginx to cut agent-current off three times" at_least disconnects $((before + 4))
shoot short-timeouts "$AGENT_DASHBOARD_URL/activity"
# A proxy with a keepalive every 10 seconds, under nginx's 20: agent-current reconnects to it once and stays, while
# agent-legacy, which sends none, is still cut off. The recreated proxy's Activity view starts empty, so two of its
# disconnects are agent-legacy's cuts.
"$DEMO" keepalive 10 >/dev/null
wait_until "both agents connected to the new proxy" both_connected
wait_until "nginx to cut agent-legacy off twice" at_least proxy_disconnects 2
# The screens page as part 3 of the tour opens it: agent-current's Activity view beside the proxy's.
shoot_screens screens "agent-current/activity+proxy/activity"

step "Part 4: The dashboard behind nginx"
"$DEMO" reset >/dev/null
wait_until "both agents connected" both_connected
# Long enough for the page to load and its WebSocket to fail a few times, so the indicator reads reconnecting.
shoot_reconnecting dashboard-reconnecting "$NGINX_DASHBOARD_URL"
"$DEMO" dashboard-host >/dev/null
shoot dashboard-live "$NGINX_DASHBOARD_URL" --timeout=4000

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in "${SHOTS[@]}"; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-34s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
step "Done; the demo is as make up starts it"
