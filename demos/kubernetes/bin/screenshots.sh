#!/usr/bin/env bash
# Retakes the screenshots on the website's Kubernetes Demo page (website/prometheus-proxy/docs/kubernetes-demo.md) from
# the running demo: it plays the page's scenarios, parts 1, 3, and 4, captures the dashboard, Prometheus, and the
# proxy's screens page in headless Chrome at three times the pixel density, each a whole window, and writes lossless
# WebP to
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

# This script is in the demo's bin/, and the demo two levels below the repository root, whose bin/ holds the code the
# demos' screenshot scripts share.
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../../.. source=bin/screenshots-lib.sh
source "$DEMO_DIR/../../bin/screenshots-lib.sh"
DEMO="$DEMO_DIR/demo.sh"
OUT_DIR="$REPO_DIR/website/prometheus-proxy/docs/images/kubernetes-demo"

# A host port of pp-monitoring's, as demo.sh takes it: its variable's value, or, with the variable unset, the port the
# running cluster publishes the NodePort on, from its node container's port mappings, or else the default.
# cluster_port <the variable's value> <NodePort> <default>
cluster_port() {
  local port="$1"
  [[ -n "$port" ]] || port="$(docker port pp-monitoring-control-plane "$2/tcp" 2>/dev/null |
    sed -nE 's/^127\.0\.0\.1:([0-9]+)$/\1/p' | head -1 || true)"
  echo "${port:-$3}"
}
DASHBOARD_URL="http://localhost:$(cluster_port "${KUBERNETES_DEMO_DASHBOARD_PORT:-}" 30094 8294)/dashboard"
PROMETHEUS_URL="http://localhost:$(cluster_port "${KUBERNETES_DEMO_PROMETHEUS_PORT:-}" 30090 9290)"
# The proxy's screens page, on the dashboard's port, and the port its agent tab reaches the agent's dashboard on, which
# a port-forward holds while the screens page is taken, as `demo.sh agent-dashboard` does for a reader.
SCREENS_URL="${DASHBOARD_URL%/dashboard}/screens"
AGENT_DASHBOARD_PORT="${KUBERNETES_DEMO_AGENT_DASHBOARD_PORT:-8295}"
TK=(kubectl --context kind-pp-target)

# The setting in the header beside WAIT_SECS, which the shared code reads. SKIP_UPTIME_WAIT takes exactly 1; any other
# value leaves the wait in.
SKIP_UPTIME_WAIT="${SKIP_UPTIME_WAIT:-}"

# An agent's row that lists no paths is passed over, so the agent is on the dashboard, for normal below, once it has
# registered them.
SKIP_PATHLESS_ROWS=1

FORWARD_PID=""

# Stops the port-forward to the agent's dashboard, if it runs.
stop_forward() {
  if [[ -n "$FORWARD_PID" ]]; then
    kill "$FORWARD_PID" 2>/dev/null || true
    wait "$FORWARD_PID" 2>/dev/null || true
    FORWARD_PID=""
  fi
}

# Undoes every scenario, whatever state a failure left the demo in: the port-forward stops, the search service goes,
# and the agent runs its own config.
restore() {
  stop_forward
  "$DEMO" remove-service >/dev/null 2>&1 || true
  "$DEMO" restore-config >/dev/null 2>&1 || true
}
start_shots "$DASHBOARD_URL"

# The agent's pod, the one not on its way out.
agent_pod() {
  "${TK[@]}" -n monitoring get pods -l app.kubernetes.io/instance=prometheus-agent \
    -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}}{{"\n"}}{{end}}{{end}}' |
    head -1
}

# Whether the agent has been connected a minute, which its detail shows as "up 1m" rather than in seconds.
agent_up_a_minute() {
  local id
  id="$(agent_id "$(agent_pod)")"
  [[ -n "$id" ]] && page_has "$DASHBOARD_URL/agents/$id" 'up [0-9]+[mhd]'
}

# The agent on the dashboard with its paths, and its four targets, the three services and its own metrics, up in
# Prometheus: the demo as it starts.
normal() {
  [[ -n "$(agent_id "$(agent_pod)")" ]] && prometheus_has 'count(up == 1) == 4'
}

# A dashboard table in each theme, at a dashboard path, in the wide window, as in the basics demo: with the services'
# cluster-local target URLs, the History and Links columns, and the activity's details, the Paths and Activity tables
# are wider than WIDTH.
shoot_table() { shoot_wide "$1" "$DASHBOARD_URL$2"; }

# Forwards AGENT_DASHBOARD_PORT to the agent's dashboard, in the background, as demo.sh agent-dashboard does, so the
# screens page's agent tab answers, and waits until it does.
start_forward() {
  local port
  port="$("${TK[@]}" -n monitoring get deployment prometheus-agent \
    -o jsonpath='{.spec.template.spec.containers[0].ports[?(@.name=="dashboard")].containerPort}')"
  [[ -n "$port" ]] || die "the agent's dashboard is off"
  "${TK[@]}" -n monitoring port-forward deploy/prometheus-agent "$AGENT_DASHBOARD_PORT:$port" >/dev/null 2>&1 &
  FORWARD_PID=$!
  wait_until "the agent's dashboard on localhost:$AGENT_DASHBOARD_PORT" forwarded
}

# Whether the port-forward reaches the agent's dashboard yet.
forwarded() { curl -fsS -o /dev/null "http://localhost:$AGENT_DASHBOARD_PORT/dashboard" 2>/dev/null; }

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

step "Part 1: Two clusters, no inbound access"
pod="$(agent_pod)"
id="$(agent_id "$pod")"
[[ -n "$id" ]] || die "the agent $pod isn't on the dashboard"
wait_until "the agent's recent scrapes" has_scrapes "$DASHBOARD_URL/agents/$id" 12
shoot agent "$DASHBOARD_URL/agents/$id"
# Prometheus renders after loading its data, so it gets a longer virtual-time budget.
shoot targets "$PROMETHEUS_URL/targets" 8000

step "Part 3: Annotate a Service, and Prometheus scrapes it"
"$DEMO" add-service >/dev/null
wait_until "search up in Prometheus" prometheus_has 'up{job="search"} == 1'
shoot_table discovered /paths
# The proxy's Paths view beside Prometheus's targets, with the agent's tab answering, as the tour has it, with
# Prometheus's budget.
start_forward
shoot_screens screens "proxy/paths+prometheus/targets" 8000
stop_forward
"$DEMO" remove-service >/dev/null

step "Part 4: Kubernetes replaces a deleted agent pod"
"$DEMO" delete-agent-pod >/dev/null
# The Activity view lists the old pod's paths leaving and the new pod's arriving, newest first.
wait_until "the new agent's scrapes" normal
shoot_table replaced /activity

write_webp agent targets discovered screens replaced
step "Done; the demo is back to its start"
