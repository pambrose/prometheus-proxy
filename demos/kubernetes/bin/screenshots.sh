#!/usr/bin/env bash
# Retakes the screenshots on the website's Kubernetes Demo page (website/prometheus-proxy/docs/kubernetes-demo.md) from
# the running demo: it plays the page's scenarios, parts 1 to 3, captures the dashboard and Prometheus in headless
# Chrome at twice the pixel density, crops each, and writes lossless WebP to
# website/prometheus-proxy/docs/images/kubernetes-demo/: <name>.webp in the dark theme and <name>-light.webp in the
# light one, which the page shows by the reader's theme. It puts the demo back to normal when it finishes, or when it
# fails.
#
# `make screenshots`, in demos/kubernetes, builds the demo from this checkout and starts it first, so the screenshots
# show the dashboard as it is in the code. Needs Chrome or Chromium (set CHROME to choose one), ImageMagick 7
# (`magick`), and `cwebp`.
#
# Two settings, for running it again and again while checking a change; the defaults take the site's screenshots:
#   WAIT_SECS=<n>       how long each wait for the demo to reach a scene's state may take before the script fails
#                       (default 150)
#   SKIP_UPTIME_WAIT=1  skip the wait of up to a minute, after a fresh start, for the agent's uptime to read in
#                       minutes; the shots then show it in seconds, so they're for checking a change, not for the site
set -euo pipefail

# This script is in the demo's bin/, and the demo two levels below the repository root.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/kubernetes-demo"

DASHBOARD_URL="http://localhost:${KUBERNETES_DEMO_DASHBOARD_PORT:-8294}/dashboard"
PROMETHEUS_URL="http://localhost:${KUBERNETES_DEMO_PROMETHEUS_PORT:-9290}"
TK=(kubectl --context kind-pp-target)

# The settings in the header. SKIP_UPTIME_WAIT takes exactly 1; any other value leaves the wait in.
WAIT_SECS="${WAIT_SECS:-150}"
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# The CSS width every screenshot is taken at, and the themes each is taken in. The Paths and Activity views are taken
# wider, as in the basics demo: with the services' cluster-local target URLs, the History and Links columns, and the
# activity's details, their tables are wider than 1280, which would scroll and cut off their right edge.
WIDTH=1280
TABLE_WIDTH=1680
THEMES=(dark light)

# The dashboard's background in each theme, which shoot_table trims below a table's last row.
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

[[ "$WAIT_SECS" =~ ^[1-9][0-9]*$ ]] || die "WAIT_SECS must be a whole number of seconds above 0, not '$WAIT_SECS'"
CHROME_BIN="$(find_chrome)"
command -v magick >/dev/null 2>&1 || die "ImageMagick 7 ('magick') is not on the PATH"
command -v cwebp >/dev/null 2>&1 || die "cwebp is not on the PATH"
curl -fsS -o /dev/null "$DASHBOARD_URL" 2>/dev/null ||
  die "the demo isn't running at $DASHBOARD_URL; start it with 'make up' in demos/kubernetes"

WORK="$(mktemp -d)"

# Undoes every scenario, whatever state a failure left the demo in: the search service goes, and the agent runs its
# own config.
restore() {
  "$DEMO" remove-service >/dev/null 2>&1 || true
  "$DEMO" restore-config >/dev/null 2>&1 || true
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

# Waits this many seconds: wait_until with a check that passes once they're up.
elapsed_since() { ((SECONDS >= $1)); }
wait_secs() {
  local until=$((SECONDS + $1))
  wait_until "$1 seconds to pass" elapsed_since "$until"
}

# The agent's pod, the one not on its way out.
agent_pod() {
  "${TK[@]}" -n monitoring get pods -l app.kubernetes.io/instance=prometheus-agent \
    -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}}{{"\n"}}{{end}}{{end}}' |
    head -1
}

# The agent list's row for the connected agent with this name: its start tag, or nothing. The row carries the agent's
# id, name, and path count as data attributes (agentListInner in ProxyDashboardHtml.kt), and the name's closing quote
# keeps one name from matching another that starts with it. A row with no paths is passed over.
agent_row() {
  local html
  html="$(curl -fsS "$DASHBOARD_URL" 2>/dev/null)" || return 0
  grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" |
    grep -vF 'data-path-count="0"' | head -1 || true
}

# An attribute's value in a start tag, or nothing.
attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<<"$2"; }

# The id of the connected agent with this name, or nothing.
agent_id() { attr data-agent "$(agent_row "$1")"; }

# Whether a page, by URL, matches an extended regex. The page is taken whole before grep reads it: grep -q stops at its
# match, and under pipefail a curl it cut off would fail the check.
page_has() {
  local body
  body="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -Eq "$2" <<<"$body"
}

