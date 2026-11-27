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

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/ktor-demo"

load_saved_ports
APP_URL="http://localhost:${KTOR_DEMO_APP_PORT:-8390}"
DASHBOARD_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/dashboard"
SCREENS_URL="http://localhost:${KTOR_DEMO_DASHBOARD_PORT:-8394}/screens"
GRAFANA_URL="http://localhost:${KTOR_DEMO_GRAFANA_PORT:-3300}"
PROMETHEUS_URL="http://localhost:${KTOR_DEMO_PROMETHEUS_PORT:-9390}"

# The setting in the header beside WAIT_SECS, which the shared code reads. SKIP_HISTORY_WAIT takes exactly 1; any other
# value leaves the wait in.
SKIP_HISTORY_WAIT="${SKIP_HISTORY_WAIT:-}"

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
restore() { normal; }
start_shots "$APP_URL/healthz"

# Whether Prometheus has this alert firing.
firing() { prometheus_has "ALERTS{alertname=\"$1\",alertstate=\"firing\"}"; }

# Whether the coffee shop's queue holds at least this many orders.
queue_at_least() {
  local queue
  queue="$(curl -fsS "$APP_URL/status" 2>/dev/null | sed -nE 's/^Queue: +([0-9]+).*/\1/p')"
  [[ -n "$queue" ]] && ((queue >= $1))
}

# Whether Prometheus has scraped the coffee shop for at least this many seconds, so Grafana's graphs have some history.
history_of() { prometheus_has "count_over_time(up{job=\"coffee-shop\"}[10m]) * 5 >= $1"; }

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

step "A rush in the screens page, and a broken espresso machine in Grafana"
"$DEMO" rush >/dev/null
wait_until "the queue to pass 30" queue_at_least 30
# The screens page as part 4 of the tour opens it, mid-rush, with the queue growing: the coffee shop's status page
# beside Grafana, the shop's without its refresh, as above.
shoot_screens screens \
  "coffee-shop/?refresh=false+grafana/d/coffee-shop?orgId=1&kiosk&from=now-10m&to=now&theme={theme}"
# The machine breaks only once the rush has filled the queue: with it broken, every espresso drink fails at the
# counter, and the few orders left to queue are fewer than three baristas brew.
"$DEMO" break-machine >/dev/null
wait_until "CoffeeShopErrors to fire" firing CoffeeShopErrors
# Grafana is captured taller than kept: the bottom of its viewport carries a fixed "Powered by" footer.
shoot grafana "$GRAFANA_URL/d/coffee-shop?orgId=1&kiosk&from=now-10m&to=now&theme={theme}" 15000 "$((HEIGHT + 100))"
"$DEMO" fix-machine >/dev/null
"$DEMO" calm >/dev/null

step "A deploy: version 1.1 takes the paths over"
"$DEMO" deploy >/dev/null 2>&1
wait_until "coffee-shop-1.1 to serve the two paths" serves_paths coffee-shop-1.1 2
sleep 10
shoot deploy "$DASHBOARD_URL/paths"
"$DEMO" rollback >/dev/null 2>&1

write_webp coffee-shop agents grafana screens deploy
step "Done; the demo is back to normal"
