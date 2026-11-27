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

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/spring-boot-demo"
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

APP_URL="http://localhost:${SPRING_BOOT_DEMO_APP_PORT:-8490}"
DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/dashboard"
AGENT_DASHBOARD_URL="http://localhost:${SPRING_BOOT_DEMO_AGENT_DASHBOARD_PORT:-8495}/dashboard"
SCREENS_URL="http://localhost:${SPRING_BOOT_DEMO_DASHBOARD_PORT:-8494}/screens"

# The settings in the header. SKIP_UPTIME_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# The screens page is taken as a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, as wide as the other demos'
# screens pages, so each of Split's two panes is about as wide as a page needs. THEMES are the themes it's taken in.
WIDTH=1680
HEIGHT=1050
THEMES=(dark light)
# SCALE is the pixel density it's taken at, device pixels to the CSS pixel: at 3, the website's lightbox, which opens a
# screenshot at up to one and a half times its window's size, has a pixel for each of a Retina screen's
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
curl -fsS -o /dev/null "$APP_URL/actuator/health" 2>/dev/null ||
  curl -fsS -o /dev/null "$SCREENS_URL" 2>/dev/null ||
  die "the demo isn't running at $APP_URL; start it with 'make up-local' in demos/spring-boot"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

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

# Whether a page, by URL, matches an extended regular expression.
page_has() {
  local html
  html="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -Eq "$2" <<<"$html"
}

# Whether the roastery's agent has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
agent_up_a_minute() {
  local id
  id="$(agent_id roastery-1.0)"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

# Captures a page at WIDTH x HEIGHT CSS pixels, at SCALE, in a theme: the screens page and both dashboards follow the
# browser's preferred color scheme.
capture() {
  local url="$1" png="$2" theme="$3" scheme=0 profile
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor="$SCALE" --blink-settings=preferredColorScheme=$scheme \
    --window-size="$WIDTH,$HEIGHT" --virtual-time-budget=8000 --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# The proxy's screens page in each theme, opened on a link (its part after the #). shoot_screens <name> <link>
shoot_screens() {
  local name="$1" link="$2" theme
  for theme in "${THEMES[@]}"; do
    capture "$SCREENS_URL#$link" "$WORK/$(named "$name" "$theme").png" "$theme"
  done
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
# rather than a prompt to select one.
shoot_screens screens "proxy/agents/$agent+agent/paths/roastery_metrics"

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for theme in "${THEMES[@]}"; do
  file="$(named screens "$theme")"
  cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
  printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
done
step "Done"
