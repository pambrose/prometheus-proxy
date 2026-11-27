# shellcheck shell=bash
# shellcheck disable=SC2153,SC2154 # DEMO_DIR, OUT_DIR, SCREENS_URL, PROMETHEUS_URL, and DASHBOARD_URL are a script's
# The code the demos' screenshot scripts, demos/*/bin/screenshots.sh, share, which each sources once it has set
# DEMO_DIR, its demo's directory: finding Chrome, capturing a page in headless Chrome in both themes, waiting for a
# scene's state, reading the proxy dashboard's agent rows, and writing the shots to the website as lossless WebP.
# What's left in each script is its demo's own: its URLs, its scenes and the checks they wait on, and how it puts the
# demo back. Change a shared step here, for every demo, rather than in a script: the copies each script once had drifted
# apart.
#
# A script sets, before the functions that read them run:
#   OUT_DIR             where write_webp writes, a directory under website/prometheus-proxy/docs/images/
#   SCREENS_URL         the proxy's screens page, which shoot_screens opens
#   PROMETHEUS_URL      the demo's Prometheus, which prometheus_has asks
#   DASHBOARD_URL       the proxy's dashboard, where agent_row finds an agent unless it's given another
#   SKIP_PATHLESS_ROWS  1 for agent_row to pass over an agent's row that lists no paths
# and may define restore, which the exit trap start_shots sets runs, to put the demo back when the script ends or
# fails.
#
# One setting, from the environment, beside a script's own:
#   WAIT_SECS=<n>  how long each wait for the demo to reach a scene's state may take before the script fails
#                  (default 150)

# This file is in the repository's bin/.
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WAIT_SECS="${WAIT_SECS:-150}"

# Every screenshot is a whole browser window, 16:10, WIDTH x HEIGHT in CSS pixels, rather than a strip cut to its
# content, so each fills the same space on the website's page and in the lightbox that opens it full size. A page that
# needs more room is taken in a wider window of the same shape, WIDE_WIDTH x WIDE_HEIGHT: the screens page, where Split
# gives each pane half the window, and a dashboard table wider than WIDTH, which would scroll and lose its right edge.
# THEMES are the themes each is taken in.
WIDTH=1280
HEIGHT=800
WIDE_WIDTH=1680
WIDE_HEIGHT=1050
THEMES=(dark light)
# SCALE is the pixel density each is taken at, device pixels to the CSS pixel: at 3, the website's lightbox, which
# opens a screenshot at up to one and a half times its window's size, has a pixel for each of a Retina screen's
# (website/prometheus-proxy/docs/javascripts/screenshots.js), so its text stays sharp.
SCALE=3

# A screenshot's file name in a theme: the dark one keeps the plain name.
named() { if [[ "$2" == dark ]]; then echo "$1"; else echo "$1-$2"; fi; }

die() {
  echo "Error: $*" >&2
  exit 1
}
step() { printf '\033[1m%s\033[0m\n' "$*"; }

# Takes each port saved in the demo's docker/.env that the environment doesn't set, as demo.sh does, so the URLs a
# script builds match what compose publishes. demo.sh's up saves there the host ports moved with the *_PORT variables;
# a variable set on the command line wins, and up saves it in turn.
load_saved_ports() {
  local file="$DEMO_DIR/docker/.env" name value
  [[ -f "$file" ]] || return 0
  while IFS='=' read -r name value || [[ -n "$name" ]]; do
    if [[ "$name" =~ ^[A-Z0-9_]+_PORT$ && -z "${!name:-}" ]]; then
      export "$name=$value"
    fi
  done <"$file"
}

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

# Whether any of these URLs answers.
answers() {
  local url
  for url in "$@"; do
    curl -fsS -o /dev/null "$url" 2>/dev/null && return 0
  done
  return 1
}

