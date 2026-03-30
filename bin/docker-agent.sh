#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(grep '^version = ' "$SCRIPT_DIR/../build.gradle.kts" | sed 's/version = "\(.*\)"/\1/')

docker run --rm -p 8083:8083 -p 8093:8093 \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  --env PROXY_HOSTNAME=mymachine.lan \
  pambrose/prometheus-agent:${VERSION}

