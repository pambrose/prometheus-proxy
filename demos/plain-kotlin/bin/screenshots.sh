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

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/plain-kotlin-demo"
COMPOSE=(docker compose -f "$DEMO_DIR/docker/compose.yml")

load_saved_ports
DASHBOARD_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/dashboard"
SCREENS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_DASHBOARD_PORT:-8694}/screens"
PROMETHEUS_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROMETHEUS_PORT:-9690}"
PROXY_URL="http://localhost:${PLAIN_KOTLIN_DEMO_PROXY_PORT:-8680}"

# The setting in the header beside WAIT_SECS, which the shared code reads. SKIP_HISTORY_WAIT takes exactly 1; any other
# value leaves the wait in.
SKIP_HISTORY_WAIT="${SKIP_HISTORY_WAIT:-}"

# Puts the demo back to one oven when the script ends, or fails. A rush, once started, runs its 50 seconds out, and a
# backlog left to one oven takes a quarter of an hour to clear, so the script clears it with three before it ends, and,
# after a run that failed, before its next scene.
restore() { "$DEMO" scale 1 >/dev/null 2>&1 || true; }
start_shots "$PROXY_URL/discovery"

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
  count="$(curl -fsS "$PROXY_URL/discovery" 2>/dev/null |
    grep -cE '"__metrics_path__": *"/oven_[^_"]+_metrics"' || true)"
  [[ "$count" == "$1" ]]
}

# Whether Prometheus has scraped an oven for at least this many seconds, so Grafana's graphs have some history.
history_of() { prometheus_has "max(count_over_time(up{job=\"oven\"}[10m])) * 5 >= $1"; }

# Whether Prometheus has this many ovens baking, so 'Orders baked / sec, by oven' draws a series for each.
baking_ovens() { prometheus_has "count(sum by (instance) (rate(bakery_orders_baked_total[30s])) > 0) >= $1"; }

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

write_webp screens
step "Done; the bakery is back to one oven, with no backlog"