# Checks what the script needs, a WAIT_SECS it can use, Chrome, ImageMagick 7, cwebp, and the demo, which answers at
# one of these URLs; then makes the work directory the shots are taken into, and sets the exit trap.
# start_shots <url>...
start_shots() {
  [[ "$WAIT_SECS" =~ ^[1-9][0-9]*$ ]] || die "WAIT_SECS must be a whole number of seconds above 0, not '$WAIT_SECS'"
  CHROME_BIN="$(find_chrome)"
  command -v magick >/dev/null 2>&1 || die "ImageMagick 7 ('magick') is not on the PATH"
  command -v cwebp >/dev/null 2>&1 || die "cwebp is not on the PATH"
  answers "$@" || die "the demo isn't running at $1; start it with 'make up-local' in ${DEMO_DIR#"$REPO_DIR"/}"
  WORK="$(mktemp -d)"
  trap finish EXIT
}

# The exit trap: the script's restore, where it defines one, then the work directory goes.
finish() {
  if declare -F restore >/dev/null; then
    restore
  fi
  rm -rf "$WORK"
}

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

# Waits this many seconds, so a graph or a countdown can move: wait_until with a check that passes once they're up.
elapsed_since() { ((SECONDS >= $1)); }
wait_secs() {
  local until=$((SECONDS + $1))
  wait_until "$1 seconds to pass" elapsed_since "$until"
}

# Whether a page, by URL, matches an extended regex. The page is taken whole before grep reads it: grep -q stops at its
# match, and under pipefail a curl it cut off would fail the check.
page_has() {
  local body
  body="$(curl -fsS "$1" 2>/dev/null)" || return 1
  grep -Eq "$2" <<<"$body"
}

# Whether a PromQL query to the demo's Prometheus returns a result.
prometheus_has() {
  local body
  body="$(curl -fsS -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" 2>/dev/null)" || return 1
  grep -q '"value"' <<<"$body"
}

