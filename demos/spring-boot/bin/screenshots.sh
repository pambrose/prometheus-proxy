#!/usr/bin/env bash
# Retakes the screenshot on the website's Spring Boot Demo page (website/prometheus-proxy/docs/spring-boot-demo.md)
# from the running demo: the proxy's screens page as part 5 of the tour opens it, the proxy's dashboard beside the
# agent's, captured in headless Chrome at three times the pixel density in both themes, a whole window, and written as
# lossless WebP to website/prometheus-proxy/docs/images/spring-boot-demo/: screens.webp in the dark theme and
# screens-light.webp in the light one, which the page shows by the reader's theme. It starts the proxy and the
# roastery first, where a scenario left either stopped.
#
# `make screenshots`, in demos/spring-boot, builds the demo from this checkout and starts it first, so the screenshot
# shows the code as it is. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7 (`magick`), and `cwebp`.
#
# Two settings, for running it again and again while checking a change; the defaults take the site's screenshot:
#   WAIT_SECS=<n>       how long each wait for the demo to reach the scene's state may take before the script fails
#                       (default 150)
#   SKIP_UPTIME_WAIT=1  skip the wait of up to a minute, after a fresh start, for the agent's uptime to read in minutes;
#                       the shot then shows it in seconds, so it's for checking a change, not for the site
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/spring-boot-demo"

load_saved_ports
APP_URL="http://localhost:${SPRING_BOOT_DEMO_APP_PORT:-8490}"
DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/dashboard"
AGENT_DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_AGENT_DASHBOARD_PORT:-8495}/dashboard"
SCREENS_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/screens"

# The setting in the header beside WAIT_SECS, which the shared code reads. SKIP_UPTIME_WAIT takes exactly 1; any other
# value leaves the wait in.
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# The roastery, or the proxy's screens page where a scenario left the roastery stopped.
start_shots "$APP_URL/actuator/health" "$SCREENS_URL"

# Whether the roastery's agent has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
agent_up_a_minute() {
  local id
  id="$(agent_id roastery-1.0)"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

step "Starting the proxy and the roastery, where a scenario left either stopped"
"$DEMO" proxy-start >/dev/null
"$DEMO" start-app >/dev/null
wait_until "roastery-1.0 to serve its path" serves_paths roastery-1.0 1
wait_until "the agent's dashboard" page_has "$AGENT_DASHBOARD_URL" 'roastery_metrics'
# make screenshots starts the demo just before this, so the agent's uptime would read a few seconds. Waiting until it
# has been up a minute makes the shot look like a demo that has been running, and gives its Paths view a minute of
# scrapes. Say why, since the script otherwise looks stuck, or that SKIP_UPTIME_WAIT skipped it.
if ! agent_up_a_minute; then
  if [[ "$SKIP_UPTIME_WAIT" == 1 ]]; then
    step "Skipping the wait for the agent's uptime to read in minutes (SKIP_UPTIME_WAIT=1): the shot shows it in" \
      "seconds, so it's for checking a change, not for the site"
  else
    step "Waiting up to a minute so the agent's uptime reads in minutes, not seconds: the demo was just started"
    wait_until "roastery-1.0 to be up a minute" agent_up_a_minute
  fi
fi

step "The proxy's dashboard beside the agent's"
agent="$(agent_id roastery-1.0)"
[[ -n "$agent" ]] || die "roastery-1.0 isn't connected"
# The screens page as part 5 of the tour opens it, before the roastery stops: roastery-1.0 selected in the proxy
# dashboard's Agents view, beside the agent's own dashboard with its path selected, so each shows its recent scrapes
# rather than a prompt to select one. Its panes are dashboards, so it takes less virtual time than Grafana's.
shoot_screens screens "proxy/agents/$agent+agent/paths/roastery_metrics" 8000

write_webp screens
step "Done"
