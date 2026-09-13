#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version lives in gradle.properties (same source the Makefile reads). Fail fast rather than pass Docker an
# image reference with an empty tag.
VERSION=$(sed -n 's/^version=//p' "$SCRIPT_DIR/../gradle.properties")
: "${VERSION:?could not read version from gradle.properties}"

docker run --rm -p 8083:8083 -p 8093:8093 \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  --env PROXY_HOSTNAME=mymachine.lan \
  "pambrose/prometheus-agent:${VERSION}"