# The agent list's row for the connected agent with this name, on the proxy dashboard at a base URL (DASHBOARD_URL
# unless given): its start tag, or nothing. The row carries the agent's id, name, and path count as data attributes
# (agentListInner in ProxyDashboardHtml.kt), which the checks read: they matched the row's text before, and every change
# to how the row looks, a tag, an LED, or a word, broke one. The name's closing quote keeps agent-east from matching
# agent-east-copy, and the proxy escapes every value, so no ">" ends a tag early. With SKIP_PATHLESS_ROWS=1, a row that
# lists no paths is passed over, for a demo where an agent's old connection stays listed, with none, beside its new one.
agent_row() {
  local html rows
  html="$(curl -fsS "${2:-$DASHBOARD_URL}" 2>/dev/null)" || return 0
  rows="$(grep -Eo '<[a-z]+ [^>]*data-agent-name="[^"]*"[^>]*>' <<<"$html" | grep -F "data-agent-name=\"$1\"" || true)"
  if [[ "${SKIP_PATHLESS_ROWS:-}" == 1 ]]; then
    rows="$(grep -vF 'data-path-count="0"' <<<"$rows" || true)"
  fi
  head -1 <<<"$rows"
}

# An attribute's value in a start tag, or nothing.
attr() { sed -nE "s/.* $1=\"([^\"]*)\".*/\1/p" <<<"$2"; }

# The id of the connected agent with this name, on the proxy dashboard at a base URL (DASHBOARD_URL unless given), or
# nothing.
agent_id() { attr data-agent "$(agent_row "$@")"; }

# Whether the connected agent with this name serves this many paths.
serves_paths() { [[ "$(attr data-path-count "$(agent_row "$1")")" == "$2" ]]; }

# Whether an agent's detail page, by URL, counts at least this many recent scrapes: enough rows to fill the window.
# The count is data on the detail's Recent scrapes header (detailInner in ProxyDashboardHtml.kt).
has_scrapes() {
  local count
  count="$(curl -fsS "$1" 2>/dev/null | grep -Eo 'data-scrape-count="[0-9]+"' | grep -Eo '[0-9]+' | head -1)"
  [[ -n "$count" ]] && ((count >= $2))
}

# Captures a page, by URL, to a PNG, in a theme, at SCALE: the dashboards, the screens page, Prometheus, and the demos'
# apps follow the browser's preferred color scheme. The timing is the virtual time the page gets to load and run, in
# milliseconds, 3000 unless given (Grafana, which renders after loading its data, takes 15000), or Chrome's
# --timeout=<ms>, real time, for a page waiting on something virtual time skips past, such as a WebSocket's answer. The
# window is WIDTH x HEIGHT CSS pixels unless given. capture <url> <png> <theme> [timing] [width] [height]
capture() {
  local url="$1" png="$2" theme="$3" timing="${4:-3000}" width="${5:-$WIDTH}" height="${6:-$HEIGHT}" scheme=0 profile
  [[ "$timing" == --* ]] || timing="--virtual-time-budget=$timing"
  [[ "$theme" == dark ]] || scheme=1
  profile="$(mktemp -d)"
  "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$profile" --force-device-scale-factor="$SCALE" --blink-settings=preferredColorScheme=$scheme \
    --window-size="$width,$height" "$timing" --screenshot="$png" "$url" >/dev/null 2>&1
  rm -rf "$profile"
  [[ -s "$png" ]] || die "Chrome wrote no screenshot for $url"
}

# A page in each theme, a whole WIDTH x HEIGHT window, by a URL in which a {theme} becomes the theme's name, for
# Grafana, which doesn't follow the browser's scheme. The timing is capture's. A page whose viewport ends in a fixed
# footer, Grafana's, is captured at a taller height, given, and cut to HEIGHT, which leaves the footer out.
# shoot <name> <url> [timing] [capture height]
shoot() {
  local name="$1" url="$2" timing="${3:-}" capture_height="${4:-$HEIGHT}" theme png
  for theme in "${THEMES[@]}"; do
    png="$WORK/$(named "$name" "$theme").png"
    capture "${url//\{theme\}/$theme}" "$png" "$theme" "$timing" "$WIDTH" "$capture_height"
    if ((capture_height != HEIGHT)); then
      magick "$png" -crop "$((WIDTH * SCALE))x$((HEIGHT * SCALE))+0+0" +repage "$png"
    fi
  done
}

# A page in each theme, a whole WIDE_WIDTH x WIDE_HEIGHT window, by a URL in which a {theme} becomes the theme's name.
# The timing is capture's. shoot_wide <name> <url> [timing]
shoot_wide() {
  local name="$1" url="$2" timing="${3:-}" theme
  for theme in "${THEMES[@]}"; do
    capture "${url//\{theme\}/$theme}" "$WORK/$(named "$name" "$theme").png" "$theme" "$timing" \
      "$WIDE_WIDTH" "$WIDE_HEIGHT"
  done
}

# The proxy's screens page in each theme, opened on a link (its part after the #), a whole WIDE_WIDTH x WIDE_HEIGHT
# window. A {theme} in the link becomes the theme's name, for a Grafana pane. The timing is capture's, Grafana's 15000
# unless given. shoot_screens <name> <link> [timing]
shoot_screens() { shoot_wide "$1" "$SCREENS_URL#$2" "${3:-15000}"; }

# Writes a shot, by its file name with no extension, to OUT_DIR as lossless WebP, and lists it with its size in
# pixels.
webp() {
  cwebp -quiet -lossless -z 9 "$WORK/$1.png" -o "$OUT_DIR/$1.webp"
  printf '  %-34s %s\n' "$1.webp" "$(magick identify -format '%wx%h' "$WORK/$1.png")"
}

# Writes the shots, by name, each in every theme, to OUT_DIR as lossless WebP. write_webp <name>...
write_webp() {
  local name theme
  step "Writing WebP to ${OUT_DIR#"$REPO_DIR"/}"
  mkdir -p "$OUT_DIR"
  for name in "$@"; do
    for theme in "${THEMES[@]}"; do
      webp "$(named "$name" "$theme")"
    done
  done
}
