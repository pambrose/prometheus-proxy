#!/usr/bin/env bash
# Retakes the screenshots on the website's Network Demo page (website/prometheus-proxy/docs/network-demo.md) from the
# running demo: it plays the page's scenarios, parts 2 to 7, captures the dashboards, Grafana, and Zipkin in headless
# Chrome at twice the pixel density, crops each, and writes lossless WebP to
# website/prometheus-proxy/docs/images/network-demo/: <name>.webp in the dark theme and <name>-light.webp in the light
# one, which the page shows by the reader's theme. Zipkin's UI has one theme, so its shot is taken once. It puts the
# demo back to normal when it finishes, or when it fails.
#
# `make screenshots`, in demos/network, builds the demo from this checkout and starts it first, so the screenshots
# show the dashboard as it is in the code. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7
# (`magick`), and `cwebp`.
#
# Two settings, for running it again and again while checking a change; the defaults take the site's screenshots:
#   WAIT_SECS=<n>       how long each wait for the demo to reach a scene's state may take before the script fails
#                       (default 150)
#   SKIP_UPTIME_WAIT=1  skip the wait of up to a minute, after a fresh start, for the agents' uptimes to read in
#                       minutes; the shots then show uptimes in seconds, so they're for checking a change, not for the
#                       site
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/network-demo"

DASHBOARD_URL="http://localhost:${NETWORK_DEMO_DASHBOARD_PORT:-8194}/dashboard"
STANDBY_DASHBOARD_URL="http://localhost:${NETWORK_DEMO_STANDBY_DASHBOARD_PORT:-8195}/dashboard"
GRAFANA_URL="http://localhost:${NETWORK_DEMO_GRAFANA_PORT:-3100}"
PROMETHEUS_URL="http://localhost:${NETWORK_DEMO_PROMETHEUS_PORT:-9190}"
ZIPKIN_URL="http://localhost:${NETWORK_DEMO_ZIPKIN_PORT:-9511}"

# The settings in the header. SKIP_UPTIME_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# The CSS width every screenshot is taken at, and the themes each is taken in.
WIDTH=1280
THEMES=(dark light)

# The demo's Grafana dashboard in kiosk mode, over a window each scene gives. A {theme} becomes the theme's name:
# Grafana doesn't follow the browser's scheme.
GRAFANA_DASHBOARD="$GRAFANA_URL/d/prometheus-proxy-network-demo?orgId=1&kiosk&to=now&theme={theme}&from=now-"

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
  die "the demo isn't running at $DASHBOARD_URL; start it with 'make up' in demos/network"

WORK="$(mktemp -d)"

# Undoes every scenario, whatever state a failure left the demo in: heal brings back every link and proxy and removes
# every toxic, and unfilter restarts agent-branch on its own config.
restore() {
  "$DEMO" heal >/dev/null 2>&1 || true
  "$DEMO" unfilter >/dev/null 2>&1 || true
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

# Waits this many seconds, so a graph or a countdown can move: wait_until with a check that passes once they're up.
elapsed_since() { ((SECONDS >= $1)); }
wait_secs() {
  local until=$((SECONDS + $1))
  wait_until "$1 seconds to pass" elapsed_since "$until"
}

# The agent list's row for the connected agent with this name, at a dashboard's base URL (DASHBOARD_URL unless given):
# its start tag, or nothing. The row carries the agent's id, name, and path count as data attributes (agentListInner
# in ProxyDashboardHtml.kt), and the name's closing quote keeps one name from matching another that starts with it. A
# row with no paths is passed over: after `demo.sh filter` restarts agent-branch on the thin link, its old connection
# stays listed, with none, until what it had queued drains through the link.
agent_row() {
  local html
  html="$(curl -fsS "${2:-$DASHBOARD_URL}" 2>/dev/null)" || return 0
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" |
    grep -vF 'data-path-count="0"' | head -1 || true
}

# An attribute's value in a start tag, or nothing.
attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<<"$2"; }

# The id of the connected agent with this name, at a dashboard's base URL (DASHBOARD_URL unless given), or nothing.
agent_id() { attr data-agent "$(agent_row "$@")"; }

# Whether the dashboard at a base URL lists both agents.
has_both_agents() { [[ -n "$(agent_row agent-hq "$1")" && -n "$(agent_row agent-branch "$1")" ]]; }

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill a fixed crop.
# The count is data on the detail's Recent scrapes header (detailInner in ProxyDashboardHtml.kt).
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo 'data-scrape-count="[0-9]+"' | grep -Eo '[0-9]+' | head -1)"
  [[ -n "$count" ]] && ((count >= $2))
}

# Whether a page, by URL, matches an extended regex. The page is taken whole before grep reads it: grep -q stops at its
# match, and under pipefail a curl it cut off would fail the check.
page_has() {
  local body
  body="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -Eq "$2" <<<"$body"
}

# Whether agent-branch has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
branch_up_a_minute() {
  local id
  id="$(agent_id agent-branch)"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

# Whether a PromQL query returns a result.
prometheus_has() {
  local body
  body="$(curl -fsS -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" 2>/dev/null)" || return 1
  grep -q '"value"' <<<"$body"
}

