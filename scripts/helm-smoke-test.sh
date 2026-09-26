#!/usr/bin/env bash
# shellcheck disable=SC2329 # dump and cleanup run from the EXIT trap
# Installs the proxy and agent charts into the current kube context and checks that a scrape of the proxy's HTTP port
# reaches the agent and comes back through it. The agent's one path scrapes the proxy's own metrics endpoint, so no
# other workload is needed, and the returned proxy_agent_map_size shows the agent connected.
#
# The images pambrose/prometheus-proxy:$IMAGE_TAG and pambrose/prometheus-agent:$IMAGE_TAG must already be on the
# cluster's nodes (pullPolicy Never): CI builds them and loads them into kind (see container-tests.yml).
#
# Usage: IMAGE_TAG=smoke scripts/helm-smoke-test.sh   (from the repository root; NAMESPACE defaults to helm-smoke)
set -euo pipefail

tag=${IMAGE_TAG:?set IMAGE_TAG to the tag of the images loaded into the cluster}
ns=${NAMESPACE:-helm-smoke}
local_port=${LOCAL_PORT:-18080}

dump() {
  echo "--- pods" >&2
  kubectl -n "$ns" get pods -o wide >&2 || true
  for deploy in prometheus-proxy prometheus-agent; do
    echo "--- $deploy logs" >&2
    kubectl -n "$ns" logs "deploy/$deploy" --tail=60 >&2 || true
  done
}

cleanup() {
  status=$?
  [ -n "${forward_pid:-}" ] && kill "$forward_pid" 2>/dev/null || true
  [ "$status" -ne 0 ] && dump
  rm -f "${agent_values:-}"
  exit "$status"
}
trap cleanup EXIT

kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install prometheus-proxy charts/prometheus-proxy --namespace "$ns" \
  --set image.tag="$tag" --set image.pullPolicy=Never \
  --wait --timeout 5m

agent_values=$(mktemp)
cat > "$agent_values" <<'EOF'
proxy:
  hostname: prometheus-proxy:50051
config: |
  agent {
    pathConfigs: [
      { name: "Proxy metrics", path: smoke_metrics, url: "http://prometheus-proxy:8082/metrics" }
    ]
  }
EOF
helm upgrade --install prometheus-agent charts/prometheus-agent --namespace "$ns" \
  --set image.tag="$tag" --set image.pullPolicy=Never -f "$agent_values" \
  --wait --timeout 5m

kubectl -n "$ns" port-forward svc/prometheus-proxy "$local_port:8080" >/dev/null 2>&1 &
forward_pid=$!
disown "$forward_pid"

for _ in $(seq 60); do
  if body=$(curl -sf "http://localhost:$local_port/smoke_metrics") && grep -Eq '^proxy_agent_map_size 1(\.0)?$' <<<"$body"; then
    echo "OK: a scrape of /smoke_metrics went through the proxy and the agent, which is connected"
    exit 0
  fi
  sleep 2
done

echo "FAILED: no successful scrape of /smoke_metrics through the proxy within 120s" >&2
exit 1