# Whether the agent has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
agent_up_a_minute() {
  local id
  id="$(agent_id "$(agent_pod)")"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill a fixed crop.
# The count is data on the detail's Recent scrapes header (detailInner in ProxyDashboardHtml.kt).
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo 'data-scrape-count="[0-9]+"' | grep -Eo '[0-9]+' | head -1)"
  [[ -n "$count" ]] && ((count >= $2))
}

# Whether a PromQL query returns a result.
prometheus_has() {
  local body
  body="$(curl -fsS -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" 2>/dev/null)" || return 1
  grep -q '"value"' <<<"$body"
}

# The agent on the dashboard with its paths, and its four targets, the three services and its own metrics, up in
# Prometheus: the demo as it starts.
normal() {
  [[ -n "$(agent_id "$(agent_pod)")" ]] && prometheus_has 'count(up == 1) == 4'
}

# Captures a page at the given CSS width (WIDTH unless given) x the given CSS height, at 2x, in a theme: the dashboard
# and Prometheus follow the browser's preferred color scheme. Prometheus renders after loading its data, so its capture
# gets a longer virtual-time budget.
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

# A dashboard table in each theme, at a dashboard path, at TABLE_WIDTH: captured taller than the table, then trimmed to
# its last row.
shoot_table() {
  local name="$1" path="$2" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "$DASHBOARD_URL$path" "$png" 900 "$theme" "" "$TABLE_WIDTH"
    magick "$png" -background "$(background "$theme")" -fuzz 2% -define trim:edges=south -trim +repage "$png"
  done
}

# A page in each theme, at WIDTH, cut at a fixed CSS height.
shoot_fixed() {
  local name="$1" url="$2" height="$3" capture_height="${4:-$3}" budget="${5:-3000}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "$url" "$png" "$capture_height" "$theme" "$budget"
    magick "$png" -crop "$((WIDTH * 2))x$((height * 2))+0+0" +repage "$png"
  done
}

# The agent detail's height ends on a Recent scrapes row's lower border, six rows down, and the targets page's just
# below its one scrape pool, for the demo's layout: change them with the dashboard's row metrics or Prometheus's.
AGENT_HEIGHT=544
TARGETS_HEIGHT=520

step "Waiting for the demo to be at its start"
"$DEMO" remove-service >/dev/null
"$DEMO" restore-config >/dev/null || true
wait_until "the agent on the dashboard and its four targets up" normal
# make screenshots starts the demo just before this, so the agent's uptime would read a few seconds. Waiting until it
# has been up a minute makes the shots look like a demo that has been running. Say why, since the script otherwise
# looks stuck, or that SKIP_UPTIME_WAIT skipped it.
if ! agent_up_a_minute; then
  if [[ "$SKIP_UPTIME_WAIT" == 1 ]]; then
    step "Skipping the wait for the agent's uptime to read in minutes (SKIP_UPTIME_WAIT=1): the shots show it in" \
      "seconds, so they're for checking a change, not for the site"
  else
    step "Waiting up to a minute so the agent's uptime reads in minutes, not seconds: the demo was just started"
    wait_until "the agent to be up a minute" agent_up_a_minute
  fi
fi

step "Part 1: The Helm charts on two clusters, with no inbound access"
pod="$(agent_pod)"
id="$(agent_id "$pod")"
[[ -n "$id" ]] || die "the agent $pod isn't on the dashboard"
wait_until "the agent's recent scrapes" has_scrapes "$DASHBOARD_URL/agents/$id" 8
shoot_fixed agent "$DASHBOARD_URL/agents/$id" "$AGENT_HEIGHT"
shoot_fixed targets "$PROMETHEUS_URL/targets" "$TARGETS_HEIGHT" "$TARGETS_HEIGHT" 8000

step "Part 2: annotate a Service, and Prometheus scrapes it"
"$DEMO" add-service >/dev/null
wait_until "search up in Prometheus" prometheus_has 'up{job="search"} == 1'
shoot_table discovered /paths
"$DEMO" remove-service >/dev/null

step "Part 3: Kubernetes replaces a deleted agent pod in about a second"
"$DEMO" delete-agent-pod >/dev/null
# The Activity view lists the old pod's paths leaving and the new pod's arriving, newest first.
wait_until "the new agent's scrapes" normal
shoot_table replaced /activity

step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
mkdir -p "$OUT_DIR"
for name in agent targets discovered replaced; do
  for theme in "${THEMES[@]}"; do
    file="$(named "$name" "$theme")"
    cwebp -quiet -lossless -z 9 "$WORK/$file.png" -o "$OUT_DIR/$file.webp"
    printf '  %-22s %s\n' "$file.webp" "$(magick identify -format '%wx%h' "$WORK/$file.png")"
  done
done
step "Done; the demo is back to its start"
