#!/usr/bin/env bash
# Retakes the screenshots on the website's Basics Demo page (website/prometheus-proxy/docs/demo.md) from the running
# demo: it plays the page's scenarios, captures the dashboard and Grafana in headless Chrome at twice the pixel density
# in both themes, each a whole window, and writes lossless WebP to website/prometheus-proxy/docs/images/demo/:
# <name>.webp in the dark theme and <name>-light.webp in the light one, which the page shows by the reader's theme. It
# puts the demo back to normal when it finishes, or when it fails.
#
# `make screenshots`, in demos/basics, builds the demo from this checkout and starts it first, so the screenshots
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
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/demo"

DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"

# The settings in the header. SKIP_UPTIME_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# Every screenshot is a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, rather than a strip cut to its
# content, so each fills the same space on the website's page and in the lightbox that opens it full size. The Paths
# views are taken in a wider window of the same shape: with the Identity, History, and Links columns, and the
# discovered services' longer paths and a gone row's tag, the table is about 1,600px wide in the eviction scene, so at
# 1280 it scrolls and its right edge is cut off. THEMES are the themes each is taken in.
WIDTH=1280
HEIGHT=800
PATHS_WIDTH=1680
PATHS_HEIGHT=1050
THEMES=(dark light)

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
  die "the demo isn't running at $DASHBOARD_URL; start it with 'make up-local' in demos/basics"

WORK="$(mktemp -d)"

# Undoes every scenario, whatever state a failure left the demo in.
restore() {
  "$DEMO" agent-thaw >/dev/null 2>&1 || true
  "$DEMO" undiscover >/dev/null 2>&1 || true
  "$DEMO" recover >/dev/null 2>&1 || true
  "$DEMO" rogue-agent-stop >/dev/null 2>&1 || true
  "$DEMO" duplicate-agent-stop >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap restore EXIT

# Waits until a command succeeds, checking every 2 seconds, or fails after WAIT_SECS with what it was waiting for. A
# check may set UNMET to what it last found missing, which the failure adds.
wait_until() {
  local what="$1" deadline=$((SECONDS + WAIT_SECS))
  shift
  UNMET=""
  until "$@"; do
    ((SECONDS < deadline)) ||
      die "timed out after ${WAIT_SECS}s waiting for $what${UNMET:+: $UNMET}; WAIT_SECS raises the limit"
    sleep 2
  done
}

page() { curl -fsS "$DASHBOARD_URL$1"; }

# Counts the matches of an extended regex in a dashboard page.
count_in() { page "$1" | grep -Eo "$2" | wc -l | tr -d ' '; }

# The agent list's row for the connected agent with this name: its start tag, or nothing. The row carries the agent's
# id, name, and path count as data attributes (agentListInner in ProxyDashboardHtml.kt), which the checks below read:
# they matched the row's text before, and every change to how the row looks, a tag, an LED, or a word, broke one. The
# name's closing quote keeps agent-east from matching agent-east-copy, and the proxy escapes every value, so no ">" ends
# a tag early.
agent_row() {
  local html
  html="$(curl -fsS "$DASHBOARD_URL" 2>/dev/null)" || return 0
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" | head -1 || true
}

# An attribute's value in a start tag, or nothing.
attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<<"$2"; }

# The id of the connected agent with this name, or nothing.
agent_id() { attr data-agent "$(agent_row "$1")"; }

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill the window.
# The count is data on the detail's Recent scrapes header (detailInner in ProxyDashboardHtml.kt).
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo 'data-scrape-count="[0-9]+"' | grep -Eo '[0-9]+' | head -1)"
  [[ -n "$count" ]] && ((count >= $2))
}

# Whether agent-east is listed with no paths, as once its copy has taken them.
east_has_no_paths() { [[ "$(attr data-path-count "$(agent_row agent-east)")" == 0 ]]; }

# Whether the Paths view shows at least this many paths from service discovery, tagged "disc". A function, so each of
# wait_until's checks counts again: a count passed as an argument was taken once, as the wait began.
has_discovered_sources() { (($(count_in "/paths" '>disc<') >= $1)); }

# Whether agent-east has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
east_up_a_minute() {
  local id
  id="$(agent_id agent-east)"
  [[ -n "$id" ]] && page "/agents/$id" | grep -Eq 'up [0-9]+[mhd]'
}

