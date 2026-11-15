#!/usr/bin/env bash
# Retakes the screenshots on the website's Live Demo page (website/prometheus-proxy/docs/demo.md) from the running demo:
# it plays the page's scenarios, captures the dashboard and Grafana in headless Chrome at twice the pixel density in the
# dark theme, crops each, and writes lossless WebP to website/prometheus-proxy/docs/images/demo/. It puts the demo back
# to normal when it finishes, or when it fails.
#
# `make demo-screenshots` builds the demo from this checkout and starts it first, so the screenshots show the dashboard
# as it is in the code. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`), and `cwebp`.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/demo"

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"

# The CSS width every screenshot is taken at, and the page color below the dashboard's last row, which the Paths views
# are trimmed to (--bg in ProxyDashboardCss.kt's dark theme).
WIDTH=1280
DARK_BG="#0d1015"

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

CHROME_BIN="$(find_chrome)"
command -v magick >/dev/null 2>&1 || die "ImageMagick 7 ('magick') is not on the PATH"
command -v cwebp >/dev/null 2>&1 || die "cwebp is not on the PATH"
curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null || die "the demo isn't running at $DASHBOARD_URL; start it with 'make demo-up'"

WORK="$(mktemp -d)"

# Undoes every scenario, whatever state a failure left the demo in.
restore() {
  "$DEMO" agent-thaw >/dev/null 2>&1 || true
  "$DEMO" undiscover >/dev/null 2>&1 || true
  "$DEMO" recover >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap restore EXIT

# Waits until a command succeeds, checking every 2 seconds, or fails with what it was waiting for.
wait_until() {
  local what="$1" deadline=$((SECONDS + 150))
  shift
  until "$@"; do
    ((SECONDS < deadline)) || die "timed out waiting for $what"
    sleep 2
  done
}

page() { curl -fsS "$DASHBOARD_URL$1"; }

# Counts the matches of an extended regex in a dashboard page.
count_in() { page "$1" | grep -Eo "$2" | wc -l | tr -d ' '; }

# The id of the connected agent with this name, from the agent list.
agent_id() {
  page "" | grep -Eo "data-agent=\"[^\"]*\"[^>]*><span><span class=\"led [a-z]+\"></span>$1<" |
    sed -E 's/^data-agent="([^"]*)".*/\1/' | head -1
}

# Whether Prometheus has DemoTargetDown firing for at least this many targets.
alerts_firing() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=count(ALERTS{alertname=\"DemoTargetDown\",alertstate=\"firing\"}) >= $1" |
    grep -q '"value"'
}

# Every path scraped, all of them answering 200, nothing departed: the state the first two screenshots show.
healthy() {
  local paths
  paths="$(page "/paths")"
  [[ "$(grep -Eo 'class="pill ok"' <<<"$paths" | wc -l | tr -d ' ')" -eq "$1" ]] &&
    ! grep -q 'class="pill crit"' <<<"$paths" &&
    ! grep -Eq '<span class="crit">[0-9]+ departed</span>' <<<"$paths"
}

# Captures a page at WIDTH x the given CSS height, in the dark theme at 2x. Grafana renders after loading its data, so
# its captures get a longer virtual-time budget.
capture() {
  local url="$1" png="$2" height="$3" budget="${4:-3000}" profile
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor=2 --blink-settings=preferredColorScheme=0 \
    --window-size="$WIDTH,$height" --virtual-time-budget="$budget" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A Paths view: captured taller than the table, then trimmed to its last row.
shoot_paths() {
  local name="$1"
  capture "$DASHBOARD_URL/paths" "$WORK/$name.png" 900
  magick "$WORK/$name.png" -background "$DARK_BG" -fuzz 2% -define trim:edges=south -trim +repage "$WORK/$name.png"
}

# A page cut at a fixed CSS height. The agent detail heights end on a row boundary for the demo's layout: change them
# with the dashboard's row metrics.
shoot_fixed() {
  local name="$1" url="$2" height="$3" capture_height="${4:-$3}" budget="${5:-3000}"
  capture "$url" "$WORK/$name.png" "$capture_height" "$budget"
  magick "$WORK/$name.png" -crop "$((WIDTH * 2))x$((height * 2))+0+0" +repage "$WORK/$name.png"
}

step "Waiting for every path to answer 200"
"$DEMO" agent-thaw >/dev/null 2>&1 || true
"$DEMO" undiscover >/dev/null
"$DEMO" recover >/dev/null
wait_until "nine healthy paths" healthy 9
# Enough history for agent-east's Recent scrapes to fill its screenshot.
sleep 10

east="$(agent_id agent-east)"
[[ -n "$east" ]] || die "agent-east isn't connected"
step "The Agents and Paths views"
shoot_fixed agents "$DASHBOARD_URL/agents/$east" 776
shoot_paths paths

step "A failing service and a hung one"
"$DEMO" outage >/dev/null
"$DEMO" slow >/dev/null
wait_until "DemoTargetDown to fire for payments and inventory" alerts_firing 2
shoot_paths outage
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot_fixed grafana "$GRAFANA_URL/d/prometheus-proxy-demo?orgId=1&kiosk&from=now-15m&to=now" 900 1000 15000
"$DEMO" recover >/dev/null
wait_until "the services to recover" healthy 9

step "Three services discovered at runtime"
"$DEMO" discover >/dev/null
wait_until "three discovered paths" healthy 12
wait_until "three 'disc' sources" test "$(count_in "/paths" '>disc<')" -ge 3
shoot_paths discovered

step "An agent that goes silent, and its eviction"
west2="$(agent_id agent-west-2)"
[[ -n "$west2" ]] || die "agent-west-2 isn't connected"
"$DEMO" agent-freeze >/dev/null 2>&1
wait_until "agent-west-2 to be idle for 16 seconds" \
  bash -c "curl -fsS '$DASHBOARD_URL/agents/$west2' | grep -Eq 'idle (1[6-9]|2[0-9])s'"
shoot_fixed evicting "$DASHBOARD_URL/agents/$west2" 536
wait_until "the proxy to evict agent-west-2" \
  bash -c "curl -fsS '$DASHBOARD_URL/paths' | grep -Eq '<span class=\"crit\">[0-9]+ departed</span>'"
sleep 4
shoot_paths evicted

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in agents paths outage grafana discovered evicting evicted; do
  cwebp -quiet -lossless -z 9 "$WORK/$name.png" -o "$OUT_DIR/$name.webp"
  printf '  %-11s %s\n' "$name.webp" "$(magick identify -format '%wx%h' "$WORK/$name.png")"
done
step "Done; the demo is back to normal"
