#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version lives in gradle.properties (same source the Makefile reads). Fail fast rather than pass Docker an
# image reference with an empty tag.
VERSION=$(sed -n 's/^version=//p' "$SCRIPT_DIR/../gradle.properties")
: "${VERSION:?could not read version from gradle.properties}"

docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
  --env PROXY_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  "pambrose/prometheus-proxy:${VERSION}"