# Whether Prometheus has DemoTargetDown firing for at least this many targets.
alerts_firing() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=count(ALERTS{alertname=\"DemoTargetDown\",alertstate=\"firing\"}) >= $1" |
    grep -q '"value"'
}

# The Last scrape times in a Paths view's HTML, one a line. Each is a <time datetime="…Z">, the view's only <time>, in
# UTC to the second, so every one has the same width and they order as strings (ProxyDashboardHtmlTest pins this): no
# date parsing, which differs between macOS's date and GNU's. Matched by that form, since the page's script mentions the
# element in a comment.
scrape_times() {
  grep -Eo 'datetime="[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"' | cut -d'"' -f2
}

# The newest Last scrape in the Paths view, or nothing while no path has one.
newest_scrape() {
  local paths
  paths="$(page "/paths" 2>/dev/null)" || return 1
  scrape_times <<<"$paths" | LC_ALL=C sort | tail -1 || true
}

# This many paths, every one answering 200 on a scrape later than a stamp from newest_scrape, nothing departed: the
# state the first two screenshots show. Sets UNMET to the first of those it finds missing.
healthy() {
  local count="$1" since="$2" paths ok fresh
  if ! paths="$(page "/paths" 2>/dev/null)"; then
    UNMET="the dashboard didn't answer"
    return 1
  fi
  ok="$(grep -Eo 'class="pill ok"' <<<"$paths" | wc -l | tr -d ' ')"
  fresh="$(scrape_times <<<"$paths" | LC_ALL=C awk -v since="$since" '$0 > since' | wc -l | tr -d ' ')"
  if grep -q 'class="pill crit"' <<<"$paths"; then
    UNMET="a path's last scrape failed"
  elif grep -Eq '<span class="crit">[0-9]+ departed</span>' <<<"$paths"; then
    UNMET="a path has departed"
  elif ((ok != count)); then
    UNMET="$ok of $count paths answer 200"
  elif ((fresh != count)); then
    UNMET="$fresh of $count were scraped after it"
  else
    return 0
  fi
  return 1
}

# Waits until healthy passes for this many paths on scrapes since the wait began: each path's last scrape later than the
# newest one the Paths view showed as it began. Without that, the wait passed at once after an agent restarted, on its
# paths' last scrapes from before the restart. The mark is the proxy's own time, not this machine's, which a
# container's clock can be seconds apart from. Later rather than at the same time, since the scrapes that set the mark
# were already in the view: a scrape in the mark's second waits for the path's next one, 5 seconds on.
wait_healthy() {
  local what="$1" count="$2" since
  since="$(newest_scrape)" || die "the dashboard didn't answer at $DASHBOARD_URL/paths"
  wait_until "$what, each scraped after ${since:-the wait began}" healthy "$count" "$since"
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

# A dashboard view in each theme, at a dashboard path, a whole window (WIDTH x HEIGHT unless given).
shoot_table() {
  local name="$1" path="$2" width="${3:-$WIDTH}" height="${4:-$HEIGHT}" theme
  for theme in "${THEMES[@]}"; do
    capture "$DASHBOARD_URL$path" "$WORK/$(named "$name" "$theme").png" "$height" "$theme" "" "$width"
  done
}

# A Paths view in each theme, a whole PATHS_WIDTH x PATHS_HEIGHT window.
shoot_paths() {
  shoot_table "$1" /paths "$PATHS_WIDTH" "$PATHS_HEIGHT"
}

# A page in each theme, a whole WIDTH x HEIGHT window. A page whose viewport ends in a fixed footer, Grafana's, is
# captured this much taller and cut to HEIGHT, which leaves the footer out. A {theme} in the URL becomes the theme's
# name, for Grafana, which doesn't follow the browser's scheme. shoot_fixed <name> <url> [capture height] [budget]
shoot_fixed() {
  local name="$1" url="$2" capture_height="${3:-$HEIGHT}" budget="${4:-3000}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "${url//\{theme\}/$theme}" "$png" "$capture_height" "$theme" "$budget"
    magick "$png" -crop "$((WIDTH * 2))x$((HEIGHT * 2))+0+0" +repage "$png"
  done
}

step "Waiting for every path to answer 200"
"$DEMO" agent-thaw >/dev/null 2>&1 || true
"$DEMO" undiscover >/dev/null
"$DEMO" recover >/dev/null
wait_healthy "nine healthy paths" 9
# make screenshots starts the demo just before this, so every agent's uptime would read a few seconds. Waiting until
# agent-east has been up a minute makes the first shots look like a demo that has been running. Say why, since the
# script otherwise looks stuck, or that SKIP_UPTIME_WAIT skipped it.
if ! east_up_a_minute; then
  if [[ "$SKIP_UPTIME_WAIT" == 1 ]]; then
    step "Skipping the wait for the agents' uptimes to read in minutes (SKIP_UPTIME_WAIT=1): the shots show them in" \
      "seconds, so they're for checking a change, not for the site"
  else
    step "Waiting up to a minute so the agents' uptimes read in minutes, not seconds: the demo was just started"
    wait_until "agent-east to be up a minute" east_up_a_minute
  fi
fi

east="$(agent_id agent-east)"
[[ -n "$east" ]] || die "agent-east isn't connected"
# Enough of agent-east's Recent scrapes to fill the shot, which the minute's wait gives but SKIP_UPTIME_WAIT doesn't.
wait_until "agent-east's scrapes" has_scrapes "$DASHBOARD_URL/agents/$east" 12
step "The Agents and Paths views"
shoot_fixed agents "$DASHBOARD_URL/agents/$east"
shoot_paths paths

step "A failing service and a hung one"
"$DEMO" outage >/dev/null
"$DEMO" slow >/dev/null
wait_until "DemoTargetDown to fire for payments and inventory" alerts_firing 2
shoot_paths outage
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot_fixed grafana "$GRAFANA_URL/d/prometheus-proxy-demo?orgId=1&kiosk&from=now-15m&to=now&theme={theme}" \
  "$((HEIGHT + 100))" 15000
"$DEMO" recover >/dev/null
wait_healthy "the services to recover" 9

step "A duplicate agent, without consolidated mode"
# agent-east has to serve its scrapes before the copy starts: the copy takes every path, and with none left agent-east
# serves no more, so a copy started too soon left the shot's Recent scrapes all but empty. The healthy wait above took
# only scrapes from after the recovery, so by now it has served one for each of its six paths, rows for the shot. Its
# id stays the same once the copy has its paths.
east="$(agent_id agent-east)"
[[ -n "$east" ]] || die "agent-east isn't connected"
"$DEMO" duplicate-agent >/dev/null 2>&1
wait_until "agent-east-copy to take agent-east's paths" east_has_no_paths
shoot_fixed duplicate "$DASHBOARD_URL/agents/$east"
"$DEMO" duplicate-agent-stop >/dev/null 2>&1
wait_healthy "nine healthy paths" 9

step "Three services discovered at runtime"
"$DEMO" discover >/dev/null
wait_healthy "three discovered paths" 12
wait_until "three 'disc' sources" has_discovered_sources 3
shoot_paths discovered

step "An agent that goes silent, and its eviction"
west2="$(agent_id agent-west-2)"
[[ -n "$west2" ]] || die "agent-west-2 isn't connected"
"$DEMO" agent-freeze >/dev/null 2>&1
wait_until "agent-west-2 to be idle for 16 seconds" \
  bash -c "curl -fsS '$DASHBOARD_URL/agents/$west2' | grep -Eq 'idle (1[6-9]|2[0-9])s'"
shoot_fixed evicting "$DASHBOARD_URL/agents/$west2"
wait_until "the proxy to evict agent-west-2" \
  bash -c "curl -fsS '$DASHBOARD_URL/paths' | grep -Eq '<span class=\"crit\">[0-9]+ departed</span>'"
sleep 4
shoot_paths evicted

step "A contractor's agent refused another team's path"
"$DEMO" rogue-agent >/dev/null 2>&1
wait_until "the proxy to refuse agent-rogue's payments path" \
  bash -c "curl -fsS '$DASHBOARD_URL/activity?q=agent-rogue' | grep -q 'not authorized to register path /payments_metrics'"
shoot_table activity "/activity?q=agent-rogue"
"$DEMO" rogue-agent-stop >/dev/null 2>&1

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in agents paths outage grafana duplicate discovered evicting evicted activity; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
step "Done; the demo is back to normal"
