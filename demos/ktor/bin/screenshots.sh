#!/usr/bin/env bash
# Retakes the screenshots on the website's Ktor Demo page (website/prometheus-proxy/docs/ktor-demo.md) from the
# running demo: it plays the page's scenarios, captures the coffee shop, the proxy dashboard, its screens page, and
# Grafana in headless Chrome at three times the pixel density in both themes, each a whole window, and writes lossless
# WebP to website/prometheus-proxy/docs/images/ktor-demo/: <name>.webp in the dark theme and <name>-light.webp in the
# light one, which the page shows by the reader's theme. It puts the demo back to normal when it finishes, or when it
# fails.
#
# `make screenshots`, in demos/ktor, builds the demo from this checkout and starts it first, so the screenshots
# show the code as it is. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`), and `cwebp`.
#
# Two settings, for running it again and again while checking a change; the defaults take the site's screenshots:
#   WAIT_SECS=<n>       how long each wait for the demo to reach a scene's state may take before the script fails
#                       (default 150)
#   SKIP_HISTORY_WAIT=1 skip the wait of a few minutes, after a fresh start, for Grafana's graphs to have some history;
#                       the shots then show short graphs, so they're for checking a change, not for the site
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/ktor-demo"
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

APP_URL="http://localhost:${KTOR_DEMO_APP_PORT:-8390}"
DASHBOARD_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/dashboard"
SCREENS_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/screens"
GRAFANA_URL="http://localhost:${KTOR_DEMO_GRAFANA_PORT:-3300}"
PROMETHEUS_URL="http://localhost:${KTOR_DEMO_PROMETHEUS_PORT:-9390}"

# The settings in the header. SKIP_HISTORY_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_HISTORY_WAIT="${SKIP_HISTORY_WAIT:-}"

# Every screenshot is a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, so each fills the same space on the
# website's page and in the lightbox that opens it full size. THEMES are the themes each is taken in.
WIDTH=1280
HEIGHT=800
# The screens page is taken in a wider window of the same shape, so each of Split's two panes is about as wide as a
# page needs.
SCREENS_WIDTH=1680
SCREENS_HEIGHT=1050
THEMES=(dark light)
# SCALE is the pixel density each is taken at, device pixels to the CSS pixel: at 3, the website's lightbox, which
# opens a screenshot at up to one and a half times its window's size, has a pixel for each of a Retina screen's
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
curl -fsS -o /dev/null "$APP_URL/healthz" 2>/dev/null ||
  die "the demo isn't running at $APP_URL; start it with 'make up-local' in demos/ktor"

WORK="$(mktemp -d)"

# Undoes every scenario, whatever state the demo was left in.
normal() {
  "$DEMO" proxy-start >/dev/null 2>&1 || true
  "$DEMO" fix-machine >/dev/null 2>&1 || true
  "$DEMO" calm >/dev/null 2>&1 || true
  if [[ -n "$(docker compose -f "$DEMO_DIR/docker/compose.yml" --profile deploy ps --quiet coffee-shop-next)" ]]; then
    "$DEMO" rollback >/dev/null 2>&1 || true
  fi
}

# Puts the demo back to normal when the script ends, or fails.
restore() {
  normal
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

# The agent list's row for the connected agent with this name: its start tag, or nothing. The row carries the agent's
# id, name, and path count as data attributes (agentListInner in ProxyDashboardHtml.kt).
agent_row() {
  local html
  html="$(curl -fsS "$DASHBOARD_URL" 2>/dev/null)" || return 0
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" | head -1 || true
}

# An attribute's value in a start tag, or nothing.
attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<<"$2"; }

# The id of the connected agent with this name, or nothing.
agent_id() { attr data-agent "$(agent_row "$1")"; }

# Whether the connected agent with this name serves this many paths.
serves_paths() { [[ "$(attr data-path-count "$(agent_row "$1")")" == "$2" ]]; }

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill the window.
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo 'data-scrape-count="[0-9]+"' | grep -Eo '[0-9]+' | head -1)"
  [[ -n "$count" ]] && ((count >= $2))
}

# Whether Prometheus has this alert firing.
firing() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=ALERTS{alertname=\"$1\",alertstate=\"firing\"}" | grep -q '"value"'
}

# Whether the coffee shop's queue holds at least this many orders.
queue_at_least() {
  local queue
  queue="$(curl -fsS "$APP_URL/status" 2>/dev/null | sed -nE 's/^Queue: +([0-9]+).*/\1/p')"
  [[ -n "$queue" ]] && ((queue >= $1))
}

