#!/usr/bin/env bash
# Retakes the screenshots on the website's Basics Demo page (website/prometheus-proxy/docs/basics-demo.md), and the
# login page's and the screens page's on its Dashboard page (web-dashboard.md), from the running demo: it plays the
# page's scenarios, captures the proxy's dashboard, its screens page, agent-rogue's dashboard, and Grafana in headless
# Chrome at three times the pixel density in both themes, each a whole window, and writes lossless WebP to
# website/prometheus-proxy/docs/images/demo/: <name>.webp in the dark theme and <name>-light.webp in the light one,
# which the page shows by the reader's theme. It puts the demo back to normal when it finishes, or when it fails.
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

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/demo"

load_saved_ports
DASHBOARD_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/dashboard"
SCREENS_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/screens"
EAST_DASHBOARD_URL="http://localhost:${DEMO_AGENT_EAST_DASHBOARD_PORT:-8095}/dashboard"
ROGUE_DASHBOARD_URL="http://localhost:${DEMO_AGENT_ROGUE_DASHBOARD_PORT:-8098}/dashboard"
GRAFANA_URL="http://localhost:${DEMO_GRAFANA_PORT:-3000}"
PROMETHEUS_URL="http://localhost:${DEMO_PROMETHEUS_PORT:-9090}"

# The setting in the header beside WAIT_SECS, which the shared code reads. SKIP_UPTIME_WAIT takes exactly 1; any other
# value leaves the wait in.
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# Undoes every scenario, whatever state a failure left the demo in. login-off leaves a proxy whose login is already off
# as it is, since Compose recreates a container only for a changed environment.
restore() {
  "$DEMO" login-off >/dev/null 2>&1 || true
  "$DEMO" agent-thaw >/dev/null 2>&1 || true
  "$DEMO" undiscover >/dev/null 2>&1 || true
  "$DEMO" recover >/dev/null 2>&1 || true
  "$DEMO" rogue-agent-stop >/dev/null 2>&1 || true
  "$DEMO" duplicate-agent-stop >/dev/null 2>&1 || true
}
start_shots "$DASHBOARD_URL"

page() { curl -fsS "$DASHBOARD_URL$1"; }

# Counts the matches of an extended regex in a dashboard page.
count_in() { page "$1" | grep -Eo "$2" | wc -l | tr -d ' '; }

# The state agent-rogue's own dashboard gives one of its paths, or nothing. Each row of its Paths view carries its path
# and its state as data attributes (pathListInner in AgentDashboardHtml.kt), which this reads rather than the row's
# text, as agent_row does on the proxy's. The path's closing quote keeps one path from matching a longer one.
rogue_path_state() {
  local html
  html="$(curl -fsS "$ROGUE_DASHBOARD_URL" 2>/dev/null)" || return 0
  attr data-state "$(grep -Eo '<[a-z]+ [^>]*data-path="[^"]*"[^>]*>' <<<"$html" | grep -F "data-path=\"$1\"" |
    head -1 || true)"
}

# Whether agent-rogue's dashboard shows its payments path refused, as once the proxy has turned it away.
rogue_payments_refused() { [[ "$(rogue_path_state payments_metrics)" == refused ]]; }

# Whether agent-east is listed with no paths, as once its copy has taken them.
east_has_no_paths() { [[ "$(attr data-path-count "$(agent_row agent-east)")" == 0 ]]; }

# Whether agent-east's own dashboard answers and lists none of the services discover adds, as once it has re-read its
# emptied discovery file. Its Paths view lists every path it knows, configured, discovered, or registered.
east_discovers_none() {
  local html
  html="$(curl -fsS "$EAST_DASHBOARD_URL" 2>/dev/null)" || return 1
  ! grep -Eq 'data-path="(search|recommendations|notifications)_metrics"' <<<"$html"
}

# Whether the Paths view shows at least this many paths from service discovery, tagged "disc". A function, so each of
# wait_until's checks counts again: a count passed as an argument was taken once, as the wait began.
has_discovered_sources() { (($(count_in "/paths" '>disc<') >= $1)); }

