#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version the docs site gives (make site-version), which between releases is the last release. Not the
# gradle.properties version: between releases that's a -SNAPSHOT or the next version, neither of which has an image.
# Fail fast, as make does when the site doesn't give one version, rather than pass Docker a bad image reference.
VERSION=$(make -s --no-print-directory -C "$SCRIPT_DIR/.." site-version) || exit 1

docker run --rm -p 8083:8083 -p 8093:8093 \
  --env AGENT_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  --env PROXY_HOSTNAME=mymachine.lan \
  "pambrose/prometheus-agent:${VERSION}"

