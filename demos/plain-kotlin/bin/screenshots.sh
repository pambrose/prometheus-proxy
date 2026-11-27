#!/usr/bin/env bash
# Retakes the screenshot on the website's Plain Kotlin Demo page (website/prometheus-proxy/docs/plain-kotlin-demo.md)
# from the running demo: it plays part 3 of the tour, a rush and two more ovens, and captures the proxy's screens page
# as that part opens it, the proxy dashboard's Paths view beside Grafana, in headless Chrome at three times the pixel
# density in both themes, a whole window, and writes lossless WebP to
# website/prometheus-proxy/docs/images/plain-kotlin-demo/: screens.webp in the dark theme and screens-light.webp in the
# light one, which the page shows by the reader's theme. It puts the demo back to one oven when it finishes, or when it
# fails.
#
# `make screenshots`, in demos/plain-kotlin, builds the demo from this checkout and starts it first, so the screenshot
# shows the code as it is. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`), and `cwebp`.
#
# Two settings, for running it again and again while checking a change; the defaults take the site's screenshot:
#   WAIT_SECS=<n>       how long each wait for the demo to reach the scene's state may take before the script fails
#                       (default 150)
#   SKIP_HISTORY_WAIT=1 skip the wait of a few minutes, after a fresh start, for Grafana's graphs to have some history;
#                       the shot then shows short graphs, so it's for checking a change, not for the site
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/plain-kotlin-demo"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
# Where demo.sh's up keeps the host ports moved with the *_PORT variables, so the URLs below match what compose
# publishes with no variable set again.
PORTS_FILE="$DEMO_DIR/docker/.env"

# Takes each port saved in docker/.env that the environment doesn't set, as demo.sh does.
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

DASHBOARD_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/dashboard"
SCREENS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/screens"
PROMETHEUS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROMETHEUS_PORT:-9690}"
PROXY_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROXY_PORT:-8680}"

# The settings in the header. SKIP_HISTORY_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_HISTORY_WAIT="${SKIP_HISTORY_WAIT:-}"

# The screens page is taken as a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, as wide as the other demos'
# screens pages, so each of Split's two panes is about as wide as a page needs. THEMES are the themes it's taken in.
WIDTH=1680
HEIGHT=1050
THEMES=(dark light)
# SCALE is the pixel density it's taken at, device pixels to the CSS pixel: at 3, the website's lightbox, which opens a
# screenshot at up to one and a half times its window's size, has a pixel for each of a Retina screen's
# (website/prometheus-proxy/docs/javascripts/screenshots.js), so its text stays sharp.
SCALE=3

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
curl -fsS -o /dev/null "$PROXY_URL/discovery" 2>/dev/null ||
  die "the demo isn't running at $PROXY_URL; start it with 'make up-local' in demos/plain-kotlin"

WORK="$(mktemp -d)"

