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

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/nginx-demo"

load_saved_ports
DASHBOARD_URL="http://localhost:${NGINX_DEMO_DASHBOARD_PORT:-8594}/dashboard"
NGINX_DASHBOARD_URL="http://localhost:${NGINX_DEMO_NGINX_PORT:-8590}/dashboard"
AGENT_DASHBOARD_URL="http://localhost:${NGINX_DEMO_AGENT_DASHBOARD_PORT:-8595}/dashboard"
PROMETHEUS_URL="http://localhost:${NGINX_DEMO_PROMETHEUS_PORT:-9590}"
SCREENS_URL="http://localhost:${NGINX_DEMO_DASHBOARD_PORT:-8594}/screens"

# Puts the demo back as `make up` starts it, whatever state a failure left it in.
restore() { "$DEMO" reset >/dev/null 2>&1 || true; }
start_shots "$DASHBOARD_URL"

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
# The screens page as part 3 of the tour opens it: agent-current's Activity view beside the proxy's. Its panes are
# dashboards, so it takes their timing, not Grafana's: Prometheus draws no graph in virtual time, and the page doesn't
# get past its first render in real time, since Chrome takes the shot once the page has loaded.
shoot_screens screens "agent-current/activity+proxy/activity" 5000

step "Part 4: The dashboard behind nginx"
"$DEMO" reset >/dev/null
wait_until "both agents connected" both_connected
# Long enough for the page to load and its WebSocket to fail a few times, so the indicator reads reconnecting.
shoot_reconnecting dashboard-reconnecting "$NGINX_DASHBOARD_URL"
"$DEMO" dashboard-host >/dev/null
shoot dashboard-live "$NGINX_DASHBOARD_URL" --timeout=4000

write_webp agents legacy-cut short-timeouts screens dashboard-reconnecting dashboard-live
step "Done; the demo is as make up starts it"