# Whether agent-east has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
east_up_a_minute() {
  local id
  id="$(agent_id agent-east)"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

# Whether Prometheus has DemoTargetDown firing for at least this many targets.
alerts_firing() { prometheus_has "count(ALERTS{alertname=\"DemoTargetDown\",alertstate=\"firing\"}) >= $1"; }

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

# A Paths view in each theme, in the wide window: with the Identity, History, and Links columns, and the discovered
# services' longer paths and a gone row's tag, the table is about 1,600px wide in the eviction scene, so at WIDTH it
# scrolls and its right edge is cut off.
shoot_paths() { shoot_wide "$1" "$DASHBOARD_URL/paths"; }

step "Waiting for every path to answer 200"
# Every agent running and unfrozen, whatever the scenarios run before left, so each registers its paths again: a
# stopped agent's paths stay departed until it does. Thawing or starting an agent that runs changes nothing.
for agent in agent-east agent-west-1 agent-west-2; do
  "$DEMO" agent-thaw "$agent" >/dev/null 2>&1 || true
  "$DEMO" agent-start "$agent" >/dev/null 2>&1 || true
done
"$DEMO" undiscover >/dev/null
"$DEMO" recover >/dev/null
# healthy refuses a departed path, and the proxy lists one for an hour (proxy.dashboard.departedPathRetentionSecs):
# agent-rogue's after rogue-agent-stop, or a discovered one after undiscover, this script's own above included. make
# screenshots' up-local leaves an unchanged proxy running, so this recreates it, and the new one starts with no paths.
# First agent-east re-reads its emptied discovery file, or it would register the discovered paths with the new proxy,
# then drop them. The agents reconnect within seconds.
wait_until "agent-east to drop its discovered paths" east_discovers_none
"${COMPOSE[@]}" --progress quiet up --detach --force-recreate --no-deps --no-build proxy
wait_until "the recreated proxy's dashboard to answer" curl -fs -o /dev/null "$DASHBOARD_URL"
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
shoot agents "$DASHBOARD_URL/agents/$east"
shoot_paths paths

step "A failing service and a hung one"
"$DEMO" outage >/dev/null
"$DEMO" slow >/dev/null
wait_until "DemoTargetDown to fire for payments and inventory" alerts_firing 2
shoot_paths outage
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot grafana "$GRAFANA_URL/d/prometheus-proxy-demo?orgId=1&kiosk&from=now-15m&to=now&theme={theme}" 15000 \
  "$((HEIGHT + 100))"
# The screens page as part 4 of the tour opens it: the proxy's failing paths beside Grafana. In Split each pane gets
# half the window, and at WIDTH the proxy's Paths table would show little more than its paths.
shoot_screens screens \
  "proxy/paths?problems=1+grafana/d/prometheus-proxy-demo?orgId=1&kiosk&from=now-15m&to=now&theme={theme}"
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
shoot duplicate "$DASHBOARD_URL/agents/$east"
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
wait_until "agent-west-2 to be idle for 16 seconds" page_has "$DASHBOARD_URL/agents/$west2" 'idle (1[6-9]|2[0-9])s'
shoot evicting "$DASHBOARD_URL/agents/$west2"
wait_until "the proxy to evict agent-west-2" page_has "$DASHBOARD_URL/paths" '<span class="crit">[0-9]+ departed</span>'
sleep 4
shoot_paths evicted

step "A contractor's agent refused another team's path"
"$DEMO" rogue-agent >/dev/null 2>&1
wait_until "the proxy to refuse agent-rogue's payments path" \
  page_has "$DASHBOARD_URL/activity?q=agent-rogue" 'not authorized to register path /payments_metrics'
shoot activity "$DASHBOARD_URL/activity?q=agent-rogue"
# The same refusal on agent-rogue's own dashboard, with the path selected, so the shot shows the proxy's reason.
wait_until "agent-rogue's dashboard to show its payments path refused" rogue_payments_refused
shoot agent "$ROGUE_DASHBOARD_URL/paths/payments_metrics"
"$DEMO" rogue-agent-stop >/dev/null 2>&1

# Last, since turning the login on and off restarts the proxy, which starts the dashboard's histories over. The login
# page needs no session, and loads nothing, so it's captured as any other page is.
step "The dashboard's login page"
"$DEMO" login >/dev/null 2>&1
shoot login "$DASHBOARD_URL/login"
"$DEMO" login-off >/dev/null 2>&1

write_webp agents paths outage grafana screens duplicate discovered evicting evicted activity agent login
step "Done; the demo is back to normal"
