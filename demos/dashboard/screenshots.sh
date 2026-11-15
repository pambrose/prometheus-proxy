#!/usr/bin/env bash
# Retakes the screenshots on the website's Live Demo page (website/prometheus-proxy/docs/demo.md) from the running demo:
# it plays the page's scenarios, captures the dashboard and Grafana in headless Chrome at twice the pixel density in both
# themes, crops each, and writes lossless WebP to website/prometheus-proxy/docs/images/demo/: <name>.webp in the dark
# theme and <name>-light.webp in the light one, which the page shows by the reader's theme. It puts the demo back to
# normal when it finishes, or when it fails.
#
# `make demo-screenshots` builds the demo from this checkout and starts it first, so the screenshots show the dashboard
# as it is in the code. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`), and `cwebp`.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/demo"

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
STANDBY_DASHBOARD_URL="http://localhost:${DEMO_STANDBY_DASHBOARD_PORT:-8095}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"
ZIPKIN_URL="http://localhost:${DEMO_ZIPKIN_PORT:-9411}"

# The CSS width every screenshot is taken at, and the themes each is taken in. The Paths views are taken wider: with the
# History column, and the discovered services' longer paths and a gone row's tag, the table is about 1,315px wide in the
# eviction scene, so at 1280 it scrolls and its right edge is cut off.
WIDTH=1280
PATHS_WIDTH=1360
THEMES=(dark light)

# The page color below the dashboard's last row in a theme, which the Paths views are trimmed to (--bg in
# ProxyDashboardCss.kt).
background() { if [[ "$1" == dark ]]; then echo "#0d1015"; else echo "#f2f5f8"; fi; }

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
  "$DEMO" proxy-up >/dev/null 2>&1 || true
  "$DEMO" proxy-up proxy-standby >/dev/null 2>&1 || true
  "$DEMO" unfilter >/dev/null 2>&1 || true
  "$DEMO" rogue-agent-stop >/dev/null 2>&1 || true
  "$DEMO" duplicate-agent-stop >/dev/null 2>&1 || true
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
  curl -fsS "${2:-$DASHBOARD_URL}" | grep -Eo "data-agent=\"[^\"]*\"[^>]*><span><span class=\"led [a-z]+\"></span>$1<" |
    sed -E 's/^data-agent="([^"]*)".*/\1/' | head -1
}

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill a fixed crop.
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo '<span>Recent scrapes</span><span>[0-9]+</span>' | grep -Eo '[0-9]+')"
  [[ -n "$count" ]] && ((count >= $2))
}

# Whether agent-east is listed with no paths, as once its copy has taken them.
east_has_no_paths() { page "" | grep -q 'agent-east</span><span class="host">[^<]*· 0 paths'; }

# The id of a recent registerpath trace of agent-east's, from Zipkin.
registerpath_trace() {
  curl -fsS -G "$ZIPKIN_URL/zipkin/api/v2/traces" --data-urlencode serviceName=agent-east \
    --data-urlencode spanName=proxyservice/registerpath --data-urlencode limit=1 |
    grep -Eo '"traceId":"[0-9a-f]+"' | head -1 | cut -d'"' -f4
}

# Whether Zipkin has a registerpath trace of agent-east's.
has_registerpath_trace() { [[ -n "$(registerpath_trace)" ]]; }

# Whether agent-east has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
east_up_a_minute() {
  local id
  id="$(agent_id agent-east)"
  [[ -n "$id" ]] && page "/agents/$id" | grep -Eq 'up [0-9]+[mhd]'
}

# Whether the dashboard at a base URL lists all three agents.
has_all_agents() { curl -fsS "$1" 2>/dev/null | grep -q '<span>3 agents</span>'; }

# Whether Prometheus scrapes the primary proxy's own metrics again: by then the agents look its name up again.
primary_metrics_up() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode 'query=max(up{job="prometheus-proxy"}) == 1' |
    grep -q '"value"'
}