# Puts the demo back to one oven when the script ends, or fails. A rush, once started, runs its 50 seconds out, and a
# backlog left to one oven takes a quarter of an hour to clear, so the script clears it with three before it ends, and,
# after a run that failed, before its next scene.
restore() {
  "$DEMO" scale 1 >/dev/null 2>&1 || true
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

# How many orders wait in the queue, a Redis list, or nothing when Redis doesn't answer.
waiting() { "${COMPOSE[@]}" exec -T redis redis-cli LLEN orders 2>/dev/null | tr -d '\r' || true; }

# Whether at least this many orders wait in the queue.
waiting_at_least() {
  local count
  count="$(waiting)"
  [[ "$count" =~ ^[0-9]+$ ]] && ((count >= $1))
}

# Whether at most a few orders wait in the queue: the backlog a rush left has cleared, and one oven keeps up with the
# counter's trickle.
backlog_cleared() {
  local count
  count="$(waiting)"
  [[ "$count" =~ ^[0-9]+$ ]] && ((count <= 5))
}

# The most orders draining has seen waiting.
PEAK=0

# Whether the backlog has fallen 50 orders below the most it has been: the rush is over, and the three ovens drain it.
draining() {
  local count
  count="$(waiting)"
  [[ "$count" =~ ^[0-9]+$ ]] || return 1
  if ((count > PEAK)); then
    PEAK=$count
  fi
  ((count <= PEAK - 50))
}

# Clears any backlog with three ovens, and goes back to one.
clear_backlog() {
  if ! backlog_cleared; then
    "$DEMO" scale 3 >/dev/null 2>&1
    wait_until "the backlog to clear" backlog_cleared
  fi
  "$DEMO" scale 1 >/dev/null
}

# Whether the proxy's service discovery lists this many ovens' metrics paths (not their agents').
discovers_ovens() {
  local count
  count="$(curl -fsS "$PROXY_URL/discovery" 2>/dev/null | grep -cE '"__metrics_path__": *"/oven_[^_"]+_metrics"' || true)"
  [[ "$count" == "$1" ]]
}

# Whether Prometheus has scraped an oven for at least this many seconds, so Grafana's graphs have some history.
history_of() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=max(count_over_time(up{job=\"oven\"}[10m])) * 5 >= $1" | grep -q '"value"'
}

# Whether Prometheus has this many ovens baking, so 'Orders baked / sec, by oven' draws a series for each.
baking_ovens() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=count(sum by (instance) (rate(bakery_orders_baked_total[30s])) > 0) >= $1" |
    grep -q '"value"'
}

# Captures a page at WIDTH x HEIGHT CSS pixels, at SCALE, in a theme: the screens page and the proxy dashboard follow
# the browser's preferred color scheme. Grafana renders after loading its data, so a page with a Grafana pane gets a
# long virtual-time budget.
capture() {
  local url="$1" png="$2" theme="$3" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor="$SCALE" --blink-settings=preferredColorScheme=$scheme \
    --window-size="$WIDTH,$HEIGHT" --virtual-time-budget=15000 --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# The proxy's screens page in each theme, opened on a link (its part after the #). A {theme} in the link becomes the
# theme's name, for a Grafana pane, which doesn't follow the browser's scheme. shoot_screens <name> <link>
shoot_screens() {
  local name="$1" link="$2" theme
  for theme in "${THEMES[@]}"; do
    capture "$SCREENS_URL#${link//\{theme\}/$theme}" "$WORK/$(named "$name" "$theme").png" "$theme"
  done
}

step "Putting the bakery back to one oven, with no backlog"
clear_backlog
# The proxy lists a stopped oven's paths as gone for an hour (proxy.dashboard.departedPathRetentionSecs): an earlier
# run's, or the ones clear_backlog just stopped. make screenshots' up-local leaves an unchanged proxy running, so this
# recreates it, and the new one starts with no paths. The oven's agent reconnects within seconds.
"${COMPOSE[@]}" --progress quiet up --detach --force-recreate --no-deps --no-build proxy
wait_until "the recreated proxy's dashboard to answer" curl -fs -o /dev/null "$DASHBOARD_URL"
wait_until "the oven's agent to register its paths again" discovers_ovens 1
if [[ "$SKIP_HISTORY_WAIT" == 1 ]]; then
  step "Skipping the wait for Grafana's graphs to have some history (SKIP_HISTORY_WAIT=1): the shot is for" \
    "checking a change, not for the site"
else
  step "Waiting up to three minutes so Grafana's graphs have some history: the demo was just started"
  WAIT_SECS=$((WAIT_SECS > 200 ? WAIT_SECS : 200)) wait_until "three minutes of scrapes" history_of 180
fi

step "A rush, and two more ovens to clear it"
"$DEMO" rush >/dev/null
wait_until "the backlog to pass 200" waiting_at_least 200
# docker compose's lines about the containers it starts go to stderr.
"$DEMO" scale 3 >/dev/null 2>&1
wait_until "the three ovens' rates in Prometheus" baking_ovens 3
wait_until "the backlog to fall, once the rush is over" draining
# Long enough for a scrape and Grafana's refresh, so its graphs show the backlog turn.
sleep 10
# The screens page as part 3 of the tour opens it, with the backlog draining: the new ovens' paths in the proxy
# dashboard's Paths view beside Grafana's Bakery dashboard, over the last five minutes, the three before the rush
# included: a fresh demo has little more than that, so a longer window would leave its graphs' left half empty. A run
# soon after another shows that one's rush too, so the site's shot is from a demo just started.
shoot_screens screens "proxy/paths+grafana/d/bakery?orgId=1&kiosk&from=now-5m&to=now&theme={theme}"

step "Clearing the backlog with the three ovens, then back to one"
clear_backlog

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for theme in "${THEMES[@]}"; do
  file="$(named screens "$theme")"
  cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
  printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
done
step "Done; the bakery is back to one oven, with no backlog"
