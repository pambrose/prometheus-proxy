#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The version the docs site gives (make site-version), which between releases is the last release. Not the
# gradle.properties version: between releases that's a -SNAPSHOT or the next version, neither of which has an image.
# Fail fast, as make does when the site doesn't give one version, rather than pass Docker a bad image reference.
VERSION=$(make -s --no-print-directory -C "$SCRIPT_DIR/.." site-version) || exit 1

docker run --rm -p 8082:8082 -p 8092:8092 -p 50051:50051 -p 8080:8080 \
  --env PROXY_CONFIG='https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/examples/simple.conf' \
  "pambrose/prometheus-proxy:${VERSION}"