# Whether agent-east's recent scrapes show at least three filtered catalog payloads, under 300,000 characters.
filtered_catalog() {
  local id
  id="$(agent_id agent-east)"
  [[ -n "$id" ]] || return 1
  [[ "$(curl -fsS "$DASHBOARD_URL/agents/$id" | grep -Eo '/catalog_metrics</span>.{0,120}· [0-9]+ chars' |
    grep -Eo '[0-9]+ chars' | awk '$1 < 300000' | wc -l | tr -d ' ')" -ge 3 ]]
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

# Captures a page at the given CSS width (WIDTH unless given) x the given CSS height, at 2x, in a theme: the dashboard
# follows the browser's preferred color scheme. Grafana renders after loading its data, so its captures get a longer
# virtual-time budget.
capture() {
  local url="$1" png="$2" height="$3" theme="$4" budget="${5:-3000}" width="${6:-$WIDTH}" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor=2 --blink-settings=preferredColorScheme=$scheme \
    --window-size="$width,$height" --virtual-time-budget="$budget" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A Paths view in each theme, at PATHS_WIDTH: captured taller than the table, then trimmed to its last row.
shoot_paths() {
  local name="$1" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "$DASHBOARD_URL/paths" "$png" 900 "$theme" 3000 "$PATHS_WIDTH"
    magick "$png" -background "$(background "$theme")" -fuzz 2% -define trim:edges=south -trim +repage "$png"
  done
}

# A page in each theme, cut at a fixed CSS height. The agent detail heights end on a row boundary for the demo's layout:
# change them with the dashboard's row metrics. A {theme} in the URL becomes the theme's name, for Grafana, which
# doesn't follow the browser's scheme.
shoot_fixed() {
  local name="$1" url="$2" height="$3" capture_height="${4:-$3}" budget="${5:-3000}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "${url//\{theme\}/$theme}" "$png" "$capture_height" "$theme" "$budget"
    magick "$png" -crop "$((WIDTH * 2))x$((height * 2))+0+0" +repage "$png"
  done
}

step "Waiting for every path to answer 200"
"$DEMO" agent-thaw >/dev/null 2>&1 || true
"$DEMO" undiscover >/dev/null
"$DEMO" recover >/dev/null
wait_until "ten healthy paths" healthy 10
# make demo-screenshots starts the demo just before this, so every agent's uptime would read a few seconds. Waiting
# until agent-east has been up a minute makes the first shots look like a demo that has been running, and gives its
# Recent scrapes enough rows to fill the screenshot. Say why, since the script otherwise looks stuck.
if ! east_up_a_minute; then
  step "Waiting up to a minute so the agents' uptimes read in minutes, not seconds: the demo was just started"
  wait_until "agent-east to be up a minute" east_up_a_minute
fi

east="$(agent_id agent-east)"
[[ -n "$east" ]] || die "agent-east isn't connected"
step "The Agents and Paths views"
shoot_fixed agents "$DASHBOARD_URL/agents/$east" 763
shoot_paths paths

step "A failing service and a hung one"
"$DEMO" outage >/dev/null
"$DEMO" slow >/dev/null
wait_until "DemoTargetDown to fire for payments and inventory" alerts_firing 2
shoot_paths outage
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot_fixed grafana "$GRAFANA_URL/d/prometheus-proxy-demo?orgId=1&kiosk&from=now-15m&to=now&theme={theme}" 900 1000 15000
"$DEMO" recover >/dev/null
wait_until "the services to recover" healthy 10

step "The primary proxy fails over to the standby"
"$DEMO" proxy-down >/dev/null 2>&1
wait_until "every agent on the standby" has_all_agents "$STANDBY_DASHBOARD_URL"
east="$(agent_id agent-east "$STANDBY_DASHBOARD_URL")"
[[ -n "$east" ]] || die "agent-east isn't on the standby"
wait_until "agent-east's scrapes through the standby" has_scrapes "$STANDBY_DASHBOARD_URL/agents/$east" 11
shoot_fixed failover "$STANDBY_DASHBOARD_URL/agents/$east" 763
"$DEMO" proxy-up >/dev/null 2>&1
wait_until "the primary's own metrics to answer again" primary_metrics_up
"$DEMO" proxy-down proxy-standby >/dev/null 2>&1
wait_until "every agent back on the primary" has_all_agents "$DASHBOARD_URL"
"$DEMO" proxy-up proxy-standby >/dev/null 2>&1
wait_until "ten healthy paths" healthy 10

step "A call traced through the firewall"
# The failover reconnected every agent, so Zipkin has their registration calls. Zipkin's UI has one theme, so this is
# taken once and serves both of the site's.
wait_until "agent-east's registerpath traces" has_registerpath_trace
capture "$ZIPKIN_URL/zipkin/traces/$(registerpath_trace)" "$WORK/tracing.png" 496 light 8000

step "A filter at the agent"
"$DEMO" filter >/dev/null 2>&1
wait_until "agent-east's filtered catalog scrapes" filtered_catalog
east="$(agent_id agent-east)"
wait_until "agent-east's scrapes since the restart" has_scrapes "$DASHBOARD_URL/agents/$east" 11
shoot_fixed filtered "$DASHBOARD_URL/agents/$east" 763
"$DEMO" unfilter >/dev/null 2>&1
wait_until "ten healthy paths" healthy 10

step "A duplicate agent, without consolidated mode"
# agent-east has to serve its scrapes before the copy starts. unfilter just restarted it, and "ten healthy paths" passes
# on the paths' last scrapes from before the restart, so a copy started then took every path before agent-east served
# four, and with no paths left it never served more. Its id stays the same once the copy has its paths.
east="$(agent_id agent-east)"
wait_until "agent-east's scrapes since the filter's restart" has_scrapes "$DASHBOARD_URL/agents/$east" 4
"$DEMO" duplicate-agent >/dev/null 2>&1
wait_until "agent-east-copy to take agent-east's paths" east_has_no_paths
shoot_fixed duplicate "$DASHBOARD_URL/agents/$east" 414
"$DEMO" duplicate-agent-stop >/dev/null 2>&1
wait_until "ten healthy paths" healthy 10

step "Three services discovered at runtime"
"$DEMO" discover >/dev/null
wait_until "three discovered paths" healthy 13
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
for name in agents paths outage grafana failover filtered duplicate discovered evicting evicted; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
cwebp -quiet -lossless -z 9 "$WORK/tracing.png" -o "$OUT_DIR/tracing.webp"
printf '  %-22s %s\n' "tracing.webp" "$(magick identify -format '%wx%h' "$WORK/tracing.png")"
step "Done; the demo is back to normal"