# Whether Prometheus has scraped the coffee shop for at least this many seconds, so Grafana's graphs have some history.
history_of() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=count_over_time(up{job=\"coffee-shop\"}[10m]) * 5 >= $1" | grep -q '"value"'
}

# Captures a page at WIDTH x the given CSS height, at SCALE, in a theme: the coffee shop and the proxy dashboard follow
# the browser's preferred color scheme. Grafana renders after loading its data, so its captures get a longer
# virtual-time budget.
capture() {
  local url="$1" png="$2" height="$3" theme="$4" budget="${5:-3000}" width="${6:-$WIDTH}" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor="$SCALE" --blink-settings=preferredColorScheme=$scheme \
    --window-size="$width,$height" --virtual-time-budget="$budget" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A page in each theme, a whole WIDTH x HEIGHT window. A page whose viewport ends in a fixed footer, Grafana's, is
# captured this much taller and cut to HEIGHT, which leaves the footer out. A {theme} in the URL becomes the theme's
# name, for Grafana, which doesn't follow the browser's scheme. shoot <name> <url> [capture height] [budget]
shoot() {
  local name="$1" url="$2" capture_height="${3:-$HEIGHT}" budget="${4:-3000}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "${url//\{theme\}/$theme}" "$png" "$capture_height" "$theme" "$budget"
    magick "$png" -crop "$((WIDTH * SCALE))x$((HEIGHT * SCALE))+0+0" +repage "$png"
  done
}

# The proxy's screens page in each theme, opened on a link (its part after the #), a whole SCREENS_WIDTH x
# SCREENS_HEIGHT window. A {theme} in the link becomes the theme's name, for a Grafana pane, which doesn't follow the
# browser's scheme. Its budget is Grafana's, which renders after loading its data. shoot_screens <name> <link>
shoot_screens() {
  local name="$1" link="$2" theme
  for theme in "${THEMES[@]}"; do
    capture "$SCREENS_URL#${link//\{theme\}/$theme}" "$WORK/$(named "$name" "$theme").png" "$SCREENS_HEIGHT" "$theme" \
      15000 "$SCREENS_WIDTH"
  done
}

step "Putting the coffee shop back to normal"
normal
wait_until "coffee-shop-1.0 to serve its two paths" serves_paths coffee-shop-1.0 2
if [[ "$SKIP_HISTORY_WAIT" == 1 ]]; then
  step "Skipping the wait for Grafana's graphs to have some history (SKIP_HISTORY_WAIT=1): the shots are for" \
    "checking a change, not for the site"
else
  step "Waiting up to three minutes so Grafana's graphs have some history: the demo was just started"
  WAIT_SECS=$((WAIT_SECS > 200 ? WAIT_SECS : 200)) wait_until "three minutes of scrapes" history_of 180
fi

shop="$(agent_id coffee-shop-1.0)"
[[ -n "$shop" ]] || die "coffee-shop-1.0 isn't connected"
wait_until "coffee-shop-1.0's scrapes" has_scrapes "$DASHBOARD_URL/agents/$shop" 12
step "The coffee shop, and its agent on the proxy dashboard"
# Without the page's two-second refresh, which keeps headless Chrome's virtual time from ever running out.
shoot coffee-shop "$APP_URL/?refresh=false"
shoot agents "$DASHBOARD_URL/agents/$shop"

step "A rush, and a broken espresso machine, in Grafana"
# The rush fills the queue first: with the machine broken, every espresso drink fails at the counter, and the few orders
# left to queue are fewer than three baristas brew.
"$DEMO" rush >/dev/null
wait_until "the queue to pass 30" queue_at_least 30
"$DEMO" break-machine >/dev/null
wait_until "CoffeeShopErrors to fire" firing CoffeeShopErrors
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot grafana "$GRAFANA_URL/d/coffee-shop?orgId=1&kiosk&from=now-10m&to=now&theme={theme}" "$((HEIGHT + 100))" 15000
# The screens page as part 4 of the tour opens it: the coffee shop's status page beside Grafana, the shop's without its
# refresh, as above.
shoot_screens screens \
  "coffee-shop/?refresh=false+grafana/d/coffee-shop?orgId=1&kiosk&from=now-10m&to=now&theme={theme}"
"$DEMO" fix-machine >/dev/null
"$DEMO" calm >/dev/null

step "A deploy: version 1.1 takes the paths over"
"$DEMO" deploy >/dev/null 2>&1
wait_until "coffee-shop-1.1 to serve the two paths" serves_paths coffee-shop-1.1 2
sleep 10
shoot deploy "$DASHBOARD_URL/paths"
"$DEMO" rollback >/dev/null 2>&1

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in coffee-shop agents grafana screens deploy; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
step "Done; the demo is back to normal"