# Every agent connected to the primary, none to the standby, and every path's job up: the demo as it starts.
normal() {
  has_both_agents "$DASHBOARD_URL" &&
    [[ -z "$(agent_row agent-hq "$STANDBY_DASHBOARD_URL")" ]] &&
    [[ -z "$(agent_row agent-branch "$STANDBY_DASHBOARD_URL")" ]] &&
    prometheus_has 'count(max by (job) (up) == 1) == 8'
}

# Heals the demo and waits until it is back to normal. A heal that lands while an agent is between links can leave it
# on the standby, so heal once more if it hasn't come back in a scrape or two.
back_to_normal() {
  "$DEMO" heal >/dev/null
  local deadline=$((SECONDS + 20))
  until normal || ((SECONDS >= deadline)); do sleep 2; done
  normal || "$DEMO" heal >/dev/null
  wait_until "both agents on the primary and every job up" normal
}

# Whether agent-branch's recent scrapes on the primary show at least three filtered inventory payloads, under 300,000
# characters, that succeeded.
filtered_inventory() {
  local id row='/inventory_metrics</span><span class="pill ok">200</span>.{0,80}· [0-9]+ chars'
  id="$(agent_id agent-branch)"
  [[ -n "$id" ]] || return 1
  [[ "$(curl -fsS "$DASHBOARD_URL/agents/$id" | grep -Eo "$row" | grep -Eo '[0-9]+ chars' | awk '$1 < 300000' |
    wc -l | tr -d ' ')" -ge 3 ]]
}

# Whether an agent's detail page, by URL, shows its last nine scrapes all failed, Prometheus having given up on each.
# The thin shot's crop holds nine rows.
all_too_late() {
  local body
  body="$(curl -fsS "$1" 2>/dev/null)" || return 1
  (($(grep -Eo 'class="pill [a-z]+">[0-9]+</span>' <<<"$body" | head -9 | grep -c '>499<') == 9))
}

# Whether the primary's dashboard shows agent-branch idle for 16 to 24 seconds: its link is cut, and the eviction
# countdown is past halfway.
branch_idle() {
  local id
  id="$(agent_id agent-branch)"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'idle (1[6-9]|2[0-4])s'
}

# Whether the standby's dashboard lists agent-branch.
branch_on_standby() { [[ -n "$(agent_row agent-branch "$STANDBY_DASHBOARD_URL")" ]]; }

# The ids of agent-branch's connectagent traces in Zipkin from the last 5 minutes, newest first, one a line.
connect_traces() {
  curl -fsS -G "$ZIPKIN_URL/zipkin/api/v2/traces" --data-urlencode serviceName=agent-branch \
    --data-urlencode spanName=proxyservice/connectagent --data-urlencode lookback=300000 \
    --data-urlencode limit=20 | grep -Eo '"traceId":"[0-9a-f]+"' | cut -d'"' -f4 | awk '!seen[$0]++'
}

# Whether Zipkin has at least this many of them: one for each reconnect.
has_connect_traces() { (($(connect_traces | wc -l) >= $1)); }

# Captures a page at WIDTH x the given CSS height, at 2x, in a theme: the dashboard follows the browser's preferred
# color scheme. Grafana and Zipkin render after loading their data, so their captures get a longer virtual-time budget.
capture() {
  local url="$1" png="$2" height="$3" theme="$4" budget="${5:-3000}" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor=2 --blink-settings=preferredColorScheme=$scheme \
    --window-size="$WIDTH,$height" --virtual-time-budget="$budget" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A page in each theme, cut at a fixed CSS height. The agent detail heights end on a row boundary for the demo's
# layout: change them with the dashboard's row metrics. A {theme} in the URL becomes the theme's name, for Grafana.
shoot_fixed() {
  local name="$1" url="$2" height="$3" capture_height="${4:-$3}" budget="${5:-3000}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "${url//\{theme\}/$theme}" "$png" "$capture_height" "$theme" "$budget"
    magick "$png" -crop "$((WIDTH * 2))x$((height * 2))+0+0" +repage "$png"
  done
}

# Grafana's dashboard in each theme, over the last this many seconds: captured taller than kept, since the bottom of
# its viewport carries a fixed "Powered by" footer. The window is short, so a scene's graphs show it and the minute or
# so of normal before it, not the scenes before that.
shoot_grafana() { shoot_fixed "$1" "${GRAFANA_DASHBOARD}${2}s" 900 1000 15000; }

step "Waiting for the demo to be back to normal"
back_to_normal
# make screenshots starts the demo just before this, so every agent's uptime would read a few seconds. Waiting until
# agent-branch has been up a minute makes the shots look like a demo that has been running, and gives Grafana's graphs
# a minute of normal before each scene. Say why, since the script otherwise looks stuck, or that SKIP_UPTIME_WAIT
# skipped it.
if ! branch_up_a_minute; then
  if [[ "$SKIP_UPTIME_WAIT" == 1 ]]; then
    step "Skipping the wait for the agents' uptimes to read in minutes (SKIP_UPTIME_WAIT=1): the shots show them in" \
      "seconds, so they're for checking a change, not for the site"
  else
    step "Waiting up to a minute so the agents' uptimes read in minutes, not seconds: the demo was just started"
    wait_until "agent-branch to be up a minute" branch_up_a_minute
  fi
fi

# The agent detail heights end on a Recent scrapes row's lower border, nine rows down, for the demo's layout; the
# failed-over tag makes the standby's header a pixel or two taller. Change them with the dashboard's row metrics.
DETAIL_HEIGHT=620
FAILOVER_DETAIL_HEIGHT=621

step "Part 2: A high-latency link slows scrapes, but nothing fails"
# Half a minute more of normal, so Grafana's two-minute window opens after the demo's start settled.
wait_secs 30
"$DEMO" far >/dev/null
# Long enough for Grafana's scrape-time graph to show agent-branch's step up beside agent-hq's flat line.
wait_secs 60
shoot_grafana far 120
back_to_normal

step "Part 3: A low-bandwidth link fails scrapes; filtering at the agent fixes it"
branch="$(agent_id agent-branch)"
[[ -n "$branch" ]] || die "agent-branch isn't connected"
"$DEMO" thin >/dev/null
# Most of a minute, well past the 30 seconds of silence the proxy evicts an agent after: agent-branch's late answers
# keep arriving, so it stays connected, under the same id, its idle time near zero.
wait_secs 50
wait_until "agent-branch's last nine scrapes to be too late for Prometheus" all_too_late "$DASHBOARD_URL/agents/$branch"
[[ "$(agent_id agent-branch)" == "$branch" ]] || die "agent-branch reconnected on the thin link"
shoot_fixed thin "$DASHBOARD_URL/agents/$branch" "$DETAIL_HEIGHT"
"$DEMO" filter >/dev/null 2>&1
wait_until "agent-branch's filtered inventory scrapes through the thin link" filtered_inventory
branch="$(agent_id agent-branch)"
wait_until "agent-branch's scrapes since the restart" has_scrapes "$DASHBOARD_URL/agents/$branch" 11
shoot_fixed filtered "$DASHBOARD_URL/agents/$branch" "$DETAIL_HEIGHT"
"$DEMO" unfilter >/dev/null 2>&1
back_to_normal

step "Part 4: The primary proxy stops, and the agents fail over to the standby"
"$DEMO" proxy-down >/dev/null 2>&1
wait_until "both agents on the standby" has_both_agents "$STANDBY_DASHBOARD_URL"
branch="$(agent_id agent-branch "$STANDBY_DASHBOARD_URL")"
[[ -n "$branch" ]] || die "agent-branch isn't on the standby"
wait_until "agent-branch's scrapes through the standby" has_scrapes "$STANDBY_DASHBOARD_URL/agents/$branch" 11
shoot_fixed failover "$STANDBY_DASHBOARD_URL/agents/$branch" "$FAILOVER_DETAIL_HEIGHT"
"$DEMO" proxy-up >/dev/null 2>&1
back_to_normal

step "Part 5: A link goes silent; the agent detects it and fails over"
branch="$(agent_id agent-branch)"
[[ -n "$branch" ]] || die "agent-branch isn't connected"
"$DEMO" cut >/dev/null
wait_until "the primary to hear nothing from agent-branch for 16 seconds" branch_idle
shoot_fixed cut "$DASHBOARD_URL/agents/$branch" "$DETAIL_HEIGHT"
wait_until "agent-branch on its standby link" branch_on_standby
back_to_normal

step "Part 6: A link that keeps dropping: the agent reconnects, traced in Zipkin"
"$DEMO" flaky >/dev/null
# Each connection through the link lasts about 15 seconds; three reconnects put three connectagent traces in Zipkin.
wait_until "three reconnects of agent-branch's in Zipkin" has_connect_traces 3
# The newest reconnect's connectagent call, as the scene's tour has the presenter open one. Zipkin's search page
# doesn't run a query from its URL in headless Chrome, so the shot is of the trace itself. Zipkin's UI has one theme,
# so this is taken once and serves both of the site's.
capture "$ZIPKIN_URL/zipkin/traces/$(connect_traces | head -1)" "$WORK/tracing.png" 496 light 8000
back_to_normal

step "Part 7: The whole WAN goes down: an alert fires, and the agents reconnect"
# A minute and a quarter of normal first, so Grafana's 90-second window shows the WAN going down from a quiet demo,
# and part 5's NetworkAgentEvicted, which lasts two minutes, has resolved.
wait_secs 75
"$DEMO" wan-down >/dev/null
wait_until "NetworkNoAgentsConnected to fire" \
  prometheus_has 'ALERTS{alertname="NetworkNoAgentsConnected",alertstate="firing"}'
shoot_grafana wan-down 90
"$DEMO" wan-up >/dev/null
back_to_normal

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in far thin filtered failover cut wan-down; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
cwebp -quiet -lossless -z 9 "$WORK/tracing.png" -o "$OUT_DIR/tracing.webp"
printf '  %-22s %s\n' "tracing.webp" "$(magick identify -format '%wx%h' "$WORK/tracing.png")"
step "Done; the demo is back to normal"
